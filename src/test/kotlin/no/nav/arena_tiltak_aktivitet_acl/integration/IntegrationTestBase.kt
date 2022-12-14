package no.nav.arena_tiltak_aktivitet_acl.integration

import no.nav.arena_tiltak_aktivitet_acl.database.DatabaseTestUtils
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.integration.executors.DeltakerTestExecutor
import no.nav.arena_tiltak_aktivitet_acl.integration.executors.GjennomforingTestExecutor
import no.nav.arena_tiltak_aktivitet_acl.integration.executors.TiltakTestExecutor
import no.nav.arena_tiltak_aktivitet_acl.integration.kafka.KafkaAktivitetskortIntegrationConsumer
import no.nav.arena_tiltak_aktivitet_acl.integration.kafka.SingletonKafkaProvider
import no.nav.arena_tiltak_aktivitet_acl.kafka.KafkaProperties
import no.nav.arena_tiltak_aktivitet_acl.mocks.OrdsClientMock
import no.nav.arena_tiltak_aktivitet_acl.repositories.*
import no.nav.arena_tiltak_aktivitet_acl.services.RetryArenaMessageProcessorService
import no.nav.arena_tiltak_aktivitet_acl.services.TiltakService
import no.nav.common.kafka.producer.KafkaProducerClientImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

@SpringBootTest
@Import(IntegrationTestConfiguration::class)
@ActiveProfiles("integration")
@TestConfiguration("application-integration.properties")
abstract class IntegrationTestBase {

	@Autowired
	lateinit var dataSource: DataSource

	@Autowired
	private lateinit var retryArenaMessageProcessorService: RetryArenaMessageProcessorService

	@Autowired
	lateinit var tiltakService: TiltakService

	@Autowired
	lateinit var tiltakExecutor: TiltakTestExecutor

	@Autowired
	lateinit var gjennomforingExecutor: GjennomforingTestExecutor

	@Autowired
	lateinit var deltakerExecutor: DeltakerTestExecutor


	@BeforeEach
	fun beforeEach() {
		DatabaseTestUtils.cleanDatabase(dataSource)
	}

	@AfterEach
	fun cleanup() {
		tiltakService.invalidateTiltakByKodeCache()
		OrdsClientMock.fnrHandlers.clear()
		OrdsClientMock.virksomhetsHandler.clear()
	}

	fun processMessages() {
		retryArenaMessageProcessorService.processMessages()
	}

	fun processFailedMessages() {
		retryArenaMessageProcessorService.processFailedMessages()
	}
}

@Profile("integration")
@TestConfiguration
open class IntegrationTestConfiguration(
) {

	@Value("\${app.env.aktivitetskortTopic}")
	lateinit var consumerTopic: String

	@Bean
	open fun tiltakExecutor(
		kafkaProducer: KafkaProducerClientImpl<String, String>,
		arenaDataRepository: ArenaDataRepository,
		translationRepository: TranslationRepository,
		tiltakRepository: TiltakRepository
	): TiltakTestExecutor {
		return TiltakTestExecutor(kafkaProducer, arenaDataRepository, translationRepository, tiltakRepository)
	}

	@Bean
	open fun gjennomforingExecutor(
		kafkaProducer: KafkaProducerClientImpl<String, String>,
		arenaDataRepository: ArenaDataRepository,
		gjennomforingRepository: GjennomforingRepository,
		translationRepository: TranslationRepository
	): GjennomforingTestExecutor {
		return GjennomforingTestExecutor(kafkaProducer, arenaDataRepository, gjennomforingRepository, translationRepository)
	}

	@Bean
	open fun deltakerExecutor(
		kafkaProducer: KafkaProducerClientImpl<String, String>,
		arenaDataRepository: ArenaDataRepository,
		translationRepository: TranslationRepository
	): DeltakerTestExecutor {
		return DeltakerTestExecutor(kafkaProducer, arenaDataRepository, translationRepository)
	}

	@Bean
	open fun dataSource(): DataSource {
		return SingletonPostgresContainer.getDataSource()
	}

	@Bean
	open fun kafkaProperties(): KafkaProperties {
		return SingletonKafkaProvider.getKafkaProperties()
	}

	@Bean
	open fun kafkaAmtIntegrationConsumer(properties: KafkaProperties): KafkaAktivitetskortIntegrationConsumer {
		return KafkaAktivitetskortIntegrationConsumer(properties, consumerTopic)
	}
}
