package site.arcol.contextoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

data class Article(val id: String, val kind: String, val title: String, val paragraphs: List<String>)
data class Lexeme(val word: String, val translation: String, val band: String?, val examFreq: Int?, val rank: Int?, val gap: Boolean)
data class Token(val text: String, val start: Int, val end: Int)
data class Sentence(val text: String, val start: Int, val end: Int)

class Content(context: Context) {
    val articles: List<Article>
    val lexicon: Map<String, Lexeme>

    init {
        val articleArray = JSONArray(context.assets.open("articles.json").bufferedReader().use { it.readText() })
        articles = (0 until articleArray.length()).map { index ->
            val obj = articleArray.getJSONObject(index)
            val raw = obj.getJSONArray("paragraphs")
            Article(obj.getString("id"), obj.getString("kind"), obj.getString("title"),
                (0 until raw.length()).map(raw::getString))
        }
        val dictionary = JSONObject(context.assets.open("lexicon.json").bufferedReader().use { it.readText() })
        lexicon = dictionary.keys().asSequence().associateWith { word ->
            val obj = dictionary.getJSONObject(word)
            Lexeme(word, obj.optString("translation"), obj.optString("band").ifBlank { null },
                obj.optString("exam_freq").toIntOrNull(), obj.optString("rank").toIntOrNull(),
                obj.optBoolean("gap", false))
        }
    }

    fun lexeme(surface: String): Lexeme? = candidates(surface).firstNotNullOfOrNull { lexicon[it] }

    fun lemma(surface: String): String = lexeme(surface)?.word ?: surface.lowercase(Locale.US)

    private fun candidates(surface: String): List<String> {
        val word = surface.lowercase(Locale.US).trim('’', '\'', '-')
        val base = word.removeSuffix("'s").removeSuffix("’s")
        return buildList {
            add(base)
            if (base.endsWith("ies")) add(base.dropLast(3) + "y")
            if (base.endsWith("es")) { add(base.dropLast(2)); add(base.dropLast(1)) }
            if (base.endsWith("s")) add(base.dropLast(1))
            if (base.endsWith("ing")) { add(base.dropLast(3)); add(base.dropLast(3) + "e") }
            if (base.endsWith("ed")) { add(base.dropLast(2)); add(base.dropLast(1)) }
        }.distinct()
    }

    companion object {
        private val wordPattern = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?(?:-[A-Za-z]+)*")
        fun tokens(text: String): List<Token> = wordPattern.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()
        fun tokenAt(text: String, offset: Int): Token? = tokens(text).firstOrNull { offset in it.start until it.end }
        fun sentenceAt(text: String, offset: Int): Sentence {
            val iterator = BreakIterator.getSentenceInstance(Locale.US)
            iterator.setText(text)
            val safe = offset.coerceIn(0, text.length.coerceAtLeast(1) - 1)
            var start = iterator.preceding(safe + 1).let { if (it == BreakIterator.DONE) 0 else it }
            var end = iterator.following(safe).let { if (it == BreakIterator.DONE) text.length else it }
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            return Sentence(text.substring(start, end), start, end)
        }
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
