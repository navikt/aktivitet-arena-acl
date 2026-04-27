package no.nav.arena_tiltak_aktivitet_acl.repositories

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import tools.jackson.databind.JsonNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.arena_tiltak_aktivitet_acl.database.DatabaseTestUtils
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataUpsertInput
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.arena_tiltak_aktivitet_acl.utils.DbUtils.isEqualTo
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDateTime
import kotlin.random.Random

class ArenaDataRepositoryTest : FunSpec({

	val dataSource = SingletonPostgresContainer.getDataSource()

	lateinit var repository: ArenaDataRepository

	beforeEach {
		val rootLogger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
		rootLogger.level = Level.WARN

		repository = ArenaDataRepository(NamedParameterJdbcTemplate(dataSource))

		DatabaseTestUtils.cleanDatabase(dataSource)
	}

	test("get - data finnes - skal hente inserta data") {
		val after = "{\"test\": \"test\"}"

		val data = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			after = after
		)

		repository.upsert(data)

		val stored = repository.get(data.arenaTableName, data.operation, data.operationPosition)

		stored shouldNotBe null
		stored.id shouldNotBe -1
		stored.arenaId shouldBe data.arenaId
		stored.before shouldBe null
		stored.after shouldBe after
	}

	fun stringToJsonNode(payload: String?): JsonNode? {
		return payload?.let {
			ObjectMapper.get().readTree(payload)
		}
	}

	test("alreadyProcessed - skal være true hvis nyeste behandlede melding sin before/after er lik innkommende melding sin before/after") {
		val gammelBefore = "{\"test\":     \"tast\"}"
		val nyBefore = "{\"lol\": \"lil\"}"
		val gammelAfter = "{\"test\":     \"test\"}"
		val nyAfter = "{\"lol\": \"lol\"}"
		val data = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			before = gammelBefore,
			after = gammelAfter
		)
		repository.upsert(data)
		repository.alreadyProcessed(data.arenaId, data.arenaTableName,stringToJsonNode(gammelBefore), stringToJsonNode(gammelAfter)) shouldBe true
		repository.alreadyProcessed(data.arenaId, data.arenaTableName, stringToJsonNode(nyBefore), stringToJsonNode(nyAfter)) shouldBe false
		repository.upsert(data.copy(before = nyBefore, after = nyAfter, operationPosition = OperationPos(Random.nextLong(10000)), operationTimestamp = LocalDateTime.now()))
		repository.alreadyProcessed(data.arenaId, data.arenaTableName, stringToJsonNode(nyBefore), stringToJsonNode(nyAfter)) shouldBe true
		repository.alreadyProcessed(data.arenaId, data.arenaTableName, stringToJsonNode(gammelBefore), stringToJsonNode(gammelAfter)) shouldBe false
	}

	test("alreadyProcessed - skal tåle null i before og/eller after") {
		val gammelBefore = "{\"test\":     \"tast\"}"
		val nyBefore = null
		val gammelAfter = null
		val nyAfter = "{\"lol\": \"lol\"}"
		val data = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			before = gammelBefore,
			after = gammelAfter
		)
		repository.upsert(data)
		repository.alreadyProcessed(data.arenaId, data.arenaTableName,stringToJsonNode(gammelBefore), stringToJsonNode(gammelAfter)) shouldBe true
		repository.alreadyProcessed(data.arenaId, data.arenaTableName, stringToJsonNode(nyBefore), stringToJsonNode(nyAfter)) shouldBe false
		repository.upsert(data.copy(before = nyBefore, after = nyAfter, operationPosition = OperationPos(Random.nextLong(10000)), operationTimestamp = LocalDateTime.now()))
		repository.alreadyProcessed(data.arenaId, data.arenaTableName, stringToJsonNode(nyBefore), stringToJsonNode(nyAfter)) shouldBe true
		repository.alreadyProcessed(data.arenaId, data.arenaTableName, stringToJsonNode(gammelBefore), stringToJsonNode(gammelAfter)) shouldBe false
	}

	test("upsert - data finnes allerede - oppdaterer eksisterende") {
		val data = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			after = "{\"test\": \"test\"}"
		)

		repository.upsert(data)

		val stored = repository.get(data.arenaTableName, data.operation, data.operationPosition)

		val newIngestedTimestamp = LocalDateTime.now()

		val data2 = data.copy(
			ingestStatus = IngestStatus.RETRY,
			ingestedTimestamp = newIngestedTimestamp,
			note = "some note"
		)

		repository.upsert(data2)

		val updated = repository.get(data.arenaTableName, data.operation, data.operationPosition)

		stored.id shouldBe updated.id
		updated.ingestStatus shouldBe IngestStatus.RETRY
		updated.ingestedTimestamp!!.isEqualTo(newIngestedTimestamp) shouldBe true
		updated.note shouldBe "some note"
	}

	test("deleteAllIgnoredData - skal slette ignorerte data") {
		val afterData = "{\"test\": \"test\"}"

		val data1 = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			after = afterData
		)

		val data2 = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			ingestStatus = IngestStatus.IGNORED,
			after = afterData
		)

		val data3 = ArenaDataUpsertInput(
			arenaTableName = ArenaTableName.TILTAK,
			arenaId = "ARENA_ID",
			operation = Operation.CREATED,
			operationPosition = OperationPos(Random.nextLong(10000)),
			operationTimestamp = LocalDateTime.now(),
			ingestStatus = IngestStatus.IGNORED,
			after = afterData
		)

		repository.upsert(data1)
		repository.upsert(data2)
		repository.upsert(data3)

		val rowsDeleted = repository.deleteAllIgnoredData()

		val allData = repository.getAll()

		rowsDeleted shouldBe 2
		allData.size shouldBe 1
	}

})
