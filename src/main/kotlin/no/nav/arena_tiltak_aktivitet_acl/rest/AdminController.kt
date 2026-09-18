package no.nav.arena_tiltak_aktivitet_acl.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import no.nav.arena_tiltak_aktivitet_acl.auth.Issuer
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.repositories.DeltakerAktivitetMappingRespository
import no.nav.security.token.support.core.api.Protected
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Protected
@Tag(name = "AdminController", description = "Admin API for poao-admin")
@RequestMapping("/api/admin")
class AdminController(
	private val arenaDataRepository: ArenaDataRepository,
	private val deltakerAktivitetMappingRepository: DeltakerAktivitetMappingRespository,
) {

	@ProtectedWithClaims(issuer = Issuer.AZURE_AD, claimMap = ["scp=admin"])
	@Operation(summary = "Hent deltaker_aktivitet_mapping for deltaker_id")
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "Mapping returnert"),
	])
	@GetMapping(value = ["/deltaker/{deltakerId}/mapping"], produces = ["application/json"])
	fun hentDeltakerAktivitetMapping(
		@Parameter(description = "Deltaker id")
		@PathVariable deltakerId: Long,
	): List<AdminDeltakerAktivitetMappingDto> {
		return deltakerAktivitetMappingRepository.getAllByDeltakelseId(DeltakelseId(deltakerId))
			.map { it.toAdminDeltakerAktivitetMappingDto() }
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
