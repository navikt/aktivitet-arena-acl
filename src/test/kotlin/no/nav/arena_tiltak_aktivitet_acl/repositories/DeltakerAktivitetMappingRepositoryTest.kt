package no.nav.arena_tiltak_aktivitet_acl.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.arena_tiltak_aktivitet_acl.database.SingletonPostgresContainer
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.ZonedDateTime
import java.util.UUID
import java.util.UUID.randomUUID

class DeltakerAktivitetMappingRepositoryTest : FunSpec({

	val datasource = SingletonPostgresContainer.getDataSource()
	lateinit var deltakerAktivitetMappingRespository: DeltakerAktivitetMappingRespository
	lateinit var aktivitetRepository: AktivitetRepository

	beforeEach {
		deltakerAktivitetMappingRespository =
			DeltakerAktivitetMappingRespository(NamedParameterJdbcTemplate(datasource))
		aktivitetRepository = AktivitetRepository(NamedParameterJdbcTemplate(datasource))
	}

	test("Skal hente id for nyeste oppfølgingsperiode etter splitt") {

		val avsluttetAktivitet = createAktivitet(ZonedDateTime.now().minusDays(1))
		aktivitetRepository.upsert(avsluttetAktivitet)
		val åpenAktivitet = createAktivitet()
		aktivitetRepository.upsert(åpenAktivitet)

		val deltakerAktivitetMappingFørSplitt = deltakerAktivitetMapping(
			oppfølgingsperiodeId = avsluttetAktivitet.oppfolgingsperiodeUUID,
			avsluttetAktivitet.id,
			oppfølgingsperiodeSlutt = ZonedDateTime.now().minusDays(1)
		)
		deltakerAktivitetMappingRespository.insert(deltakerAktivitetMappingFørSplitt)
		val deltakerAktivitetSomSkalFørteTilSplitt = deltakerAktivitetMapping(
			oppfølgingsperiodeId = åpenAktivitet.oppfolgingsperiodeUUID,
			åpenAktivitet.id,
			oppfølgingsperiodeSlutt = null
		)
		deltakerAktivitetMappingRespository.insert(deltakerAktivitetSomSkalFørteTilSplitt)


		val deltakerMappingEtterSplitt = deltakerAktivitetMappingRespository.getCurrentDeltakerAktivitetMapping(
			DeltakelseId(deltakerAktivitetMappingFørSplitt.deltakelseId),
			AktivitetKategori.TILTAKSAKTIVITET
		)!!

		deltakerMappingEtterSplitt.oppfolgingsPeriodeId shouldBe deltakerAktivitetSomSkalFørteTilSplitt.oppfolgingsPeriodeId
	}
})

private fun createAktivitet(oppfølgingsperiodeSlutt: ZonedDateTime? = null): AktivitetDbo {
	return AktivitetDbo(
		id = randomUUID(),
		personIdent = "123123123",
		kategori = AktivitetKategori.TILTAKSAKTIVITET,
		data = "{}",
		arenaId = "ARENATA-222",
		tiltakKode = "MIDLONNTIL",
		oppfolgingsperiodeUUID = randomUUID(),
		oppfolgingsSluttTidspunkt = oppfølgingsperiodeSlutt,
	)
}

private fun deltakerAktivitetMapping(
	oppfølgingsperiodeId: UUID,
	aktivitetId: UUID,
	oppfølgingsperiodeSlutt: ZonedDateTime? = null
) =
	DeltakerAktivitetMappingDbo(
		deltakelseId = 123,
		aktivitetId = aktivitetId,
		aktivitetKategori = "TILTAKSAKTIVITET",
		oppfolgingsPeriodeId = oppfølgingsperiodeId,
		oppfolgingsPeriodeSluttTidspunkt = oppfølgingsperiodeSlutt,
	)
