package no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet

import no.nav.arena_tiltak_aktivitet_acl.repositories.AktivitetDbo
import no.nav.arena_tiltak_aktivitet_acl.utils.ObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

data class Ident(
	val identType: String = "ARENAIDENT",
	val ident: String
)

data class Attributt(
	val label: String,
	val verdi: String
)

enum class Sentiment {
	POSITIVE, // Fått tilbud, Klar for oppstart, Påbegynt, Påmeldt? (gode nyheter for brukeren)
	NEGATIVE, // Ikke fått jobben, Avbrutt, Annulert, Fått avslag (dårlige nyheter for brukeren)
	NEUTRAL, // Deltar, Vurderes?
}

data class Etikett(
	val tekst: String,
	val sentiment: Sentiment,
	val kode: String
)

data class Aktivitetskort(
	val id: UUID,
	val personIdent: String, // Må alltid være fnr/dnr
	val tittel: String,
	val aktivitetStatus: AktivitetStatus,
	val etiketter: List<Etikett>,
	val startDato: LocalDate?,
	val sluttDato: LocalDate?,
	val beskrivelse: String?, // alle, men annen oppførsel på tiltak(jobbklubb)
	val endretAv: Ident,
	val endretTidspunkt: LocalDateTime,
	val avtaltMedNav: Boolean,
	val detaljer: List<Attributt>
) {
	private val objectMapper = ObjectMapper.get()
	fun toDbo(headers: AktivitetskortHeaders, forelopigIgnorert: Boolean) = AktivitetDbo(
		id = id,
		personIdent = personIdent,
		kategori = AktivitetKategori.TILTAKSAKTIVITET,
		data = objectMapper.writeValueAsString(this),
		arenaId = headers.arenaId,
		tiltakKode = headers.tiltakKode,
		oppfolgingsperiodeUUID = headers.oppfolgingsperiode,
		forelopigIgnorert = forelopigIgnorert
	)

	fun toKafkaMessage() = KafkaMessageDto(
			messageId = UUID.randomUUID(),
			actionType = ActionType.UPSERT_AKTIVITETSKORT_V1,
			aktivitetskort = this,
			aktivitetskortType = AktivitetskortType.ARENA_TILTAK
		)
}

