package no.nav.arena_tiltak_aktivitet_acl.integration.executors

import io.kotest.common.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetskortHeaders
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.KafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.AktivitetResult
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.DeltakerCommand
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.HandledAndIgnored
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.HandledResult
import no.nav.arena_tiltak_aktivitet_acl.integration.kafka.KafkaAktivitetskortIntegrationConsumer
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.services.ArenaId
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.common.kafka.producer.KafkaProducerClientImpl
import java.time.LocalDateTime

class DeltakerTestExecutor(
	kafkaProducer: KafkaProducerClientImpl<String, String>,
	arenaDataRepository: ArenaDataRepository,
	val aktivitetRepository: AktivitetRepository
) : TestExecutor(
	kafkaProducer = kafkaProducer,
	arenaDataRepository = arenaDataRepository,
) {

	private val topic = "deltaker"

	companion object {
		private val messageFlow = MutableSharedFlow<TestRecord>(replay = 1)
		init {
			KafkaAktivitetskortIntegrationConsumer.subscribeAktivitet { record, headers ->
				messageFlow.emit(TestRecord(record,headers))
			}
		}
	}

	fun execute(command: DeltakerCommand, pos: Long = incrementAndGetPosition(), operationTimestamp: LocalDateTime? = null): AktivitetResult {
		return sendAndCheck(
			command.toArenaKafkaMessageDto(pos, operationTimestamp ?: LocalDateTime.now()),
			command.tiltakDeltakerId.toString()
		)
	}
	private suspend fun waitForAktivitetskortOnOutgoingTopic(isCorrectRecord: (TestRecord) -> Boolean): TestRecord {
		return withTimeout(5000) {
			messageFlow.first { isCorrectRecord(it) }
		}
	}

	private fun sendAndCheck(wrapper: ArenaKafkaMessageDto, tiltakDeltakerId: String): AktivitetResult {
		sendKafkaMessage(topic, objectMapper.writeValueAsString(wrapper), tiltakDeltakerId)
		return getResults(wrapper)
	}

	private fun getResults(wrapper: ArenaKafkaMessageDto): AktivitetResult {
		val arenaData = pollArenaData(
			ArenaTableName.DELTAKER,
			Operation.fromArenaOperationString(wrapper.opType),
			OperationPos(wrapper.pos.toLong())
		)

		val deltakelseId = DeltakelseId(arenaData.arenaId.toLong())
		var deltakerAktivitetMapping = aktivitetRepository.getAllBy(
			ArenaId(
				deltakelseId,
				AktivitetKategori.TILTAKSAKTIVITET
			)
		).values
		// There is no ack for messages which are put in retry,
		// use translation-table for checking if record is processed <- GJELDER IKKE LENGER
		when (arenaData.ingestStatus) {
			IngestStatus.IGNORED, IngestStatus.INVALID -> {}
			IngestStatus.NEW -> {}
			IngestStatus.QUEUED, IngestStatus.RETRY, IngestStatus.FAILED -> {}
			IngestStatus.HANDLED_AND_IGNORED -> {
				return HandledAndIgnored(
					arenaData.operationPosition,
					arenaData,
					deltakerAktivitetMapping
				)
			}
			IngestStatus.HANDLED -> {
				val message: TestRecord = runBlocking {
					waitForAktivitetskortOnOutgoingTopic {
						deltakerAktivitetMapping = aktivitetRepository.getAllBy(ArenaId(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET)).values
						deltakerAktivitetMapping.any { a -> it.melding.aktivitetskort.id == a.id }
					}
				}
				return HandledResult(
					arenaData.operationPosition,
					arenaData,
					deltakerAktivitetMapping,
					message.melding,
					message.headers,
				)
			}
		}
		return AktivitetResult(
			arenaData.operationPosition,
			arenaData,
			deltakerAktivitetMapping
		)
	}
}

data class TestRecord(
	val melding: KafkaMessageDto,
	val headers: AktivitetskortHeaders
)
