package no.nav.arena_tiltak_aktivitet_acl.services

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.AvsluttetOppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.clients.oppfolging.Oppfolgingsperiode
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.repositories.AdvisoryLockRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetDbo
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingDbo
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingRespository
import no.nav.arena_tiltak_aktivitet_acl.repositories.EksisterendeForelopigId
import no.nav.arena_tiltak_aktivitet_acl.repositories.FantIdITranslationTabell
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortId
import no.nav.arena_tiltak_aktivitet_acl.repositories.ForelopigAktivitetskortIdRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.NyForelopigId
import org.junit.Test
import java.time.ZonedDateTime
import java.util.UUID

class AktivitetskortIdServiceTest {

	val aktivitetRepository = mockk<AktivitetRepository>().also {
		every { it.closeClosedPerioder(any(), any(), any()) } just runs
	}
	val forelopigIdRepository = mockk<ForelopigAktivitetskortIdRepository>().also {
		every { it.deleteDeltakelseId(any()) } returns 1
	}
	val advisoryLockRepository = mockk<AdvisoryLockRepository>().also {
		every { it.safeDeltakelse(any()) } returns AdvisoryLockRepository.SafeDeltakelse(DeltakelseId(123))
	}
	val deltakerAktivitetMappingRepository = mockk<DeltakerAktivitetMappingRespository>().also {
		every { it.insert(any()) } returns 1
		every { it.markerOppfølgingsperiodeSomAvsluttet(any(), any()) } just runs
	}

	val aktivitetskortIdService = AktivitetskortIdService(
		aktivitetRepository,
		forelopigIdRepository,
		advisoryLockRepository,
		deltakerAktivitetMappingRepository
	)

	fun mockAktivitetRepository(arenaId: ArenaId, aktivitetsKortId: UUID? = null) {
		every { aktivitetRepository.getCurrentAktivitetsId(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns aktivitetsKortId
		if (aktivitetsKortId != null) {
			every { aktivitetRepository.getAktivitet(aktivitetsKortId) } returns mockk<AktivitetDbo>()
		}
	}

	fun gittDeltakelseHarIngenAktivitetskortId(arenaId: ArenaId) {
		mockAktivitetRepository(arenaId, null)
		every { deltakerAktivitetMappingRepository.getCurrentDeltakerAktivitetMapping(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns null
		var forelopigAktivitetId: UUID? = null
		every { forelopigIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori) } answers {
			if (forelopigAktivitetId == null) {
				forelopigAktivitetId = UUID.randomUUID()
				NyForelopigId(forelopigAktivitetId)
			} else {
				EksisterendeForelopigId(forelopigAktivitetId)
			}
		}
	}

	fun gittDeltakelseHarAktivitetMenIngenDeltakerMapping(arenaId: ArenaId, aktivitetsKort: AktivitetDbo) {
		mockAktivitetRepository(arenaId, aktivitetsKort.id)
		every { deltakerAktivitetMappingRepository.getCurrentDeltakerAktivitetMapping(arenaId.deltakelseId, arenaId.aktivitetKategori) }returns null andThen
			DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = aktivitetsKort.id,
			oppfolgingsPeriodeId = aktivitetsKort.oppfolgingsperiodeUUID,
			oppfolgingsPeriodeSluttTidspunkt = aktivitetsKort.oppfolgingsSluttTidspunkt
		)
		every { forelopigIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns FantIdITranslationTabell(aktivitetsKort.id)
		every { aktivitetRepository.getAktivitet(aktivitetsKort.id) } returns aktivitetsKort
	}

	fun gittDeltakelseHarAktivitetskortId(arenaId: ArenaId, aktivitetsKortId: UUID, oppfolgingsperiodeId: UUID, oppfolgingsperiodeSluttTidspunkt: ZonedDateTime? = null) {
		mockAktivitetRepository(arenaId, aktivitetsKortId)
		every { deltakerAktivitetMappingRepository.getCurrentDeltakerAktivitetMapping(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = aktivitetsKortId,
			oppfolgingsPeriodeId = oppfolgingsperiodeId,
			oppfolgingsPeriodeSluttTidspunkt = oppfolgingsperiodeSluttTidspunkt
		)
	}

	fun gittDeltakelseHarForeløpigId(arenaId: ArenaId, aktivitetsKortId: ForelopigAktivitetskortId) {
		mockAktivitetRepository(arenaId, null)
		every { forelopigIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns aktivitetsKortId
		every { deltakerAktivitetMappingRepository.getCurrentDeltakerAktivitetMapping(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns null
	}

	fun gittIdFinnesBareITranslationTabell(arenaId: ArenaId, aktivitetId: FantIdITranslationTabell) {
		mockAktivitetRepository(arenaId, null)
		every { forelopigIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns aktivitetId
		every { deltakerAktivitetMappingRepository.getCurrentDeltakerAktivitetMapping(arenaId.deltakelseId, arenaId.aktivitetKategori) } returns null
	}

	@Test
	fun `Skal gi ut samme foreløpige aktivitetskortId i påfølgende kall`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		gittDeltakelseHarIngenAktivitetskortId(arenaId)

		val aktivitetskortIdForste = aktivitetskortIdService.getOrCreate(arenaId, UkjentPersonIngenPerioder(arenaId.deltakelseId))
		val aktivitetskortIdAndre = aktivitetskortIdService.getOrCreate(arenaId, UkjentPersonIngenPerioder(arenaId.deltakelseId))

		withClue("Første kall til getOrCreate skal returnere en Forelopig") {
			(aktivitetskortIdForste is AktivitetskortIdService.Forelopig) shouldBe true
		}
		withClue("Andre kall til getOrCreate skal returnere en Forelopig") {
			(aktivitetskortIdAndre is AktivitetskortIdService.Forelopig) shouldBe true
		}
		withClue("Skal gi samme foreløpige id i påfølgende kall") {
			(aktivitetskortIdForste as AktivitetskortIdService.Forelopig).forelopigAktivitetskortId.id shouldBe (aktivitetskortIdAndre as AktivitetskortIdService.Forelopig).forelopigAktivitetskortId.id
		}
	}

	@Test
	fun `Ukjent person skal gi ut foreløpig id`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		gittDeltakelseHarIngenAktivitetskortId(arenaId)

		val aktivitetsKortId = aktivitetskortIdService.getOrCreate(arenaId, UkjentPersonIngenPerioder(arenaId.deltakelseId))

		withClue("Skal være en Forelopig id") {
			(aktivitetsKortId is AktivitetskortIdService.Forelopig) shouldBe true
		}
	}

	@Test
	fun `Manglende oppfolgingsperioder skal gi ut foreløpig id`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		gittDeltakelseHarIngenAktivitetskortId(arenaId)

		val aktivitetskortIdForste = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(emptyList()))

		withClue("Skal være en Forelopig id") {
			(aktivitetskortIdForste is AktivitetskortIdService.Forelopig) shouldBe true
		}
	}

	@Test
	fun `Skal gi ut samme id på getOrCreate hvis den allerede finnes`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val aktivitetskortId = UUID.randomUUID()
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		gittDeltakelseHarAktivitetskortId(arenaId,aktivitetskortId, oppfolgingsperiode.uuid)

		val aktivitetskortIdForste = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(listOf(oppfolgingsperiode)))
		withClue("Skal gi ut eksisteredne aktivitetskortId hvis den finnes") {
			(aktivitetskortIdForste as AktivitetskortIdService.Funnet).aktivitetskortId shouldBe aktivitetskortId
		}
	}

	@Test
	fun `Skal opprette id hvis det finnes passende-periode og den ikke allerede er opprettet (er foreløpig)`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarForeløpigId(arenaId, EksisterendeForelopigId(aktivitetskortId))

		val aktivitetskortIdForste = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(listOf(oppfolgingsperiode)))
		withClue("Når det finnes aktivitet getOrCreate skal returnere en Created") {
			(aktivitetskortIdForste as AktivitetskortIdService.Opprettet).aktivitetskortId shouldBe aktivitetskortId
		}
		verify { deltakerAktivitetMappingRepository.insert(DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = aktivitetskortId,
			oppfolgingsPeriodeId = oppfolgingsperiode.uuid,
			oppfolgingsPeriodeSluttTidspunkt = oppfolgingsperiode.sluttTidspunkt
		)) }
		verify { forelopigIdRepository.deleteDeltakelseId(arenaId) }
	}

	@Test
	fun `Skal gi ut ny id (splitt) hvis deltakelse er i ny oppfolgingsperiode - BrukNyestePeriode`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val gammelPeriode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now().minusDays(2), ZonedDateTime.now().minusDays(1))
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarAktivitetskortId(arenaId,  aktivitetskortId, gammelPeriode.uuid, null)

		val nyAktivitetskortId = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(listOf(gammelPeriode, oppfolgingsperiode)))

		withClue("Ny periode skal gi ny id") {
			(nyAktivitetskortId as AktivitetskortIdService.Opprettet).aktivitetskortId shouldNotBe aktivitetskortId
		}
		verify { deltakerAktivitetMappingRepository.insert(DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = (nyAktivitetskortId as AktivitetskortIdService.Opprettet).aktivitetskortId,
			oppfolgingsPeriodeId = oppfolgingsperiode.uuid,
			oppfolgingsPeriodeSluttTidspunkt = oppfolgingsperiode.sluttTidspunkt
		)) }
		verify { aktivitetRepository.closeClosedPerioder(arenaId.deltakelseId, arenaId.aktivitetKategori, any()) }
		verify { deltakerAktivitetMappingRepository.markerOppfølgingsperiodeSomAvsluttet(gammelPeriode.uuid, gammelPeriode.sluttTidspunkt!!) }
	}

	@Test
	fun `Skal gi ut ny id (splitt) hvis deltakelse er i ny oppfolgingsperiode - PeriodeMatch`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val gammelPeriode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now().minusDays(2), ZonedDateTime.now().minusDays(1))
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarAktivitetskortId(arenaId,  aktivitetskortId, gammelPeriode.uuid, null)

		val nyAktivitetsKortId = aktivitetskortIdService.getOrCreate(arenaId, FerdigMatchetPeriode(oppfolgingsperiode, listOf(gammelPeriode, oppfolgingsperiode)))
		withClue("Skal gi ny id for ny periode") {
			(nyAktivitetsKortId as AktivitetskortIdService.Opprettet).aktivitetskortId shouldNotBe aktivitetskortId
		}
		verify { deltakerAktivitetMappingRepository.insert(DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = (nyAktivitetsKortId as AktivitetskortIdService.Opprettet).aktivitetskortId,
			oppfolgingsPeriodeId = oppfolgingsperiode.uuid,
			oppfolgingsPeriodeSluttTidspunkt = oppfolgingsperiode.sluttTidspunkt
		)) }
		verify { aktivitetRepository.closeClosedPerioder(arenaId.deltakelseId, arenaId.aktivitetKategori, any()) }
	}

	@Test
	fun `Skal gi IKKE ut ny id hvis deltakelse er i gammel oppfolgingsperiode - PeriodeMatch`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val gammelPeriode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now().minusDays(2), ZonedDateTime.now().minusDays(1))
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarAktivitetskortId(arenaId,  aktivitetskortId, gammelPeriode.uuid, null)

		val nyAktivitetsKortId = aktivitetskortIdService.getOrCreate(arenaId, FerdigMatchetPeriode(gammelPeriode, listOf(gammelPeriode, oppfolgingsperiode)))
		withClue("Skal ikke gi ny id for gammel periode") {
			(nyAktivitetsKortId as AktivitetskortIdService.Funnet).aktivitetskortId shouldBe aktivitetskortId
		}
		verify { aktivitetRepository.closeClosedPerioder(arenaId.deltakelseId, arenaId.aktivitetKategori, listOf(
			AvsluttetOppfolgingsperiode(gammelPeriode.uuid, gammelPeriode.startTidspunkt, gammelPeriode.sluttTidspunkt!!)))
		}
	}

	@Test
	fun `Skal sett avsluttet og gi ut gammel ID hvis ikke endret periode, men den er avsluttet - PeriodeMatch`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val gammelPeriode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now().minusDays(2), ZonedDateTime.now().minusDays(1))
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarAktivitetskortId(arenaId,  aktivitetskortId, gammelPeriode.uuid, null)

		val nyAktivitetsKortId = aktivitetskortIdService.getOrCreate(arenaId, FerdigMatchetPeriode(gammelPeriode, listOf(gammelPeriode)))
		withClue("Skal ikke gi ny id for gammel periode") {
			(nyAktivitetsKortId as AktivitetskortIdService.Funnet).aktivitetskortId shouldBe aktivitetskortId
		}
		verify { aktivitetRepository.closeClosedPerioder(arenaId.deltakelseId, arenaId.aktivitetKategori, listOf(
			AvsluttetOppfolgingsperiode(gammelPeriode.uuid, gammelPeriode.startTidspunkt, gammelPeriode.sluttTidspunkt!!)))
		}
	}

	@Test
	fun `Skal gi ut eksisterende id hvis den finnes men ikke klarte finne oppfølging til deltakelse - UkjentPersonIngenPerioder`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val gammelPeriode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now().minusDays(2), ZonedDateTime.now().minusDays(1))
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarAktivitetskortId(arenaId,  aktivitetskortId, gammelPeriode.uuid, null)

		val nyAktivitetsKortId = aktivitetskortIdService.getOrCreate(arenaId, UkjentPersonIngenPerioder(arenaId.deltakelseId))
		withClue("Skal gi eksisterende id når ingen perioder er tilgjengelig") {
			(nyAktivitetsKortId as AktivitetskortIdService.Funnet).aktivitetskortId shouldBe aktivitetskortId
		}
	}

	@Test
	fun `Skal gi ut eksisterende id hvis den finnes men ikke klarte finne oppfølging til deltakelse - BrukNyestePeriode med tom liste (ingen oppfølging)`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val gammelPeriode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now().minusDays(2), ZonedDateTime.now().minusDays(1))
		val aktivitetskortId = UUID.randomUUID()
		gittDeltakelseHarAktivitetskortId(arenaId,  aktivitetskortId, gammelPeriode.uuid, null)

		val nyAktivitetsKortId = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(emptyList()))
		withClue("Skal gi eksisterende id når ingen perioder er tilgjengelig") {
			(nyAktivitetsKortId as AktivitetskortIdService.Funnet).aktivitetskortId shouldBe aktivitetskortId
		}
	}

	@Test
	fun `Skal gi ut eksisterende id hvis den finnes i translation tabell - periode finnes`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		val translationId = FantIdITranslationTabell(UUID.randomUUID())
		gittIdFinnesBareITranslationTabell(arenaId, translationId)

		val aktivitetskortId = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(listOf(oppfolgingsperiode)))

		withClue("TranslationId skal være Created") {
			(aktivitetskortId is AktivitetskortIdService.Opprettet) shouldBe true
			(aktivitetskortId as AktivitetskortIdService.Opprettet).aktivitetskortId shouldBe translationId.id
		}
		verify { deltakerAktivitetMappingRepository.insert(DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = (aktivitetskortId as AktivitetskortIdService.Opprettet).aktivitetskortId,
			oppfolgingsPeriodeId = oppfolgingsperiode.uuid,
			oppfolgingsPeriodeSluttTidspunkt = oppfolgingsperiode.sluttTidspunkt
		)) }
		verify { forelopigIdRepository.deleteDeltakelseId(arenaId) }
	}

	@Test
	fun `Hvis arenaId finnes i translation-tabell, og perioder ikke er kjent skal translationId brukes som aktivitetsId og insertes i foreløpig id`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val translationId = FantIdITranslationTabell(UUID.randomUUID())
		gittIdFinnesBareITranslationTabell(arenaId, translationId)

		val aktivitetskortId = aktivitetskortIdService.getOrCreate(arenaId, UkjentPersonIngenPerioder(arenaId.deltakelseId))

		withClue("TranslationId skal være Created") {
			(aktivitetskortId is AktivitetskortIdService.Forelopig) shouldBe true
			(aktivitetskortId as AktivitetskortIdService.Forelopig).forelopigAktivitetskortId.id shouldBe translationId.id
		}
		verify { forelopigIdRepository.getOrCreate(arenaId.deltakelseId, arenaId.aktivitetKategori) }
	}

	@Test
	fun `Skal gi ut eksisterende id hvis den finnes i translation tabell - også når aktivitet finnes i aktivitet tabell - periode finnes`() {
		val arenaId = ArenaId(DeltakelseId(12345), AktivitetKategori.TILTAKSAKTIVITET)
		val oppfolgingsperiode = Oppfolgingsperiode(UUID.randomUUID(), ZonedDateTime.now(), null)
		val aktivitetskortId = UUID.randomUUID()
		val translationId = FantIdITranslationTabell(aktivitetskortId)
		val personIdent = "10037698709"
		val aktivitetsKort = AktivitetDbo(
			id = aktivitetskortId,
			arenaId = "ARENATA${arenaId.deltakelseId.value}",
			kategori = arenaId.aktivitetKategori,
			personIdent = personIdent,
			oppfolgingsperiodeUUID = oppfolgingsperiode.uuid,
			oppfolgingsSluttTidspunkt = oppfolgingsperiode.sluttTidspunkt,
			tiltakKode = "VASV",
			data = "{}")

		gittIdFinnesBareITranslationTabell(arenaId, translationId)

		gittDeltakelseHarAktivitetMenIngenDeltakerMapping(arenaId, aktivitetsKort)

		val aktivitetskortIdResult = aktivitetskortIdService.getOrCreate(arenaId, BrukNyestePeriode(listOf(oppfolgingsperiode)))

		withClue("TranslationId skal være Created") {
			(aktivitetskortIdResult is AktivitetskortIdService.Funnet) shouldBe true
			(aktivitetskortIdResult as AktivitetskortIdService.Funnet).aktivitetskortId shouldBe translationId.id
		}
		verify { deltakerAktivitetMappingRepository.insert(DeltakerAktivitetMappingDbo(
			deltakelseId = arenaId.deltakelseId.value,
			aktivitetKategori = arenaId.aktivitetKategori.name,
			aktivitetId = (aktivitetskortIdResult as AktivitetskortIdService.Funnet).aktivitetskortId,
			oppfolgingsPeriodeId = oppfolgingsperiode.uuid,
			oppfolgingsPeriodeSluttTidspunkt = oppfolgingsperiode.sluttTidspunkt
		)) }
	}
}
