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
				aktivitet.oppfolgingsperiode_slutt_tidspunkt,
				COALESCE(aktivitet.oppfolgingsperiode_slutt_tidspunkt, TO_TIMESTAMP('9999', 'YYYY')) slutt
			FROM deltaker_aktivitet_mapping
			inner join aktivitet on deltaker_aktivitet_mapping.oppfolgingsperiode_id = aktivitet.oppfolgingsperiode_uuid
			WHERE deltaker_id = :deltaker_id and aktivitet_kategori = :aktivitet_kategori and aktivitet.id = deltaker_aktivitet_mapping.aktivitetskort_id
			ORDER BY deltaker_id, slutt desc
		""".trimIndent()
		val parameters = mapOf("deltaker_id" to deltakelseId.value, "aktivitet_kategori" to aktivitetKategori.name)
		return template.query(sql, parameters) { row, _ -> row.toDbo() }
			.firstOrNull()
	}

	open fun insert(dbo: DeltakerAktivitetMappingDbo): Int {
		val sql = """
			INSERT INTO deltaker_aktivitet_mapping(deltaker_id, aktivitetskort_id, aktivitet_kategori, oppfolgingsperiode_id)
		 	VALUES (:deltaker_id, :aktivitet_id, :aktivitet_kategori, :oppfolgingsperiode_id)
		""".trimIndent()
		val parameters = mapOf(
			"deltaker_id" to dbo.deltakelseId,
			"aktivitet_id" to dbo.aktivitetId,
			"aktivitet_kategori" to dbo.aktivitetKategori,
			"oppfolgingsperiode_id" to dbo.oppfolgingsPeriodeId,
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
		oppfolgingsPeriodeSluttTidspunkt = this.getNullableZonedDateTime("oppfolgingsperiode_slutt_tidspunkt"),
	)
}
