package no.nav.arena_tiltak_aktivitet_acl.integration.commands.deltaker

import no.nav.arena_tiltak_aktivitet_acl.domain.db.ArenaDataDbo
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.Aktivitetskort
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.AktivitetskortHeaders
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.KafkaMessageDto
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.OperationPos
import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetMetaData
import org.junit.jupiter.api.fail

class HandledResult(
	position: OperationPos,
	arenaDataDbo: ArenaDataDbo,
	deltakerAktivitetMapping: Collection<AktivitetMetaData>,
	val output: KafkaMessageDto,
	val headers: AktivitetskortHeaders,
): AktivitetResult(position, arenaDataDbo, deltakerAktivitetMapping) {
	fun output(check: (data: KafkaMessageDto) -> Unit): AktivitetResult {
		check(output)
		return this
	}
	fun aktivitetskort(check: (aktivitetskort: Aktivitetskort) -> Unit): AktivitetResult {
		check(output.aktivitetskort)
		return this
	}
}

class HandledAndIgnored(
	position: OperationPos,
	arenaDataDbo: ArenaDataDbo,
	deltakerAktivitetMapping: Collection<AktivitetMetaData>,
): AktivitetResult(position, arenaDataDbo, deltakerAktivitetMapping)

open class AktivitetResult(
	val position: OperationPos,
	val arenaDataDbo: ArenaDataDbo,
	val deltakerAktivitetMapping: Collection<AktivitetMetaData>
) {
	fun expectHandled(check: (data: HandledResult) -> Unit) {
		if (this !is HandledResult) fail("Expected arena message to have ingest status HANDLED but was ${this.arenaDataDbo.ingestStatus}")
		check(this)
	}

	fun expectHandledAndIngored(check: (data: HandledAndIgnored) -> Unit) {
		if (this !is HandledAndIgnored) fail("Expected arena message to have ingest status HandledAndIgnored and to be ignored but was ${this.arenaDataDbo.ingestStatus}")
		check(this)
	}
	fun arenaData(check: (data: ArenaDataDbo) -> Unit): AktivitetResult {
		check.invoke(arenaDataDbo)
		return this
	}
}
