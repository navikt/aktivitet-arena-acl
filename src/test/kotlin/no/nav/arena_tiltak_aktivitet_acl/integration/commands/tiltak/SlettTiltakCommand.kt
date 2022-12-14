package no.nav.arena_tiltak_aktivitet_acl.integration.commands.tiltak

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Tiltak
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaOperation
import no.nav.arena_tiltak_aktivitet_acl.utils.ARENA_TILTAK_TABLE_NAME
import java.time.LocalDateTime

class SlettTiltakCommand(
	val kode: String = "INDOPPFAG",
	val navn: String,
	val administrasjonskode: Tiltak.Administrasjonskode = Tiltak.Administrasjonskode.IND
) : TiltakCommand() {

	override fun execute(
		position: String,
		executor: (wrapper: ArenaKafkaMessageDto, kode: String) -> TiltakResult
	): TiltakResult {
		val wrapper = ArenaKafkaMessageDto(
			table = ARENA_TILTAK_TABLE_NAME,
			opType = ArenaOperation.D.name,
			opTs = LocalDateTime.now().format(opTsFormatter),
			pos = position,
			before = createPayload(kode, navn, administrasjonskode.name),
			after = null
		)

		return executor.invoke(wrapper, kode)

	}
}
