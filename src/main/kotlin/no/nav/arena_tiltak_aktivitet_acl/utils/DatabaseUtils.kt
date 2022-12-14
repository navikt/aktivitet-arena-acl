package no.nav.arena_tiltak_aktivitet_acl.utils

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

object DatabaseUtils {

	fun <V> sqlParameters(vararg pairs: Pair<String, V>): MapSqlParameterSource {
		return MapSqlParameterSource().addValues(pairs.toMap())
	}

}
