package site.arcol.contextoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

data class Article(val id: String, val kind: String, val title: String, val paragraphs: List<String>)
data class Lexeme(val word: String, val translation: String, val band: String?, val examFreq: Int?, val rank: Int?, val gap: Boolean,
                  val ipaUk: String = "", val ipaUs: String = "")
data class Token(val text: String, val start: Int, val end: Int)
data class Sentence(val text: String, val start: Int, val end: Int)

class Content(context: Context) {
    var articles by mutableStateOf<List<Article>>(emptyList())
        private set
    var lexicon by mutableStateOf<Map<String, Lexeme>>(emptyMap())
        private set
    var banks by mutableStateOf<List<WordBank>>(emptyList())
        private set
    private var builtinArticles: List<Article> = emptyList()
    private var builtinLexicon: Map<String, Lexeme> = emptyMap()
    private var generalLexicon: Map<String, Lexeme> = emptyMap()
    private var wordForms: Map<String, List<String>> = emptyMap()
    private var knownWords: Set<String> = emptySet()
    private val aliases = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        builtinArticles = articles; builtinLexicon = lexicon
        val general = JSONObject(context.assets.open("general_10000.json").bufferedReader().use { it.readText() })
        val words = general.getJSONObject("words")
        generalLexicon = words.keys().asSequence().associateWith { word ->
            val entry = words.getJSONObject(word); val ipa = entry.optString("ipa_uk")
            Lexeme(word, entry.getString("translation"), null, null, entry.optInt("rank"), false,
                if (ipa.isBlank()) "" else "/" + ipa.trim('/') + "/")
        }
        val forms = general.getJSONObject("forms")
        wordForms = forms.keys().asSequence().associateWith { surface -> forms.getJSONArray(surface).let { array -> (0 until array.length()).map(array::getString) } }
        val known = general.getJSONArray("known_words"); knownWords = (0 until known.length()).map(known::getString).toSet()
        lexicon = generalLexicon + builtinLexicon
        banks = builtinBanks()
    }

    fun refresh(imported: List<Article>, importedBanks: List<WordBank>, savedAliases: Map<String, String>) {
        aliases.putAll(savedAliases)
        articles = builtinArticles + imported
        banks = builtinBanks() + importedBanks
        lexicon = generalLexicon + importedBanks.flatMap { it.words.entries }.associate { it.key to it.value } + builtinLexicon
    }
    fun rememberLemma(surface: String, lemma: String) { aliases.putIfAbsent(surface.lowercase(Locale.US), lemma) }

    fun lexeme(surface: String): Lexeme? = candidates(surface).firstNotNullOfOrNull { lexicon[it] }

    fun lemma(surface: String): String = aliases[surface.lowercase(Locale.US)] ?: lexeme(surface)?.word ?: surface.lowercase(Locale.US)

    private fun builtinBanks() = listOf(WordBank("builtin", "考纲 4801 · 主背 3000", builtinLexicon), WordBank("general", "通用高频 10000 · ECDICT", generalLexicon))
    fun knownWord(word: String): Boolean = word.lowercase(Locale.US) in knownWords || word in lexicon
    fun morphologyCandidates(surface: String): List<String> = (wordForms[surface.lowercase(Locale.US)].orEmpty() + candidates(surface).filter { it in lexicon }).distinct()
    fun localIdentity(surface: String): JSONObject? {
        val lower = surface.lowercase(Locale.US)
        val closed = mapOf("the" to "det.", "a" to "det.", "an" to "det.", "and" to "conj.", "or" to "conj.", "of" to "prep.",
            "in" to "prep.", "on" to "prep.", "by" to "prep.", "with" to "prep.", "from" to "prep.")
        val possibilities = morphologyCandidates(surface)
        val roots = possibilities.filter { it != lower }
        if (roots.size > 1 || (roots.isNotEmpty() && lower in lexicon)) return null
        val root = roots.singleOrNull() ?: lower
        val parts = Regex("(?:^|[ /;\\n])((?:n|v|vt|vi|a|adj|adv|prep|pron|det|conj|aux)\\.)")
            .findAll(lexicon[root]?.translation.orEmpty()).map { normalizedPart(it.groupValues[1]) }.toSet()
        if (root == lower && parts.size > 1 && lower !in closed) return null
        if (parts.isEmpty() && lower !in closed) return null
        return JSONObject().put("lemma", root).put("form", if (root == lower) "" else when {
            lower.endsWith("'s") || lower.endsWith("’s") -> "所有格"
            lower.endsWith("ing") -> "现在分词"; lower.endsWith("ed") -> "过去式 / 过去分词"; lower.endsWith("s") -> "复数 / 第三人称单数"; else -> "词形变化"
        }).put("part_of_speech", closed[lower] ?: parts.singleOrNull().orEmpty())
    }

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
        fun sentences(text: String): List<Sentence> {
            val iterator = BreakIterator.getSentenceInstance(Locale.US)
            iterator.setText(text)
            val result = mutableListOf<Sentence>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                var trimmedStart = start
                var trimmedEnd = end
                while (trimmedStart < trimmedEnd && text[trimmedStart].isWhitespace()) trimmedStart++
                while (trimmedEnd > trimmedStart && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
                if (trimmedEnd > trimmedStart) result += Sentence(text.substring(trimmedStart, trimmedEnd), trimmedStart, trimmedEnd)
                start = end
                end = iterator.next()
            }
            return result
        }
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
