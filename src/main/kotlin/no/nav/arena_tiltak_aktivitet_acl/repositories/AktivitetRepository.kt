package no.nav.arena_tiltak_aktivitet_acl.repositories

import java.sql.ResultSet
import java.util.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori

import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import org.intellij.lang.annotations.Language
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
open class AktivitetRepository(
	private val template: NamedParameterJdbcTemplate
) {
	fun upsert(aktivitet: AktivitetDbo) {
		val sql = """
			INSERT INTO aktivitet(id, person_ident, kategori_type, data)
			VALUES (:id,
					:person_ident,
					:kategori_type,
					:data::jsonb)
			ON CONFLICT ON CONSTRAINT aktivitet_pkey
			DO UPDATE SET data = :data::jsonb
		""".trimIndent()

		val parameters = MapSqlParameterSource().addValues(
			mapOf(
				"id" to aktivitet.id,
				"person_ident" to aktivitet.personIdent,
				"kategori_type" to aktivitet.kategori.name,
				"data" to aktivitet.data
			)
		)

		template.update(sql, parameters)
	}

	private val rowMapper = RowMapper { rs, _ -> rs.toAktivitetDbo() }

	fun getAktivitet(aktivitetId: UUID): AktivitetDbo? {
		@Language("SQL")
		val sql = """
			SELECT * FROM aktivitet WHERE id = :id
		""".trimIndent()
		val parameters = mapOf("id" to aktivitetId)

		return template.query(sql, parameters, rowMapper).firstOrNull()
	}
}

fun ResultSet.toAktivitetDbo() =
	AktivitetDbo(
		id = this.getUUID("id"),
		personIdent = this.getString("person_ident"),
		kategori = AktivitetKategori.valueOf(this.getString("kategori_type")),
		data = this.getString("data")
	)
