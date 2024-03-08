package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.lang.IllegalStateException
import java.util.*

@Component
class AktivitetskortIdRepository(
	private val template: NamedParameterJdbcTemplate
) {

	fun deleteDeltakelseId(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): Int {
		val sql = """
			DELETE FROM forelopig_aktivitet_id WHERE deltakelse_id = :deltakelseId and kategori = :kategori
		""".trimIndent()
		return template.update(sql,
			mapOf(
				"kategori" to aktivitetKategori.name,
				"deltakelseId" to deltakelseId.value,
			))
	}

	fun getOrCreate(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori, idOverride: UUID? = null): UUID {
		val currentId = getCurrentId(deltakelseId, aktivitetKategori)
		if (idOverride != null && currentId != null && idOverride != currentId)
			throw IllegalStateException("Mismatch på id-override idOverride: $idOverride eksisterendeId: $currentId")
		if (currentId != null) return currentId

		val generatedId = idOverride ?: UUID.randomUUID()
		val insertNewId = """
			INSERT INTO forelopig_aktivitet_id(id, kategori, deltakelse_id) VALUES (:id, :kategori, :deltakelseId)
		""".trimIndent()
		template.update(insertNewId,
			mapOf(
				"id" to generatedId,
				"kategori" to aktivitetKategori.name,
				"deltakelseId" to deltakelseId.value,
			))
		return generatedId
	}



	private fun getCurrentId(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): UUID? {
		val getCurrentId = """
			SELECT id FROM forelopig_aktivitet_id WHERE deltakelse_id = :deltakelseId and kategori = :aktivitetKategori
		""".trimIndent()
		return template.query(
			getCurrentId,
			mapOf(
				"deltakelseId" to deltakelseId.value,
				"aktivitetKategori" to aktivitetKategori.name
			)
		) { row, _ -> row.getUUID("id") }
			.firstOrNull()
	}

}
