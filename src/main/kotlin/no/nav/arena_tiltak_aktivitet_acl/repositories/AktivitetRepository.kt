package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.AvsluttetOppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.services.ArenaId
import no.nav.arena_tiltak_aktivitet_acl.utils.getNullableZonedDateTime
import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.ZonedDateTime
import java.util.*

@Component
class AktivitetRepository(
	private val template: NamedParameterJdbcTemplate
) {
	private val log = LoggerFactory.getLogger(javaClass)
	fun upsert(aktivitet: AktivitetDbo) {
		@Language("PostgreSQL")
		val sql = """
			INSERT INTO aktivitet(id, person_ident, kategori_type, data, arena_id, tiltak_kode, oppfolgingsperiode_uuid, oppfolgingsperiode_slutt_tidspunkt, forelopig_ignorert)
			VALUES (:id,
					:person_ident,
					:kategori_type,
					:data::jsonb,
					:arena_id,
					:tiltak_kode,
					:oppfolgingsperiode_uuid,
					:oppfolgingsperiode_slutt_tidspunkt,
					:forelopig_ignorert)
			ON CONFLICT ON CONSTRAINT aktivitet_pkey
			DO UPDATE SET data = :data::jsonb,
				oppfolgingsperiode_slutt_tidspunkt = :oppfolgingsperiode_slutt_tidspunkt,
				oppfolgingsperiode_uuid = :oppfolgingsperiode_uuid,
				forelopig_ignorert = :forelopig_ignorert
		""".trimIndent()

		val parameters = MapSqlParameterSource().addValues(
			mapOf(
				"id" to aktivitet.id,
				"person_ident" to aktivitet.personIdent,
				"kategori_type" to aktivitet.kategori.name,
				"data" to aktivitet.data,
				"arena_id" to aktivitet.arenaId,
				"tiltak_kode" to aktivitet.tiltakKode,
				"oppfolgingsperiode_uuid" to aktivitet.oppfolgingsperiodeUUID,
				"oppfolgingsperiode_slutt_tidspunkt" to aktivitet.oppfolgingsSluttTidspunkt?.toOffsetDateTime(),
				"forelopig_ignorert" to aktivitet.forelopigIgnorert
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

	fun getCurrentAktivitetsId(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): UUID? {
		@Language("PostgreSQL")
		val sql = """
			SELECT DISTINCT ON (arena_id)
				arena_id,
			    aktivitet.id,
			    COALESCE(aktivitet.oppfolgingsperiode_slutt_tidspunkt, TO_TIMESTAMP('9999', 'YYYY')) slutt
			FROM aktivitet
			WHERE arena_id = :arenaId
			ORDER BY arena_id, slutt DESC
		""".trimIndent()
		val parameters = mapOf("arenaId" to "${aktivitetKategori.prefix}${deltakelseId.value}")
		return template.query(sql, parameters) { row, _ -> row.getUUID("id") }
			.also {
				if (it.size > 1) {
					log.error("Got multiple results on currently active aktivitetskort: ${it.size}, deltakerId: ${deltakelseId.value}")
					throw IncorrectResultSizeDataAccessException(1, it.size)
				}
			}
			.firstOrNull()
	}

	fun getAllBy(arendaId: ArenaId): Map<UUID, AktivitetMetaData> {
		@Language("PostgreSQL")
		val sql = """
			SELECT
				oppfolgingsperiode_uuid as oppfolgingsPeriode,
				id,
				oppfolgingsperiode_slutt_tidspunkt,
				COALESCE(aktivitet.oppfolgingsperiode_slutt_tidspunkt, TO_TIMESTAMP('9999', 'YYYY')) oppfolging_slutt_tidspunkt_eller_max,
				person_ident
			FROM aktivitet WHERE arena_id = :arenaId
			ORDER BY oppfolging_slutt_tidspunkt_eller_max DESC
		""".trimIndent()
		val params = mapOf("arenaId" to "${arendaId.aktivitetKategori.prefix}${arendaId.deltakelseId.value}")
		return template.query(sql, params) { row, _ ->
			AktivitetMetaData(
				row.getUUID("id"),
				row.getUUID("oppfolgingsPeriode"),
				row.getNullableZonedDateTime("oppfolgingsperiode_slutt_tidspunkt"),
				row.getString("person_ident")) }
			.groupBy { it.oppfolgingsPeriode }
			.mapValues { it.value.first() }
	}

	fun closeClosedPerioder(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, oppfolgingsperioder: List<AvsluttetOppfolgingsperiode>) {
		@Language("PostgreSQL")
		val sql = """
			UPDATE aktivitet SET oppfolgingsperiode_slutt_tidspunkt = :slutt
			WHERE arena_id = :arenaId and oppfolgingsperiode_uuid = :oppfolgingsperiode
		""".trimIndent()
		val params = oppfolgingsperioder
			.map {
				mapOf(
					"arenaId" to "${aktivitetKategori.prefix}${deltakelseId.value}",
					"slutt" to it.sluttDato.toOffsetDateTime(),
					"oppfolgingsperiode" to it.uuid
				)
			}.toTypedArray()
		template.batchUpdate(sql, params)
	}
}

fun ResultSet.toAktivitetDbo() =
	AktivitetDbo(
		id = this.getUUID("id"),
		personIdent = this.getString("person_ident"),
		kategori = AktivitetKategori.valueOf(this.getString("kategori_type")),
		data = this.getString("data"),
		arenaId = this.getString("arena_id"),
		tiltakKode = this.getString("tiltak_kode"),
		oppfolgingsperiodeUUID = this.getUUID("oppfolgingsperiode_uuid"),
		oppfolgingsSluttTidspunkt = this.getNullableZonedDateTime("oppfolgingsperiode_slutt_tidspunkt"),
	)

data class AktivitetMetaData(
	val id: UUID,
	val oppfolgingsPeriode: UUID,
	val oppfolgingsperiodeSlutt: ZonedDateTime?,
	val person_ident: String,
)
