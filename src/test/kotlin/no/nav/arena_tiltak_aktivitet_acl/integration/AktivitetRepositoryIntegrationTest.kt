package no.nav.arena_tiltak_aktivitet_acl.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetDbo
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import org.intellij.lang.annotations.Language
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.ZonedDateTime
import java.util.*

class AktivitetRepositoryIntegrationTest: StringSpec({
	val dataSource = SingletonPostgresContainer.getDataSource()
	val template = NamedParameterJdbcTemplate(dataSource)
	val repository = AktivitetRepository(template)
	val periode = Oppfolgingsperiode(
		UUID.randomUUID(),
		ZonedDateTime.now(),
		null
	)

	"upsert once should save data" {
		val id = UUID.randomUUID()
		val aktivitet = AktivitetDbo(
			id = id,
			personIdent = "123123123",
			kategori = AktivitetKategori.TILTAKSAKTIVITET,
			data = "{}",
			arenaId = "ARENATA-111",
			tiltakKode = "MIDLONNTIL",
			oppfolgingsperiodeUUID = periode.uuid,
		)
		repository.upsertPeriode(periode)
		repository.upsert(aktivitet)
		repository.getAktivitet(id) shouldBe aktivitet
	}

	"upsert should not throw on duplicate key" {
		val periode = Oppfolgingsperiode(
			UUID.randomUUID(),
			ZonedDateTime.now(),
			null
		)
		val aktivitet = AktivitetDbo(
			id = UUID.randomUUID(),
			personIdent = "123123123",
			kategori = AktivitetKategori.TILTAKSAKTIVITET,
			data = "{}",
			arenaId = "ARENATA-112",
			tiltakKode = "MIDLONNTIL",
			oppfolgingsperiodeUUID = periode.uuid,
		)
		repository.upsertPeriode(periode)
		repository.upsert(aktivitet)
		repository.upsert(aktivitet)
	}

	"upsert should throw on multiple open perioder" {
		val periode = Oppfolgingsperiode(
			UUID.randomUUID(),
			ZonedDateTime.now(),
			null
		)
		val aktivitet = AktivitetDbo(
			id = UUID.randomUUID(),
			personIdent = "123123123",
			kategori = AktivitetKategori.TILTAKSAKTIVITET,
			data = "{}",
			arenaId = "ARENATA-114",
			tiltakKode = "MIDLONNTIL",
			oppfolgingsperiodeUUID = periode.uuid)
		val nyAktivitetskortSammeDeltakelse = aktivitet.copy(id = UUID.randomUUID())
		repository.upsertPeriode(periode)
		repository.upsert(aktivitet)
		shouldThrow<DuplicateKeyException> {
			repository.upsert(nyAktivitetskortSammeDeltakelse)
		}
	}

	"should throw on multiple same periode + arenaId" {
		val periode = Oppfolgingsperiode(
			UUID.randomUUID(),
			ZonedDateTime.now().minusSeconds(10),
			ZonedDateTime.now()
		)
		val aktivitet = AktivitetDbo(
			id = UUID.randomUUID(),
			personIdent = "123123123",
			kategori = AktivitetKategori.TILTAKSAKTIVITET,
			data = "{}",
			arenaId = "ARENATA-114",
			tiltakKode = "MIDLONNTIL",
			oppfolgingsperiodeUUID = periode.uuid)
		val nyAktivitetskortSammeDeltakelse = aktivitet.copy(id = UUID.randomUUID())
		repository.upsertPeriode(periode)
		repository.upsert(aktivitet)
		shouldThrow<DuplicateKeyException> {
			repository.upsert(nyAktivitetskortSammeDeltakelse)
		}
	}

	"upsert should not throw on same arenaId" {
		val periode = Oppfolgingsperiode(
			UUID.randomUUID(),
			ZonedDateTime.now().minusDays(10),
			ZonedDateTime.now().minusDays(2)
		)
		val annenPeriode = Oppfolgingsperiode(
			UUID.randomUUID(),
			ZonedDateTime.now().minusSeconds(10),
			null
		)
		val aktivitet = AktivitetDbo(
			id = UUID.randomUUID(),
			personIdent = "123123123",
			kategori = AktivitetKategori.TILTAKSAKTIVITET,
			data = "{}",
			arenaId = "ARENATA-116",
			tiltakKode = "MIDLONNTIL",
			oppfolgingsperiodeUUID = periode.uuid)
		val nyAktivitetskortForskjelligPeriode = aktivitet.copy(
			id = UUID.randomUUID(),
			oppfolgingsperiodeUUID = annenPeriode.uuid)
		repository.upsertPeriode(periode)
		repository.upsertPeriode(annenPeriode)
		repository.upsert(aktivitet)
		repository.upsert(nyAktivitetskortForskjelligPeriode)
	}

	"upsert should update data on duplicate key" {
		val periode = Oppfolgingsperiode(
			UUID.randomUUID(),
			ZonedDateTime.now().minusSeconds(10),
			null
		)
		val id = UUID.randomUUID()
		val aktivitet = AktivitetDbo(
			id = id,
			personIdent = "123123123",
			kategori = AktivitetKategori.TILTAKSAKTIVITET,
			data = "{}",
			arenaId = "ARENATA-113",
			tiltakKode = "MIDLONNTIL",
			oppfolgingsperiodeUUID = periode.uuid
		)
		@Language("JSON")
		val updatedData = """{"data": "newData"}""".trimIndent()
		val updatedAktivtet = aktivitet.copy(data = updatedData)
		repository.upsertPeriode(periode)
		repository.upsert(aktivitet)
		repository.upsert(updatedAktivtet)
		repository.getAktivitet(id)?.data shouldBe updatedData
	}
})
