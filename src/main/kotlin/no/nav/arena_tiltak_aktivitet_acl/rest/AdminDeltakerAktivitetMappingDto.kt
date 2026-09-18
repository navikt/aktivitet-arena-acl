package no.nav.arena_tiltak_aktivitet_acl.rest

import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingDbo
import java.time.ZonedDateTime
import java.util.UUID

data class AdminDeltakerAktivitetMappingDto(
	val deltakelseId: Long,
	val aktivitetId: UUID,
	val aktivitetKategori: String,
	val oppfolgingsPeriodeId: UUID,
	val oppfolgingsPeriodeSluttTidspunkt: ZonedDateTime?,
)

fun DeltakerAktivitetMappingDbo.toAdminDeltakerAktivitetMappingDto() = AdminDeltakerAktivitetMappingDto(
	deltakelseId = deltakelseId,
	aktivitetId = aktivitetId,
	aktivitetKategori = aktivitetKategori,
	oppfolgingsPeriodeId = oppfolgingsPeriodeId,
	oppfolgingsPeriodeSluttTidspunkt = oppfolgingsPeriodeSluttTidspunkt,
)
