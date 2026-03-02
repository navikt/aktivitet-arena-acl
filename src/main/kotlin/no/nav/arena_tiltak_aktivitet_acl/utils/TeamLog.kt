package no.nav.arena_tiltak_aktivitet_acl.utils

import org.slf4j.LoggerFactory;

object TeamLog {
	@JvmField
	val teamLog = LoggerFactory.getLogger("team-logs-logger") ?: throw IllegalStateException("Klarte ikke å instansiere Team Logs logger.")
}
