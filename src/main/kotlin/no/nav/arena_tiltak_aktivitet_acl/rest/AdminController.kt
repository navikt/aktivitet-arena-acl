package no.nav.arena_tiltak_aktivitet_acl.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import no.nav.arena_tiltak_aktivitet_acl.auth.Issuer
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingRespository
import no.nav.security.token.support.core.api.Protected
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

@RestController
@Protected
@Tag(name = "AdminController", description = "Admin API for poao-admin")
@RequestMapping("/api/admin")
class AdminController(
	private val arenaDataRepository: ArenaDataRepository,
	private val deltakerAktivitetMappingRepository: DeltakerAktivitetMappingRespository,
) {

	@ProtectedWithClaims(issuer = Issuer.AZURE_AD, claimMap = ["scp=admin"])
	@Operation(summary = "Hent deltaker_aktivitet_mapping for funksjonellId eller oppfolgingsperiodeId")
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "Mapping returnert"),
	])
	@GetMapping(value = ["/deltaker/mapping"], produces = ["application/json"])
	fun hentDeltakerAktivitetMapping(
		@Parameter(description = "Funksjonell id", required = false, `in` = ParameterIn.QUERY)
		@RequestParam(required = false) funksjonellId: UUID?,
		@Parameter(description = "Oppfolgingsperiode id", required = false, `in` = ParameterIn.QUERY)
		@RequestParam(required = false) oppfolgingsperiodeId: UUID?,
	): List<AdminDeltakerAktivitetMappingDto> {
		val hasFunksjonellId = funksjonellId != null
		val hasOppfolgingsperiodeId = oppfolgingsperiodeId != null
		if (hasFunksjonellId == hasOppfolgingsperiodeId) {
			throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Send exactly one of funksjonellId or oppfolgingsperiodeId")
		}

		val result = when {
			hasFunksjonellId -> deltakerAktivitetMappingRepository.getAllByFunksjonellId(funksjonellId)
			else -> deltakerAktivitetMappingRepository.getAllByOppfolgingsperiodeId(oppfolgingsperiodeId!!)
		}
		return result.map { it.toAdminDeltakerAktivitetMappingDto() }
	}

	@ProtectedWithClaims(issuer = Issuer.AZURE_AD, claimMap = ["scp=admin"])
	@Operation(summary = "Hent arena_data for arenaId")
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "Arena data returnert"),
	])
	@GetMapping(value = ["/arena/{arenaId}/data"], produces = ["application/json"])
	fun hentArenaData(
		@Parameter(description = "ArenaId / deltakelseId")
		@PathVariable arenaId: String,
	): List<AdminArenaDataDto> {
		return arenaDataRepository.getAllByArenaIdOrderedByOperationPos(arenaId).map { it.toAdminArenaDataDto() }
	}
}
