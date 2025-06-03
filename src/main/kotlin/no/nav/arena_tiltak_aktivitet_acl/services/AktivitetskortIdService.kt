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
	/**
	 * SafeDeltakelse will make sure no other transaction is processing the same deltakelse for the duration of the ongoing transaction.
	 * If another transaction is processing the same deltakelse (i.e. AktivitetService) this transaction will wait its turn until the other transaction is complete.
	 * @see no.nav.arena_tiltak_aktivitet_acl.services.AktivitetService.upsert
	 */
	@Transactional
	open fun getOrCreate(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, allePerioder: List<Oppfolgingsperiode>): AktivitetskortIdResult {
		// Lock on deltakelseId. Gjelder så lenge den pågående transaksjonen er aktiv.
		advisoryLockRepository.safeDeltakelse(deltakelseId).use {
			val trengerNyId = sjekkOmNyAktivitetsIdMåLages(deltakelseId, aktivitetKategori, allePerioder)
			when (trengerNyId) {
				is AvsluttetPeriode -> closeClosedPerioder(allePerioder, deltakelseId, aktivitetKategori)
				is NyPeriode -> {
					// TODO: Insert i deltaker_aktivitet_mapping
					deltakerAktivitetMappingRespository.upsert(
						DeltakerAktivitetMappingDbo(
							deltakelseId = deltakelseId.value,
							aktivitetId = UUID.randomUUID(),
							aktivitetKategori = aktivitetKategori.name,
							oppfolgingsPeriodeId = trengerNyId.periode.uuid,
							oppfolgingsPeriodeSluttdato = trengerNyId.periode.sluttDato
						)
					)
				}
				is BareForelopigId -> return trengerNyId.forelopigAktivitetskortId.toAktivitetskortIdResult()
				is IngenEndring -> return Gotten(trengerNyId.sisteAktivitetskortId)
				is ManglerOppfolgingsPerioder -> return trengerNyId.forelopigAktivitetskortId.toAktivitetskortIdResult()
			}

//			val currentId = aktivitetRepository.getCurrentAktivitetsId(deltakelseId, aktivitetKategori)
			val currentMapping = deltakerAktivitetMappingRespository.getCurrentDeltakerAktivitetMapping(deltakelseId, aktivitetKategori)
			if (currentMapping != null) return Gotten(currentMapping.aktivitetId)
			// Opprett i ny tabell
			return forelopigAktivitetskortIdRepository.getOrCreate(deltakelseId, aktivitetKategori).toAktivitetskortIdResult()
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

	private fun sjekkOmNyAktivitetsIdMåLages(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, allePerioder: List<Oppfolgingsperiode>): TrengerNyIdResultat {
		val sisteAktivitet = aktivitetRepository.getCurrentAktivitetsId(deltakelseId, aktivitetKategori)
			?.let { aktivitetRepository.getAktivitet(it) }
		val nyestePeriode = allePerioder.maxByOrNull { it.startDato }
		return when {
			sisteAktivitet == null -> {
				log.info("Ingen aktivitetskort opprettet for deltakelseId: $deltakelseId, gir ut foreløpig id")
				BareForelopigId(forelopigAktivitetskortIdRepository.getOrCreate(deltakelseId, aktivitetKategori)
					.also {
						when (it) {
							is NyForelopigId -> {
								log.info("Foreløpig aktivitetskortId ble opprettet")
							}
							is EksisterendeForelopigId -> {
								log.info("Foreløpig aktivitestkortId eksisterte fra før")
							}
						}
					})
			}
			nyestePeriode == null -> {
				log.info("Ingen oppfølgingsperioder funnet")
				ManglerOppfolgingsPerioder(forelopigAktivitetskortIdRepository.getOrCreate(deltakelseId, aktivitetKategori))
			}
			sisteAktivitet.oppfolgingsperiodeUUID == nyestePeriode.uuid -> {
				if (sisteAktivitet.oppfolgingsSluttTidspunkt == null && nyestePeriode.sluttDato != null) {
					log.info("Oppdaterer oppfolgingsperiode med sluttdato")
					return AvsluttetPeriode(nyestePeriode)
				} else {
					log.info("Returnerer siste aktivitetskortId")
					IngenEndring(sisteAktivitet.id)
				}
			}
			else -> {
				log.info("Bruker har fått ny periode siden sist aktivitetskort på deltakelse $deltakelseId ble opprettet. Oppretter nytt aktivitetskort for ny periode på samme deltakelse.")
				NyPeriode(nyestePeriode)
			}
		}
	}

	private fun closeClosedPerioder(oppfolgingsperioder: List<Oppfolgingsperiode>, deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori) {
		val avsluttedePerioder = oppfolgingsperioder
			.mapNotNull {
				it.sluttDato
					?.let { slutt -> AvsluttetOppfolgingsperiode(it.uuid, it.startDato, slutt) }
			}
		aktivitetRepository.closeClosedPerioder(deltakelseId, aktivitetKategori, avsluttedePerioder)
	}
}


sealed class TrengerNyIdResultat()
class BareForelopigId(val forelopigAktivitetskortId: ForelopigAktivitetskortId): TrengerNyIdResultat()
class ManglerOppfolgingsPerioder(val forelopigAktivitetskortId: ForelopigAktivitetskortId): TrengerNyIdResultat()
class IngenEndring(val sisteAktivitetskortId: UUID) : TrengerNyIdResultat()
class AvsluttetPeriode(val periode: Oppfolgingsperiode) : TrengerNyIdResultat()
class NyPeriode(val periode: Oppfolgingsperiode): TrengerNyIdResultat()
