package no.nav.arena_tiltak_aktivitet_acl.services

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessage
import no.nav.arena_tiltak_aktivitet_acl.exceptions.IgnoredException
import no.nav.arena_tiltak_aktivitet_acl.processors.DeltakerProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.GjennomforingProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.TiltakProcessor
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.utils.ARENA_DELTAKER_TABLE_NAME
import no.nav.arena_tiltak_aktivitet_acl.utils.ARENA_GJENNOMFORING_TABLE_NAME
import no.nav.arena_tiltak_aktivitet_acl.utils.ARENA_TILTAK_TABLE_NAME
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
open class RetryArenaMessageProcessorService(
	private val arenaDataRepository: ArenaDataRepository,
	private val tiltakProcessor: TiltakProcessor,
	private val gjennomforingProcessor: GjennomforingProcessor,
	private val deltakerProcessor: DeltakerProcessor,
	private val meterRegistry: MeterRegistry
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val mapper = ObjectMapper.get()

	companion object {
		private const val MAX_INGEST_ATTEMPTS = 10
	}

	fun processMessages(batchSize: Int = 500) {
		processMessagesWithStatus(IngestStatus.RETRY, batchSize)
	}

	fun processFailedMessages(batchSize: Int = 500) {
		processMessagesWithStatus(IngestStatus.FAILED, batchSize)
	}

	private fun processMessagesWithStatus(status: IngestStatus, batchSize: Int) {
		processMessages(ARENA_TILTAK_TABLE_NAME, status, batchSize)
		processMessages(ARENA_GJENNOMFORING_TABLE_NAME, status, batchSize)
		processMessages(ARENA_DELTAKER_TABLE_NAME, status, batchSize)
	}

	private fun processMessages(tableName: String, status: IngestStatus, batchSize: Int) {
		var fromId = 0
		var data: List<ArenaDataDbo>

		val start = Instant.now()
		var totalHandled = 0

		do {
			data = arenaDataRepository.getByIngestStatus(tableName, status, fromId, batchSize)
			data.forEach { process(it) }
			totalHandled += data.size
			fromId = data.maxOfOrNull { it.id.plus(1) } ?: Int.MAX_VALUE
		} while (data.isNotEmpty())

		// Sette QUEUED med lavest ID til RETRY
		val messagesMovedToRetry = arenaDataRepository.moveQueueForward()
		log.info("Moved $messagesMovedToRetry messages from QUEUED to RETRY")

		val duration = Duration.between(start, Instant.now())

		if (totalHandled > 0)
			log.info("[$tableName]: Handled $totalHandled $status messages in ${duration.toSeconds()}.${duration.toMillisPart()} seconds.")
	}


	private fun process(arenaDataDbo: ArenaDataDbo) {
		withTimer(arenaDataDbo.arenaTableName) {
			try {
				when (arenaDataDbo.arenaTableName) {
					ARENA_TILTAK_TABLE_NAME -> tiltakProcessor.handleArenaMessage(toArenaKafkaMessage(arenaDataDbo))
					ARENA_GJENNOMFORING_TABLE_NAME -> gjennomforingProcessor.handleArenaMessage(
						toArenaKafkaMessage(
							arenaDataDbo
						)
					)
					ARENA_DELTAKER_TABLE_NAME -> deltakerProcessor.handleArenaMessage(toArenaKafkaMessage(arenaDataDbo))
				}
			} catch (e: Exception) {
				val currentIngestAttempts = arenaDataDbo.ingestAttempts + 1
				val hasReachedMaxRetries = currentIngestAttempts >= MAX_INGEST_ATTEMPTS

				if (e is IgnoredException) {
					log.info("${arenaDataDbo.id} in table ${arenaDataDbo.arenaTableName}: '${e.message}'")
					arenaDataRepository.updateIngestStatus(arenaDataDbo.id, IngestStatus.IGNORED)
				} else if (arenaDataDbo.ingestStatus == IngestStatus.RETRY && hasReachedMaxRetries) {
					arenaDataRepository.updateIngestStatus(arenaDataDbo.id, IngestStatus.FAILED)
				}
				arenaDataRepository.updateIngestAttempts(arenaDataDbo.id, currentIngestAttempts, e.message)
			}
		}
	}

	private inline fun <reified D> toArenaKafkaMessage(arenaDataDbo: ArenaDataDbo): ArenaKafkaMessage<D> {
		return ArenaKafkaMessage(
			arenaTableName = arenaDataDbo.arenaTableName,
			operationType = arenaDataDbo.operation,
			operationTimestamp = arenaDataDbo.operationTimestamp,
			operationPosition = arenaDataDbo.operationPosition,
			before = arenaDataDbo.before?.let { mapper.readValue(it, D::class.java) },
			after = arenaDataDbo.after?.let { mapper.readValue(it, D::class.java) }
		)
	}

	private fun withTimer(tableName: String, runnable: () -> Unit) {
		val timer = Timer.builder("batchProcessor")
			.publishPercentiles(0.5, 0.95) // median and 95th percentile
			.publishPercentileHistogram()
			.tag("tableName", tableName)
			.register(meterRegistry)

		timer.record { runnable.invoke() }
	}

}
