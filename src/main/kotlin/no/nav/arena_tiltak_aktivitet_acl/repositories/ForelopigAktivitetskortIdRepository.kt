package no.nav.arena_tiltak_aktivitet_acl.repositories

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.services.ArenaId
import no.nav.arena_tiltak_aktivitet_acl.utils.getUUID
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.lang.IllegalStateException
import java.util.*

sealed class ForelopigAktivitetskortId(val id: UUID)
class EksisterendeForelopigId(id: UUID) : ForelopigAktivitetskortId(id)
class FantIdITranslationTabell(id: UUID) : ForelopigAktivitetskortId(id)
class NyForelopigId(id: UUID) : ForelopigAktivitetskortId(id)

@Component
class ForelopigAktivitetskortIdRepository(
	private val template: NamedParameterJdbcTemplate
) {

	fun deleteDeltakelseId(arenaId: ArenaId): Int {
		val sql = """
			DELETE FROM forelopig_aktivitet_id WHERE deltakelse_id = :deltakelseId and kategori = :kategori
		""".trimIndent()
		return template.update(sql,
			mapOf(
				"kategori" to arenaId.aktivitetKategori.name,
				"deltakelseId" to arenaId.deltakelseId.value,
			))
	}

	fun getOrCreate(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): ForelopigAktivitetskortId {
		// forelopigId > translationId > opprett ny id
		getCurrentId(deltakelseId, aktivitetKategori)?.let { return it }
		getIdFromTranslationTable(deltakelseId, aktivitetKategori)?.let { return it }
		createNewId(deltakelseId, aktivitetKategori).let { return it }
	}

	private fun createNewId(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): NyForelopigId {
		val generatedId = UUID.randomUUID()
		val insertNewId = """
			INSERT INTO forelopig_aktivitet_id(id, kategori, deltakelse_id) VALUES (:id, :kategori, :deltakelseId)
		""".trimIndent()
		template.update(insertNewId,
			mapOf(
				"id" to generatedId,
				"kategori" to aktivitetKategori.name,
				"deltakelseId" to deltakelseId.value,
			))
		return NyForelopigId(generatedId)
	}

	private fun getCurrentId(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): EksisterendeForelopigId? {
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
			?.let { EksisterendeForelopigId(it) }
	}

	private fun getIdFromTranslationTable(deltakelseId: DeltakelseId, aktivitetKategori: AktivitetKategori): FantIdITranslationTabell? {
		val getIdFromTranslationTable = """
			SELECT aktivitet_id FROM translation WHERE arena_id = :arena_id and aktivitet_kategori = :aktivitet_kategori
		""".trimIndent()
		return template.query(
			getIdFromTranslationTable,
			mapOf(
				"arena_id" to deltakelseId.value,
				"aktivitet_kategori" to aktivitetKategori.name
			)
		) { row, _ -> row.getUUID("aktivitet_id") }
			.firstOrNull()
			?.let { FantIdITranslationTabell(it) }
	}

}
