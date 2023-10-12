package no.nav.arena_tiltak_aktivitet_acl.processors

import ArenaOrdsProxyClient
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.OppfolgingClient
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.database.DatabaseTestUtils
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.db.IngestStatus
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Operation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.exceptions.DependencyNotIngestedException
import no.nav.arena_tiltak_aktivitet_acl.exceptions.IgnoredException
import no.nav.arena_tiltak_aktivitet_acl.exceptions.OppfolgingsperiodeNotFoundException
import no.nav.arena_tiltak_aktivitet_acl.mocks.OppfolgingClientMock
import no.nav.arena_tiltak_aktivitet_acl.repositories.*
import no.nav.arena_tiltak_aktivitet_acl.services.*
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*

class DeltakerProcessorTest : FunSpec({

	val dataSource = SingletonPostgresContainer.getDataSource()

	val ordsClient = mock<ArenaOrdsProxyClient> {
		on { hentFnr(anyLong()) } doReturn "01010051234"
	}

	val defaultOppfolgingsperioder = listOf(
		Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startDato = ZonedDateTime.now().minusMonths(2),
			sluttDato = ZonedDateTime.now().minusMonths(1)
		),
		Oppfolgingsperiode(
			uuid = UUID.randomUUID(),
			startDato = ZonedDateTime.now().minusWeeks(2),
			sluttDato = null
		)
	)

	val kafkaProducerService = mock<KafkaProducerService>()

	lateinit var arenaDataRepository: ArenaDataRepository
	lateinit var idArenaIdTilAktivitetskortIdRepository: ArenaIdTilAktivitetskortIdRepository
	lateinit var personSporingRepository: PersonSporingRepository

	val nonIgnoredGjennomforingArenaId = 1L
	val ignoredGjennomforingArenaId = 2L

	beforeEach {
		val template = NamedParameterJdbcTemplate(dataSource)
		arenaDataRepository = ArenaDataRepository(template)
		idArenaIdTilAktivitetskortIdRepository = ArenaIdTilAktivitetskortIdRepository(template)
		personSporingRepository = PersonSporingRepository(template)

		DatabaseTestUtils.cleanAndInitDatabase(dataSource, "/deltaker-processor_test-data.sql")
	}

	fun createDeltakerProcessor(oppfolgingsperioder: List<Oppfolgingsperiode> = defaultOppfolgingsperioder): DeltakerProcessor {
		val template = NamedParameterJdbcTemplate(dataSource)

		val oppfolgingClient = mock<OppfolgingClient> {
			on { hentOppfolgingsperioder(anyString()) } doReturn oppfolgingsperioder
		}

		return DeltakerProcessor(
			arenaDataRepository = arenaDataRepository,
			arenaIdArenaIdTilAktivitetskortIdService = ArenaIdTilAktivitetskortIdService(idArenaIdTilAktivitetskortIdRepository),
			kafkaProducerService = kafkaProducerService,
			aktivitetService = AktivitetService(AktivitetRepository(template)),
			gjennomforingRepository = GjennomforingRepository(template),
			tiltakService = TiltakService(TiltakRepository(template)),
			oppfolgingsperiodeService = OppfolgingsperiodeService(oppfolgingClient),
			personsporingService = PersonsporingService(personSporingRepository, ordsClient),
			deltakerAktivitetMappingRepository = DeltakerAktivitetMappingRepository(template)
		)
	}

	fun getAndCheckArenaDataRepositoryEntry(
		operation: Operation,
		position: String,
		expectedStatus: IngestStatus = IngestStatus.HANDLED
	): ArenaDataDbo {
		val arenaDataRepositoryEntry = shouldNotThrowAny {
			arenaDataRepository.get(ArenaTableName.DELTAKER, operation, position)
		}

		arenaDataRepositoryEntry shouldNotBe null
		arenaDataRepositoryEntry.ingestStatus shouldBe expectedStatus

		when (arenaDataRepositoryEntry.operation) {
			Operation.CREATED -> {
				arenaDataRepositoryEntry.before shouldBe null
				arenaDataRepositoryEntry.after shouldNotBe null
			}
			Operation.MODIFIED -> {
				arenaDataRepositoryEntry.before shouldNotBe null
				arenaDataRepositoryEntry.after shouldNotBe null
			}
			Operation.DELETED -> {
				arenaDataRepositoryEntry.before shouldNotBe null
				arenaDataRepositoryEntry.after shouldBe null
			}
		}

		return arenaDataRepositoryEntry
	}

	test("DeltakerProcessor should get a translation on non-ignored Gjennomforing") {
		val newDeltaker = createArenaDeltakerKafkaMessage(
			tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
			deltakerArenaId = 1L
		)

		createDeltakerProcessor().handleArenaMessage(newDeltaker)


		getAndCheckArenaDataRepositoryEntry(operation = Operation.CREATED, (operationPos).toString())

		val translationEntry = idArenaIdTilAktivitetskortIdRepository.get(DeltakelseId(1), AktivitetKategori.TILTAKSAKTIVITET)

		translationEntry shouldNotBe null
	}

	test("Skal kaste ignored exception for ignorerte statuser") {
		val statuser = listOf("VENTELISTE", "AKTUELL", "JATAKK", "INFOMOETE")

		shouldThrowExactly<IgnoredException> {
			createDeltakerProcessor().handleArenaMessage(createArenaDeltakerKafkaMessage(
				tiltakGjennomforingArenaId = ignoredGjennomforingArenaId,
				deltakerArenaId = 1,
				deltakerStatusKode = "AKTUELL"
			))
		}

		statuser.forEachIndexed { idx, status ->

			createDeltakerProcessor().handleArenaMessage(createArenaDeltakerKafkaMessage(
				tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
				deltakerArenaId = idx.toLong() + 1,
				deltakerStatusKode = status
			))

			getAndCheckArenaDataRepositoryEntry(operation = Operation.CREATED, (operationPos).toString())
		}
	}

	test("Insert Deltaker with gjennomføring not processed should throw exception") {
		shouldThrowExactly<DependencyNotIngestedException> {
			createDeltakerProcessor().handleArenaMessage(
				createArenaDeltakerKafkaMessage(
					2348790L,
					1L
				)
			)
		}
	}

	test("Should process deleted deltaker") {
		shouldThrowExactly<IgnoredException> {
			createDeltakerProcessor().handleArenaMessage(
				createArenaDeltakerKafkaMessage(
					tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
					deltakerArenaId = 1L,
					operation = Operation.DELETED
				)
			)
		}
	}

	test("Skal opprette translation hvis regDato (opprettetTidspunkt) er innen en oppfølgingsperiode") {
		val opprettetTidspunkt = OppfolgingClientMock.defaultOppfolgingsperioder.last().startDato.toLocalDateTime().plusSeconds(10)
		val newDeltaker = createArenaDeltakerKafkaMessage(
			tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
			deltakerArenaId = 1L,
			registrertDato = opprettetTidspunkt)
		createDeltakerProcessor().handleArenaMessage(newDeltaker)
		getAndCheckArenaDataRepositoryEntry(operation = Operation.CREATED, (operationPos).toString())
		val translationEntry = idArenaIdTilAktivitetskortIdRepository.get(DeltakelseId(1), AktivitetKategori.TILTAKSAKTIVITET)
		translationEntry shouldNotBe null
	}

	test("Skal kaste OppfolgingsperiodeNotFoundException hvis ingen perioder ") {
		val oppfolgingsperioder = listOf<Oppfolgingsperiode>()
		val opprettetTidspunkt = LocalDateTime.now().minusDays(6)
		val newDeltaker = createArenaDeltakerKafkaMessage(
			tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
			deltakerArenaId = 1L,
			registrertDato = opprettetTidspunkt)
		shouldThrowExactly<OppfolgingsperiodeNotFoundException> {
			createDeltakerProcessor(oppfolgingsperioder).handleArenaMessage(newDeltaker)
		}
	}

	test("Skal kaste IgnoredException hvis ingen passende perioder og eldre enn 1 uke") {
		val oppfolgingsperioder = listOf(
			Oppfolgingsperiode(
				uuid = UUID.randomUUID(),
				startDato = ZonedDateTime.now().minusMonths(2),
				sluttDato = ZonedDateTime.now().minusMonths(1)),
			Oppfolgingsperiode(
				uuid = UUID.randomUUID(),
				startDato = ZonedDateTime.now().minusWeeks(2),
				sluttDato = null)
		)
		val opprettetTidspunkt = LocalDateTime.now().minusMonths(3)
		val newDeltaker = createArenaDeltakerKafkaMessage(
			tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
			deltakerArenaId = 1L,
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt)
		shouldThrowExactly<IgnoredException> {
			createDeltakerProcessor(oppfolgingsperioder).handleArenaMessage(newDeltaker)
		}
	}

	test("skal permanent ignorere deltakelser som er avsluttet hvis de ikke har noen passende oppfølgingsperioder") {
		val oppfolgingsperioder = listOf(
			Oppfolgingsperiode(
				uuid = UUID.randomUUID(),
				startDato = ZonedDateTime.now().minusMonths(2),
				sluttDato = ZonedDateTime.now().minusMonths(1)
			),
			Oppfolgingsperiode(
				uuid = UUID.randomUUID(),
				startDato = ZonedDateTime.now().minusWeeks(2),
				sluttDato = null
			)
		)

		val opprettetTidspunkt = LocalDateTime.now().minusWeeks(3)

		val newDeltaker = createArenaDeltakerKafkaMessage(
			tiltakGjennomforingArenaId = nonIgnoredGjennomforingArenaId,
			deltakerArenaId = 1L,
			registrertDato = opprettetTidspunkt,
			endretTidspunkt = opprettetTidspunkt,
			deltakerStatusKode = "FULLF"
		)

		shouldThrowExactly<IgnoredException> {
			createDeltakerProcessor(oppfolgingsperioder).handleArenaMessage(newDeltaker)
		}
	}
})

