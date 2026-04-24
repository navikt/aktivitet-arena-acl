package no.nav.arena_tiltak_aktivitet_acl.utils


import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

object ObjectMapper {

	private val instance = JsonMapper.builder()
		.addModule(KotlinModule.Builder().build())
		.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
		.build()

	fun get() = instance!!

}
