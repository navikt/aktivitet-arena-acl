package no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging

import java.time.ZonedDateTime
import java.time.chrono.ChronoZonedDateTime
import java.util.*

data class Oppfolgingsperiode (
	val uuid: UUID,
	val startTidspunkt: ZonedDateTime,
	val sluttTidspunkt: ZonedDateTime?
) {
	fun tidspunktInnenforPeriode(tidspunkt: ChronoZonedDateTime<*>): Boolean {
		val startetIPeriode = tidspunkt.isAfter(startTidspunkt) || tidspunkt.isEqual(startTidspunkt)
		val foerSluttDato = sluttTidspunkt == null || sluttTidspunkt.isAfter(tidspunkt)
		return startetIPeriode && foerSluttDato
	}
}

data class AvsluttetOppfolgingsperiode (
	val uuid: UUID,
	val startDato: ZonedDateTime,
	val sluttDato: ZonedDateTime
)
