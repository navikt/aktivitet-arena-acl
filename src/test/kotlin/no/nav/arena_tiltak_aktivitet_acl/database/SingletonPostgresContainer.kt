package no.nav.arena_tiltak_aktivitet_acl.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import org.testcontainers.utility.DockerImageName
import java.util.*
import javax.sql.DataSource

object SingletonPostgresContainer {

	private val log = LoggerFactory.getLogger(javaClass)

	private const val postgresDockerImageName = "postgres:14.18-alpine" // same as gcp

	private var postgresContainer: PostgreSQLContainer<Nothing>? = null

	fun getDataSource(): DataSource {
		return createDataSource(getContainer())
	}

	private fun getContainer(): PostgreSQLContainer<Nothing> {
		if (postgresContainer == null) {
			log.info("Starting new postgres database...")

			val container = createContainer()
			postgresContainer = container

			container.start()

			log.info("Applying database migrations...")
			applyMigrations(createDataSource(container))

			setupShutdownHook()
		}

		return postgresContainer as PostgreSQLContainer<Nothing>
	}

	private fun applyMigrations(dataSource: DataSource) {
		val properties = Properties()
		properties["flyway.cleanDisabled"] = false
		properties["flyway.postgresql.transactional.lock"] = false
		val flyway: Flyway = Flyway.configure()
			.dataSource(dataSource)
			.configuration(properties)
			.table("flyway_schema_history")
			.connectRetries(10)
			.load()

		flyway.clean()
		flyway.migrate()
	}

	private fun createContainer(): PostgreSQLContainer<Nothing> {
		return PostgreSQLContainer<Nothing>(DockerImageName.parse(postgresDockerImageName))
			.waitingFor(HostPortWaitStrategy())
	}

	private fun createDataSource(container: PostgreSQLContainer<Nothing>): DataSource {
		val config = HikariConfig()

		config.jdbcUrl = container.jdbcUrl
		config.username = container.username
		config.password = container.password
		config.maximumPoolSize = 10
		config.minimumIdle = 1

		return HikariDataSource(config)
	}

	private fun setupShutdownHook() {
		Runtime.getRuntime().addShutdownHook(Thread {
			log.info("Shutting down postgres database...")
			postgresContainer?.stop()
		})
	}

}
