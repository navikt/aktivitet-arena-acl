package no.nav.arena_tiltak_aktivitet_acl.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import no.nav.arena_tiltak_aktivitet_acl.auth.AuthService
import no.nav.arena_tiltak_aktivitet_acl.auth.Issuer
import no.nav.arena_tiltak_aktivitet_acl.domain.dto.TranslationQuery
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetKategori
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.services.AktivitetService
import no.nav.arena_tiltak_aktivitet_acl.services.AktivitetskortIdService
import no.nav.arena_tiltak_aktivitet_acl.services.OppfolgingsperiodeService
import no.nav.security.token.support.core.api.Protected
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*


@RestController
@Protected
@Tag(name = "TranslationController", description = "API for mapping mellomg arenaid for ulike typer tiltak og funksjonell aktivitetsid i aktivitetsplan")
@RequestMapping("/api/translation")
class TranslationController(
	private val authService: AuthService,
	private val aktivitetskortIdService: AktivitetskortIdService,
	private val aktivitetService: AktivitetService,
	private val oppfolgingsperiodeService: OppfolgingsperiodeService,
) {

	@ProtectedWithClaims(issuer = Issuer.AZURE_AD)
	@Operation(summary = "Hent aktivitetsid uuid for arena tiltaksdeltakelse", description = "Mapper mellom arenaid nøkkel i Arena for ulike typer tiltak og funksjonell aktivitetsid i aktivitetsplan")
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "Funksjonell aktivitetsId for arenaid returnert ok "),
		ApiResponse(responseCode = "404", description = "Fant ingen funksjonell aktivitetsid for oppgitt arenaid og aktivitetskategori.")
		])
	@PostMapping(value=["/arenaid"], produces=["application/json"], consumes =["application/json"])
	fun finnAktivitetsIdForArenaId(
		@Parameter(description = "Request object", schema = Schema(implementation = TranslationQuery::class, required = true))
		@RequestBody query: TranslationQuery
	): UUID {
		authService.validerErM2MToken()
		val deltakelseId = DeltakelseId(query.arenaId)
		val aktivitetKategori = query.aktivitetKategori

		val oppfolgingsperioder = finnPersonIdent(deltakelseId, query.aktivitetKategori)
			?.let { personIdent -> oppfolgingsperiodeService.hentAlleOppfolgingsperioder(personIdent) }
			?.let { AktivitetskortIdService.BrukNyestePeriode(it) } ?: AktivitetskortIdService.UkjentPersonIngenPerioder(deltakelseId)

		return aktivitetskortIdService.getOrCreate(deltakelseId, aktivitetKategori,  oppfolgingsperioder)
			.let { when (it) {
				is AktivitetskortIdService.Gotten -> it.aktivitetskortId
				is AktivitetskortIdService.Created -> it.forelopigAktivitetskortId.id
			} }
	}

	fun finnPersonIdent(deltakelseId: DeltakelseId, aktivitetsKategori: AktivitetKategori): String? {
		return aktivitetService.getAllBy(deltakelseId, aktivitetsKategori)
			.firstOrNull()
			?.let { aktivitet -> return aktivitet.person_ident }
	}
}

