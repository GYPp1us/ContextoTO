package site.arcol.contextoto

import java.util.Locale

internal fun abbreviatePartOfSpeech(value: String): String {
    val normalized = value.trim().lowercase(Locale.US).removeSuffix(".")
    return when (normalized) {
        "preposition", "prep" -> "PREP."
        "adjective", "adj" -> "ADJ."
        "adverb", "adv" -> "ADV."
        "pronoun", "pron" -> "PRON."
        "conjunction", "conj" -> "CONJ."
        "interjection", "interj", "int" -> "INT."
        "determiner", "det" -> "DET."
        "auxiliary verb", "auxiliary", "aux" -> "AUX."
        "phrasal verb", "phr v", "phr.v" -> "PHR.V."
        "transitive verb", "vt" -> "VT."
        "intransitive verb", "vi" -> "VI."
        "verb", "v" -> "V."
        "noun", "n" -> "N."
        else -> value.trim().uppercase(Locale.US)
    }
}
