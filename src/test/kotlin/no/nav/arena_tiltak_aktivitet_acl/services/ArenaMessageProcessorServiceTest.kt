package no.nav.arena_tiltak_aktivitet_acl.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.ArenaGjennomforingKafkaMessage
import no.nav.arena_tiltak_aktivitet_acl.gruppetiltak.processor.GruppeTiltakProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.DeltakerProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.GjennomforingProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.TiltakProcessor
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory

class ArenaMessageProcessorServiceTest : StringSpec({

	val mapper = ObjectMapper.get()

	var arenaDataRepository: ArenaDataRepository = mock()
	var tiltakProcessor: TiltakProcessor = mock()
	var gjennomforingProcessor: GjennomforingProcessor = mock()
	var deltakerProcessor: DeltakerProcessor = mock()
	var gruppeTiltakProcessor: GruppeTiltakProcessor = mock()
	lateinit var meterRegistry: MeterRegistry
	lateinit var messageProcessor: ArenaMessageProcessorService

	beforeEach {
		val rootLogger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
		rootLogger.level = Level.WARN
		clearMocks(
			tiltakProcessor,
			gjennomforingProcessor,
			deltakerProcessor,
			gruppeTiltakProcessor
		)
		meterRegistry = SimpleMeterRegistry()
		messageProcessor = ArenaMessageProcessorService(
			tiltakProcessor = tiltakProcessor,
			gjennomforingProcessor = gjennomforingProcessor,
			deltakerProcessor = deltakerProcessor,
			arenaDataRepository = arenaDataRepository,
			gruppeTiltakProcessor = gruppeTiltakProcessor,
			meterRegistry = meterRegistry
		)
	}

	"should handle arena deltaker message" {
		val tiltakdeltakereJsonFileContent =
			javaClass.classLoader.getResource("data/arena-tiltakdeltakerendret-v1.json").readText()
		val tiltakdeltakere: List<JsonNode> = mapper.readValue(tiltakdeltakereJsonFileContent)
		val deltakerJson = tiltakdeltakere.toList()[0].toString()

		every {
			deltakerProcessor.handleArenaMessage(any())
		} returns Unit

		every {
			arenaDataRepository.alreadyProcessed(any(), any(), any())
		} returns false

		messageProcessor.handleArenaGoldenGateRecord(
			ConsumerRecord("test", 1, 1, "123456", deltakerJson)
		)

		verify(exactly = 1) {
			deltakerProcessor.handleArenaMessage(any())
		}
	}

	"should handle arena gjennomforing message" {
		val tiltakgjennomforingerJsonFileContent =
			javaClass.classLoader.getResource("data/arena-tiltakgjennomforingendret-v1.json").readText()
		val tiltakgjennomforinger: List<JsonNode> = mapper.readValue(tiltakgjennomforingerJsonFileContent)
		val tiltakgjennomforingJson = tiltakgjennomforinger.toList()[0].toString()

		every {
			gjennomforingProcessor.handleArenaMessage(any())
		} returns Unit

		every {
			arenaDataRepository.alreadyProcessed(any(), any(), any())
		} returns false

		messageProcessor.handleArenaGoldenGateRecord(
			ConsumerRecord("test", 1, 1, "123456", tiltakgjennomforingJson)
		)

		verify(exactly = 1) {
			gjennomforingProcessor.handleArenaMessage(any())
		}
	}

	"should handle arena tiltak message" {
		val tiltakJsonFileContent =
			javaClass.classLoader.getResource("data/arena-tiltakendret-v1.json").readText()
		val tiltakList: List<JsonNode> = mapper.readValue(tiltakJsonFileContent)
		val tiltakJson = tiltakList.toList()[0].toString()

		every {
			tiltakProcessor.handleArenaMessage(any())
		} returns Unit

		every {
			arenaDataRepository.alreadyProcessed(any(), any(), any())
		} returns false

		messageProcessor.handleArenaGoldenGateRecord(
			ConsumerRecord("test", 1, 1, "123456", tiltakJson)
		)

		verify(exactly = 1) {
			tiltakProcessor.handleArenaMessage(any())
		}
	}

	"should handle message with unicode NULL" {
		val tiltakgjennomforingerJsonFileContent =
			javaClass.classLoader.getResource("data/arena-tiltakgjennomforingendret-v1-bad-unicode.json")
				.readText()
		val tiltakgjennomforinger: List<JsonNode> = mapper.readValue(tiltakgjennomforingerJsonFileContent)
		val tiltakgjennomforingJson = tiltakgjennomforinger.toList()[0].toString()

		every {
			gjennomforingProcessor.handleArenaMessage(any())
		} returns Unit

		every {
			arenaDataRepository.alreadyProcessed(any(), any(), any())
		} returns false

		messageProcessor.handleArenaGoldenGateRecord(
			ConsumerRecord("test", 1, 1, "123456", tiltakgjennomforingJson)
		)

		val capturingSlot = CapturingSlot<ArenaGjennomforingKafkaMessage>()

		verify(exactly = 1) {
			gjennomforingProcessor.handleArenaMessage(capture(capturingSlot))
		}

		val capturedData = capturingSlot.captured

		capturedData.after?.VURDERING_GJENNOMFORING shouldBe "Vurdering"
	}

	"should skip messages already stored in arenadata table" {
		val tiltakJsonFileContent =
			javaClass.classLoader.getResource("data/arena-tiltakendret-v1.json").readText()
		val tiltakList: List<JsonNode> = mapper.readValue(tiltakJsonFileContent)
		val tiltakJson = tiltakList.toList()[0].toString()

		every {
			tiltakProcessor.handleArenaMessage(any())
		} returns Unit

		every {
			arenaDataRepository.alreadyProcessed(any(), any(), any())
		} returns true

		messageProcessor.handleArenaGoldenGateRecord(
			ConsumerRecord("test", 1, 1, "123456", tiltakJson)
		)


		verify(exactly = 0) {
			tiltakProcessor.handleArenaMessage(any())
		}
	}

})
