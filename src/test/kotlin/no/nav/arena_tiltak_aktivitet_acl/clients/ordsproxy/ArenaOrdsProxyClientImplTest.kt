package no.nav.arena_tiltak_aktivitet_acl.clients.ordsproxy

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@WireMockTest
class ArenaOrdsProxyClientImplTest {
	var proxyToken = "PROXY_TOKEN"

	@Test
	fun `hentFnr() skal lage riktig request og parse respons`(wmRuntimeInfo: WireMockRuntimeInfo) {
		val client = ArenaOrdsProxyClientImpl(
			baseUrl = wmRuntimeInfo.httpBaseUrl,
			tokenProvider = { proxyToken },
		)

		givenThat(
			get(urlEqualTo("/api/ords/fnr?personId=987654"))
				.withHeader("Authorization", equalTo("Bearer $proxyToken"))
				.willReturn(
					aResponse()
						.withStatus(200)
						.withBody(
							"""
								{
									"fnr": "78900"
								}
							""".trimIndent()
						)
				)

		)

		assertEquals("78900", client.hentFnr(987654L))
	}

	@Test
	fun `hentFnr() skal null hvis stauts er 404`(wmRuntimeInfo: WireMockRuntimeInfo) {
		val client = ArenaOrdsProxyClientImpl(
			baseUrl = wmRuntimeInfo.httpBaseUrl,
			tokenProvider = { proxyToken },
		)

		givenThat(
			get(urlEqualTo("/api/ords/fnr?personId=987654"))
				.willReturn(aResponse().withStatus(404))
		)

		assertNull(client.hentFnr(987654L))
	}

	@Test
	fun `hentArbeidsgiver() skal lage riktig request og parse respons`(wmRuntimeInfo: WireMockRuntimeInfo) {
		val client = ArenaOrdsProxyClientImpl(
			baseUrl = wmRuntimeInfo.httpBaseUrl,
			tokenProvider = { proxyToken },
		)

		givenThat(
			get(urlEqualTo("/api/ords/arbeidsgiver?arbeidsgiverId=1234567"))
				.withHeader("Authorization", equalTo("Bearer $proxyToken"))
				.willReturn(
					aResponse()
						.withStatus(200)
						.withBody(
							"""
								{
									"virksomhetsnummer": "6834920",
								 	"organisasjonsnummerMorselskap": "74894532"
								}
							""".trimIndent()
						)
				)

		)

		val expectedArbeidsgiver = Arbeidsgiver(
			virksomhetsnummer = "6834920",
			organisasjonsnummerMorselskap = "74894532"
		)

		assertEquals(expectedArbeidsgiver, client.hentArbeidsgiver(1234567L))
	}

	@Test
	fun `hentArbeidsgiver() skal returnere null hvis status er 404`(wmRuntimeInfo: WireMockRuntimeInfo) {
		val client = ArenaOrdsProxyClientImpl(
			baseUrl = wmRuntimeInfo.httpBaseUrl,
			tokenProvider = { proxyToken },
		)

		givenThat(
			get(urlEqualTo("/api/ords/arbeidsgiver?arbeidsgiverId=1234567"))
				.willReturn(aResponse().withStatus(404))
		)

		assertNull(client.hentArbeidsgiver(1234567L))
	}

}
