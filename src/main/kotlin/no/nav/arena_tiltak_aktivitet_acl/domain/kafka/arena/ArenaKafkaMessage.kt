package no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import java.time.LocalDateTime

data class ArenaKafkaMessage<D>(
	val arenaTableName: String,
	val operationType: Operation,
	val operationTimestamp: LocalDateTime,
	val operationPosition: String,
	val before: D?,
	val after: D?
) {
	fun getData(): D {
		return when (operationType) {
			Operation.CREATED -> after ?: throw NoSuchElementException("Message with opType=CREATED is missing 'after'")
			Operation.MODIFIED -> after ?: throw NoSuchElementException("Message with opType=MODIFIED is missing 'after'")
			Operation.DELETED -> before ?: throw NoSuchElementException("Message with opType=DELETED is missing 'before'")
		}
	}
}

typealias ArenaTiltakKafkaMessage = ArenaKafkaMessage<ArenaTiltak>

typealias ArenaGjennomforingKafkaMessage = ArenaKafkaMessage<ArenaGjennomforingDto>

typealias ArenaDeltakerKafkaMessage = ArenaKafkaMessage<ArenaDeltaker>
