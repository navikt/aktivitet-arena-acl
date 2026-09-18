package no.nav.arena_tiltak_aktivitet_acl.rest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import java.time.LocalDateTime

class AdminArenaDataDtoTest : FunSpec({
	test("toAdminArenaDataDto excludes before and after") {
		val dto = ArenaDataDbo(
			id = 1,
			arenaTableName = ArenaTableName.DELTAKER,
			arenaId = "123",
			operation = Operation.CREATED,
			operationPosition = OperationPos(7),
			operationTimestamp = LocalDateTime.parse("2026-09-18T08:00:00"),
			ingestStatus = IngestStatus.HANDLED,
			ingestedTimestamp = LocalDateTime.parse("2026-09-18T08:01:00"),
			ingestAttempts = 2,
			lastAttempted = LocalDateTime.parse("2026-09-18T08:02:00"),
			before = "{\"hidden\":true}",
			after = "{\"hidden\":true}",
			note = "ok"
		)

		val json = ObjectMapper.get().writeValueAsString(dto.toAdminArenaDataDto())

		json shouldContain "\"arenaId\":\"123\""
		json shouldContain "\"note\":\"ok\""
		json shouldNotContain "before"
		json shouldNotContain "after"
	}
})
