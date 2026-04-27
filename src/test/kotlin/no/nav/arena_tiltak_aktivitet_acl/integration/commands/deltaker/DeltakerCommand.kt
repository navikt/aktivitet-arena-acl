package no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker

import tools.jackson.databind.JsonNode
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.ArenaDeltakelse
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.DeltakelseId
import no.nav.arena_tiltak_aktivitet_acl.integration.commands.Command
import java.time.LocalDateTime

abstract class DeltakerCommand(val tiltakDeltakerId: DeltakelseId) : Command(tiltakDeltakerId.value.toString()) {

	abstract fun toArenaKafkaMessageDto(pos: Long, opTs: LocalDateTime = LocalDateTime.now()): ArenaKafkaMessageDto

	fun createPayload(input: DeltakerInput): JsonNode {
		val data = ArenaDeltakelse(
			TILTAKDELTAKER_ID = input.tiltakDeltakelseId.value,
			PERSON_ID = input.personId,
			TILTAKGJENNOMFORING_ID = input.tiltakgjennomforingId,
			DELTAKERSTATUSKODE = input.deltakerStatusKode,
			DELTAKERTYPEKODE = GENERIC_STRING,
			AARSAKVERDIKODE_STATUS = GENERIC_STRING,
			OPPMOTETYPEKODE = GENERIC_STRING,
			PRIORITET = GENERIC_INT,
			BEGRUNNELSE_INNSOKT = GENERIC_STRING,
			BEGRUNNELSE_PRIORITERING = GENERIC_STRING,
			REG_DATO = dateFormatter.format(input.registrertDato),
			REG_USER = input.endretAv.ident,
			MOD_DATO = dateFormatter.format(input.endretTidspunkt),
			MOD_USER = input.endretAv.ident,
			DATO_SVARFRIST = GENERIC_STRING,
			DATO_FRA = input.datoFra
				?.let { dateFormatter.format(it.atStartOfDay()) },
			DATO_TIL = input.datoTil
				?.let { dateFormatter.format(it.atStartOfDay()) },
			BEGRUNNELSE_STATUS = GENERIC_STRING,
			PROSENT_DELTID = input.prosentDeltid,
			BRUKERID_STATUSENDRING = GENERIC_STRING,
			DATO_STATUSENDRING = dateFormatter.format(input.datoStatusEndring.atStartOfDay()),
			AKTIVITET_ID = GENERIC_LONG,
			BRUKERID_ENDRING_PRIORITERING = GENERIC_STRING,
			DATO_ENDRING_PRIORITERING = GENERIC_STRING,
			DOKUMENTKODE_SISTE_BREV = GENERIC_STRING,
			STATUS_INNSOK_PAKKE = GENERIC_STRING,
			STATUS_OPPTAK_PAKKE = GENERIC_STRING,
			OPPLYSNINGER_INNSOK = GENERIC_STRING,
			PARTISJON = GENERIC_INT,
			BEGRUNNELSE_BESTILLING = input.innsokBegrunnelse,
			ANTALL_DAGER_PR_UKE = input.antallDagerPerUke,
			EKSTERN_ID = input.eksternId
		)
		return objectMapper.readTree(objectMapper.writeValueAsString(data))
	}

}
