package no.nav.arena_tiltak_aktivitet_acl.repositories

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.*

class AktivitetskortIdRepositoryTest : StringSpec({
	val datasource = SingletonPostgresContainer.getDataSource()
	lateinit var repository: ForelopigAktivitetskortIdRepository
	beforeEach {
		repository = ForelopigAktivitetskortIdRepository(NamedParameterJdbcTemplate( datasource))
	}
	"overrideId should override" {
		val deltakelseId = DeltakelseId()
		val overrideUUID = UUID.randomUUID()
		val result = repository.getOrCreate(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET, overrideUUID)
		result.id shouldBe overrideUUID
		repository.getOrCreate(deltakelseId, AktivitetKategori.TILTAKSAKTIVITET).id shouldBe overrideUUID
	}
})
