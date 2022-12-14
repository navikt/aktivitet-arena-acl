package no.nav.arena_tiltak_aktivitet_acl.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DateUtils {

	private val arenaDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")

	fun parseArenaDateTime(arenaDateTime: String): LocalDateTime {
		return LocalDateTime.parse(arenaDateTime, arenaDateTimeFormatter)
	}

}
