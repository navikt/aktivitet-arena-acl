package no.nav.arena_tiltak_aktivitet_acl.services

import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.AvsluttetOppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.repositories.AdvisoryLockRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingDbo
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingRespository
import no.nav.arena_tiltak_aktivitet_acl.repositories.EksisterendeForelopigId
import no.nav.arena_tiltak_aktivitet_acl.repositories.FantIdITranslationTabell
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortId
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortIdRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.NyForelopigId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import java.util.*
import java.util.UUID
import kotlin.Long

@Service
open class AktivitetskortIdService(
	val aktivitetRepository: AktivitetRepository, // Brukes kun for å sjekke at aktivitetId er konsistent
	val forelopigAktivitetskortIdRepository: ForelopigAktivitetskortIdRepository,
	val advisoryLockRepository: AdvisoryLockRepository,
	val deltakerAktivitetMappingRespository: DeltakerAktivitetMappingRespository
) {
	private val log = LoggerFactory.getLogger(AktivitetskortIdService::class.java)

	/**
	 * SafeDeltakelse will make sure no other transaction is processing the same deltakelse for the duration of the ongoing transaction.
	 * If another transaction is processing the same deltakelse (i.e. AktivitetService) this transaction will wait its turn until the other transaction is complete.
	 * @see no.nav.arena_tiltak_aktivitet_acl.services.AktivitetService.upsert
	 */
	@Transactional
	open fun getOrCreate(arenaId: ArenaId, oppfolgingsperioder: OppfolgingsPeriodeInput): AktivitetskortIdResult {
		// Lock on deltakelseId. Gjelder så lenge den pågående transaksjonen er aktiv.
		advisoryLockRepository.safeDeltakelse(arenaId.deltakelseId).use {
			val trengerNyId = sjekkOmNyAktivitetsIdMåLages(arenaId, oppfolgingsperioder)
			when (trengerNyId) {
				is AvsluttetPeriode -> {
					settSluttdato(trengerNyId.avsluttetPeriode, arenaId)
					return Funnet(trengerNyId.sisteAktivitetskortId)
				}
				is NyAktivitetskortId -> {
					deltakerAktivitetMappingRespository.insert(trengerNyId.toDbo())
					forelopigAktivitetskortIdRepository.deleteDeltakelseId(arenaId)
					/* Etter at man inserter i deltakerAktivitetMappingRespository er ikke id-en lenger foreløpig */
					return Opprettet(trengerNyId.forelopigAktivitetskortId.id)
				}
				is NyPeriodeSplitt -> {
					val nyAktivitetskortId = UUID.randomUUID()
					deltakerAktivitetMappingRespository.insert(trengerNyId.toDbo(nyAktivitetskortId))
					settSluttdato(trengerNyId.gammelPeriode, arenaId)
					return Opprettet(nyAktivitetskortId)
				}
				is BareForelopigIdManglerOppfolgingsperiodeForDeltakelse -> return trengerNyId.forelopigAktivitetskortId.toAktivitetskortIdResult()
				is EksisterendeId -> return Funnet(trengerNyId.sisteAktivitetskortId)
				is EksisterendeIdManglerOppfolgingsPeriodeInput -> return Funnet(trengerNyId.aktivitetskortId)
			}
		}
	}

	sealed class AktivitetskortIdResult
	class Forelopig(val forelopigAktivitetskortId: ForelopigAktivitetskortId): AktivitetskortIdResult()
	class Opprettet(val aktivitetskortId: UUID): AktivitetskortIdResult()
	class Funnet(val aktivitetskortId: UUID): AktivitetskortIdResult()

	fun ForelopigAktivitetskortId.toAktivitetskortIdResult(): AktivitetskortIdResult {
		return when (this) {
			is NyForelopigId -> Forelopig(this)
			is EksisterendeForelopigId -> Forelopig(this)
			is FantIdITranslationTabell -> Forelopig(this)
		}
	}

	private fun sjekkOmNyAktivitetsIdMåLages(arenaId: ArenaId, periodeInput: OppfolgingsPeriodeInput): TrengerNyIdResultat {
		val sisteAktivitet = aktivitetRepository.getCurrentAktivitetsId(arenaId.deltakelseId, arenaId.aktivitetKategori)
			?.let { aktivitetRepository.getAktivitet(it) }

		val sisteAktivitetskortId = deltakerAktivitetMappingRespository.getCurrentDeltakerAktivitetMapping(arenaId.deltakelseId, arenaId.aktivitetKategori)
		val currentOppfolgingsperiodeId = sisteAktivitetskortId?.oppfolgingsPeriodeId

		if (sisteAktivitet != null && sisteAktivitetskortId == null) {
			deltakerAktivitetMappingRespository.insert(DeltakerAktivitetMappingDbo(
				deltakelseId = sisteAktivitet.arenaId.substring(7).toLong(), // ARENATA6568951
				aktivitetId = sisteAktivitet.id,
				aktivitetKategori = arenaId.aktivitetKategori.name,
				oppfolgingsPeriodeId = sisteAktivitet.oppfolgingsperiodeUUID,
			 	oppfolgingsPeriodeSluttTidspunkt = sisteAktivitet.oppfolgingsSluttTidspunkt
			)
			)
			log.error("Fant opprettet aktivitetskort uten aktivitetId i mapping tabell, delatker-aktivitet mapping er ikke blitt oppdatert for deltakelseId: ${arenaId.deltakelseId.value}")
			//throw IllegalStateException("App må fikses!")
		}

		if (periodeInput is UkjentPersonIngenPerioder) {
			log.warn("Ukjent person for deltakelseId: $arenaId, ingen aktivitetskort eller deltakelse-aktivitet mapping funnet")
		}

		val oppfolgingsperioder = when (periodeInput) {
			is BrukNyestePeriode -> periodeInput.oppfolgingsperioder
			is FerdigMatchetPeriode -> periodeInput.oppfolgingsperioder
			is UkjentPersonIngenPerioder -> emptyList()
		}

		val periodeForDeltakelse = when (periodeInput) {
			is FerdigMatchetPeriode -> periodeInput.periodeForDeltakelse
			is BrukNyestePeriode -> oppfolgingsperioder.maxByOrNull { it.startTidspunkt } // Hva med tom liste? Ingen perioder?
			is UkjentPersonIngenPerioder -> null
		}

		return when {
			sisteAktivitetskortId == null -> {
				when (periodeForDeltakelse != null) {
					true -> {
						if (periodeForDeltakelse.sluttTidspunkt != null) {
							log.warn("Utleverte aktivitetskortId for oppfolgingsperiode som er avsluttet")
						}
						NyAktivitetskortId(arenaId,
							forelopigAktivitetskortIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori)
								.also { logIdMappingOpprettet(it, periodeForDeltakelse, arenaId) }, periodeForDeltakelse)
					}
					false -> BareForelopigIdManglerOppfolgingsperiodeForDeltakelse(arenaId, forelopigAktivitetskortIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori)
							.also { logOmForelopigIdBleOpprettet(it, arenaId) })
				}
			}
			periodeForDeltakelse == null -> {
				if (periodeInput is UkjentPersonIngenPerioder) log.info("Ingen oppfølgingsperioder funnet for deltakelseId: ${arenaId.deltakelseId}")
				if (periodeInput is BrukNyestePeriode) log.info("Ingen oppfølgingsperioder funnet for deltakelseId: ${arenaId.deltakelseId}")
				EksisterendeIdManglerOppfolgingsPeriodeInput(arenaId,sisteAktivitetskortId.aktivitetId)
			}
			currentOppfolgingsperiodeId == periodeForDeltakelse.uuid -> {
				if (sisteAktivitetskortId.oppfolgingsPeriodeSluttTidspunkt == null && periodeForDeltakelse.sluttTidspunkt != null) {
					log.info("Oppdaterer oppfolgingsperiode med sluttdato")
					return AvsluttetPeriode(arenaId,sisteAktivitetskortId.aktivitetId, AvsluttetOppfolgingsperiode(
						uuid = periodeForDeltakelse.uuid,
						startDato = periodeForDeltakelse.startTidspunkt,
						sluttDato = periodeForDeltakelse.sluttTidspunkt
					))
				} else {
					log.info("Returnerer siste aktivitetskortId:${sisteAktivitetskortId.aktivitetId} for deltakelseId: ${arenaId.deltakelseId}, periode: ${periodeForDeltakelse.uuid}")
					EksisterendeId(arenaId, sisteAktivitetskortId.aktivitetId)
				}
			}
			else -> {
				log.info("Bruker har fått ny periode:${periodeForDeltakelse.uuid}, gammel:$currentOppfolgingsperiodeId siden sist aktivitetskortsId på deltakelse ${arenaId.deltakelseId} ble opprettet. Oppretter ny aktivitetskortId for ny periode på samme deltakelse ${arenaId.deltakelseId}.")
				val gammelPeriode = oppfolgingsperioder.first { it.uuid == currentOppfolgingsperiodeId }
				NyPeriodeSplitt(
					arenaId,
					periodeForDeltakelse,
					AvsluttetOppfolgingsperiode(
						uuid = gammelPeriode.uuid,
						startDato = gammelPeriode.startTidspunkt,
						sluttDato = gammelPeriode.sluttTidspunkt!!
					)
				)
			}
		}
	}

	private fun logOmForelopigIdBleOpprettet(forelopigAktivitetskortId: ForelopigAktivitetskortId, arenaId: ArenaId) {
		when (forelopigAktivitetskortId) {
			is NyForelopigId -> log.info("Foreløpig aktivitetskortId ble opprettet for person uten oppfølging deltakelseId:${arenaId.deltakelseId.value}, foreløpigAktivitetskortId:${forelopigAktivitetskortId.id}")
			is EksisterendeForelopigId -> log.info("Foreløpig aktivitestkortId eksisterte fra før på person uten oppfølging deltakelseId:${arenaId.deltakelseId.value}, foreløpigAktivitetskortId:${forelopigAktivitetskortId.id}")
			is FantIdITranslationTabell -> log.info("Foreløpig aktivitestkortId ble funnet i translation tabell for person uten oppfølging deltakelseId:${arenaId.deltakelseId.value}, foreløpigAktivitetskortId:${forelopigAktivitetskortId.id}")
		}
	}

	private fun logIdMappingOpprettet(forelopigAktivitetskortId: ForelopigAktivitetskortId, nyestePeriode: Oppfolgingsperiode, arenaId: ArenaId) {
		when (forelopigAktivitetskortId) {
			is NyForelopigId -> log.info("Ny foreløipig aktivitetskortId:${forelopigAktivitetskortId.id} brukt til oppretting av mapping for deltakelse:${arenaId.deltakelseId.value}, periode:${nyestePeriode.uuid}")
			is EksisterendeForelopigId -> log.info("Eksisterende aktivitestkortId:${forelopigAktivitetskortId.id} brukt for å opprette mapping på deltakelse:${arenaId.deltakelseId.value}, periode:${nyestePeriode.uuid}")
			is FantIdITranslationTabell -> log.info("AktivitestkortId fra translation tabell brukt for å opprette mapping på deltakelseId:${arenaId.deltakelseId.value}, foreløpigAktivitetskortId:${forelopigAktivitetskortId.id}, periode:${nyestePeriode.uuid}")
		}
	}

	private fun settSluttdato(avsluttetPeriode: AvsluttetOppfolgingsperiode, arenaId: ArenaId) {
		aktivitetRepository.closeClosedPerioder(arenaId.deltakelseId, arenaId.aktivitetKategori, listOf(avsluttetPeriode))
	}
}

data class ArenaId(
	val deltakelseId: DeltakelseId,
	val aktivitetKategori: AktivitetKategori) {
	override fun toString() = "${aktivitetKategori.prefix}$deltakelseId"
}

sealed class OppfolgingsPeriodeInput
class UkjentPersonIngenPerioder(val deltakelseId: DeltakelseId) : OppfolgingsPeriodeInput()
class BrukNyestePeriode(val oppfolgingsperioder: List<Oppfolgingsperiode>): OppfolgingsPeriodeInput()
class FerdigMatchetPeriode(val periodeForDeltakelse: Oppfolgingsperiode, val oppfolgingsperioder: List<Oppfolgingsperiode>): OppfolgingsPeriodeInput()

sealed class TrengerNyIdResultat(val arenaId: ArenaId)
class BareForelopigIdManglerOppfolgingsperiodeForDeltakelse(arenaId: ArenaId, val forelopigAktivitetskortId: ForelopigAktivitetskortId): TrengerNyIdResultat(arenaId)
class EksisterendeIdManglerOppfolgingsPeriodeInput(arenaId: ArenaId, val aktivitetskortId: UUID): TrengerNyIdResultat(arenaId)
class EksisterendeId(arenaId: ArenaId, val sisteAktivitetskortId: UUID) : TrengerNyIdResultat(arenaId)
class AvsluttetPeriode(arenaId: ArenaId, val sisteAktivitetskortId: UUID, val avsluttetPeriode: AvsluttetOppfolgingsperiode) : TrengerNyIdResultat(arenaId)
class NyPeriodeSplitt(arenaId: ArenaId, val periode: Oppfolgingsperiode, val gammelPeriode: AvsluttetOppfolgingsperiode): TrengerNyIdResultat(arenaId)
class NyAktivitetskortId(
	arenaId: ArenaId,
	/* Denne er bare foreløpig en veldig kort periode fra man henter den ut, til den lagres i deltakerAktiviteteMapping
	* Når getOrCreate returnerer er den ikke lenger foreløpig */
	val forelopigAktivitetskortId: ForelopigAktivitetskortId, val periode: Oppfolgingsperiode): TrengerNyIdResultat(arenaId)

fun NyAktivitetskortId.toDbo(): DeltakerAktivitetMappingDbo {
	return DeltakerAktivitetMappingDbo(
		deltakelseId = arenaId.deltakelseId.value,
		aktivitetId = this.forelopigAktivitetskortId.id,
		aktivitetKategori = arenaId.aktivitetKategori.name,
		oppfolgingsPeriodeId = this.periode.uuid,
		oppfolgingsPeriodeSluttTidspunkt = this.periode.sluttTidspunkt
	)
}

fun NyPeriodeSplitt.toDbo(nyAktivitetskortId: UUID): DeltakerAktivitetMappingDbo {
	return DeltakerAktivitetMappingDbo(
		deltakelseId = arenaId.deltakelseId.value,
		aktivitetId = nyAktivitetskortId,
		aktivitetKategori = arenaId.aktivitetKategori.name,
		oppfolgingsPeriodeId = this.periode.uuid,
		oppfolgingsPeriodeSluttTidspunkt = this.periode.sluttTidspunkt
	)
}
