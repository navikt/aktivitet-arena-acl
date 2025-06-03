package no.nav.arena_tiltak_aktivitet_acl.services

import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.AvsluttetOppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Aktivitetskort
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetskortHeaders
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.repositories.AdvisoryLockRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortIdRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingRespository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
open class AktivitetService(
	val aktivitetRepository: AktivitetRepository,
	val forelopigAktivitetskortIdRepository: ForelopigAktivitetskortIdRepository,
	val deltakerLockRepository: AdvisoryLockRepository,
	val deltakerAktivitetMappingRespository: DeltakerAktivitetMappingRespository
) {
	/**
	 * SafeDeltakelse will make sure no other transaction is processing the same deltakelse for the duration of the ongoing transaction.
	 * If another transaction is processing the same deltakelse (i.e. TranslationController, AktivitetskortIdService) this transaction will wait its turn until the other transaction is complete.
	 * @see no.nav.arena_tiltak_aktivitet_acl.services.AktivitetskortIdService.getOrCreate
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	open fun upsert(aktivitet: Aktivitetskort, headers: AktivitetskortHeaders, deltakelseId: DeltakelseId, forelopigIgnorert: Boolean = false) {
		deltakerLockRepository.safeDeltakelse(deltakelseId).use {
			aktivitetRepository.upsert(aktivitet.toDbo(headers, forelopigIgnorert))
			forelopigAktivitetskortIdRepository.deleteDeltakelseId(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET)
		}
	}
	open fun get(aktivitetId: UUID) = aktivitetRepository.getAktivitet(aktivitetId)
	open fun getAllBy(aktivitetId: DeltakelseId, aktivitetsKategori: AktivitetKategori) =
		aktivitetRepository.getAllBy(aktivitetId, aktivitetsKategori)

	open fun closeClosedPerioder(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, oppfolgingsperioder: List<Oppfolgingsperiode>) {
		val avsluttedePerioder = oppfolgingsperioder
			.mapNotNull {
				it.sluttDato
					?.let { slutt -> AvsluttetOppfolgingsperiode(it.uuid, it.startDato, slutt) }
			}
		aktivitetRepository.closeClosedPerioder(deltakelseId, aktivitetKategori, avsluttedePerioder)
	}
}
