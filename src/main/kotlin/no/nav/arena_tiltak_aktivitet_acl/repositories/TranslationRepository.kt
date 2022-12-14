package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.domain.db.TranslationDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.utils.DatabaseUtils.sqlParameters
import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
open class TranslationRepository(
	private val template: NamedParameterJdbcTemplate
) {

	private val rowMapper = RowMapper { rs, _ ->
		TranslationDbo(
			aktivitetId = rs.getUUID("aktivitet_id"),
			arenaId = rs.getLong("arena_id"),
			aktivitetKategori = AktivitetKategori.valueOf(rs.getString("aktivitet_kategori"))
		)
	}

	fun insert(entry: TranslationDbo) {
		val sql = """
			INSERT INTO translation(aktivitet_id, arena_id, aktivitet_kategori)
			VALUES (:aktivitet_id, :arena_id, :aktivitet_kategori)
		""".trimIndent()

		try {
			template.update(sql, entry.asParameterSource())
		} catch (e: DuplicateKeyException) {
			throw IllegalStateException("Translation entry on table with id ${entry.arenaId} already exist.")
		}
	}

	fun get(arenaId: Long, aktivitetKategori: AktivitetKategori): TranslationDbo? {
		val sql = """
			SELECT *
				FROM translation
				WHERE arena_id = :arena_id
				AND aktivitet_kategori = :aktivitet_kategori
		""".trimIndent()

		val parameters = sqlParameters(
			"arena_id" to arenaId,
			"aktivitet_kategori" to aktivitetKategori.name
		)

		return template.query(sql, parameters, rowMapper)
			.firstOrNull()
	}

	private fun TranslationDbo.asParameterSource() = sqlParameters(
		"aktivitet_id" to aktivitetId,
		"arena_id" to arenaId,
		"aktivitet_kategori" to aktivitetKategori.name
	)

}

