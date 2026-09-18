package no.nav.arena_tiltak_aktivitet_acl.rest

import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import java.time.LocalDateTime

data class AdminArenaDataDto(
	val id: Int,
	val arenaTableName: ArenaTableName,
	val arenaId: String,
	val operation: Operation,
	val operationPosition: OperationPos,
	val operationTimestamp: LocalDateTime,
	val ingestStatus: IngestStatus,
	val ingestedTimestamp: LocalDateTime?,
	val ingestAttempts: Int,
	val lastAttempted: LocalDateTime?,
	val note: String?,
)

fun ArenaDataDbo.toAdminArenaDataDto() = AdminArenaDataDto(
	id = id,
	arenaTableName = arenaTableName,
	arenaId = arenaId,
	operation = operation,
	operationPosition = operationPosition,
	operationTimestamp = operationTimestamp,
	ingestStatus = ingestStatus,
	ingestedTimestamp = ingestedTimestamp,
	ingestAttempts = ingestAttempts,
	lastAttempted = lastAttempted,
	note = note,
)
