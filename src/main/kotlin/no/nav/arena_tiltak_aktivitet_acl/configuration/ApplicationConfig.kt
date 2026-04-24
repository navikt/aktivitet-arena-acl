package no.nav.arena_tiltak_aktivitet_acl.configuration

import io.getunleash.DefaultUnleash
import io.getunleash.Unleash
import no.nav.common.rest.filter.LogRequestFilter
import no.nav.common.token_client.builder.AzureAdTokenClientBuilder
import no.nav.common.token_client.client.MachineToMachineTokenClient
import no.nav.common.utils.EnvironmentUtils
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@EnableJwtTokenValidation(ignore = ["org.springdoc", "org.springframework"])
@Configuration
@EnableConfigurationProperties(UnleashConfig::class)
open class ApplicationConfig {

	companion object {
		const val APPLICATION_NAME = "aktivitet-arena-acl"
	}

	@Bean
	@Profile("default")
	open fun machineToMachineTokenClient(): MachineToMachineTokenClient {
		return AzureAdTokenClientBuilder.builder()
			.withNaisDefaults()
			.buildMachineToMachineTokenClient()
	}

	@Bean
	open fun logRequestFilterRegistrationBean(): FilterRegistrationBean<LogRequestFilter> {
		val registration =
			FilterRegistrationBean(LogRequestFilter(APPLICATION_NAME, EnvironmentUtils.isDevelopment().orElse(false)))

		registration.order = 1
		registration.addUrlPatterns("/*")
		return registration
	}

	@Bean
	@Profile("default")
	open fun unleash(config: UnleashConfig): Unleash {
		return DefaultUnleash(config.toUnleashConfig())
	}

}

fun getEnvOrProperty(key: String): String {
	return System.getenv(key) ?: System.getProperty(key)
}
