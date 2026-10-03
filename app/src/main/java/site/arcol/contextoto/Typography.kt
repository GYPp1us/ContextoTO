package site.arcol.contextoto

import java.util.Locale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight

internal fun resolveGlyphWeight(text: AnnotatedString, offset: Int): FontWeight =
    text.spanStyles.lastOrNull { offset in it.start until it.end && it.item.fontWeight != null }?.item?.fontWeight ?: FontWeight.Normal

internal fun resolveGlyphColor(text: AnnotatedString, offset: Int, fallback: Color): Color =
    text.spanStyles.lastOrNull { offset in it.start until it.end &&
        it.item.color != Color.Unspecified && it.item.color.alpha > 0f }?.item?.color ?: fallback

internal fun resolveGlyphSize(text: AnnotatedString, offset: Int, fallbackSp: Float): Float =
    text.spanStyles.lastOrNull { offset in it.start until it.end &&
        it.item.fontSize != TextUnit.Unspecified }?.item?.fontSize?.value ?: fallbackSp

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
