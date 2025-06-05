package no.nav.veilarbaktivitet.aktivitetskort

import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.OppfolgingClient
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.services.FinnOppfolgingResult
import no.nav.arena_tiltak_aktivitet_acl.services.OppfolgingsperiodeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*

class OppfolgingsperiodeServiceTest {
	private lateinit var oppfolgingClient: OppfolgingClient
	private lateinit var oppfolgingsperiodeService: OppfolgingsperiodeService

	@BeforeEach
	fun setup() {
		oppfolgingClient = Mockito.mock(OppfolgingClient::class.java)
		oppfolgingsperiodeService = OppfolgingsperiodeService(oppfolgingClient)
	}

	@Test
	fun `opprettetTidspunkt passer i gammel periode`() {
		val riktigPeriode: Oppfolgingsperiode =
			oppfperiodeDTO(DATE_TIME.minusDays(30), DATE_TIME.minusDays(20))
		val perioder = listOf(
			riktigPeriode,
			oppfperiodeDTO(DATE_TIME.minusDays(10), null)
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(25))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `opprettetTidspunkt passer i gjeldende periode`() {
		val riktigPeriode: Oppfolgingsperiode = oppfperiodeDTO(DATE_TIME.minusDays(10), null)
		val perioder = listOf(
			oppfperiodeDTO(DATE_TIME.minusDays(30), DATE_TIME.minusDays(20)),
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusHours(10))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `opprettetTidspunkt passer paa startDato`() { // skal være inklusiv med andre ord
		val riktigPeriode: Oppfolgingsperiode = oppfperiodeDTO(DATE_TIME.minusDays(10), null)
		val perioder = listOf(
			oppfperiodeDTO(DATE_TIME.minusDays(30), DATE_TIME.minusDays(20)),
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(10))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `feil periode er naermere, men foer oppstart`() {
		val riktigPeriode: Oppfolgingsperiode = oppfperiodeDTO(DATE_TIME.plusDays(6), null)
		val perioder = listOf(
			riktigPeriode,
			oppfperiodeDTO(DATE_TIME.minusDays(4), DATE_TIME.minusDays(2))
		)

		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME)
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `opprettetTidspunkti to gamle perioder`() {
		// Er riktig fordi den er "nyere" enn den andre perioden
		val riktigPeriode: Oppfolgingsperiode = oppfperiodeDTO(DATE_TIME.minusDays(16), DATE_TIME.minusDays(5))
		val perioder = listOf(
			oppfperiodeDTO(DATE_TIME.minusDays(20), DATE_TIME.minusDays(10)),
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(15))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `opprettetTidspunkt i en gammel og en gjeldende periode`() {
		val riktigPeriode: Oppfolgingsperiode = oppfperiodeDTO(DATE_TIME.minusDays(16), DATE_TIME)
		val perioder = listOf(
			oppfperiodeDTO(DATE_TIME.minusDays(20), DATE_TIME.minusDays(10)),
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(15))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `opprettetTidspunkt mot en bruker som ikke har oppfolgingsperioder`() {
		val perioder: List<Oppfolgingsperiode> = listOf()
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(15))
		assertThat(oppfolgingsperiode).isNull()
	}

	@Test
	fun `velg naermeste periode etter opprettetitdspunkt OG som er 10 min innen opprettetTidspunkt`() {
		val riktigPeriode: Oppfolgingsperiode =
			oppfperiodeDTO(DATE_TIME.minusDays(10).plusMinutes(5), DATE_TIME)
		val perioder = listOf(
			oppfperiodeDTO(DATE_TIME.minusDays(10).minusMinutes(4), DATE_TIME.minusDays(10).minusMinutes(2)),
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(10))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	@Test
	fun `ikke velg periode hvis perioden slutter foer aktivitetens opprettetTidspunkt`() {
		val riktigPeriode: Oppfolgingsperiode =
			oppfperiodeDTO(DATE_TIME.minusDays(10).minusMinutes(5), DATE_TIME.minusDays(10).minusMinutes(2))
		val perioder = listOf(
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(10))
		assertThat(oppfolgingsperiode).isNull()
	}

	@Test
	fun ti_min_innen_en_gjeldende_periode() {
		val riktigPeriode: Oppfolgingsperiode = oppfperiodeDTO(DATE_TIME.minusDays(10).plusMinutes(5), null)
		val perioder = listOf(
			riktigPeriode
		)
		val oppfolgingsperiode: Oppfolgingsperiode? =
			stubOgFinnOppgolgingsperiode(perioder, LOCAL_DATE_TIME.minusDays(10))
		assertThat(oppfolgingsperiode!!.uuid).isEqualTo(riktigPeriode.uuid)
	}

	private fun stubOgFinnOppgolgingsperiode(
		perioder: List<Oppfolgingsperiode>,
		opprettetTidspunkt: LocalDateTime
	): Oppfolgingsperiode? {
		Mockito.`when`(oppfolgingClient.hentOppfolgingsperioder(ArgumentMatchers.anyString()))
			.thenReturn(perioder)

		val result = oppfolgingsperiodeService.finnOppfolgingsperiode(FNR, opprettetTidspunkt)
		return when (result) {
			is FinnOppfolgingResult.FunnetPeriodeResult -> result.oppfolgingsperiode
			else -> null
		}
	}

	private fun oppfperiodeDTO(startDato: ZonedDateTime, sluttDato: ZonedDateTime?): Oppfolgingsperiode {
		return Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = startDato,
			sluttTidspunkt = sluttDato
		)
	}

	companion object {
		private const val FNR: String = "123"
		private val DATE_TIME = ZonedDateTime.now()
		private val LOCAL_DATE_TIME = DATE_TIME.toLocalDateTime()
	}
}
