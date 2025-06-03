package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.utils.getLocalDateTime
import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import no.nav.arena_tiltak_aktivitet_acl.utils.getZonedDateTime
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.ZonedDateTime
import java.util.UUID

data class DeltakerAktivitetMappingDbo(
	val deltakelseId: Long,
	val aktivitetId: UUID,
	val aktivitetKategori: String,
	val oppfolgingsPeriodeId: UUID,
	val oppfolgingsPeriodeSluttdato: ZonedDateTime?,
)

@Component
open class DeltakerAktivitetMappingRespository(
	private val template: NamedParameterJdbcTemplate,
) {

	fun getCurrentDeltakerAktivitetMapping(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): DeltakerAktivitetMappingDbo? {
		val sql = """
			SELECT DISTINCT ON (deltaker_id)
				deltaker_id,
				aktivitet_id,
				aktivitet_kategori,
				oppfolgingsperiode_uuid,
				COALESCE(oppfolgingsperiode_slutttidspunkt, TO_TIMESTAMP('9999', 'YYYY')) slutt
			FROM deltaker_aktivitet_mapping
			WHERE deltaker_id = :deltaker_id and aktivitet_kategori = :aktivitet_kategori
			ORDER BY deltaker_id, slutt
		""".trimIndent()
		val parameters = mapOf("deltaker_id" to deltakelseId.value, "aktivitet_kategori" to aktivitetKategori.name)
		return template.query(sql, parameters) { row, _ ->
			DeltakerAktivitetMappingDbo(
				deltakelseId = row.getLong("deltaker_id"),
				aktivitetId = row.getUUID("aktivitet_id"),
				aktivitetKategori = row.getString("aktivitet_kategori"),
				oppfolgingsPeriodeId = row.getUUID("oppfolgingsperiode_uuid"),
				oppfolgingsPeriodeSluttdato = row.getZonedDateTime("oppfolgingsperiode_sluttdato"),
			)
		}.firstOrNull()
	}

	fun upsert(dbo: DeltakerAktivitetMappingDbo): Int {
		val sql = """
			INSERT INTO deltaker_aktivitet_mapping(deltaker_id, aktivitet_id, aktivitet_kategori, oppfolgingsperiode_uuid, oppfolgingsperiode_sluttdato)
		 	VALUES (:deltaker_id, :aktivitet_id, :aktivitet_kategori, :oppfolgingsperiode_uuid, :oppfolgingsperiode_sluttdato)
		""".trimIndent()
		val parameters = mapOf(
			"deltaker_id" to dbo.deltakelseId,
			"aktivitet_id" to dbo.aktivitetId,
			"aktivitet_kategori" to dbo.aktivitetKategori,
			"oppfolgingsperiode_uuid" to dbo.oppfolgingsPeriodeId,
			"oppfolgingsperiode_sluttdato" to dbo.oppfolgingsPeriodeSluttdato,
		)
		return template.update(sql, parameters)
	}

}
