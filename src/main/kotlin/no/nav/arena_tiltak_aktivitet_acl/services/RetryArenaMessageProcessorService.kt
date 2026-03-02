package no.nav.arena_tiltak_aktivitet_acl.services

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.*
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessage
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.exceptions.IgnoredException
import no.nav.arena_tiltak_aktivitet_acl.exceptions.OlderThanCurrentStateException
import no.nav.arena_tiltak_aktivitet_acl.processors.DeltakerProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.GjennomforingProcessor
import no.nav.arena_tiltak_aktivitet_acl.processors.TiltakProcessor
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlin.time.measureTimedValue
import no.nav.arena_tiltak_aktivitet_acl.utils.TeamLog.teamLog

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
		processMessages(ArenaTableName.TILTAK, status, batchSize)
		processMessages(ArenaTableName.GJENNOMFORING, status, batchSize)
		processMessages(ArenaTableName.DELTAKER, status, batchSize)
	}

	private fun processMessages(tableName: ArenaTableName, status: IngestStatus, batchSize: Int) {
		log.info("processMessages $tableName ingestStatus: $status")
		val startOperationTs = LocalDateTime.MIN
		val start = Instant.now()

		val totalHandled = runBlocking {
			tailrec suspend fun processNextBatch(currentBatch: List<ArenaDataDbo>, totalHandled: Int = 0): Int {
				log.info("Next batch: ${currentBatch.size} messages")
				if (currentBatch.isEmpty()) return totalHandled
				// Prosess up to batchSize in parallel then wait for all to finish
				currentBatch.map { async(Dispatchers.IO) { process(it) } }.awaitAll()
				log.info("Finished processing ${currentBatch.size} in parallel(?)")
				val nextStartOperationTs = currentBatch.maxByOrNull { it.operationTimestamp }?.operationTimestamp
				if (nextStartOperationTs != null) log.info("Next operationTs ${nextStartOperationTs}") else log.info("No next pos, batch finished")
				val nextBatch = nextStartOperationTs?.let {
					val timedStartBatch = measureTimedValue {
						arenaDataRepository.getByIngestStatus(tableName, status, nextStartOperationTs, batchSize)
					}
					log.info("Fetched next part of batch in :${timedStartBatch.duration.inWholeSeconds} seconds")
					timedStartBatch.value
				} ?: emptyList()
				return processNextBatch(nextBatch, totalHandled + nextBatch.size)
			}
			log.info("Fetching batch by ingestStatus: ${status.name} table: $tableName $batchSize")
			val timedStartBatch = measureTimedValue {
				 arenaDataRepository.getByIngestStatus(tableName, status, startOperationTs, batchSize)
			}
			log.info("Starting on batch with ${timedStartBatch.value.size} messages table: $tableName fetched in :${timedStartBatch.duration.inWholeSeconds} seconds")
			return@runBlocking processNextBatch(timedStartBatch.value)
		}

		// Sette QUEUED med lavest ID til RETRY
		val messagesMovedToRetry = measureTimedValue {
			arenaDataRepository.moveQueueForward(tableName)
		}
		log.info("Moved ${messagesMovedToRetry.value} messages from QUEUED to RETRY $tableName in ${messagesMovedToRetry.duration.inWholeSeconds} seconds")

		val duration = Duration.between(start, Instant.now())

		if (totalHandled > 0)
			log.info("[$tableName]: Handled $totalHandled $status messages in ${duration.toSeconds()}.${duration.toMillisPart()} seconds.")
	}


	private fun process(arenaDataDbo: ArenaDataDbo) {
		withTimer(arenaDataDbo.arenaTableName.tableName) {
			try {
				when (arenaDataDbo.arenaTableName) {
					ArenaTableName.TILTAK -> tiltakProcessor.handleArenaMessage(toArenaKafkaMessage(arenaDataDbo))
					ArenaTableName.GJENNOMFORING -> gjennomforingProcessor.handleArenaMessage(
						toArenaKafkaMessage(
							arenaDataDbo
						)
					)
					ArenaTableName.DELTAKER -> deltakerProcessor.handleArenaMessage(toArenaKafkaMessage(arenaDataDbo))
				}
			} catch (e: Throwable) {
				val currentIngestAttempts = arenaDataDbo.ingestAttempts + 1
				val hasReachedMaxRetries = currentIngestAttempts >= MAX_INGEST_ATTEMPTS

				if (e is IgnoredException) {
					log.info("${arenaDataDbo.id} in table ${arenaDataDbo.arenaTableName}: '${e.message}'")
					arenaDataRepository.updateIngestStatus(arenaDataDbo.id, IngestStatus.IGNORED)
				} else if (e is OlderThanCurrentStateException) {
					arenaDataRepository.updateIngestStatus(arenaDataDbo.id, IngestStatus.IGNORED, e.message)
				} else if (arenaDataDbo.ingestStatus == IngestStatus.RETRY && hasReachedMaxRetries) {
					arenaDataRepository.updateIngestStatus(arenaDataDbo.id, IngestStatus.FAILED)
				}
				log.error("feilet retry-behandling av medling med arenaId: ${arenaDataDbo.arenaId}, id: ${arenaDataDbo.id}, tabell: ${arenaDataDbo.arenaTableName}, attempts: $currentIngestAttempts", e)
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

		timer.record(runnable)
	}

}
