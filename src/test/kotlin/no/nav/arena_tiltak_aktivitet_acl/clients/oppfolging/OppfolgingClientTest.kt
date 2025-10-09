package no.nav.arena_tiltak_aktivitet_acl.clients.enhetsregister

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.OppfolgingClientImpl
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.util.*

@WireMockTest
class OppfolgingClientTest {
	var proxyToken = "PROXY_TOKEN"

	@Test
	fun `should work` (wmRuntimeInfo: WireMockRuntimeInfo) {
		val client = OppfolgingClientImpl(
			baseUrl = wmRuntimeInfo.httpBaseUrl,
			tokenProvider = { proxyToken },
		)

		val uuid = UUID.randomUUID()
		val startDato = ZonedDateTime.now().minusMonths(2)

		@Language("JSON")
		val body = """
			[
				{
					"uuid": "$uuid",
					"startDato": "$startDato",
					"sluttDato": null
				}
			]
		""".trimIndent()

		WireMock.givenThat(
			WireMock.get(WireMock.urlEqualTo("/veilarboppfolging/api/v3/oppfolging/hent-perioder"))
				.withHeader("Authorization", WireMock.equalTo("Bearer $proxyToken"))
				.willReturn(
					WireMock.aResponse()
						.withStatus(200)
						.withBody(body)
				)
		)

		Assertions.assertEquals(listOf(Oppfolgingsperiode(
			uuid = uuid,
			startTidspunkt = startDato,
			sluttTidspunkt = null
		)), client.hentOppfolgingsperioder("123"))
	}

}
