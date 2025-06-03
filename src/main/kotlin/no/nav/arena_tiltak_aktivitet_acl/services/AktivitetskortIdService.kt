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
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortId
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortIdRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.NyForelopigId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
open class AktivitetskortIdService(
	val aktivitetRepository: AktivitetRepository,
	val forelopigAktivitetskortIdRepository: ForelopigAktivitetskortIdRepository,
	val advisoryLockRepository: AdvisoryLockRepository,
	val deltakerAktivitetMappingRespository: DeltakerAktivitetMappingRespository
) {
	private val log = LoggerFactory.getLogger(AktivitetskortIdService::class.java)

	open fun getByPeriode(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, oppfolgingsperiode: Oppfolgingsperiode): DeltakerAktivitetMappingDbo? {
		return deltakerAktivitetMappingRespository.getByPeriode(deltakelseId, aktivitetKategori, oppfolgingsperiode.uuid)
	}

	sealed class OppfolgingsPeriodeInput
	class UkjentPerson(val deltakelseId: DeltakelseId) : OppfolgingsPeriodeInput()
	class OppfolgingsperioderFunnet(val oppfolgingsperioder: List<Oppfolgingsperiode>): OppfolgingsPeriodeInput()
	class PeriodeMatch(val periodeForDeltakelse: Oppfolgingsperiode,  val oppfolgingsperioder: List<Oppfolgingsperiode>): OppfolgingsPeriodeInput()
	/**
	 * SafeDeltakelse will make sure no other transaction is processing the same deltakelse for the duration of the ongoing transaction.
	 * If another transaction is processing the same deltakelse (i.e. AktivitetService) this transaction will wait its turn until the other transaction is complete.
	 * @see no.nav.arena_tiltak_aktivitet_acl.services.AktivitetService.upsert
	 */
	@Transactional
	open fun getOrCreate(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, oppfolgingsperioder: OppfolgingsPeriodeInput): AktivitetskortIdResult {
		// Lock on deltakelseId. Gjelder så lenge den pågående transaksjonen er aktiv.
		advisoryLockRepository.safeDeltakelse(deltakelseId).use {
			val trengerNyId = sjekkOmNyAktivitetsIdMåLages(deltakelseId, aktivitetKategori, oppfolgingsperioder)
			when (trengerNyId) {
				is AvsluttetPeriode -> {
					settSluttdato(trengerNyId.avsluttetPeriode, deltakelseId, aktivitetKategori)
					return Gotten(trengerNyId.sisteAktivitetskortId)
				}
				is NyAktivitetskortId -> {
					deltakerAktivitetMappingRespository.insert(
						DeltakerAktivitetMappingDbo(
							deltakelseId = deltakelseId.value,
							aktivitetId = trengerNyId.forelopigAktivitetskortId.id,
							aktivitetKategori = aktivitetKategori.name,
							oppfolgingsPeriodeId = trengerNyId.periode.uuid,
							oppfolgingsPeriodeSluttTidspunkt = trengerNyId.periode.sluttTidspunkt
						)
					)
					forelopigAktivitetskortIdRepository.deleteDeltakelseId(deltakelseId, aktivitetKategori)
					return Created(trengerNyId.forelopigAktivitetskortId)
				}
				is NyPeriode -> {
					val nyAktivitetskortId = UUID.randomUUID()
					deltakerAktivitetMappingRespository.insert(
						DeltakerAktivitetMappingDbo(
							deltakelseId = deltakelseId.value,
							aktivitetId = nyAktivitetskortId,
							aktivitetKategori = aktivitetKategori.name,
							oppfolgingsPeriodeId = trengerNyId.periode.uuid,
							oppfolgingsPeriodeSluttTidspunkt = trengerNyId.periode.sluttTidspunkt
						)
					)
					return Gotten(nyAktivitetskortId)
				}
				is BareForelopigIdManglerOppfolging -> return trengerNyId.forelopigAktivitetskortId.toAktivitetskortIdResult()
				is IngenEndring -> return Gotten(trengerNyId.sisteAktivitetskortId)
				is ManglerOppfolgingsPerioder -> return trengerNyId.forelopigAktivitetskortId.toAktivitetskortIdResult()
			}
		}
	}

	sealed class AktivitetskortIdResult
	class Created(val forelopigAktivitetskortId: ForelopigAktivitetskortId): AktivitetskortIdResult()
	class Gotten(val aktivitetskortId: UUID): AktivitetskortIdResult()
	fun ForelopigAktivitetskortId.toAktivitetskortIdResult(): AktivitetskortIdResult {
		return when (this) {
			is NyForelopigId -> Created(this)
			is EksisterendeForelopigId -> Gotten(this.id)
		}
	}

	private fun sjekkOmNyAktivitetsIdMåLages(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, periodeInput: OppfolgingsPeriodeInput): TrengerNyIdResultat {
		val sisteAktivitet = aktivitetRepository.getCurrentAktivitetsId(deltakelseId, aktivitetKategori)
			?.let { aktivitetRepository.getAktivitet(it) }

		val sisteAktivitetsIdForDeltakelse = deltakerAktivitetMappingRespository.getCurrentDeltakerAktivitetMapping(deltakelseId, aktivitetKategori)
		val currentOppfolgingsperiodeId = sisteAktivitetsIdForDeltakelse?.oppfolgingsPeriodeId

		if (sisteAktivitet != null && sisteAktivitetsIdForDeltakelse == null) {
			log.error("Fant opprettet aktivitetskort uten aktivitetId i mapping tabell, delatker-aktivitet mapping er ikke blitt oppdatert for deltakelseId: $deltakelseId, aktivitetKategori: $aktivitetKategori")
			throw IllegalStateException("App må fikses!")
		}

		if (periodeInput is UkjentPerson) {
			log.warn("Ukjent person for deltakelseId: $deltakelseId, ingen aktivitetskort eller deltakelse-aktivitet mapping funnet")
		}
		val allePerioder = when (periodeInput) {
			is OppfolgingsperioderFunnet -> periodeInput.oppfolgingsperioder
			is PeriodeMatch -> periodeInput.oppfolgingsperioder
			is UkjentPerson -> emptyList()
		}

		val periodeForDeltakelse = when (periodeInput) {
			is PeriodeMatch -> periodeInput.periodeForDeltakelse
			is OppfolgingsperioderFunnet -> allePerioder.maxByOrNull { it.startTidspunkt }
			else -> null
		}

		return when {
			sisteAktivitetsIdForDeltakelse == null -> {
				when (periodeForDeltakelse != null) {
					true -> NyAktivitetskortId(
						forelopigAktivitetskortIdRepository.getOrCreate(deltakelseId, aktivitetKategori)
							.also { logIdMappingOpprettet(it, periodeForDeltakelse, deltakelseId) }, periodeForDeltakelse)
					false -> BareForelopigIdManglerOppfolging(forelopigAktivitetskortIdRepository.getOrCreate(deltakelseId, aktivitetKategori)
							.also { logOmForelopigIdBleOpprettet(it, deltakelseId) })
				}
			}
			periodeForDeltakelse == null -> {
				log.info("Ingen oppfølgingsperioder funnet")
				ManglerOppfolgingsPerioder(forelopigAktivitetskortIdRepository.getOrCreate(deltakelseId, aktivitetKategori))
			}
			currentOppfolgingsperiodeId == periodeForDeltakelse.uuid -> {
				if (sisteAktivitetsIdForDeltakelse.oppfolgingsPeriodeSluttTidspunkt == null && periodeForDeltakelse.sluttTidspunkt != null) {
					log.info("Oppdaterer oppfolgingsperiode med sluttdato")
					return AvsluttetPeriode(sisteAktivitetsIdForDeltakelse.aktivitetId, AvsluttetOppfolgingsperiode(
						uuid = periodeForDeltakelse.uuid,
						startDato = periodeForDeltakelse.startTidspunkt,
						sluttDato = periodeForDeltakelse.sluttTidspunkt
					))
				} else {
					log.info("Returnerer siste aktivitetskortId:${sisteAktivitetsIdForDeltakelse.aktivitetId} for deltakelseId: $deltakelseId, periode: ${periodeForDeltakelse.uuid}")
					IngenEndring(sisteAktivitetsIdForDeltakelse.aktivitetId)
				}
			}
			else -> {
				log.info("Bruker har fått ny periode:${periodeForDeltakelse.uuid}, gammel:$currentOppfolgingsperiodeId siden sist aktivitetskortsId på deltakelse $deltakelseId ble opprettet. Oppretter ny aktivitetskortId for ny periode på samme deltakelse $deltakelseId.")
				NyPeriode(
					periodeForDeltakelse,
					AvsluttetOppfolgingsperiode(
						uuid = sisteAktivitetsIdForDeltakelse.oppfolgingsPeriodeId,
						startDato = allePerioder.first { it.uuid == currentOppfolgingsperiodeId }.startTidspunkt,
						sluttDato = sisteAktivitetsIdForDeltakelse.oppfolgingsPeriodeSluttTidspunkt!!
					)
				)
			}
		}
	}

	private fun logOmForelopigIdBleOpprettet(forelopigAktivitetskortId: ForelopigAktivitetskortId, deltakelseId: DeltakelseId) {
		when (forelopigAktivitetskortId) {
			is NyForelopigId -> log.info("Foreløpig aktivitetskortId ble opprettet for person uten oppfølging deltakelseId:${deltakelseId.value}, foreløpigAktivitetskortId:${forelopigAktivitetskortId.id}")
			is EksisterendeForelopigId -> log.info("Foreløpig aktivitestkortId eksisterte fra før på person uten oppfølging deltakelseId:${deltakelseId.value}, foreløpigAktivitetskortId:${forelopigAktivitetskortId.id}")
		}
	}

	private fun logIdMappingOpprettet(forelopigAktivitetskortId: ForelopigAktivitetskortId, nyestePeriode: Oppfolgingsperiode, deltakelseId: DeltakelseId) {
		when (forelopigAktivitetskortId) {
			is NyForelopigId -> log.info("Ny foreløipig aktivitetskortId:${forelopigAktivitetskortId.id} brukt til oppretting av mapping for deltakelse:${deltakelseId.value}, periode:${nyestePeriode.uuid}")
			is EksisterendeForelopigId -> log.info("Eksisterende aktivitestkortId:${forelopigAktivitetskortId.id} brukt for å opprette mapping på deltakelse:${deltakelseId.value} , periode:${nyestePeriode.uuid}")
		}
	}

	private fun settSluttdato(avsluttetPeriode: AvsluttetOppfolgingsperiode, deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori) {
		aktivitetRepository.closeClosedPerioder(deltakelseId, aktivitetKategori, listOf(avsluttetPeriode))
	}
}

sealed class TrengerNyIdResultat()
class BareForelopigIdManglerOppfolging(val forelopigAktivitetskortId: ForelopigAktivitetskortId): TrengerNyIdResultat()
class ManglerOppfolgingsPerioder(val forelopigAktivitetskortId: ForelopigAktivitetskortId): TrengerNyIdResultat()
class IngenEndring(val sisteAktivitetskortId: UUID) : TrengerNyIdResultat()
class AvsluttetPeriode(val sisteAktivitetskortId: UUID, val avsluttetPeriode: AvsluttetOppfolgingsperiode) : TrengerNyIdResultat()
class NyPeriode(val periode: Oppfolgingsperiode, val gammelPeriode: AvsluttetOppfolgingsperiode): TrengerNyIdResultat()
class NyAktivitetskortId(val forelopigAktivitetskortId: ForelopigAktivitetskortId, val periode: Oppfolgingsperiode): TrengerNyIdResultat()
