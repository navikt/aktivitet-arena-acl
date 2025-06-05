package no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import no.nav.common.rest.client.RestClient.baseClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.function.Supplier

class OppfolgingClientImpl(
	private val baseUrl: String,
	private val tokenProvider: Supplier<String>,
	private val httpClient: OkHttpClient = baseClient(),
) : OppfolgingClient {

	override fun hentOppfolgingsperioder(fnr: String): List<Oppfolgingsperiode> {

		val request = Request.Builder()
			.url("$baseUrl/veilarboppfolging/api/v2/oppfolging/perioder?fnr=${fnr}")
			.addHeader("Authorization", "Bearer ${tokenProvider.get()}")
			.get()
			.build()

		httpClient.newCall(request).execute().use { response ->

			if (!response.isSuccessful) {
				throw RuntimeException("Klarte ikke å hente oppfølgingsperioder. fnr=${fnr} status=${response.code}")
			}

			val body = response.body?.string() ?: throw RuntimeException("Body is missing")

			return ObjectMapper.get().readValue<List<OppfolgingsperiodeDto>>(body).map { dto -> Oppfolgingsperiode(
				uuid = dto.uuid,
				startTidspunkt = dto.startDato,
				sluttTidspunkt = dto.sluttDato
			) }
		}
	}

}
