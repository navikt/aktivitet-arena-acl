package no.nav.arena_tiltak_aktivitet_acl.integration.commands.tiltak

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Tiltak
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaKafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.ArenaOperation
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.padUntil20Characters
import no.nav.arena_tiltak_aktivitet_acl.utils.ArenaTableName
import java.time.LocalDateTime
import java.util.*

class NyttTiltakCommand(
	val kode: String = "INDOPPFAG",
	val navn: String = UUID.randomUUID().toString(),
	val administrasjonskode: Tiltak.Administrasjonskode = Tiltak.Administrasjonskode.IND,
) : TiltakCommand(kode) {
	override fun toArenaKafkaMessageDto(pos: Long) = ArenaKafkaMessageDto(
		table = ArenaTableName.TILTAK,
		opType = ArenaOperation.I.name,
		opTs = LocalDateTime.now().format(opTsFormatter),
		pos = pos.padUntil20Characters(),
		before = null,
		after = createPayload(kode, navn, administrasjonskode.name)
	)
}
