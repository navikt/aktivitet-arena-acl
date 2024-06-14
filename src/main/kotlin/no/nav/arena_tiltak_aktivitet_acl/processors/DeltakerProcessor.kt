package no.nav.arena_tiltak_aktivitet_acl.processors

import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.db.toUpsertInputWithStatusHandled
import no.nav.arena_tiltak_aktivitet_acl.domain.db.toUpsertInputWithStatusHandledAndIgnored
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetStatus.AVBRUTT
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetStatus.FULLFORT
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.ArenaDeltakerKafkaMessage
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.TiltakDeltakelse
import no.nav.arena_tiltak_aktivitet_acl.exceptions.*
import no.nav.arena_tiltak_aktivitet_acl.processors.converters.ArenaDeltakerConverter
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.GjennomforingRepository
import no.nav.arena_tiltak_aktivitet_acl.services.*
import no.nav.arena_tiltak_aktivitet_acl.services.OppfolgingsperiodeService.Companion.defaultSlakk
import no.nav.arena_tiltak_aktivitet_acl.services.OppfolgingsperiodeService.Companion.tidspunktTidligereEnnRettFoerStartDato
import no.nav.arena_tiltak_aktivitet_acl.utils.SecureLog.secureLog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.util.*

@Component
open class DeltakerProcessor(
	private val arenaDataRepository: ArenaDataRepository,
	private val kafkaProducerService: KafkaProducerService,
	private val gjennomforingRepository: GjennomforingRepository,
	private val aktivitetService: AktivitetService,
	private val tiltakService: TiltakService,
	private val personsporingService: PersonsporingService,
	private val oppfolgingsperiodeService: OppfolgingsperiodeService,
	private val aktivitetskortIdService: AktivitetskortIdService
) : ArenaMessageProcessor<ArenaDeltakerKafkaMessage> {

	companion object {
		val AKTIVITETSPLAN_LANSERINGSDATO: LocalDateTime = LocalDateTime.of(2017, Month.DECEMBER, 4, 0,0)
	}

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	override fun handleArenaMessage(message: ArenaDeltakerKafkaMessage) {
		val arenaDeltaker = message.getData()
		val arenaGjennomforingId = arenaDeltaker.TILTAKGJENNOMFORING_ID
		val deltakelse = arenaDeltaker.mapTiltakDeltakelse()

		// Ikke behandle aktiviteter som ikke var "aktive" ved lansering
		deltakelse.sjekkIkkeFerdigFørLansering()

		val ingestStatus: IngestStatus? = runCatching {
			arenaDataRepository.get(
				message.arenaTableName,
				message.operationType,
				message.operationPosition
			).ingestStatus
		}.getOrNull()

		val hasNewerHandledMessages = arenaDataRepository.hasHandledDeltakelseWithLaterTimestamp(DeltakelseId(arenaDeltaker.TILTAKDELTAKER_ID), message.operationTimestamp)
		if (hasNewerHandledMessages) throw OlderThanCurrentStateException("Har behandlet nyere meldinger på id=${arenaDeltaker.TILTAKDELTAKER_ID} allerede. Hopper over melding")

		val hasUnhandled = arenaDataRepository.hasUnhandledDeltakelse(arenaDeltaker.TILTAKDELTAKER_ID)
		val isFirstInQueue = ingestStatus == IngestStatus.RETRY || ingestStatus == IngestStatus.FAILED
		if (hasUnhandled && !isFirstInQueue) throw OutOfOrderException("Venter på at tidligere deltakelse med id=${arenaDeltaker.TILTAKDELTAKER_ID} skal bli håndtert")

		val gjennomforing = gjennomforingRepository.get(arenaGjennomforingId)
			?: throw DependencyNotIngestedException("Venter på at gjennomføring med id=$arenaGjennomforingId skal bli håndtert")

		val tiltak = tiltakService.getByKode(gjennomforing.tiltakKode)
			?: throw DependencyNotIngestedException("Venter på at tiltak med id=${gjennomforing.tiltakKode} skal bli håndtert")

		val personIdent = personsporingService.get(deltakelse.personId, arenaGjennomforingId).fodselsnummer

		/*
		 Hvis oppfølgingsperiode ikke finnes,
		 hopper vi ut her, enten med retry eller ignored, siden handleOppfolgingsperiodeNull kaster exception alltid.
		*/
		val periodeMatch =
			if (deltakelse.opprettetFørMenAktivEtterLansering()) {
				getOppfolgingsperiodeForPersonVedLansering(personIdent)
			} else getOppfolgingsPeriode(deltakelse, personIdent)
		val endring = utledEndringsType(periodeMatch, deltakelse.tiltakdeltakelseId, arenaDeltaker.DELTAKERSTATUSKODE, tiltak.administrasjonskode, message.operationTimestamp, message.operationType)

		when (endring) {
			is EndringsType.NyttAktivitetskortByttPeriode  -> {
				secureLog.info("Endring på deltakelse ${deltakelse.tiltakdeltakelseId} på deltakerId ${deltakelse.tiltakdeltakelseId} til ny aktivitetsid ${endring.aktivitetskortId} og oppfølgingsperiode ${periodeMatch.oppfolgingsperiode.uuid}. " +
					"Oppretter nytt aktivitetskort for personIdent $personIdent og endrer eksisterende translation entry")
				syncOppfolgingsperioder(deltakelse.tiltakdeltakelseId, periodeMatch.allePerioder)
			}
			is EndringsType.NyttAktivitetskort -> {}
			is EndringsType.OppdaterAktivitet -> {
				log.info("Patcher oppfølgingsperiode sluttdato for aktivitet deltakerId:${deltakelse.tiltakdeltakelseId}")
				syncOppfolgingsperioder(deltakelse.tiltakdeltakelseId, periodeMatch.allePerioder)
			}
		}

		val aktivitet = ArenaDeltakerConverter
			.convertToTiltaksaktivitet(
				deltaker = deltakelse,
				aktivitetskortId = endring.aktivitetskortId,
				personIdent = personIdent,
				arrangorNavn = gjennomforing.arrangorNavn,
				gjennomforingNavn = gjennomforing.navn ?: "Ukjent navn",
				tiltak = tiltak,
				isDelete = message.operationType == Operation.DELETED
			)


		val aktivitetskortHeaders = AktivitetskortHeaders(
			arenaId = "${KafkaProducerService.TILTAK_ID_PREFIX}${deltakelse.tiltakdeltakelseId}",
			tiltakKode = tiltak.kode,
			oppfolgingsperiode = periodeMatch.oppfolgingsperiode.uuid,
			oppfolgingsSluttDato = periodeMatch.oppfolgingsperiode.sluttDato
		)

		aktivitetService.upsert(aktivitet, aktivitetskortHeaders, deltakelse.tiltakdeltakelseId, IgnorertStatus.IKKE_IGNORERT != endring.skalIgnoreres )

		if (endring.skalIgnoreres == IgnorertStatus.IGNORERT_SLETTEMELDING) {
			log.info("Mottok slettemelding men deltaker var allerede i en ferdig-status")
			arenaDataRepository.upsert(message.toUpsertInputWithStatusHandledAndIgnored(deltakelse.tiltakdeltakelseId, "ignorert slettemelding"))
			return
		}

		if (endring.skalIgnoreres == IgnorertStatus.FORELOPIG_IGNORERT) {
			log.info("Deltakeren har status=${arenaDeltaker.DELTAKERSTATUSKODE} og administrasjonskode=${tiltak.administrasjonskode} som ikke skal håndteres")
			arenaDataRepository.upsert(message.toUpsertInputWithStatusHandledAndIgnored(deltakelse.tiltakdeltakelseId, "foreløpig ignorert"))
			return
		}

		arenaDataRepository.upsert(message.toUpsertInputWithStatusHandled(deltakelse.tiltakdeltakelseId))
		val outgoingMessage = aktivitet.toKafkaMessage()
		secureLog.info("Sender melding for aktivitetskort id=${endring.aktivitetskortId} arenaId=${deltakelse.tiltakdeltakelseId} personId=${deltakelse.personId} fnr=$personIdent")
		log.info("Sender medling messageId=${outgoingMessage.messageId} aktivitetskort id=$endring.aktivitetskortId  arenaId=${deltakelse.tiltakdeltakelseId} type=${outgoingMessage.actionType}")
		kafkaProducerService.sendTilAktivitetskortTopic(
			aktivitet.id,
			outgoingMessage,
			aktivitetskortHeaders
		)
	}

	//	Alle tiltaksaktiviteter hentes med unntak for tiltak av
	//	administrasjonstypene Institusjonelt tiltak (INST) og Individuelt tiltak (IND) som har deltakerstatus Aktuell (AKTUELL).
	private fun skalIgnoreres(arenaDeltakerStatusKode: String, administrasjonskode: Tiltak.Administrasjonskode, deltakelseId: DeltakelseId, operationTimestamp: LocalDateTime, operationType: Operation): IgnorertStatus {
		// hvis vi har en tidligere endring som har gått gjennom til aktivitetsplan, kan vi ikke ignorere disse endringene.
		return when {
			operationType == Operation.DELETED && ArenaDeltakerConverter.toAktivitetStatus(arenaDeltakerStatusKode).erAvsluttet() ->
				IgnorertStatus.IGNORERT_SLETTEMELDING
			arenaDataRepository.harTidligereEndringSomIkkeErIgnorert(deltakelseId, operationTimestamp) ->
				IgnorertStatus.IKKE_IGNORERT
			arenaDeltakerStatusKode == "AKTUELL"
				&& administrasjonskode in listOf(Tiltak.Administrasjonskode.IND, Tiltak.Administrasjonskode.INST) ->
				IgnorertStatus.FORELOPIG_IGNORERT
			else -> IgnorertStatus.IKKE_IGNORERT
		}
	}

	private fun handleOppfolgingsperiodeNull(deltakelse: TiltakDeltakelse, personIdent: String, tidspunkt: LocalDateTime, tiltakDeltakelseId: DeltakelseId): Nothing {
		secureLog.info("Fant ikke oppfølgingsperiode for personIdent=$personIdent")
		val erFerdig = deltakelse.datoTil?.isBefore(LocalDate.now()) ?: false
		when {
			deltakelse.erAvsluttet() || erFerdig ->
				throw IgnoredException("Avsluttet deltakelse og ingen oppfølgingsperiode, id=${tiltakDeltakelseId.value}")
			tidspunktTidligereEnnRettFoerStartDato(tidspunkt, LocalDateTime.now(), defaultSlakk) ->
				throw IgnoredException("Opprettet for mer enn $defaultSlakk siden og ingen oppfølgingsperiode, id=${tiltakDeltakelseId.value}")
			else -> throw OppfolgingsperiodeNotFoundException("Deltakelse endret tidspunkt=${tidspunkt}, Finner ingen passende oppfølgingsperiode, id=${tiltakDeltakelseId.value}")
		}
	}

	private fun getOppfolgingsPeriode(deltaker: TiltakDeltakelse, personIdent: String): FinnOppfolgingResult {
		val oppslagsDato = deltaker.datoTil
			?.let { tilDato -> minOf(tilDato.atStartOfDay(), deltaker.modDato) } ?: deltaker.modDato
		return oppfolgingsperiodeService.finnOppfolgingsperiode(personIdent, oppslagsDato)
//		return when (funnetPeriode) {
//			is FinnOppfolgingResult.FunnetPeriodeResult -> funnetPeriode
//			is FinnOppfolgingResult.IngenPeriodeResult -> handleOppfolgingsperiodeNull(deltaker, personIdent, deltaker.modDato, deltaker.tiltakdeltakelseId)
//		}
	}

	private fun getOppfolgingsperiodeForPersonVedLansering(personIdent: String): FinnOppfolgingResult.FunnetPeriodeResult {
		val oppfolgingsperiodeVedAktivitetsplanLansering = oppfolgingsperiodeService.finnOppfolgingsperiode(personIdent, AKTIVITETSPLAN_LANSERINGSDATO)
		return when (oppfolgingsperiodeVedAktivitetsplanLansering) {
			is FinnOppfolgingResult.FunnetPeriodeResult -> oppfolgingsperiodeVedAktivitetsplanLansering
			is FinnOppfolgingResult.IngenPeriodeResult -> throw IgnoredException("Deltakelse aktiv ved aktivitetsplan lansering, men bruker ikke under oppfølging på det tidspunktet.")
		}
	}

	private fun utledEndringsType(
		periodeMatch: FinnOppfolgingResult,
		deltakelseId: DeltakelseId,
		deltakerStatusKode: String,
		administrasjonskode: Tiltak.Administrasjonskode,
		operationTimestamp: LocalDateTime,
		operationType: Operation
	): EndringsType {
		val skalIgnoreres = skalIgnoreres(deltakerStatusKode, administrasjonskode, deltakelseId, operationTimestamp, operationType)
		val aktivitetsKort = aktivitetService.getAllBy(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET)

		val eksisterendeAktivitetsId = when (periodeMatch) {
			is FinnOppfolgingResult.FunnetPeriodeResult -> aktivitetsKort
				.firstOrNull { it.oppfolgingsPeriode == periodeMatch.oppfolgingsperiode.uuid }?.id
			is FinnOppfolgingResult.IngenPeriodeResult -> aktivitetsKort.
		}

		return when {
			måAvslutteAktivitetskort() -> EndringsType.AvbrytDeltakelse(aktivitetsKort.first().id)
			// Har tidligere deltakelse på samme oppfolgingsperiode
			eksisterendeAktivitetsId != null -> EndringsType.OppdaterAktivitet(eksisterendeAktivitetsId, skalIgnoreres)
			// Har ingen tidligere aktivitetskort
			aktivitetsKort.isEmpty() -> EndringsType.NyttAktivitetskort(getAkivitetskortId(deltakelseId), periodeMatch.oppfolgingsperiode, skalIgnoreres)
			// Har tidligere deltakelse men ikke på samme oppfølgingsperiode
			else -> {

				EndringsType.NyttAktivitetskortByttPeriode(periodeMatch.oppfolgingsperiode, skalIgnoreres)
			}
		}
	}

	fun getAkivitetskortId(deltakelseId: DeltakelseId): UUID {
		return aktivitetskortIdService.getOrCreate(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET)
	}

	fun syncOppfolgingsperioder(deltakelseId: DeltakelseId, oppfolginsperioder: List<Oppfolgingsperiode>) {
		aktivitetService.closeClosedPerioder(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET, oppfolginsperioder)
	}

	private fun TiltakDeltakelse.opprettetFørLansering(): Boolean {
		return this.regDato.isBefore(AKTIVITETSPLAN_LANSERINGSDATO)
			&& this.modDato.isBefore(AKTIVITETSPLAN_LANSERINGSDATO)
	}
	private fun TiltakDeltakelse.varAktivEtterLansering(): Boolean {
		return this.datoTil?.isAfter(AKTIVITETSPLAN_LANSERINGSDATO.toLocalDate()) == true
	}
	private fun TiltakDeltakelse.sjekkIkkeFerdigFørLansering() {
		if (this.opprettetFørLansering() && !this.varAktivEtterLansering()) {
			throw IgnoredException("Deltakeren registrert=${this.regDato} opprettet før aktivitetsplan skal ikke håndteres")
		}
	}
	private fun TiltakDeltakelse.opprettetFørMenAktivEtterLansering(): Boolean {
		// Hvis deltakelsen er opprettet før aktivitetsplan lanseringsdato,
		// _men_ datoTil er etter aktivitetsplan lanseringsdato,
		// _og_ bruker hadde en aktiv oppfølgingsperiode ved aktivitetsplan lanseringsdato
		// så skal vi opprette aktivitetskort
		return this.opprettetFørLansering() && this.varAktivEtterLansering()
	}

	private fun TiltakDeltakelse.erAvsluttet(): Boolean {
		return ArenaDeltakerConverter.toAktivitetStatus(this.deltakerStatusKode).erAvsluttet()
	}

	private fun måAvslutteAktivitetskort(): Boolean {
		val harOpprettetAktivitetskortUtenSplitt = oppfolgingsperiodeTilAktivitetskortId == 1
		val ingenOppfølgingsperiode = periodeMatch is FinnOppfolgingResult.IngenPeriodeResult
		val erAvsluttet = listOf(AVBRUTT, FULLFORT).contains(oppfolgingsperiodeTilAktivitetskortId.first().status)
		val måavslutteOpprettetAktivitetskort = harOpprettetAktivitetskortUtenSplitt && ingenOppfølgingsperiode && !erAvsluttet
	}
}

enum class IgnorertStatus {
	FORELOPIG_IGNORERT,
	IGNORERT_SLETTEMELDING,
	IKKE_IGNORERT
}

sealed class EndringsType(val aktivitetskortId: UUID, val skalIgnoreres: IgnorertStatus) {
	class OppdaterAktivitet(aktivitetskortId: UUID, skalIgnoreres: IgnorertStatus): EndringsType(aktivitetskortId, skalIgnoreres)
	class NyttAktivitetskort(aktivitetskortId:UUID, val oppfolgingsperiode: Oppfolgingsperiode, skalIgnoreres: IgnorertStatus): EndringsType(aktivitetskortId, skalIgnoreres)
	class NyttAktivitetskortByttPeriode(val oppfolgingsperiode: Oppfolgingsperiode, skalIgnoreres: IgnorertStatus): EndringsType(UUID.randomUUID(), skalIgnoreres)
	class AvbrytDeltakelse(aktivitetskortId: UUID)
}



