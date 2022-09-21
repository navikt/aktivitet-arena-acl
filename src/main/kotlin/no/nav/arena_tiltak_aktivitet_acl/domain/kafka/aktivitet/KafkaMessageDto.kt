package no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet

import java.time.LocalDateTime
import java.util.*

enum class Operation {
	CREATED,
	MODIFIED,
	DELETED;

	companion object {
		fun fromArenaOperationString(arenaOperationString: String): Operation {
			return when (arenaOperationString) {
				"I" -> CREATED
				"U" -> MODIFIED
				"D" -> DELETED
				else -> throw IllegalArgumentException("Unknown arena operation $arenaOperationString")
			}
		}
	}
}

enum class ActionType {
	UPSERT_TILTAK_AKTIVITET_V1,
	UPSERT_UTDANNING_AKTIVITET_V1,
	UPSERT_GRUPPE_AKTIVITET_V1
}

data class KafkaMessageDto<D>(
	val messageId: UUID,
	val source: String = "ARENA_TILTAK_AKTIVITET_ACL",
	val sendt: LocalDateTime,
	val actionType: ActionType,
	val payload: D // f.eks UpsertTiltakAktivitetV1/TiltakAktivitet
)