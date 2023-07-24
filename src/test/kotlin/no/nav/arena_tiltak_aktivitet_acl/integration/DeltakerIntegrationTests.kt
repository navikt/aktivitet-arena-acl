package no.nav.arena_tiltak_aktivitet_acl.integration

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.*
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.AktivitetResult
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.DeltakerInput
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.NyDeltakerCommand
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker.OppdaterDeltakerCommand
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.gjennomforing.GjennomforingInput
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.gjennomforing.NyGjennomforingCommand
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.tiltak.NyttTiltakCommand
import no.nav.arena_tiltak_aktivitet_acl.mocks.OppfolgingClientMock
import no.nav.arena_tiltak_aktivitet_acl.mocks.OrdsClientMock
import no.nav.arena_tiltak_aktivitet_acl.processors.DeltakerProcessor
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.TiltakDbo
import no.nav.arena_tiltak_aktivitet_acl.repositories.TranslationRepository
import no.nav.arena_tiltak_aktivitet_acl.services.KafkaProducerService.Companion.TILTAK_ID_PREFIX
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*

class DeltakerIntegrationTests : IntegrationTestBase() {

	@Autowired
	lateinit var aktivitetRepository: AktivitetRepository

	@Autowired
	lateinit var arenaDataRepository: ArenaDataRepository

	@Autowired
	lateinit var translationRepository: TranslationRepository

	data class TestData (
		val gjennomforingId: Long = Random().nextLong(),
		val deltakerId: Long = Random().nextLong(),
		val gjennomforingInput: GjennomforingInput = GjennomforingInput(gjennomforingId = gjennomforingId),
		val tiltak: TiltakDbo = TiltakDbo(UUID.randomUUID(), "TILT", "Tiltak navn", "IND")
	)

	private fun setup(): TestData {
		val tiltak = tiltakExecutor.execute(NyttTiltakCommand())
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			.tiltak

		val testData = TestData(tiltak = tiltak)

		gjennomforingExecutor.execute(NyGjennomforingCommand(testData.gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }

		return testData
	}

	@Test
	fun `ingest deltaker`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			.result { _, translation, output -> translation!!.aktivitetId shouldBe output!!.aktivitetskort.id }
			.outgoingPayload { it.isSame(deltakerInput, tiltak) }
			.aktivitet {
				it.tiltakKode shouldBe gjennomforingInput.tiltakKode
				it.arenaId shouldBe TILTAK_ID_PREFIX + deltakerInput.tiltakDeltakerId
				it.oppfolgingsperiodeUUID shouldNotBe null
				it.oppfolgingsSluttTidspunkt shouldBe null
			}
	}

	@Test
	fun `skal være historisk hvis opprettet i avsluttet periode`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()

		val opprettetTidspunkt = LocalDateTime.now().minusWeeks(6)

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)
		val fnr = result.aktivitet?.personIdent

		val oppfolgingPerioder  = OppfolgingClientMock.oppfolgingsperioder.get(fnr)!!
		val gammelPeriode = oppfolgingPerioder.first()

		result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			.result { _, translation, output -> translation!!.aktivitetId shouldBe output!!.aktivitetskort.id }
			.outgoingPayload { it.isSame(deltakerInput, tiltak) }
			.aktivitet {
				it.tiltakKode shouldBe gjennomforingInput.tiltakKode
				it.arenaId shouldBe TILTAK_ID_PREFIX + deltakerInput.tiltakDeltakerId
				it.oppfolgingsperiodeUUID shouldNotBe null
				it.oppfolgingsperiodeUUID shouldBe gammelPeriode.uuid
				it.oppfolgingsSluttTidspunkt shouldNotBe null
				it.oppfolgingsSluttTidspunkt shouldBe gammelPeriode.sluttDato
			}

	}


	@Test
	fun `historisk skal være null hvis ingen oppfølgingsperioder`() {
		val (gjennomforingId, deltakerId, gjennomforingInput) = setup()
		val opprettetTidspunkt = LocalDateTime.now().minusMonths(6)
		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)
		result.arenaDataDbo.ingestStatus shouldBe IngestStatus.RETRY
		result.aktivitet shouldBe null
	}

	@Test
	fun `process deltakelse in the correct order`() {
		val (gjennomforingId, deltakerId, gjennomforingInput) = TestData()

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
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

		val aktivitetId = translationRepository.get(deltakerId, AktivitetKategori.TILTAKSAKTIVITET)?.aktivitetId
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
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			deltakerStatusKode = "INFOMOETE", // Aktivitetstatus: Planlagt
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result: AktivitetResult = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }

		// Fail first message after 10 retries
		(1..10).forEach{ processMessages() }
		val resultDbo = arenaDataRepository.get(result.arenaDataDbo.arenaTableName, result.arenaDataDbo.operation, result.arenaDataDbo.operationPosition )
		resultDbo.ingestStatus shouldBe IngestStatus.FAILED

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
		processFailedMessages()

		val aktivitetId = translationRepository.get(deltakerId, AktivitetKategori.TILTAKSAKTIVITET)?.aktivitetId
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
	fun `ingest existing deltaker`() {
		val (gjennomforingId, deltakerId, gjennomforingInput, tiltak) = setup()

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoFra = LocalDate.now().minusDays(10),
			endretAv = Ident(ident = "SIG123"),
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		val deltakerInputUpdated = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			datoTil = LocalDate.now().plusDays(30),
			endretAv = Ident(ident = "SIG123"),
		)
		val updatedDeltakerCommand = NyDeltakerCommand(deltakerInputUpdated)
		val updatedResult = deltakerExecutor.execute(updatedDeltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			.result { _, translation, output -> translation!!.aktivitetId shouldBe output!!.aktivitetskort.id }
			.outgoingPayload { it.isSame(deltakerInput, tiltak) }

		updatedResult.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			.result { _, translation, output -> translation!!.aktivitetId shouldBe output!!.aktivitetskort.id }
			.outgoingPayload { it.isSame(deltakerInputUpdated, tiltak) }
	}

	@Test
	fun `ignore deltaker before aktivitetsplan launch`() {
		val (gjennomforingId, deltakerId) = setup()

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = DeltakerProcessor.AKTIVITETSPLAN_LANSERINGSDATO.minusDays(1)
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.IGNORED }
	}

	@Test
	fun `tittel should be set to tiltaksnavn when gjennomforing navn is null`() {
		val TILTAKSNAVN_OVERRIDE = "Tiltaksnavn override"

		val gjennomforingId: Long = Random().nextLong()
		val deltakerId: Long = Random().nextLong()
		val gjennomforingInput = GjennomforingInput(
			gjennomforingId = gjennomforingId,
			navn = null
		)

		val tiltak = tiltakExecutor.execute(NyttTiltakCommand(navn = TILTAKSNAVN_OVERRIDE))
			.let { result ->
				result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
				result.tiltak
			}

		gjennomforingExecutor.execute(NyGjennomforingCommand(gjennomforingInput))
			.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }


		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123")
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.HANDLED }
			.output { it.actionType shouldBe ActionType.UPSERT_AKTIVITETSKORT_V1 }
			.result { _, _, output -> output?.aktivitetskort?.tittel shouldBe TILTAKSNAVN_OVERRIDE }
			.outgoingPayload { it.isSame(deltakerInput, tiltak) }
	}

	@Test
	fun `nye aktiviteter uten oppfolgingsperioder skal retries til en oppfolgingsperiode starter`() {
		val (gjennomforingId, deltakerId, gjennomforingInput) = setup()

		val oppfolgingsperioder = listOf<Oppfolgingsperiode>()
		val fnr = "12345"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = oppfolgingsperioder

		val opprettetTidspunkt = LocalDateTime.now()

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			personId = 123L
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }

		val gjeldendePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startDato = ZonedDateTime.now().minusDays(1),
			sluttDato = null
		)
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(gjeldendePeriode)

		processMessages()

		val arenaDataDbo = arenaDataRepository.get(ArenaTableName.DELTAKER, Operation.CREATED, result.position)
		arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED // aktivitet skal være sendt
	}


	@Test
	fun `skal kunne sette oppfolgingsperiode med slack på 1 uke på retry`() {
		val (gjennomforingId, deltakerId, gjennomforingInput) = setup()

		// Finnes ingen oppfolgingsperioder
		val fnr = "12345"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = emptyList()

		val opprettetTidspunkt = LocalDateTime.now().minusWeeks(1) // over en uke gammel aktivitet

		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			personId = 123L
		)

		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		val result = deltakerExecutor.execute(deltakerCommand)

		result.arenaData { it.ingestStatus shouldBe IngestStatus.RETRY }

		// Pågående oppfolgingsperiode blir satt
		val gjeldendePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startDato = ZonedDateTime.now().minusDays(1),
			sluttDato = null
		)
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(gjeldendePeriode)

		processMessages()

		val arenaDataDbo = arenaDataRepository.get(ArenaTableName.DELTAKER, Operation.CREATED, result.position)
		arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED // aktivitet skal være sendt
	}

	@Test
	fun `skal bruke samme oppfølgingsperiode på neste oppdatering`() {
		val (gjennomforingId, deltakerId, _) = setup()
		val gjeldendePeriode = Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startDato = ZonedDateTime.now().minusDays(1),
			sluttDato = null
		)
		val fnr = "12345"
		OrdsClientMock.fnrHandlers[123L] = { fnr }
		OppfolgingClientMock.oppfolgingsperioder[fnr] = listOf(gjeldendePeriode)
		val opprettetTidspunkt = LocalDateTime.now()
		val deltakerInput = DeltakerInput(
			tiltakDeltakerId = deltakerId,
			tiltakgjennomforingId = gjennomforingId,
			innsokBegrunnelse = "innsøkbegrunnelse",
			endretAv = Ident(ident = "SIG123"),
			registrertDato = opprettetTidspunkt,
			personId = 123L
		)
		val deltakerCommand = NyDeltakerCommand(deltakerInput)
		deltakerExecutor.execute(deltakerCommand)
			.let {
				it.arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED
				it.aktivitet?.oppfolgingsperiodeUUID shouldBe gjeldendePeriode.uuid
			}
		// Skal ikke gjøre oppslag på periode men bruke eksiterende periode satt på aktiviteten
		OppfolgingClientMock.oppfolgingsperioder[fnr] = emptyList()
		val oppdaterComand = OppdaterDeltakerCommand(deltakerInput, deltakerInput
			.copy(deltakerStatusKode = "FULLF"))
		deltakerExecutor.execute(oppdaterComand)
			.let {
				it.arenaDataDbo.ingestStatus shouldBe IngestStatus.HANDLED
				it.aktivitet?.oppfolgingsperiodeUUID shouldBe gjeldendePeriode.uuid
			}
	}



	private fun Aktivitetskort.isSame(
		deltakerInput: DeltakerInput,
		tiltak: TiltakDbo
	) {
		personIdent shouldBe "12345"
		tittel shouldBe tiltak.navn
		aktivitetStatus shouldBe AktivitetStatus.GJENNOMFORES
		etiketter.size shouldBe 0
		startDato shouldBe deltakerInput.datoFra
		sluttDato shouldBe deltakerInput.datoTil
		beskrivelse shouldBe null
		detaljer[0].verdi shouldBe "virksomhetnavn"
		detaljer[1].verdi shouldBe "${deltakerInput.prosentDeltid}%"
		detaljer[2].verdi shouldBe deltakerInput.antallDagerPerUke.toString()
		endretAv shouldBe deltakerInput.endretAv
	}
}

