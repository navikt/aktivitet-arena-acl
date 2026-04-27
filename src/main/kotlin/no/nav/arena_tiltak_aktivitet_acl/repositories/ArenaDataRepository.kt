package no.nav.arena_tiltak_aktivitet_acl.repositories

import tools.jackson.databind.JsonNode
import io.micrometer.core.annotation.Timed
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataUpsertInput
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.dto.LogStatusCountDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.arena_tiltak_aktivitet_acl.utils.DatabaseUtils.sqlParameters
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDateTime

@Component
open class ArenaDataRepository(
	private val template: NamedParameterJdbcTemplate,
) {

	fun upsert(upsertData: ArenaDataUpsertInput) {
		//language=PostgreSQL
		val sql = """
			INSERT INTO arena_data(arena_table_name, arena_id, operation_type, operation_pos, operation_timestamp, ingest_status,
								   ingested_timestamp, before, after, note)
			VALUES (:arena_table_name,
					:arena_id,
					:operation_type,
					:operation_pos,
					:operation_timestamp,
					:ingest_status,
					:ingested_timestamp,
					:before::json,
					:after::json,
					:note)
			ON CONFLICT (arena_table_name, operation_pos) DO UPDATE SET
					ingest_status      = :ingest_status,
					ingested_timestamp = :ingested_timestamp,
					note 			   = :note
		""".trimIndent()

		template.update(
			sql, mapOf(
				"arena_table_name" to upsertData.arenaTableName.tableName,
				"arena_id" to upsertData.arenaId,
				"operation_type" to upsertData.operation.name,
				"operation_pos" to upsertData.operationPosition.value,
				"operation_timestamp" to upsertData.operationTimestamp,
				"ingest_status" to upsertData.ingestStatus.name,
				"ingested_timestamp" to upsertData.ingestedTimestamp,
				"before" to upsertData.before,
				"after" to upsertData.after,
				"note" to upsertData.note,
			)
		)
	}

	fun upsertTemp(upsertData: ArenaDataUpsertInput) {
		//language=PostgreSQL
		val sql = """
			INSERT INTO temp_arena_data(arena_table_name, arena_id, operation_type, operation_pos, operation_timestamp, ingest_status,
								   ingested_timestamp, before, after, note)
			VALUES (:arena_table_name,
					:arena_id,
					:operation_type,
					:operation_pos,
					:operation_timestamp,
					:ingest_status,
					:ingested_timestamp,
					:before::json,
					:after::json,
					:note)
			ON CONFLICT (arena_table_name, operation_type, operation_pos) DO UPDATE SET
					ingest_status      = :ingest_status,
					ingested_timestamp = :ingested_timestamp,
					note 			   = :note
		""".trimIndent()

		template.update(
			sql, mapOf(
				"arena_table_name" to upsertData.arenaTableName.tableName,
				"arena_id" to upsertData.arenaId,
				"operation_type" to upsertData.operation.name,
				"operation_pos" to upsertData.operationPosition.value,
				"operation_timestamp" to upsertData.operationTimestamp,
				"ingest_status" to upsertData.ingestStatus.name,
				"ingested_timestamp" to upsertData.ingestedTimestamp,
				"before" to upsertData.before,
				"after" to upsertData.after,
				"note" to upsertData.note,
			)
		)
	}

	fun updateIngestStatus(id: Int, ingestStatus: IngestStatus, note: String? = null) {
		//language=PostgreSQL
		val sqlWithoutNote = """
			UPDATE arena_data
			SET ingest_status = :ingest_status, last_attempted = :last_attempted
			WHERE id = :id
		""".trimIndent()
		//language=PostgreSQL
		val sqlWithNote = """
			UPDATE arena_data
			SET ingest_status = :ingest_status, last_attempted = :last_attempted, note = :note
			WHERE id = :id
		""".trimIndent()

		template.update(
			if (note != null) sqlWithNote else sqlWithoutNote, mapOf(
				"ingest_status" to ingestStatus.name,
				"id" to id,
				"last_attempted" to LocalDateTime.now(),
				"note" to note
			).filter { it.value != null }
		)
	}

	fun updateIngestAttempts(id: Int, ingestAttempts: Int, note: String?) {
		//language=PostgreSQL
		val sql = """
			UPDATE arena_data SET
				ingest_attempts = :ingest_attempts,
			 	last_attempted = :last_attempted,
			 	note = :note
			  WHERE id = :id
		""".trimIndent()

		template.update(
			sql, sqlParameters(
				"ingest_attempts" to ingestAttempts,
				"last_attempted" to LocalDateTime.now(),
				"note" to note,
				"id" to id
			)
		)
	}

	fun get(tableName: ArenaTableName, operation: Operation, position: OperationPos): ArenaDataDbo {
		//language=PostgreSQL
		val sql = """
			SELECT *
			FROM arena_data
			WHERE arena_table_name = :arena_table_name
				AND operation_type = :operation_type
				AND operation_pos = :operation_pos
		""".trimIndent()

		val parameters = sqlParameters(
			"arena_table_name" to tableName.tableName,
			"operation_type" to operation.name,
			"operation_pos" to position.value,
		)

		return template.query(sql, parameters, arenaDataRowMapper).firstOrNull()
			?: throw NoSuchElementException("Element from table ${tableName.name}, operation: $operation, position: $position does not exist")
	}

	fun getByIngestStatus(
		tableName: ArenaTableName,
		status: IngestStatus,
		fromTimestamp: LocalDateTime,
		limit: Int = 500
	): List<ArenaDataDbo> {
		//language=PostgreSQL
		val sql = """
			SELECT *
			FROM arena_data
			WHERE ingest_status = :ingestStatus
			AND arena_table_name = :tableName
			AND operation_timestamp > :fromTs
			ORDER BY operation_timestamp ASC, operation_pos ASC
			LIMIT :limit
		""".trimIndent()

		val parameters = sqlParameters(
			"ingestStatus" to status.name,
			"tableName" to tableName.tableName,
			"fromTs" to fromTimestamp,
			"limit" to limit
		)
		val resultat = template.query(sql, parameters, arenaDataRowMapper)
		return resultat
	}

	fun getStatusCount(): List<LogStatusCountDto> {
		//language=PostgreSQL
		val sql = """
			SELECT ingest_status, count(*)
			FROM arena_data
			GROUP BY ingest_status
		""".trimIndent()

		val logRowMapper = RowMapper { rs, _ ->
			LogStatusCountDto(
				status = IngestStatus.valueOf(rs.getString("ingest_status")),
				count = rs.getInt("count")
			)
		}

		return template.query(sql, logRowMapper)
	}

	fun getAll(): List<ArenaDataDbo> {
		//language=PostgreSQL
		val sql = """
			SELECT *
			FROM arena_data
		""".trimIndent()

		return template.query(sql, arenaDataRowMapper)
	}

	fun deleteAllIgnoredData(): Int {
		//language=PostgreSQL
		val sql = """
			DELETE FROM arena_data WHERE ingest_status = 'IGNORED'
		""".trimIndent()

		return template.update(sql, EmptySqlParameterSource())
	}

	@Timed(value = "acl.query.hasUnhandledDeltakelse")
	fun hasUnhandledDeltakelse(deltakelseArenaId: Long): Boolean {
		//language=PostgreSQL
		val sql = """
			SELECT count(*) as antall FROM arena_data
			where arena_id = :arena_id
				AND arena_table_name = :deltakerTableName
				AND ingest_status in ('NEW', 'RETRY', 'FAILED', 'QUEUED','INVALID')
		""".trimIndent()
		val params = sqlParameters(
			"arena_id" to deltakelseArenaId.toString(),
			"deltakerTableName" to ArenaTableName.DELTAKER.tableName
		)
		return template.queryForObject(sql, params) { a, _ -> a.getInt("antall") }
			?.let { it > 0 } ?: false
	}

	fun hasHandledDeltakelseWithLaterTimestamp(deltakelseId: DeltakelseId, operationTimestamp: LocalDateTime): Boolean {
		//language=PostgreSQL
		val sql = """
			SELECT count(*) as antallNyereMeldinger FROM arena_data
			where arena_id = :arena_id
				AND arena_table_name = :deltakerTableName
				AND (ingest_status = 'HANDLED' or ingest_status = 'HANDLED_AND_IGNORED')
				AND operation_timestamp > :operationTimestamp
		""".trimIndent()
		val params = sqlParameters(
			"arena_id" to deltakelseId.value.toString(),
			"deltakerTableName" to ArenaTableName.DELTAKER.tableName,
			"operationTimestamp" to operationTimestamp
		)
		return template.queryForObject(sql, params) { a, _ -> a.getInt("antallNyereMeldinger") }
			?.let { it > 0 } ?: false
	}

	fun moveQueueForward(arenaTableName: ArenaTableName): Int {
		// For en deltakelse (arena_id)
		// Sett QUEUED til RETRY kun hvis det ikke finnes noen RETRY eller FAILED
		// Alt som er RETRY eller FAILED er alltid først i køen
		//language=PostgreSQL
		val sql = """
			update arena_data target set ingest_status = 'RETRY' from (
			select distinct on (arena_id) * from arena_data a
			        WHERE a.arena_table_name = :arenaTableName
			        AND a.ingest_status = 'QUEUED'
			    AND NOT EXISTS(
			            SELECT 1
			            FROM arena_data a3
			            WHERE a3.ingest_status in ('RETRY', 'FAILED')
			              AND a3.arena_id = a.arena_id
			              AND a3.arena_table_name = :arenaTableName
			        )
			    order by a.arena_id,a.operation_timestamp asc, a.operation_pos asc) source
			where target.arena_id = source.arena_id and target.operation_pos = source.operation_pos;
		""".trimIndent()
		return template.update(sql, mapOf("arenaTableName" to arenaTableName.tableName))
	}

	fun alreadyProcessed(deltakelseArenaId: String, tableName: ArenaTableName, before: JsonNode?, after: JsonNode?): Boolean {
		val sql = """
			WITH latestRow AS (
				SELECT arena_id, MAX(operation_timestamp) latestOpTs
				FROM arena_data WHERE
					arena_id = :arenaId
					AND arena_table_name = :tableName
				GROUP BY arena_id)
			SELECT EXISTS(
				SELECT 1
				FROM arena_data
				JOIN latestRow ON arena_data.operation_timestamp = latestRow.latestOpTs
				and arena_data.arena_id = :arenaId
			${if (after != null) "AND after @> :after::jsonb" else "AND after IS NULL"}
        	${if (before != null) "AND before @> :before::jsonb" else "AND before IS NULL"}
			)
		""".trimIndent()
		return template.queryForObject(
			sql,
			mapOf("arenaId" to deltakelseArenaId, "tableName" to tableName.tableName, "before" to before?.toString(), "after" to after?.toString()),
		) { row: ResultSet, _ -> row.getBoolean(1) }
	}

	fun getMostRecentDeltakelse(deltakelseArenaId: DeltakelseId): ArenaDataDbo {
		val sql = """
				SELECT DISTINCT ON (arena_data.arena_id) *
				FROM arena_data WHERE
					arena_id = :deltakelseId AND arena_table_name = 'SIAMO.TILTAKDELTAKER'
				ORDER BY arena_id, operation_timestamp DESC, operation_pos DESC;
		""".trimIndent()
		return template.queryForObject(sql, mapOf("deltakelseId" to deltakelseArenaId.value.toString()), arenaDataRowMapper)
	}

	fun harTidligereEndringSomIkkeErIgnorert(deltakelseId: DeltakelseId, operationTimestamp: LocalDateTime): Boolean {
		//language=PostgreSQL
		val sql = """
			select exists(
				SELECT DISTINCT ON (arena_id) *
					FROM arena_data WHERE
    					arena_id = :deltakelseId AND arena_table_name = 'SIAMO.TILTAKDELTAKER'
                  		and ingest_status = 'HANDLED'
                  		and note is null
                  		and operation_timestamp <= :operationTimestamp
				ORDER BY arena_id, operation_timestamp asc) ;
		""".trimIndent()
		return template.queryForObject(
			sql,
			mapOf("deltakelseId" to deltakelseId.value.toString(), "operationTimestamp" to operationTimestamp),
		) { row: ResultSet, _ -> row.getBoolean(1)}
	}

}
val arenaDataRowMapper = RowMapper { rs, _ ->
	ArenaDataDbo(
		id = rs.getInt("id"),
		arenaTableName = ArenaTableName.fromValue(rs.getString("arena_table_name")),
		arenaId = rs.getString("arena_id"),
		operation = Operation.valueOf(rs.getString("operation_type")),
		operationPosition = OperationPos(rs.getLong("operation_pos")),
		operationTimestamp = rs.getTimestamp("operation_timestamp").toLocalDateTime(),
		ingestStatus = IngestStatus.valueOf(rs.getString("ingest_status")),
		ingestedTimestamp = rs.getTimestamp("ingested_timestamp")?.toLocalDateTime(),
		ingestAttempts = rs.getInt("ingest_attempts"),
		lastAttempted = rs.getTimestamp("last_attempted")?.toLocalDateTime(),
		before = rs.getString("before"),
		after = rs.getString("after"),
		note = rs.getString("note")
	)
}
