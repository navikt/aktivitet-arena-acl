package no.nav.arena_tiltak_aktivitet_acl.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.date.shouldBeWithin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.nav.arena_tiltak_aktivitet_acl.clients.IdMappingClient
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.dto.TranslationQuery
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.*
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.gjennomforing.GjennomforingInput
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.gjennomforing.NyGjennomforingCommand
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.tiltak.NyttTiltakCommand
import no.nav.arena_tiltak_aktivitet_acl.mocks.OppfolgingClientMock
import no.nav.arena_tiltak_aktivitet_acl.mocks.OrdsClientMock
import no.nav.arena_tiltak_aktivitet_acl.processors.DeltakerProcessor.Companion.AKTIVITETSPLAN_LANSERINGSDATO
import no.nav.arena_tiltak_aktivitet_acl.processors.converters.ArenaDeltakerConverter.AMO
import no.nav.arena_tiltak_aktivitet_acl.processors.converters.ArenaDeltakerConverter.ENKELAMO
import no.nav.arena_tiltak_aktivitet_acl.processors.converters.ArenaDeltakerConverter.GRUPPEAMO
import no.nav.arena_tiltak_aktivitet_acl.processors.converters.ArenaDeltakerConverter.JOBBKLUBB
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.TiltakDbo
import no.nav.arena_tiltak_aktivitet_acl.services.KafkaProducerService
import no.nav.arena_tiltak_aktivitet_acl.services.KafkaProducerService.Companion.TILTAK_ID_PREFIX
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.mockito.kotlin.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random

class DeltakerIntegrationTests : IntegrationTestBase() {

	@Autowired
	lateinit var aktivitetRepository: AktivitetRepository

	@Autowired
	lateinit var arenaDataRepository: ArenaDataRepository

	@MockitoSpyBean
	lateinit var kafkaProducerService: KafkaProducerService

	data class TestData(
		val gjennomforingId: Long = Random.nextLong(),
		val deltakerId: DeltakelseId = DeltakelseId(),
		val gjennomforingInput: GjennomforingInput = GjennomforingInput(gjennomforingId = gjennomforingId),
		val tiltak: TiltakDbo = TiltakDbo(UUID.randomUUID(), "TILT", "Tiltak navn", "IND")
	)

	private fun setup(administrasjonskode: Tiltak.Administrasjonskode = Tiltak.Administrasjonskode.IND): TestData {
		val tiltak = tiltakExecutor.execute(NyttTiltakCommand(administrasjonskode = administrasjonskode))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }.tiltak
		return TestData(tiltak = tiltak)
			.also { testData ->
				gjennomforingExecutor.execute(NyGjennomforingCommand(testData.gjennomforingInput))
					.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			}
	}

	@Test
	fun `ingest deltaker`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		var aktivitetId: UUID? = null
		result.expectHandled { handledResult ->
			handledResult.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			handledResult.aktivitetskort { it.isSame(deltakerInput, tiltak, gjennomforingInput) }
			handledResult.headers.tiltakKode shouldBe gjennomforingInput.tiltakKode
			handledResult.headers.arenaId shouldBe TILTAK_ID_PREFIX + deltakerInput.tiltakDeltakelseId
			handledResult.headers.oppfolgingsperiode shouldNotBe null
			handledResult.headers.oppfolgingsSluttDato shouldBe null
			handledResult.aktivitetskort {
				aktivitetId = it.id
			}
			handledResult.deltakerAktivitetMapping.any { mapping -> mapping.id == aktivitetId} shouldBe true
		}

		val translation = hentTranslationMedRestClient(deltakerId)
		translation shouldBe aktivitetId
	}

	@Test
	fun `feilregistrert i arena gir avbrutt aktivitet`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "GJENN",
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		var aktivitetId: UUID? = null
		result.expectHandled { handledResult ->
			handledResult.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			handledResult.aktivitetskort { it.aktivitetStatus == AktivitetStatus.GJENNOMFORES }
			handledResult.aktivitetskort {
				aktivitetId = it.id
			}
		}

		val translation = hentTranslationMedRestClient(deltakerId)
		translation shouldBe aktivitetId

		val deltakerCommand2 = NyDeltakerCommand(deltakerInput.copy(deltakerStatusKode = "FEILREG"))
		val result2: AktivitetResult = deltakerExecutor.execute(deltakerCommand2)
		result2.expectHandled {
			it.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			it.aktivitetskort { it.aktivitetStatus == AktivitetStatus.AVBRUTT }
		}
	}

	@Test
	fun `skal gi 200 når id-mapping ikke finnes (og lage mapping)`() {
		val token = issueAzureAdM2MToken()
		val client = IdMappingClient(port!!) { token }
		val (response, _) = client.hentMapping(TranslationQuery(123123, AktivitetKategori.TILTAKSAKTIVITET))
		response.code shouldBe HttpStatus.OK.value()
	}

	@Test
	fun `skal kreve token`() {
		val client = IdMappingClient(port!!) { "" }
		val (response, _) = client.hentMapping(TranslationQuery(11221, AktivitetKategori.TILTAKSAKTIVITET))
		response.code shouldBe HttpStatus.UNAUTHORIZED.value()
	}

	@Test
	fun `skal være historisk hvis endret i avsluttet periode`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()

		val gammelPeriode = OppfolgingClientMock.defaultOppfolgingsperioder.first()
		val opprettetTidspunkt = gammelPeriode.startTidspunkt.plusSeconds(1)

		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = opprettetTidspunkt.toLocalDateTime()
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.expectHandled {
			it.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			it.deltakerAktivitetMapping.any { mapping -> mapping.id == it.output.aktivitetskort.id} shouldBe true
			it.aktivitetskort { it.isSame(deltakerInput, tiltak, gjennomforingInput) }
			it.headers.tiltakKode shouldBe gjennomforingInput.tiltakKode
			it.headers.arenaId shouldBe TILTAK_ID_PREFIX + deltakerInput.tiltakDeltakelseId
			it.headers.oppfolgingsperiode shouldBe gammelPeriode.uuid
			it.headers.oppfolgingsSluttDato!!.shouldBeWithin(Duration.ofMillis(1), gammelPeriode.sluttTidspunkt!!)
		}
	}
	@Test
	fun `skal oppdatere aktivitetskort i aktiv periode`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()

		val gammelPeriode = OppfolgingClientMock.defaultOppfolgingsperioder.first()
		val aktivPeriode = OppfolgingClientMock.defaultOppfolgingsperioder.last()
		val opprettetTidspunkt = aktivPeriode.startTidspunkt.plusSeconds(1)

		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = opprettetTidspunkt.toLocalDateTime(),
			datoFra = gammelPeriode.startTidspunkt.plusDays(1).toLocalDate(),
			datoTil = aktivPeriode.startTidspunkt.plusMonths(6).toLocalDate(),
			deltakerStatusKode = "GJENN"
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.expectHandled {
			it.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			it.deltakerAktivitetMapping.any { mapping -> mapping.id == it.output.aktivitetskort.id} shouldBe true
			it.aktivitetskort { it.isSame(deltakerInput, tiltak, gjennomforingInput) }
			it.headers.tiltakKode shouldBe gjennomforingInput.tiltakKode
			it.headers.arenaId shouldBe TILTAK_ID_PREFIX + deltakerInput.tiltakDeltakelseId
			it.headers.oppfolgingsperiode shouldBe aktivPeriode.uuid
		}

		val oppdatertDeltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = opprettetTidspunkt.toLocalDateTime(),
			datoFra = gammelPeriode.startTidspunkt.plusDays(1).toLocalDate(),
			datoTil = gammelPeriode.startTidspunkt.plusDays(1).toLocalDate(),
			deltakerStatusKode = "GJENN_AVL"
		)
		val oppdatertDeltakerCommand = NyDeltakerCommand(oppdatertDeltakerInput)
		val oppdatertResult = deltakerExecutor.execute(oppdatertDeltakerCommand)

		oppdatertResult.expectHandled {
			it.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			it.deltakerAktivitetMapping.any { mapping -> mapping.id == it.output.aktivitetskort.id} shouldBe true
			it.aktivitetskort { it.isSame(oppdatertDeltakerInput, tiltak, gjennomforingInput, AktivitetStatus.AVBRUTT) }
			it.headers.tiltakKode shouldBe gjennomforingInput.tiltakKode
			it.headers.arenaId shouldBe TILTAK_ID_PREFIX + oppdatertDeltakerInput.tiltakDeltakelseId
			it.headers.oppfolgingsperiode shouldBe aktivPeriode.uuid
		}
	}


	@Test
	fun `skal ikke opprette gammelt aktivitetskort hvis langt utenfor oppfølgingsperioder (ignored)`() {
		val (gjennomforingId, deltakerId) = setup()
		val opprettetTidspunkt = LocalDateTime.now().minusMonths(6)
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = opprettetTidspunkt,
			registrertDato = opprettetTidspunkt
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)
		result.arenaDataDbo.ingestStatus shouldBe IngestStatus.IGNORED
	}

	@Test
	fun `skal IKKE prossesere meldinger med eldre operation-timestamp selvom pos er høyere`() {
		val gjennomforingId = Random.nextLong()
		val deltakelseId = Random.nextLong()
		val tiltak = tiltakExecutor.execute(NyttTiltakCommand(kode = ENKELAMO))
		tiltak.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		val opprettetTidspunkt = LocalDateTime.now()
		val fullf = DeltakerInput(
			tiltakDeltakelseId = DeltakelseId(deltakelseId),
			tiltakgjennomforingId = gjennomforingId,
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = opprettetTidspunkt,
			registrertDato = opprettetTidspunkt,
			deltakerStatusKode = "FULLF"
		)
		val fullfCommand = NyDeltakerCommand(fullf)
		val gjennCommand = NyDeltakerCommand(fullf.copy(endretAv = Ident(ident = "HPA321"), deltakerStatusKode = "GJENN"))

		gjennomforingExecutor.execute(NyGjennomforingCommand(GjennomforingInput(
			gjennomforingId = gjennomforingId, tiltakKode = tiltak.tiltak.kode)))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
		deltakerExecutor.execute(fullfCommand, pos = 1, operationTimestamp = LocalDateTime.now())
			.expectHandled {}
		deltakerExecutor.execute(gjennCommand, pos = 2, operationTimestamp = LocalDateTime.now().minusDays(1))
			.arenaData {
				it.note shouldStartWith "Har behandlet nyere meldinger"
				it.ingestStatus shouldBe IngestStatus.IGNORED
			}
	}

	@Test
	fun `skal bruker regDato til å finne oppfolgingsperiode hvis ingens finnes på modDato`() {
		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(30),
			sluttTidspunkt = ZonedDateTime.now().minusDays(7)
		)
		val arenaPersonIdent = 121212L
		val fnr = "616161"
		OrdsClientMock.fnrHandlers[arenaPersonIdent] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)

		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			personId = arenaPersonIdent,
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = foerstePeriode.sluttTidspunkt!!.minusDays(1).toLocalDateTime(),
			registrertDato = foerstePeriode.sluttTidspunkt!!.plusDays(1).toLocalDateTime()
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand).expectHandled {
			it.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
		}
	}

	@Test
	fun `process deltakelse in the correct order`() {
		val (gjennomforingId, deltakerId, gjennomforingInput) = TestData()

		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			deltakerStatusKode = "INFOMOETE", // Aktivitetstatus: Planlagt
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result: AktivitetResult = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }

		val deltakerCommand2 = NyDeltakerCommand(deltakerInput.copy(deltakerStatusKode = "GJENN"))
		val result2: AktivitetResult = deltakerExecutor.execute(deltakerCommand2)

		result2.arenaData { it.ingestStatus shouldBe IngestStatus.QUEUED }

		tiltakExecutor.execute(NyttTiltakCommand())
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		gjennomforingExecutor.execute(NyGjennomforingCommand(gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		val deltakerCommand3 = NyDeltakerCommand(deltakerInput.copy(deltakerStatusKode = "FULLF"))
		val result3: AktivitetResult = deltakerExecutor.execute(deltakerCommand3)

		result3.arenaData { it.ingestStatus shouldBe IngestStatus.QUEUED }

		// Cron-job
		processMessages()

		val aktivitetId = idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET)).second
		aktivitetId shouldNotBe null

		val mapper = ObjectMapper.get()
		val data = aktivitetRepository.getAktivitet(aktivitetId!!)!!.data
		val aktivitetskort = mapper.readValue(data, Aktivitetskort::class.java)
		aktivitetskort.aktivitetStatus shouldBe AktivitetStatus.PLANLAGT

		processMessages()

		val data2 = aktivitetRepository.getAktivitet(aktivitetId)!!.data
		val aktivitetskort2 = mapper.readValue(data2, Aktivitetskort::class.java)
		aktivitetskort2.aktivitetStatus shouldBe AktivitetStatus.GJENNOMFORES

		processMessages()

		val data3 = aktivitetRepository.getAktivitet(aktivitetId)!!.data
		val aktivitetskort3 = mapper.readValue(data3, Aktivitetskort::class.java)
		aktivitetskort3.aktivitetStatus shouldBe AktivitetStatus.FULLFORT
	}

	@Test
	fun `process deltakelse in the correct order also when failed`() {
		val (gjennomforingId, deltakerId, gjennomforingInput) = TestData()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			deltakerStatusKode = "INFOMOETE", // Aktivitetstatus: Planlagt
			endretAv = Ident(ident = "SIG123"),
		)
		val planlagtCommand = NyDeltakerCommand(deltakerInput)
		val plandlagtCommandResult: AktivitetResult = deltakerExecutor.execute(planlagtCommand)
		plandlagtCommandResult.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }
		val arenaData = plandlagtCommandResult.arenaDataDbo

		// Fail first message after 10 retries
		(1..10).forEach { processMessages() }
		val resultDbo =
			arenaDataRepository.get(arenaData.arenaTableName, arenaData.operation, arenaData.operationPosition)
		resultDbo.ingestStatus shouldBe IngestStatus.FAILED

		val gjennomforingCommand = NyDeltakerCommand(deltakerInput.copy(deltakerStatusKode = "GJENN"))
		val gjennomforingCommandResult: AktivitetResult = deltakerExecutor.execute(gjennomforingCommand)
		gjennomforingCommandResult.arenaData { it.ingestStatus shouldBe IngestStatus.QUEUED }

		tiltakExecutor.execute(NyttTiltakCommand())
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		gjennomforingExecutor.execute(NyGjennomforingCommand(gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		val fullfortCommand = NyDeltakerCommand(deltakerInput.copy(deltakerStatusKode = "FULLF"))
		val fullfortCommandResult: AktivitetResult = deltakerExecutor.execute(fullfortCommand)
		fullfortCommandResult.arenaData { it.ingestStatus shouldBe IngestStatus.QUEUED }

		// Cron-job
		processFailedMessages()
		val aktivitetId = idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET)).second!!

		fun String.toAktivitetskort() = ObjectMapper.get().readValue(this, Aktivitetskort::class.java)

		val planlagtAktivitetskort = aktivitetRepository.getAktivitet(aktivitetId)!!.data.toAktivitetskort()
		planlagtAktivitetskort.aktivitetStatus shouldBe AktivitetStatus.PLANLAGT

		processMessages()
		val gjennomforingAktivitet = aktivitetRepository.getAktivitet(aktivitetId)!!.data.toAktivitetskort()
		gjennomforingAktivitet.aktivitetStatus shouldBe AktivitetStatus.GJENNOMFORES

		processMessages()
		val fullfortAktivitet = aktivitetRepository.getAktivitet(aktivitetId)!!.data.toAktivitetskort()
		fullfortAktivitet.aktivitetStatus shouldBe AktivitetStatus.FULLFORT
	}

	@Test
	fun `ingest existing deltaker`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()

		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(10),
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		val deltakerInputUpdated = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoTil = LocalDate.now().plusDays(30),
			endretAv = Ident(ident = "SIG123"),
		)
		val updatedDeltakerCommand = NyDeltakerCommand(deltakerInputUpdated)
		val updatedResult = deltakerExecutor.execute(updatedDeltakerCommand)

		result.expectHandled { r ->
			r.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			r.deltakerAktivitetMapping.any { mapping -> mapping.id == r.output.aktivitetskort.id } shouldBe true
			r.aktivitetskort { it.isSame(deltakerInput, tiltak, gjennomforingInput) }
		}

		updatedResult.expectHandled { r ->
			r.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			r.deltakerAktivitetMapping.any { mapping -> mapping.id == r.output.aktivitetskort.id } shouldBe true
			r.aktivitetskort { it.isSame(deltakerInputUpdated, tiltak, gjennomforingInput) }
		}
	}

	@Test
	fun `ignore deltaker moddato before aktivitetsplan launch if tilDato before aktivitetsplan launch`() {
		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			datoTil = AKTIVITETSPLAN_LANSERINGSDATO.toLocalDate().minusDays(1),
			endretTidspunkt = AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1),
			registrertDato = AKTIVITETSPLAN_LANSERINGSDATO.minusDays(2)
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData {
			it.ingestStatus shouldBe IngestStatus.IGNORED
			it.note shouldBe "Deltakeren registrert=${deltakerInput.registrertDato} opprettet før aktivitetsplan skal ikke håndteres"
		}
	}

	@Test
	fun `ignore deltaker moddato before aktivitetsplan launch if tilDato after aktivitetplan launch, but no oppfolgingsperiode`() {
		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			datoTil = AKTIVITETSPLAN_LANSERINGSDATO.plusMonths(1).toLocalDate(),
			endretTidspunkt = AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1),
			registrertDato = AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1)
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData {
			it.ingestStatus shouldBe IngestStatus.IGNORED
			it.note shouldBe "Deltakelse aktiv ved aktivitetsplan lansering, men bruker ikke under oppfølging på det tidspunktet."
		}
	}

	@Test
	fun `dont ignore deltaker moddato before aktivitetsplan launch if tildato after aktivitetsplan launch and oppfolgingsperiode was active`() {
		val (gjennomforingId, deltakerId) = setup()

		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.of(AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1), ZoneId.systemDefault()),
			sluttTidspunkt = null
		)

		val deltakerInput = DeltakerInput(
			personId = 345L,
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = AKTIVITETSPLAN_LANSERINGSDATO.minusYears(1),
			endretTidspunkt = AKTIVITETSPLAN_LANSERINGSDATO.minusYears(1),
			datoTil = LocalDate.now().plusYears(25)
		)

		val fnr = "12345678901"
		OrdsClientMock.fnrHandlers[deltakerInput.personId!!] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)



		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.expectHandled {
			data -> data.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
		}
	}

	@Test
	fun `find correct periode for deltakelse before aktivitetsplan launch when moddato is very recent`() {
		val (gjennomforingId, deltakerId) = setup()

		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.of(AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1), ZoneId.systemDefault()),
			sluttTidspunkt = null
		)

		val deltakerInput = DeltakerInput(
			personId = 345L,
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = AKTIVITETSPLAN_LANSERINGSDATO.minusYears(1),
			endretTidspunkt = LocalDateTime.now(),
			datoTil = LocalDate.now().plusYears(25)
		)

		val fnr = "12345678901"
		OrdsClientMock.fnrHandlers[deltakerInput.personId!!] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)



		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.expectHandled {
				data -> data.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
		}
	}

	@Test
	fun `dont ignore created before aktivitetplan launch launch but modified after - missing tildato`() {
		val (gjennomforingId, deltakerId) = setup()

		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.of(AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1), ZoneId.systemDefault()),
			sluttTidspunkt = null
		)

		val deltakerInput = DeltakerInput(
			personId = 345L,
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = AKTIVITETSPLAN_LANSERINGSDATO.minusYears(1),
			endretTidspunkt = LocalDateTime.now(),
			datoTil = null
		)

		val fnr = "12345678901"
		OrdsClientMock.fnrHandlers[deltakerInput.personId!!] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)



		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.expectHandled {
				data -> data.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
		}
	}

	@Test
	fun `process deltakelser-updates with no periodematch if aktivitetskort already created`() {
		val (gjennomforingId, deltakerId) = setup()
		val oppfølgingsperiodeStart = ZonedDateTime.now().minusMonths(1)
		val tilDatoInniPeriode = oppfølgingsperiodeStart.plusDays(4)
		val tilDatoUtenforPeriode = oppfølgingsperiodeStart.minusDays(15).toLocalDate()

		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = oppfølgingsperiodeStart,
			sluttTidspunkt = null
		)

		val deltakerInput = DeltakerInput(
			personId = 444L,
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = tilDatoInniPeriode.toLocalDateTime(),
			endretTidspunkt = tilDatoInniPeriode.toLocalDateTime(),
			datoTil = null
		)

		val fnr = "12345678902"
		OrdsClientMock.fnrHandlers[deltakerInput.personId!!] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)
		result.expectHandled {
				data -> data.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
		}
		val oppdaterInput = deltakerInput.copy(datoTil = tilDatoUtenforPeriode)
		val oppdaterDeltakerCommand = OppdaterDeltakerCommand(deltakerInput, oppdaterInput)
		val oppdaterResult = deltakerExecutor.execute(oppdaterDeltakerCommand)
		oppdaterResult.expectHandled {
				data -> data.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
		}
	}

	@Test
	fun `tittel should be set to default value when gjennomforing navn is null`() {
		val gjennomforingId: Long = Random.nextLong()
		val deltakerId = DeltakelseId()
		val gjennomforingInput = GjennomforingInput(gjennomforingId = gjennomforingId, navn = null)
		val tiltak = tiltakExecutor.execute(NyttTiltakCommand())
			.let { result ->
				result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
				result.tiltak
			}
		gjennomforingExecutor.execute(NyGjennomforingCommand(gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		val endretDato = OppfolgingClientMock.defaultOppfolgingsperioder.last().startTidspunkt.toLocalDateTime()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = endretDato,
			endretTidspunkt = endretDato
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val aktivitetResult = deltakerExecutor.execute(deltakerCommand)

		aktivitetResult.expectHandled { result ->
			result.output.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1
			result.output.aktivitetskort.tittel shouldBe "Ukjent navn"
			result.aktivitetskort { it.isSame(deltakerInput, tiltak, gjennomforingInput) }
		}
	}

	@ParameterizedTest(name = "Tittel skal prefixes for {0}")
	@ValueSource(strings = [AMO, GRUPPEAMO, ENKELAMO])
	fun `tittel should be prefixed for some tiltakskoder`(tiltaksKode: String) {
		val gjennomforingId: Long = Random.nextLong()
		val deltakerId = DeltakelseId()
		val gjennomforingInput =
			GjennomforingInput(gjennomforingId = gjennomforingId, tiltakKode = tiltaksKode, navn = "Klubbmøte")
		tiltakExecutor.execute(NyttTiltakCommand(kode = tiltaksKode))
			.let { result ->
				result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
				result.tiltak
			}
		gjennomforingExecutor.execute(NyGjennomforingCommand(gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		val endretDato = OppfolgingClientMock.defaultOppfolgingsperioder.last().startTidspunkt.toLocalDateTime()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = endretDato,
			endretTidspunkt = endretDato
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val aktivitetResult = deltakerExecutor.execute(deltakerCommand)

		aktivitetResult.expectHandled { result ->
			result.output.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1
			result.output.aktivitetskort.tittel shouldMatch "^(Gruppe AMO:|AMO-kurs:|Enkeltplass AMO:) ${gjennomforingInput.navn}\$"
		}
	}

	@Test
	fun `beskrivelse should be gjennomforingsnavn for tiltakstype JOBBKLUBB`() {
		val gjennomforingId: Long = Random.nextLong()
		val deltakerId = DeltakelseId()
		val gjennomforingInput =
			GjennomforingInput(gjennomforingId = gjennomforingId, tiltakKode = JOBBKLUBB, navn = "Klubbmøte")
		tiltakExecutor.execute(NyttTiltakCommand(kode = JOBBKLUBB))
			.let { result ->
				result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
				result.tiltak
			}
		gjennomforingExecutor.execute(NyGjennomforingCommand(gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		val endretDato = OppfolgingClientMock.defaultOppfolgingsperioder.last().startTidspunkt.toLocalDateTime()

		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = endretDato,
			endretTidspunkt = endretDato
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val aktivitetResult = deltakerExecutor.execute(deltakerCommand)

		aktivitetResult.expectHandled { result ->
			result.output.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1
			result.output.aktivitetskort.beskrivelse shouldBe gjennomforingInput.navn
		}
	}

	@Test
	fun `nye aktiviteter uten oppfolgingsperioder som er opprettet for mindre enn en uke siden skal få ingeststatus RETRY`() {
		val (gjennomforingId, deltakerId) = setup()
		val oppfolgingsperioder = listOf<Oppfolgingsperiode>()
		val fnr = "54321"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = oppfolgingsperioder
		val opprettetTidspunkt = LocalDateTime.now().minusDays(7).plusSeconds(20)
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)
		val result = deltakerExecutor.execute(NyDeltakerCommand(deltakerInput))
		result.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }
	}

	@Test
	fun `nye aktiviteter uten oppfolgingsperioder som er endret for mer enn en uke siden skal få ingeststatus IGNORED`() {
		val (gjennomforingId, deltakerId) = setup()
		val oppfolgingsperioder = listOf<Oppfolgingsperiode>()
		val fnr = "54321"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = oppfolgingsperioder
		val opprettetTidspunkt = LocalDateTime.now().minusDays(7).minusSeconds(20)
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)
		val result = deltakerExecutor.execute(NyDeltakerCommand(deltakerInput))
		result.arenaData { it.ingestStatus shouldBe IngestStatus.IGNORED }
	}


	@Test
	fun `skal kunne sette oppfolgingsperiode med slack på 1 uke på retry`() {
		val (gjennomforingId, deltakerId) = setup()

		// Finnes ingen oppfolgingsperioder
		val fnr = "414141"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = emptyList()

		val opprettetTidspunkt = LocalDateTime.now().minusWeeks(1).plusSeconds(20) // litt mindre enn en uke gammel aktivitet

		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }

		// Pågående oppfolgingsperiode blir satt
		val gjeldendePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(1),
			sluttTidspunkt = null
		)
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(gjeldendePeriode)

		processMessages()
		processMessages()

		val arenaDataDbo = arenaDataRepository.get(ArenaTableName.DELTAKER, Operation.CREATED, result.position)
		arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED // aktivitet skal være sendt
	}

	@Test
	fun `hvis neste oppdatering i ny periode skal vi opprette nytt aktivitetskort med endretTidspunkt lik mod_dato`() {
		val (gjennomforingId, deltakerId, _) = setup()
		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(5),
			sluttTidspunkt = ZonedDateTime.now().minusDays(3)
		)
		val fnr = "515151"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)
		val opprettetTidspunkt = LocalDateTime.now().minusDays(4)
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		var aktivitetsId1: UUID? = null
		deltakerExecutor.execute(deltakerCommand)
			.expectHandled { handledResult ->
				handledResult.arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED
				handledResult.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
				handledResult.aktivitetskort {
					it.endretTidspunkt shouldBe opprettetTidspunkt.truncatedTo(ChronoUnit.SECONDS)
					aktivitetsId1 = it.id
				}
			}
		withClue("ArenaId-endepunkt skal returnere samme aktivitetsId som aktiviteten har") {
			hentTranslationMedRestClient(deltakerId) shouldBe aktivitetsId1
		}
		// Skal opprette ny aktivitet dersom oppdatering kommer på ny periode
		val nyperiode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(1),
			sluttTidspunkt = null
		)
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode, nyperiode)
		val endretTidspunkt = LocalDateTime.now()
		val oppdaterComand = OppdaterDeltakerCommand(deltakerInput, deltakerInput.copy(endretTidspunkt = endretTidspunkt)
			.copy(deltakerStatusKode = "AVSLAG"))
		var aktivitetsId2: UUID? = null
		deltakerExecutor.execute(oppdaterComand)
			.expectHandled { handledResult ->
				handledResult.arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED
				handledResult.headers.oppfolgingsperiode shouldBe nyperiode.uuid
				handledResult.aktivitetskort {
					it.etiketter shouldContain Etikett("Avslag", Sentiment.NEGATIVE, "AVSLAG")
					it.endretTidspunkt shouldBe endretTidspunkt.truncatedTo(ChronoUnit.SECONDS)
					aktivitetsId2 = it.id
				}
			}
		withClue("Skal opprette nytt kort, ikke gjenbruke gammel id") {
			aktivitetsId1 shouldNotBe aktivitetsId2
		}
		withClue("Skal gi ut ny id på arenaid endepunkt") {
			hentTranslationMedRestClient(deltakerId) shouldBe aktivitetsId2
		}
	}

	@Test
	fun `hvis neste oppdatering utenfor periode og aktivitet avsluttet - ignorer`() {
		val (gjennomforingId, deltakerId, _) = setup()
		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(5),
			sluttTidspunkt = ZonedDateTime.now().minusDays(3)
		)
		val fnr = "515151"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)
		val opprettetTidspunkt = LocalDateTime.now().minusDays(4)
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand)
			.expectHandled { handledResult ->
				handledResult.arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED
				handledResult.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
			}

		OppfolgingClientMock.oppfolgingsperioder[fnr] = emptyList()
		val oppdaterComand = OppdaterDeltakerCommand(deltakerInput, deltakerInput.copy(endretTidspunkt = LocalDateTime.now())
			.copy(deltakerStatusKode = "AVSLAG"))
		deltakerExecutor.execute(oppdaterComand)
			.arenaData {
				it.ingestStatus shouldBe IngestStatus.IGNORED
				it.note shouldContain "Avsluttet deltakelse og ingen oppfølgingsperiode"
			}

	}

	@Test
	fun `hvis neste nylige oppdatering utenfor periode men ikke aktivitet avsluttet - retry`() {
		val (gjennomforingId, deltakerId, _) = setup()
		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(5),
			sluttTidspunkt = ZonedDateTime.now().minusDays(3)
		)
		val fnr = "515151"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode)
		val opprettetTidspunkt = LocalDateTime.now().minusDays(4)
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand)
			.expectHandled { handledResult ->
				handledResult.arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED
				handledResult.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
			}

		OppfolgingClientMock.oppfolgingsperioder[fnr] = emptyList()
		val oppdaterComand = OppdaterDeltakerCommand(deltakerInput, deltakerInput.copy(endretTidspunkt = LocalDateTime.now())
			.copy(deltakerStatusKode = "GJENN"))
		deltakerExecutor.execute(oppdaterComand)
			.arenaData {
				it.ingestStatus shouldBe IngestStatus.RETRY
			}
	}

	@Test
	fun `skal lage id-mapping på tidligere ignorerte deltakelser men ikke publisere aktivitetskort før ikke-ignorert tilstand`() {
		val (gjennomforingId, deltakelseId, _) = setup(Tiltak.Administrasjonskode.INST)
		val deltakerInputIgnored = DeltakerInput(
			tiltakDeltakelseId = deltakelseId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "AKTUELL",
		)
		val deltakerCommandIgnored = NyDeltakerCommand(deltakerInputIgnored)
		deltakerExecutor.execute(deltakerCommandIgnored).expectHandledAndIngored { result -> result.arenaDataDbo.note shouldBe "foreløpig ignorert" }

		idMappingClient.hentMapping(TranslationQuery(deltakelseId.value, AktivitetKategori.TILTAKSAKTIVITET)) shouldNotBe null

		val deltakerInput = deltakerInputIgnored.copy(deltakerStatusKode = "GJENN")
		val deltakerCommand = OppdaterDeltakerCommand(deltakerInputIgnored, deltakerInput)
		deltakerExecutor.execute(deltakerCommand).expectHandled { result -> result.output.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
	}

	@Test
	fun `skal ikke ignorere ignorerbare statuser hvis en tidligere endring ikke er ignorert`() {
		val (gjennomforingId, deltakelseId, _) = setup(Tiltak.Administrasjonskode.INST)
		val deltakerInputIgnored = DeltakerInput(
			tiltakDeltakelseId = deltakelseId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "AKTUELL",
		)
		val deltakerCommandIgnored = NyDeltakerCommand(deltakerInputIgnored)
		deltakerExecutor.execute(deltakerCommandIgnored)
			.expectHandledAndIngored { result -> result.arenaDataDbo.note shouldBe "foreløpig ignorert"}


		idMappingClient.hentMapping(TranslationQuery(deltakelseId.value, AktivitetKategori.TILTAKSAKTIVITET)) shouldNotBe null

		val deltakerInputIkkeIgnorert = deltakerInputIgnored.copy(deltakerStatusKode = "GJENN")
		val deltakerCommandIkkeIgnorert = OppdaterDeltakerCommand(deltakerInputIgnored, deltakerInputIkkeIgnorert)
		deltakerExecutor.execute(deltakerCommandIkkeIgnorert)
			.expectHandled { result ->
				result.output.aktivitetskort.aktivitetStatus shouldBe AktivitetStatus.GJENNOMFORES }


		val nyDeltakerInputIgnorertStatus = deltakerInputIkkeIgnorert.copy(deltakerStatusKode = "AKTUELL")
		val nyDeltakerCommandIgnorertStatus = OppdaterDeltakerCommand(deltakerInputIkkeIgnorert, nyDeltakerInputIgnorertStatus)
		val aktivitetResultNyIgnorertStatus = deltakerExecutor.execute(nyDeltakerCommandIgnorertStatus)
			.expectHandled { result ->
				result.output.aktivitetskort.aktivitetStatus shouldBe AktivitetStatus.PLANLAGT
			}
	}

	@Test
	fun `skal ignorere ignorerbare statuser hvis alle tidligere endringer er ignorert`() {
		val (gjennomforingId, deltakelseId, _) = setup(Tiltak.Administrasjonskode.INST)
		val deltakerInputIgnored = DeltakerInput(
			tiltakDeltakelseId = deltakelseId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "AKTUELL",
		)
		val deltakerCommandIgnored = NyDeltakerCommand(deltakerInputIgnored)
		deltakerExecutor.execute(deltakerCommandIgnored)
			.expectHandledAndIngored { result -> result.arenaDataDbo.note shouldBe "foreløpig ignorert" }

		idMappingClient.hentMapping(TranslationQuery(deltakelseId.value, AktivitetKategori.TILTAKSAKTIVITET)) shouldNotBe null

		val deltakerInputFremdelesIgnorert = deltakerInputIgnored.copy(datoTil = LocalDate.now())
		val deltakerCommandFremdelesIgnorert = OppdaterDeltakerCommand(deltakerInputIgnored, deltakerInputFremdelesIgnorert)
		deltakerExecutor.execute(deltakerCommandFremdelesIgnorert)
			.expectHandledAndIngored { result -> result.arenaDataDbo.note shouldBe "foreløpig ignorert" }

	}

	@Test
	@Disabled ("Kun relevant ved full last av historiske aktiviteter. Logikken kræsjer med ny funksjonalitet for å støtte oppdatering av aktivitet i aktiv oppfølgingsperiode.")
	fun `hvis retry av gamle deltakelser på gamle perioder, skal gamle aktiviteter oppdateres`() {
		val (gjennomforingId, deltakerId, _) = setup()
		val foerstePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(7),
			sluttTidspunkt = ZonedDateTime.now().minusDays(5)
		)

		val gjeldendePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startTidspunkt = ZonedDateTime.now().minusDays(3),
			sluttTidspunkt = null
		)
		val fnr = "515151"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(foerstePeriode, gjeldendePeriode)
		val opprettetTidspunkt = LocalDateTime.now().minusDays(6)
		val forsteDeltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			personId = 123L
		)
		val andreDeltakerInput = forsteDeltakerInput.copy(endretTidspunkt = LocalDateTime.now(), datoTil = LocalDate.now())
		val deltakerCommand = NyDeltakerCommand(forsteDeltakerInput)
		var foersteAktivitetsId:UUID? = null
		deltakerExecutor.execute(deltakerCommand)
			.expectHandled { handledResult ->
				handledResult.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
				handledResult.aktivitetskort { foersteAktivitetsId = it.id }
			}

		foersteAktivitetsId shouldNotBe null

		val oppdaterCommand = OppdaterDeltakerCommand(forsteDeltakerInput, andreDeltakerInput)
		var andreAktivitetsId:UUID? = null
		deltakerExecutor.execute(oppdaterCommand)
			.expectHandled {
				data -> data.headers.oppfolgingsperiode shouldBe gjeldendePeriode.uuid
				data.aktivitetskort { andreAktivitetsId = it.id }
			}

		andreAktivitetsId shouldNotBe null
		withClue("Ny periode skal gi ny aktivitetsId") {
			andreAktivitetsId shouldNotBe foersteAktivitetsId
		}

		val forsteDeltakelseSattTilRetry = NyDeltakerCommand(forsteDeltakerInput)
		deltakerExecutor.execute(forsteDeltakelseSattTilRetry)
			.expectHandled {
				data -> data.headers.oppfolgingsperiode shouldBe foerstePeriode.uuid
				data.aktivitetskort { it.id shouldBe foersteAktivitetsId }
			}
	}

	private fun <T> any(type: Class<T>): T = Mockito.any<T>(type)

	@Test
	fun `skal ikke opprettet aktivitetId (i mappingtabell) men ingeststatus oppdatereshvis sending av kafkamelding feiler`() {
		doThrow(IllegalStateException("LOL")).`when`(kafkaProducerService)
			.sendTilAktivitetskortTopic(this.any(UUID::class.java), any(KafkaMessageDto::class.java), any(AktivitetskortHeaders::class.java))
		val (gjennomforingId, deltakelseId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakelseId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(1),
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand).arenaData { arenaData ->
				arenaData.ingestStatus shouldBe IngestStatus.RETRY
				arenaData.note shouldBe "LOL"
		}
		aktivitetRepository.getCurrentAktivitetsId(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET) shouldBe null
	}

	@Test
	fun `skal opprette mapping selvom aktivitet ikke finnes enda`() {
		val (gjennomforingId, deltakerId) = setup()
		val generertId = idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET))
			.second
		generertId shouldNotBe null
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(1),
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand).expectHandled { arenaData ->
			withClue("Id utlevert fra arenaid endepunkt skal være lik id til aktivitetskort som opprettes senere") {
				arenaData.output.aktivitetskort.id shouldBe generertId
			}
		}
		idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET))
			.second shouldBe generertId
	}

	@Test
	fun `skal ha riktig mapping selv om translationcontroller og deltakerprocessor kjoerer samtidig`() {
		val (gjennomforingId, deltakerId) = setup()
		val generertId = idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET))
			.second
		generertId shouldNotBe null
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(1),
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		var generertId1: UUID? = null
		var generertId2: UUID? = null
		runBlocking {
			async(Dispatchers.IO) {// NB Ikke bruk default-dispatcher for async. Den håndterer ikke blokkerende kall
				deltakerExecutor.execute(deltakerCommand).expectHandled { arenaData ->
					generertId1 = arenaData.output.aktivitetskort.id
				}
			}
			async(Dispatchers.IO) {
				var delayTime = Random.nextLong(50, 200)
				delay(delayTime)
				generertId2 = idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET)).second
			}
		}
		generertId1 shouldNotBe null
		generertId1 shouldBe generertId2

	}

	@Test
	fun `Skal sette aktivitet med planlegger-status til avbrutt-status når vi mottar melding med status DELETED`() {
		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(1),
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "TILBUD"
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand).expectHandled { arenaData ->
			arenaData.output.aktivitetskort.aktivitetStatus shouldBe AktivitetStatus.PLANLAGT
		}

		val slettetDeltakerCommand = SletteDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(slettetDeltakerCommand).expectHandled { arenaData ->
			arenaData.arenaDataDbo.operation shouldBe Operation.DELETED
			arenaData.output.aktivitetskort.aktivitetStatus shouldBe AktivitetStatus.AVBRUTT
		}
	}

	@Test
	fun `Skal ignorere (handled men ingen aktivitetskort) slettemelding hvis aktivitet allerede er i ferdig-status`() {
		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(1),
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "FULLF"
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand).expectHandled { arenaData ->
			arenaData.output.aktivitetskort.aktivitetStatus shouldBe AktivitetStatus.FULLFORT
		}
		val slettetDeltakerCommand = SletteDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(slettetDeltakerCommand).expectHandledAndIngored {
			result -> result.arenaDataDbo.note shouldBe "ignorert slettemelding"
		}
	}

	@Test
	fun `Skal ignorere (handled men ingen aktivitetskort) deltakelser opprettet utenfor arena (de har en eksternId)`() {
		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			endretAv = Ident(ident = "SIG123"),
			eksternId = "asdasas"
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand).arenaData {
			it.ingestStatus shouldBe IngestStatus.IGNORED
			it.note shouldContain "eksternId: asdasas"
		}
	}

	@Test
	fun `Skal ikke lage aktivitetskort hvis eneste melding på deltakelse er slettemelding`() {
		// Dette burde aldri skje
		val (gjennomforingId, deltakerId) = setup()
		val deltakerInput = DeltakerInput(
			tiltakDeltakelseId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			endretAv = Ident(ident = "SIG123"),
			deltakerStatusKode = "FULLF"
		)
		val slettetDeltakerCommand = SletteDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(slettetDeltakerCommand).expectHandledAndIngored {
			result -> result.arenaDataDbo.note shouldBe "ignorert slettemelding"
		}
	}

	private val idMappingClient: IdMappingClient by lazy {
		val token = issueAzureAdM2MToken()
		IdMappingClient(port!!) { token }
	}

	private fun hentTranslationMedRestClient(deltakerId: DeltakelseId): UUID? {
		return idMappingClient.hentMapping(TranslationQuery(deltakerId.value, AktivitetKategori.TILTAKSAKTIVITET))
			.let { (response, result) ->
				response.isSuccessful shouldBe true
				result
			}
	}

	private fun Aktivitetskort.isSame(
		deltakerInput: DeltakerInput,
		tiltak: TiltakDbo,
		gjennomforingInput: GjennomforingInput,
		aktivitetsStatus: AktivitetStatus = AktivitetStatus.GJENNOMFORES
	) {
		personIdent shouldBe "12345"
		tittel shouldBe (gjennomforingInput.navn ?: "Ukjent navn")
		aktivitetStatus shouldBe aktivitetsStatus
		etiketter.size shouldBe 0
		startDato shouldBe deltakerInput.datoFra
		sluttDato shouldBe deltakerInput.datoTil
		beskrivelse shouldBe null
		detaljer[0].verdi shouldBe "virksomhetnavn"
		detaljer[1].verdi shouldBe "${deltakerInput.prosentDeltid}%"
		detaljer[2].verdi shouldBe deltakerInput.antallDagerPerUke.toString()
		endretAv shouldBe deltakerInput.endretAv
		tiltak.kode shouldBe gjennomforingInput.tiltakKode
	}
}

