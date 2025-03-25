package no.nav.arena_tiltak_aktivitet_acl.domain.kafka.aktivitet

//Subsett av arenastatuser
enum class DeltakelseStatus(val tekst: String, val sentiment: Sentiment ) {
	SOKT_INN("Søkt inn", Sentiment.NEUTRAL), //Aktuell
	AVSLAG("Avslag", Sentiment.NEGATIVE),
	IKKE_AKTUELL("Ikke aktuelt", Sentiment.NEUTRAL), //IKKAKTUELL
	IKKE_MOETT("Ikke møtt", Sentiment.NEGATIVE), //IKKEMK
	INFOMOETE("Infomøte", Sentiment.NEUTRAL),
	TAKKET_JA("Takket ja", Sentiment.POSITIVE), //JATAKK
	TAKKET_NEI("Takket nei", Sentiment.NEUTRAL), //NEITAKK
	FATT_PLASS("Fått plass", Sentiment.POSITIVE), //TILBUD
	VENTELISTE("Venteliste", Sentiment.NEUTRAL),
	FEILREG("Feilregistrert", Sentiment.NEGATIVE);

fun toEtikett(): Etikett {
	return Etikett(tekst = tekst, sentiment = sentiment, kode = this.name)
	}
}
