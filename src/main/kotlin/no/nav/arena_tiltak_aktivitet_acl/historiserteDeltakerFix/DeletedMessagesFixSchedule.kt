package no.nav.arena_tiltak_aktivitet_acl.historiserteDeltakerFix

import io.getunleash.Unleash
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.ArenaDeltakelse
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetskortIdRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.utils.ONE_MINUTE
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import no.nav.arena_tiltak_aktivitet_acl.utils.asBackwardsFormattedLocalDateTime
import no.nav.common.job.JobRunner
import no.nav.common.job.leader_election.LeaderElectionClient
import org.slf4j.LoggerFactory
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.IllegalStateException

/* Fikser deltakerlser som har blitt slettet fra tiltaksdeltaker tabellen
* Enten har vi gått glipp av slettemelding og står i feil state
* eller så mangler vi deltakelsen helt (feks ARENTAH fra synkrone endpunkt)
*   */
@Component
class DeletedMessagesFixSchedule(
	val historiskDeltakelseRepo: HistoriskDeltakelseRepo,
	val arenaDataRepository: ArenaDataRepository,
	val aktivitetskortIdRepository: AktivitetskortIdRepository,
	val leaderElectionClient: LeaderElectionClient,
	val unleash: Unleash
) {
	private val log = LoggerFactory.getLogger(javaClass)

//	@Scheduled(initialDelay = ONE_MINUTE, fixedDelay = Long.MAX_VALUE/1_000_000)
	fun prosesserDataFraHistoriskeDeltakelser() {
		if (!leaderElectionClient.isLeader) return
		if (!unleash.isEnabled("aktivitet-arena-acl.deletedMessagesFix.enabled")) return
		JobRunner.run("prosesserDataFraHistoriskeDeltakelser") {
// 		hentNesteBatchMedHistoriskeDeltakelser()
//			.map { it.utledFixMetode() }
//			.map { fix -> utførFix(fix, HistoriskDeltakelseRepo.Table.hist_tiltakdeltaker) }
//		hentNesteBatchMedSlettedeDeltakelser()
//			.map { it.utledFixMetode() }
//			.map { fix -> utførFix(fix, HistoriskDeltakelseRepo.Table.deleted_singles_hist_format) }
			hentNesteBatchMedTapteDeltakelser()
				.map { it.utledFixMetode() }
				.map { fix -> utførFix(fix, HistoriskDeltakelseRepo.Table.lost_in_translation) }
		}
	}

	fun utførFix(fix: FixMetode, table: HistoriskDeltakelseRepo.Table) {
		when (fix) {
			is OpprettMedLegacyId -> {
				log.info("OpprettMedLegacyId ${fix.deltakelseId}")
				// Bruk ID-som allerede eksisterer i Veilarbaktivitet
				aktivitetskortIdRepository.getOrCreate(fix.deltakelseId, AktivitetKategori.TILTAKSAKTIVITET, fix.funksjonellId)
//				arenaDataRepository.upsertTemp(fix.toArenaDataUpsertInput())
			}
			is Opprett -> {
				log.info("Opprett ny for historisk deltakelseid ${fix.historiskDeltakelseId}")
//				arenaDataRepository.upsertTemp(fix.toArenaDataUpsertInput())
			}
			is Oppdater -> {
				log.info("Oppdater eksisterende deltakerid ${fix.deltakelseId}")
				arenaDataRepository.upsertTemp(fix.toArenaDataUpsertInput())
				historiskDeltakelseRepo.oppdaterFixMetode(fix, table)
			}
			is OppdaterTaptIACLMenFinnesIVeilarbaktivitet -> {
				log.info("OppdaterTaptIACLMenFinnesIVeilarbaktivitet eksisterende deltakerid ${fix.deltakelseId}")
				aktivitetskortIdRepository.getOrCreate(fix.deltakelseId, AktivitetKategori.TILTAKSAKTIVITET, fix.funksjonellId)
				arenaDataRepository.upsertTemp(fix.toArenaDataUpsertInput())
				historiskDeltakelseRepo.oppdaterFixMetode(fix, table)
			}
		}

	}

	fun hentPosFraHullet(): OperationPos {
		// TODO: Bruk posisjonene fra hullet
		/*
		operation_pos 100493841434 til 109986616390 er ledige,
		9492774956 ledige plasser

		select width_bucket(cast(operation_pos as numeric) , 2130012227006, 2800480067873, 10000000) as bucket,
       		count(*) as frequency from arena_data
                             where cast(operation_pos as numeric) > 2130012227006
                         group by bucket order by bucket
		;

		(2800480067873-2130012227006)/10000000 - bucket size 67046
		hull mellom bucket nr 1498878 og 1640466
		altså fra 1498879 til 1640465 (141586 buckets) -
 		altså fra pos 1498879 * 67046 til 1640465 * 67046
 		altså pos 100493841434 til 109986616390
 		9492774956 ledige plasser
		 */
		State.minimumpos++
		return OperationPos(State.minimumpos)
	}

	private fun hentNesteBatchMedHistoriskeDeltakelser(): List<HistoriskDeltakelse> {
		return  historiskDeltakelseRepo.getHistoriskeDeltakelser(HistoriskDeltakelseRepo.Table.hist_tiltakdeltaker)
	}
	private fun hentNesteBatchMedSlettedeDeltakelser(): List<SlettetDeltakelse> {
		return  historiskDeltakelseRepo.getHistoriskeDeltakelser(HistoriskDeltakelseRepo.Table.deleted_singles_hist_format).map { SlettetDeltakelse(it) }
	}

	private fun hentNesteBatchMedTapteDeltakelser(): List<TaptDeltakelse> {
		return  historiskDeltakelseRepo.getTapteDeltakelser()
	}

	fun TaptDeltakelse.utledFixMetode(): FixMetode {
		val deltakelseId = DeltakelseId(this.data.hist_tiltakdeltaker_id)
		val sisteArenaDeltakelse = finnSisteOppdateringArenaDeltakelseNullable(deltakelseId)
		if (sisteArenaDeltakelse != null) {
			log.info("Fant eksisterende deltakelse på tapt-deltakelse: ${sisteArenaDeltakelse.TILTAKDELTAKER_ID}, historisk splitt som mangler?")
		}
		val legacyId = historiskDeltakelseRepo.getLegacyId(deltakelseId) ?: throw IllegalStateException("Må finnes en id i translation tabellen")
		return OppdaterTaptIACLMenFinnesIVeilarbaktivitet(deltakelseId, this.data, legacyId, this.operation,  hentPosFraHullet())
	}

	fun SlettetDeltakelse.utledFixMetode(): FixMetode {
		val deltakelseId = DeltakelseId(this.data.hist_tiltakdeltaker_id)
		val sisteArenaDeltakelse = finnSisteOppdateringArenaDeltakelseNullable(deltakelseId)
		return when {
			sisteArenaDeltakelse != null -> Oppdater(deltakelseId, this.data, hentPosFraHullet())
			else -> {
				val legacyId = historiskDeltakelseRepo.getLegacyId(deltakelseId)
				when {
					legacyId != null -> OpprettMedLegacyId(deltakelseId, this.data, legacyId, hentPosFraHullet())
					else -> Opprett(deltakelseId, this.data, hentPosFraHullet())
				}
			}
		}
	}

	fun HistoriskDeltakelse.utledFixMetode(): FixMetode {
		val datoStatusEndring = this.dato_statusendring?.asBackwardsFormattedLocalDateTime("dato_statusendring")
		val arenaDataDeltakelser =
			historiskDeltakelseRepo.finnEksisterendeDeltakelserForGjennomforing(person_id, tiltakgjennomforing_id) // alle deltakelser vi har i våre data for denne person-gjennomføring
		val matchMedFilter = arenaDataDeltakelser
			.filter { it.lastestStatusEndretDato == datoStatusEndring } // er det noen av våre deltakelser som matcher med denne historisk deltakelsen?

		return when {
			// Bare 1 kan matche
			matchMedFilter.size > 1 -> throw IllegalArgumentException("Flere matcher på historiske, ${matchMedFilter.joinToString { it.deltakelseId.toString() }}")
			matchMedFilter.size == 1 -> { // 1 match med filter
				val match = matchMedFilter.first()
				return Oppdater(match.deltakelseId, this, generertPos = hentPosFraHullet())
			}
			//  Har ikke sett denne meldingen før men finnes kanskje matchende arena-data hvis vi har legacy-id
			// matchMedFilter.size == 0
			arenaDataDeltakelser.isNotEmpty() -> {
				// Her kan det hende vi har den likevel, men dato_statusendring er ikke oppdatert hos oss. (hullet)
				log.info("Fant ingen eksisterende arenadeltakelse for historisk deltakelse ${this.hist_tiltakdeltaker_id}")
				val legacyId = datoStatusEndring?.let { historiskDeltakelseRepo.getLegacyId(this.person_id, this.tiltakgjennomforing_id, it) } // Jovisst, vi hadde den likevel - OK
				// hvis legacy id finnes i arena_data -> Oppdater
				when {
					legacyId != null -> {
						if (historiskDeltakelseRepo.deltakelseExists(legacyId)) {  // Fant den den i translation, men vi har den i arena_data
							// Siden dato-statusendring ikke matcher vet vi at dataen vår ikke er oppdatert
							Oppdater(legacyId.deltakerId, this, generertPos = hentPosFraHullet())
						} else {
							OpprettMedLegacyId(legacyId.deltakerId, this, legacyId.funksjonellId, generertPos = hentPosFraHullet())
						}
					}
					else -> Opprett(genererDeltakelseId(), this, generertPos = hentPosFraHullet())
				}
			}
			else -> { // Ingen deltakerlser på person-gjennomføring i våre data (arena-data)
				val legacyId = datoStatusEndring?.let { historiskDeltakelseRepo.getLegacyId(this.person_id, this.tiltakgjennomforing_id, it) } // Jovisst, vi hadde den likevel - OK
				when {
					legacyId != null -> OpprettMedLegacyId(legacyId.deltakerId, this, legacyId.funksjonellId, hentPosFraHullet())
					else -> Opprett(genererDeltakelseId(), this, hentPosFraHullet())
				}
			}
		}
	}

	fun finnArenaDeltakelse(deltakelseId: DeltakelseId, operationPos: OperationPos): ArenaDeltakelse {
		return (historiskDeltakelseRepo.getMostRecentDeltakelse(deltakelseId, operationPos)
			?: throw IllegalArgumentException("Fant ikke deltakelse i arena-data: ${deltakelseId.value}"))
			.toArenaDeltakelse()
	}
	fun finnSisteOppdateringArenaDeltakelse(deltakelseId: DeltakelseId): ArenaDeltakelse {
		return arenaDataRepository.getMostRecentDeltakelse(deltakelseId).toArenaDeltakelse()
	}
	fun finnSisteOppdateringArenaDeltakelseNullable(deltakelseId: DeltakelseId): ArenaDeltakelse? {
		return runCatching { finnSisteOppdateringArenaDeltakelse(deltakelseId) }
			.getOrElse { if (it is IncorrectResultSizeDataAccessException) null else throw it }
	}
	fun genererDeltakelseId(): DeltakelseId {
		return historiskDeltakelseRepo.getNextFreeDeltakerId(State.forrigeLedigeDeltakelse)
			.also { State.forrigeLedigeDeltakelse = it }
			.also { log.info("Fant ledig deltakelseId: ${it.value}") }
	}
}

/*
HAR_DELTAKELSE_RIKTIG_STATUS, // All good, ikke gjør noe
HAR_DELTAKELSE_FEIL_STATUS, // Fix status, simuler en slettemelding

HAR_IKKE_DELTAKELSE_MEN_HAR_TRANSLATION,
// Lag nytt kort i riktig status, Trygt å lage så lenge det er i ny tabell?

HAR_IKKE_DELTAKELSE_NOEN_PLASSER,
// Lag nytt kort, risikerer duplikater hvis kort egentlig finnes i veilarbaktivitet men ikk i ACL

 */
val mapper = ObjectMapper.get()
fun ArenaDataDbo.toArenaDeltakelse(): ArenaDeltakelse {
	return when (this.operation) {
		Operation.DELETED -> this.before // Skal egentlig ikke skje?
		else -> this.after
	}
		.let { mapper.readValue(it, ArenaDeltakelse::class.java) }
}

object State {
//	var minimumpos = 100493841434
//	var minimumpos = 100493929331+1
	var minimumpos = 100493934002+1
	var forrigeLedigeDeltakelse = DeltakelseId(153)
}
