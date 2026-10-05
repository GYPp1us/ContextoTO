package site.arcol.contextoto

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

data class WordBank(val id: String, val name: String, val words: Map<String, Lexeme>)
data class Meaning(val text: String, val part: String = "", val source: String, val reference: String = "") {
    fun json() = JSONObject().put("text", text).put("part", part).put("source", source).put("reference", reference)
    companion object { fun parse(o: JSONObject) = Meaning(o.getString("text"), o.optString("part"), o.getString("source"), o.optString("reference")) }
}
data class Appearance(val id: String, val word: String, val articleId: String, val paragraph: Int,
                      val start: Int, val end: Int, val sentence: String, val surface: String,
                      val meaning: String, val part: String, val queriedAt: Long, val legacy: Boolean = false)
data class Memory(val stability: Double = 0.0, val difficulty: Double = 5.0, val due: Long = 0,
                  val last: Long = 0, val reviews: Int = 0, val successes: Int = 0, val successDay: String = "") {
    fun json() = JSONObject().put("stability", stability).put("difficulty", difficulty).put("due", due)
        .put("last", last).put("reviews", reviews).put("successes", successes).put("success_day", successDay)
    companion object { fun parse(o: JSONObject) = Memory(o.optDouble("stability", 0.0), o.optDouble("difficulty", 5.0),
        o.optLong("due"), o.optLong("last"), o.optInt("reviews"), o.optInt("successes"), o.optString("success_day")) }
}
data class StudyWord(val word: String, val archived: Boolean, val memory: Memory) {
    val mastered get() = !archived && memory.stability >= 21 && memory.successes >= 2
}

/** Versioned local exponential forgetting curve. Not an FSRS implementation or a recall assessment. */
object MemoryCurve {
    const val VERSION = 1
    const val DAY = 86_400_000L
    fun retention(memory: Memory, now: Long): Double = if (memory.stability <= 0) 0.0 else
        exp(-max(0.0, (now - memory.last).toDouble() / DAY) / memory.stability * -kotlin.math.ln(.9))
    fun grade(old: Memory, remembered: Boolean, now: Long, day: String): Memory {
        val difficulty = (old.difficulty + if (remembered) -.35 else .8).coerceIn(1.0, 10.0)
        val elapsed = max(0.0, (now - old.last).toDouble() / DAY)
        val stability = if (remembered) {
            if (old.reviews == 0) 1.0 else if (elapsed < .5) max(1.0, old.stability)
            else max(1.0, old.stability * (1.45 + (10 - difficulty) * .15 + (1 - retention(old, now)) * 2))
        } else if (old.reviews == 0) .12 else max(.12, old.stability * .35)
        val interval = if (remembered) (stability * DAY).toLong() else 10 * 60_000L
        val successes = if (!remembered) 0 else old.successes + if (old.successDay != day) 1 else 0
        return Memory(stability, difficulty, now + interval, now, old.reviews + 1, successes,
            if (remembered) day else "")
    }
}

data class ReviewQuestion(val id: String, val word: String, val answer: Meaning, val options: List<String>,
                          val correct: Int, val created: Long, val selected: Int? = null, val action: String? = null) {
    val answered get() = action != null
    fun json() = JSONObject().put("id", id).put("word", word).put("answer", answer.json())
        .put("options", JSONArray(options)).put("correct", correct).put("created", created)
        .put("selected", selected ?: JSONObject.NULL).put("action", action ?: JSONObject.NULL).put("version", 1)
    companion object {
        fun parse(o: JSONObject) = ReviewQuestion(o.getString("id"), o.getString("word"), Meaning.parse(o.getJSONObject("answer")),
            o.getJSONArray("options").let { a -> (0 until a.length()).map(a::getString) }, o.getInt("correct"), o.getLong("created"),
            if (o.isNull("selected")) null else o.getInt("selected"), if (o.isNull("action")) null else o.getString("action"))
    }
}

fun meaningIdentity(text: String): String = text.lowercase().replace(Regex("[\\s\\p{Punct}，。；、：]"), "")
fun dictionaryMeanings(translation: String, source: String, reference: String = ""): List<Meaning> =
    translation.split(Regex("\\s*/\\s*|；|;|\\n")).mapNotNull { raw ->
        val match = Regex("^([a-z]{1,6}\\.)\\s*(.*)", RegexOption.IGNORE_CASE).find(raw.trim())
        val text = match?.groupValues?.get(2) ?: raw.trim()
        text.takeIf { it.isNotBlank() }?.let { Meaning(it, match?.groupValues?.get(1).orEmpty(), source, reference) }
    }.flatMap { meaning ->
        val pieces = mutableListOf<String>(); val piece = StringBuilder(); var depth = 0
        meaning.text.forEach { char ->
            if (char in "(（[") depth++
            if (char in ")）]") depth = (depth - 1).coerceAtLeast(0)
            if (char in ",，、" && depth == 0) { if (piece.isNotBlank()) pieces += piece.toString().trim(); piece.setLength(0) }
            else piece.append(char)
        }
        if (piece.isNotBlank()) pieces += piece.toString().trim()
        pieces.map { meaning.copy(text = it) }
    }

/** Known true senses of the headword can never be distractors, regardless of their source. */
fun makeQuestion(word: String, meanings: List<Meaning>, pool: List<Pair<String, Meaning>>,
                 now: Long, random: Random = Random.Default): ReviewQuestion? {
    val senses = meanings.filter { it.text.isNotBlank() }.distinctBy { meaningIdentity(it.text) }
    if (senses.isEmpty()) return null
    val answer = senses.random(random)
    val trueMeanings = senses.map { meaningIdentity(it.text) }.toSet()
    val candidates = pool.filter { (other, meaning) -> other != word && meaningIdentity(meaning.text) !in trueMeanings }
        .map { it.second }.distinctBy { meaningIdentity(it.text) }.shuffled(random)
        .sortedBy { if (answer.part.isNotBlank() && it.part == answer.part) 0 else 1 }
    if (candidates.size < 3) return null
    val options = (candidates.take(3).map { it.text } + answer.text).shuffled(random)
    return ReviewQuestion(UUID.randomUUID().toString(), word, answer, options, options.indexOf(answer.text), now)
}
