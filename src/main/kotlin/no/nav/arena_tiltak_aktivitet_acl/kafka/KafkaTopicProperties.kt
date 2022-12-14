package no.nav.arena_tiltak_aktivitet_acl.kafka

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.env")
data class KafkaTopicProperties(
	var arenaTiltakTopic: String = "",
	var arenaTiltakGjennomforingTopic: String = "",
	var arenaTiltakDeltakerTopic: String = "",
)
