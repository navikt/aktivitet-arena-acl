package no.nav.arena_tiltak_aktivitet_acl.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.ZonedDateTime
import java.util.UUID

class DeltakerAktivitetMappingRepositoryTest : FunSpec({

	val datasource = SingletonPostgresContainer.getDataSource()
	lateinit var repository: DeltakerAktivitetMappingRespository

	beforeEach {
		repository = DeltakerAktivitetMappingRespository(NamedParameterJdbcTemplate(datasource))
	}

	test("Skal hente id for nyeste oppfølgingsperiode etter splitt") {
		val deltakerAktivitetMappingFørSplitt = deltakerAktivitetMapping(
			oppfølgingsperiodeId = UUID.randomUUID(),
			oppfølgingsperiodeSlutt = ZonedDateTime.now().minusDays(1)
		)
		repository.insert(deltakerAktivitetMappingFørSplitt)
		val deltakerAktivitetSomSkalFøreTilSplitt = deltakerAktivitetMapping(
			oppfølgingsperiodeId = UUID.randomUUID(),
			oppfølgingsperiodeSlutt = null
		)
		repository.insert(deltakerAktivitetSomSkalFøreTilSplitt)

		val deltakerMappingEtterSplitt = repository.getCurrentDeltakerAktivitetMapping(
			DeltakelseId(deltakerAktivitetMappingFørSplitt.deltakelseId),
			AktivitetKategori.TILTAKSAKTIVITET
		)!!

		deltakerMappingEtterSplitt.oppfolgingsPeriodeId shouldBe deltakerAktivitetSomSkalFøreTilSplitt.oppfolgingsPeriodeId
	}
})

private fun deltakerAktivitetMapping(oppfølgingsperiodeId: UUID, oppfølgingsperiodeSlutt: ZonedDateTime? = null) =
	DeltakerAktivitetMappingDbo(
		deltakelseId = 123,
		aktivitetId = UUID.randomUUID(),
		aktivitetKategori = "TILTAKSAKTIVITET",
		oppfolgingsPeriodeId = oppfølgingsperiodeId,
		oppfolgingsPeriodeSluttTidspunkt = oppfølgingsperiodeSlutt,
	)
