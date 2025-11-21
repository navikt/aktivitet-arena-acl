package no.nav.arena_tiltak_aktivitet_acl.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.date.shouldHaveHour
import io.kotest.matchers.date.shouldHaveMinute
import io.kotest.matchers.date.shouldHaveSameDayAs
import io.kotest.matchers.date.shouldHaveSameMonthAs
import io.kotest.matchers.date.shouldHaveSameYearAs
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDateTime

class DeltakerAktivitetMappingRepositoryTest : FunSpec( {
	val datasource = SingletonPostgresContainer.getDataSource()
	lateinit var repository: GjennomforingRepository
	val now = LocalDateTime.now()
	beforeEach {
		repository = DeltakerAktivitetMappingRespository(NamedParameterJdbcTemplate( datasource))
	}

	infix fun LocalDateTime.shouldBeCloseTo(other: LocalDateTime) {
		this shouldHaveSameYearAs other
		this shouldHaveSameMonthAs other
		this shouldHaveSameDayAs other
		this shouldHaveHour other.hour
		this shouldHaveMinute other.minute
	}
})
