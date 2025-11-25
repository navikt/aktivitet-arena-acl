package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import java.time.ZonedDateTime
import java.util.*

data class AktivitetDbo (
	val id: UUID,
	val personIdent: String,
	val kategori: AktivitetKategori,
	val data: String,
	val arenaId: String,
	val tiltakKode: String,
	val oppfolgingsperiodeUUID: UUID,
	val forelopigIgnorert: Boolean = false
) {
}
