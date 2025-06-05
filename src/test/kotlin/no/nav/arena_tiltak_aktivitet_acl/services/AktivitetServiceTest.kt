package no.nav.arena_tiltak_aktivitet_acl.services

import io.kotest.common.runBlocking
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.historiserteDeltakerFix.arenaYearfirstFormat
import no.nav.arena_tiltak_aktivitet_acl.integration.IntegrationTestBase
import no.nav.arena_tiltak_aktivitet_acl.repositories.AdvisoryLockRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetDbo
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingRespository
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortIdRepository
import no.nav.arena_tiltak_aktivitet_acl.utils.asBackwardsFormattedLocalDateTime
import no.nav.arena_tiltak_aktivitet_acl.utils.asLocalDateTime
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AktivitetServiceTest : IntegrationTestBase() {
	val log = LoggerFactory.getLogger(javaClass)

	@Autowired
	lateinit var advisoryLockRepository: AdvisoryLockRepository

	@Autowired
	lateinit var aktivitetRepository: AktivitetRepository

	@Autowired
	lateinit var forelopigAktivitetskortIdRepository: ForelopigAktivitetskortIdRepository

	@Autowired
	lateinit var deltakerAktivitetMappingRespository: DeltakerAktivitetMappingRespository

	@Autowired
	lateinit var transactionTemplate: TransactionTemplate

	fun slowAktivitetRepository(): AktivitetRepository {
		val mock = mockk<AktivitetRepository>()
		val isSlow = AtomicBoolean(true)

		every { mock.upsert(any()) } answers {
			// Make sure first call is slow
			val isFirst = isSlow.getAndSet(false)
			val aktivitet = firstArg<AktivitetDbo>()
			log.info("isFirst $isFirst arenaId: ${aktivitet.arenaId} ${aktivitet}")
			if (isFirst) {
				runBlocking { delay(100) }
				aktivitetRepository.upsert(aktivitet)
			} else {
				aktivitetRepository.upsert(aktivitet)
			}
		}
		return mock
	}

	val aktivitetskort = Aktivitetskort(
		id = UUID.randomUUID(),
		personIdent = "01234567890",
		tittel = "Tittel",
		aktivitetStatus = AktivitetStatus.GJENNOMFORES,
		etiketter = emptyList(),
		startDato = null,
		sluttDato = null,
		beskrivelse = null,
		endretAv = Ident("IdentType", "ident"),
		endretTidspunkt = LocalDateTime.now(),
		avtaltMedNav = true,
		detaljer = emptyList()
	)

	fun headers(deltakelseId: DeltakelseId): AktivitetskortHeaders {
		return AktivitetskortHeaders(
			"${AktivitetKategori.TILTAKSAKTIVITET.prefix}${deltakelseId.value}",
			"ASDAS",
			UUID.randomUUID(),
			null
		)
	}

	fun aktivitetskort(): Aktivitetskort {
		return aktivitetskort
			.copy(id = UUID.randomUUID())
	}

	fun firstSlowAktivitetsService() = AktivitetService(
		slowAktivitetRepository(),
		forelopigAktivitetskortIdRepository,
		advisoryLockRepository,
		deltakerAktivitetMappingRespository,
	)

	@Test
	fun `skal blokkere prossessring på samme deltakelse-id`() {
		val firstSlowAktivitetsService = firstSlowAktivitetsService()
		val deltakelseId = DeltakelseId(1123)
		val aktivitetskort = aktivitetskort()
		val headers = headers(deltakelseId)

		val startOrder = mutableListOf<Long>()
		val excutionOrder = mutableListOf<Long>()
		kotlinx.coroutines.runBlocking {
			val first = async(Dispatchers.IO) {
				transactionTemplate.executeWithoutResult {
					startOrder.add(1)
					firstSlowAktivitetsService.upsert(
						aktivitetskort,
						headers,
						deltakelseId,
					)
					excutionOrder.add(1)
				}
			}
			val second = async(Dispatchers.IO) {
				delay(100)
				transactionTemplate.executeWithoutResult {
					startOrder.add(2)
					firstSlowAktivitetsService.upsert(
						aktivitetskort,
						headers.copy(oppfolgingsSluttDato = ZonedDateTime.now()),
						deltakelseId,
					)
					excutionOrder.add(2)
				}
			}
			listOf(first, second).awaitAll()
		}
		startOrder shouldBe listOf(1, 2)
		excutionOrder shouldBe listOf(1, 2)
	}

	@Test
	fun `skal ikke blokke andre deltakelse-id-er`() {
		val firstSlowAktivitetsService = firstSlowAktivitetsService()
		val deltakelseId = DeltakelseId(2123)
		val deltakelseId2 = DeltakelseId(2321)

		val excutionOrder = mutableListOf<Long>()
		kotlinx.coroutines.runBlocking {
			val first = async(Dispatchers.IO) {
				transactionTemplate.executeWithoutResult {
					firstSlowAktivitetsService.upsert(
						aktivitetskort(),
						headers(deltakelseId),
						deltakelseId,
					)
					excutionOrder.add(deltakelseId.value)
				}
			}
			val second = async(Dispatchers.IO) {
				delay(10)
				transactionTemplate.executeWithoutResult {
					firstSlowAktivitetsService.upsert(
						aktivitetskort(),
						headers(deltakelseId2),
						deltakelseId2,
					)
					excutionOrder.add(deltakelseId2.value)
				}
			}
			listOf(first, second).awaitAll()
		}
		excutionOrder shouldBe listOf(deltakelseId2.value, deltakelseId.value)
	}

	@Test
	fun `test advisory locking`() {
		val startOrder = Collections.synchronizedList(mutableListOf<Int>())
		val finishOrder = Collections.synchronizedList(mutableListOf<Int>())

		val lockId = DeltakelseId(3131)
		kotlinx.coroutines.runBlocking {
			val first = async(Dispatchers.IO) {
				startOrder.add(1)
				transactionTemplate.executeWithoutResult {
					advisoryLockRepository.lockDeltakelse(lockId)
					log.info("Lock acquired first")
					log.info("waiting in first")
					Thread.sleep(100)
					log.info("Releasing first lock")
				}
				finishOrder.add(1)
			}
			val second = async(Dispatchers.IO) {
				delay(20)
				startOrder.add(2)
				transactionTemplate.executeWithoutResult {
					advisoryLockRepository.lockDeltakelse(lockId)
					log.info("Lock acquired second")
				}
				finishOrder.add(2)
			}
			listOf(first, second).awaitAll()
		}
		log.info("Lock automatically released on transaction commit or rollback")
		startOrder shouldContainInOrder listOf(1, 2)
		finishOrder shouldContainInOrder listOf(1, 2)

	}

	@Test
	fun `Test date formatter` () {
		val testDato = LocalDateTime.of(2024, 1,31,13,21,31)
		testDato.format(arenaYearfirstFormat) shouldBe "2024-01-31 13:21:31"
	}

	@Test
	fun `Test arenaYearfirstFormat formatter without time` () {
		"2023-11-10".asLocalDateTime().format(arenaYearfirstFormat) shouldBe "2023-11-10 00:00:00"
	}

	@Test
	fun `Test arenaYearfirstFormat formatter` () {
		"04.05.2023 13:06:23".asBackwardsFormattedLocalDateTime("dato").format(arenaYearfirstFormat) shouldBe "2023-05-04 13:06:23"
	}
}
