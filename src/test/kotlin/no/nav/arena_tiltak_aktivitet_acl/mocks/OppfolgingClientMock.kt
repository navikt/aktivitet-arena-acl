package no.nav.arena_tiltak_aktivitet_acl.mocks

import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.OppfolgingClient
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZonedDateTime
import java.util.*

@Configuration
open class OppfolgingClientMock {
	private val log = LoggerFactory.getLogger(javaClass)

	companion object {
		val oppfolgingsperioder = mutableMapOf<String, List<Oppfolgingsperiode>>()
		val defaultOppfolgingsperioder = listOf(
			Oppfolgingsperiode(
				uuid = UUID.randomUUID(),
				startTidspunkt = ZonedDateTime.now().minusMonths(2),
				sluttTidspunkt = ZonedDateTime.now().minusMonths(1)
			),
			Oppfolgingsperiode(
				uuid = UUID.randomUUID(),
				startTidspunkt = ZonedDateTime.now().minusWeeks(2),
				sluttTidspunkt = null
			)
		)
	}

	@Bean
	open fun oppfolgingClientClient(): OppfolgingClient {

		return object : OppfolgingClient {
			override fun hentOppfolgingsperioder(fnr: String): List<Oppfolgingsperiode> {

				if (oppfolgingsperioder[fnr] != null) {
					log.info("Fant mock oppfolgingsperiode for person: $fnr ${oppfolgingsperioder[fnr]}")
					return oppfolgingsperioder[fnr]!!
				}
				log.info("Bruker default oppfolgingsperiode")
				return defaultOppfolgingsperioder
			}
		}
	}


}
