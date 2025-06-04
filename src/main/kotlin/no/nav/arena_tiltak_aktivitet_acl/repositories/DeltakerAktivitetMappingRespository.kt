package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.utils.getNullableZonedDateTime
import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.ZonedDateTime
import java.util.UUID

data class DeltakerAktivitetMappingDbo(
	val deltakelseId: Long,
	val aktivitetId: UUID,
	val aktivitetKategori: String,
	val oppfolgingsPeriodeId: UUID,
	val oppfolgingsPeriodeSluttTidspunkt: ZonedDateTime?,
)

@Component
open class DeltakerAktivitetMappingRespository(
	private val template: NamedParameterJdbcTemplate,
) {

	open fun getCurrentDeltakerAktivitetMapping(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): DeltakerAktivitetMappingDbo? {
		val sql = """
			SELECT DISTINCT ON (deltaker_id)
				deltaker_id,
				aktivitetskort_id,
				aktivitet_kategori,
				oppfolgingsperiode_id,
				oppfolgingsperiode_slutttidspunkt,
				COALESCE(oppfolgingsperiode_slutttidspunkt, TO_TIMESTAMP('9999', 'YYYY')) slutt
			FROM deltaker_aktivitet_mapping
			WHERE deltaker_id = :deltaker_id and aktivitet_kategori = :aktivitet_kategori
			ORDER BY deltaker_id, slutt desc
		""".trimIndent()
		val parameters = mapOf("deltaker_id" to deltakelseId.value, "aktivitet_kategori" to aktivitetKategori.name)
		return template.query(sql, parameters) { row, _ -> row.toDbo() }
			.firstOrNull()
	}

	open fun getByPeriode(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, oppfolgingsPeriodeId: UUID): DeltakerAktivitetMappingDbo? {
		val sql = """
			SELECT
				deltaker_id,
				aktivitetskort_id,
				aktivitet_kategori,
				oppfolgingsperiode_id,
				oppfolgingsperiode_slutttidspunkt
			FROM deltaker_aktivitet_mapping
			WHERE deltaker_id = :deltaker_id
				and aktivitet_kategori = :aktivitet_kategori
				and oppfolgingsperiode_id = :oppfolgingsperiode_id
		""".trimIndent()
		val parameters = mapOf(
			"deltaker_id" to deltakelseId.value,
			"aktivitet_kategori" to aktivitetKategori.name,
			"oppfolgingsperiode_id" to oppfolgingsPeriodeId
		)
		return template.query(sql, parameters) { row, _ -> row.toDbo() }
			.let { if (it.size > 1) throw IllegalStateException("Expected only one result, but found ${it.size}") else it }
			.firstOrNull()
	}

	open fun insert(dbo: DeltakerAktivitetMappingDbo): Int {
		val sql = """
			INSERT INTO deltaker_aktivitet_mapping(deltaker_id, aktivitetskort_id, aktivitet_kategori, oppfolgingsperiode_id, oppfolgingsperiode_slutttidspunkt)
		 	VALUES (:deltaker_id, :aktivitet_id, :aktivitet_kategori, :oppfolgingsperiode_id, :oppfolgingsperiode_slutttidspunkt)
		""".trimIndent()
		val parameters = mapOf(
			"deltaker_id" to dbo.deltakelseId,
			"aktivitet_id" to dbo.aktivitetId,
			"aktivitet_kategori" to dbo.aktivitetKategori,
			"oppfolgingsperiode_id" to dbo.oppfolgingsPeriodeId,
			"oppfolgingsperiode_slutttidspunkt" to dbo.oppfolgingsPeriodeSluttTidspunkt?.toOffsetDateTime(),
		)
		return template.update(sql, parameters)
	}
}

fun ResultSet.toDbo(): DeltakerAktivitetMappingDbo {
	return DeltakerAktivitetMappingDbo(
		deltakelseId = this.getLong("deltaker_id"),
		aktivitetId = this.getUUID("aktivitetskort_id"),
		aktivitetKategori = this.getString("aktivitet_kategori"),
		oppfolgingsPeriodeId = this.getUUID("oppfolgingsperiode_id"),
		oppfolgingsPeriodeSluttTidspunkt = this.getNullableZonedDateTime("oppfolgingsperiode_slutttidspunkt"),
	)
}
