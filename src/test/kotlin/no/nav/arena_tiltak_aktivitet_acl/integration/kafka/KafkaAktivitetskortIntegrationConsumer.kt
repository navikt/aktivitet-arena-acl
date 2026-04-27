package no.nav.arena_tiltak_aktivitet_acl.integration.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.JsonNode
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetskortHeaders.Companion.fromKafkaHeaders
import no.nav.arena_tiltak_aktivitet_acl.kafka.KafkaProperties
import no.nav.arena_tiltak_aktivitet_acl.utils.JsonUtils
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import no.nav.common.kafka.consumer.KafkaConsumerClient
import no.nav.common.kafka.consumer.util.KafkaConsumerClientBuilder
import no.nav.common.kafka.consumer.util.deserializer.Deserializers.stringDeserializer
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.*
import kotlinx.coroutines.runBlocking

class KafkaAktivitetskortIntegrationConsumer(
	kafkaProperties: KafkaProperties,
	topic: String
) {

	private val client: KafkaConsumerClient
	companion object {
		private val aktivitetSubscriptions =  mutableListOf<suspend (wrapper: KafkaMessageDto, headers: AktivitetskortHeaders) -> Unit>()
		fun subscribeAktivitet(handler: suspend (record: KafkaMessageDto, headers: AktivitetskortHeaders) -> Unit) {
			aktivitetSubscriptions.add(handler)
		}
	}

	init {
		val config = KafkaConsumerClientBuilder.TopicConfig<String, String>()
			.withLogging()
			.withConsumerConfig(
				topic,
				stringDeserializer(),
				stringDeserializer(),
				::handle
			)
		client = KafkaConsumerClientBuilder.builder()
			.withProperties(kafkaProperties.consumer())
			.withTopicConfig(config)
			.build()
		client.start()
	}

	private fun handle(record: ConsumerRecord<String, String>) {
		val unknownMessageWrapper = JsonUtils.fromJson(record.value(), UnknownMessageWrapper::class.java)
		when (unknownMessageWrapper.actionType) {
			ActionType.UPSERT_AKTIVITETSKORT_V1 -> {
				val deltakerPayload =
					ObjectMapper.get().treeToValue(unknownMessageWrapper.aktivitetskort, Aktivitetskort::class.java)
				val message = toKnownMessageWrapper(deltakerPayload, unknownMessageWrapper)
				runBlocking {
					aktivitetSubscriptions.forEach { handleMessage ->
						handleMessage(message, fromKafkaHeaders(record.headers()))
					}
				}
			}
		}
	}

	private fun  toKnownMessageWrapper(aktivitetkort: Aktivitetskort, unknownMessageWrapper: UnknownMessageWrapper): KafkaMessageDto {
		return KafkaMessageDto(
			messageId = unknownMessageWrapper.messageId,
			source = unknownMessageWrapper.utsender,
			actionType = unknownMessageWrapper.actionType,
			aktivitetskort = aktivitetkort,
			aktivitetskortType = unknownMessageWrapper.aktivitetskortType
		)
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	data class UnknownMessageWrapper(
		val messageId: UUID,
		val utsender: String = "ARENA_TILTAK_AKTIVITET_ACL",
		val actionType: ActionType,
		val aktivitetskortType: AktivitetskortType,
		val aktivitetskort: JsonNode
	)
}
