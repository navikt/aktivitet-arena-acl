package no.nav.arena_tiltak_aktivitet_acl.integration.executors

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.tiltak.TiltakCommand
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.tiltak.TiltakResult
import no.nav.arena_tiltak_aktivitet_acl.integration.utils.nullableAsyncRetryHandler
import no.nav.arena_tiltak_aktivitet_acl.repositories.TranslationRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.TiltakRepository
import no.nav.arena_tiltak_aktivitet_acl.utils.ARENA_TILTAK_TABLE_NAME
import no.nav.common.kafka.producer.KafkaProducerClientImpl
import org.junit.jupiter.api.fail

class TiltakTestExecutor(
	kafkaProducer: KafkaProducerClientImpl<String, String>,
	arenaDataRepository: ArenaDataRepository,
	translationRepository: TranslationRepository,
	private val tiltakRepository: TiltakRepository
) : TestExecutor(
	kafkaProducer = kafkaProducer,
	arenaDataRepository = arenaDataRepository,
	translationRepository = translationRepository
) {

	private val topic = "tiltak"

	fun execute(command: TiltakCommand): TiltakResult {
		return command.execute(incrementAndGetPosition()) { wrapper, kode -> executor(wrapper, kode) }
	}

	private fun executor(arenaWrapper: ArenaKafkaMessageDto, kode: String): TiltakResult {
		sendKafkaMessage(topic, objectMapper.writeValueAsString(arenaWrapper))

		val data = getArenaData(
			ARENA_TILTAK_TABLE_NAME,
			Operation.fromArenaOperationString(arenaWrapper.opType),
			arenaWrapper.pos
		)

		val storedTiltak = nullableAsyncRetryHandler({ tiltakRepository.getByKode(kode) })
			?: fail("Forventet at tiltak med kode $kode ligger i tiltak databasen.")

		return TiltakResult(
			arenaDataDbo = data,
			tiltak = storedTiltak
		)
	}
}
