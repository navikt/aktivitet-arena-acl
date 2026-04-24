package no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName

enum class ArenaOperation {
	I,
	U,
	D
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArenaKafkaMessageDto(
	val table: ArenaTableName,

	@JsonProperty("op_type")
	val opType: String,

	@JsonProperty("op_ts")
	val opTs: String,

	val pos: String,
	val before: JsonNode?,
	val after: JsonNode?
)
