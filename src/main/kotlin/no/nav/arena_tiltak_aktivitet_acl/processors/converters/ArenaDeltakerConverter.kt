package no.nav.arena_tiltak_aktivitet_acl.processors.converters

import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet.*
import no.nav.arena_tiltak_aktivitet_acl.domain.kafka.arena.tiltak.TiltakDeltakelse
import java.util.*

object ArenaDeltakerConverter {
	const val JOBBKLUBB = "JOBBK"
	const val AMO = "AMO"
	const val GRUPPEAMO = "GRUPPEAMO"
	const val ENKELAMO = "ENKELAMO"

	fun toAktivitetStatus(status: String): AktivitetStatus {
		return when (status) {
			"AKTUELL" -> AktivitetStatus.PLANLAGT
			"INFOMOETE" -> AktivitetStatus.PLANLAGT
			"JATAKK" -> AktivitetStatus.PLANLAGT
			"TILBUD" -> AktivitetStatus.PLANLAGT
			"VENTELISTE" -> AktivitetStatus.PLANLAGT
			"FULLF" -> AktivitetStatus.FULLFORT
			"GJENN" -> AktivitetStatus.GJENNOMFORES
			"GJENN_AVB" -> AktivitetStatus.AVBRUTT
			"GJENN_AVL" -> AktivitetStatus.AVBRUTT
			"IKKAKTUELL" -> AktivitetStatus.AVBRUTT
			"AVSLAG" -> AktivitetStatus.AVBRUTT
			"DELAVB" -> AktivitetStatus.AVBRUTT
			"IKKEM" -> AktivitetStatus.AVBRUTT
			"NEITAKK" -> AktivitetStatus.AVBRUTT
			"FEILREG" -> AktivitetStatus.AVBRUTT
			else -> throw IllegalArgumentException("Ugyldig arenastatus $status kan ikke konverteres")
		}
	}

	fun toDeleteStatus(deltakerStatusKode: String): AktivitetStatus {
		val lastStatus = toAktivitetStatus(deltakerStatusKode)
		return when (lastStatus) {
			AktivitetStatus.FULLFORT -> AktivitetStatus.FULLFORT
			AktivitetStatus.GJENNOMFORES -> AktivitetStatus.FULLFORT
			else -> AktivitetStatus.AVBRUTT
		}
	}

	fun toDeltakelseStatus(status: String): DeltakelseStatus? {
		return when (status) {
			"AKTUELL" -> DeltakelseStatus.SOKT_INN
			"INFOMOETE" -> DeltakelseStatus.INFOMOETE
			"JATAKK" -> DeltakelseStatus.TAKKET_JA
			"TILBUD" -> DeltakelseStatus.FATT_PLASS
			"VENTELISTE" -> DeltakelseStatus.VENTELISTE
			"IKKAKTUELL" -> DeltakelseStatus.IKKE_AKTUELL
			"AVSLAG" -> DeltakelseStatus.AVSLAG
			"IKKEM" -> DeltakelseStatus.IKKE_MOETT
			"NEITAKK" -> DeltakelseStatus.TAKKET_NEI
			"FEILREG" -> DeltakelseStatus.FEILREG
			else -> null
		}
	}

	fun toTittel(gjennomforingNavn: String, tiltakKode: String): String {
		val prefix = when (tiltakKode) {
			AMO -> "AMO-kurs: "
			GRUPPEAMO -> "Gruppe AMO: "
			ENKELAMO -> "Enkeltplass AMO: "
			else -> ""
		}
		return "$prefix$gjennomforingNavn"

	}

	fun convertToTiltaksaktivitet(
		deltaker: TiltakDeltakelse,
		aktivitetskortId: UUID,
		personIdent: String,
		arrangorNavn: String?,
		gjennomforingNavn: String,
		tiltak: Tiltak,
		isDelete: Boolean // Slettemeldinger inneholder bare forrige state og skal settes i en ferdig-status
	): Aktivitetskort {
		return Aktivitetskort(
			id = aktivitetskortId,
			personIdent = personIdent,
			tittel = toTittel(gjennomforingNavn, tiltak.kode),
			aktivitetStatus = if (isDelete) toDeleteStatus(deltaker.deltakerStatusKode) else toAktivitetStatus(deltaker.deltakerStatusKode),
			startDato = deltaker.datoFra,
			sluttDato = deltaker.datoTil,
			avtaltMedNav = true, // Arenatiltak er alltid Avtalt med NAV
			etiketter = listOfNotNull(
				toDeltakelseStatus(deltaker.deltakerStatusKode)?.toEtikett()
			),
			beskrivelse = if (tiltak.kode == JOBBKLUBB) gjennomforingNavn else null,
			endretTidspunkt = deltaker.modDato,
			endretAv = Ident(ident = deltaker.modUser ?: deltaker.regUser ?: throw IllegalArgumentException("Missing modUser")),
			detaljer = listOfNotNull(
				if (arrangorNavn != null) Attributt("Arrangør", arrangorNavn) else null,
				if (deltaker.prosentDeltid != null) Attributt("Deltakelse", "${deltaker.prosentDeltid}%") else null,
				if (deltaker.dagerPerUke != null) Attributt("Antall dager per uke", deltaker.dagerPerUke.toString()) else null,
			)
		)
	}
}
