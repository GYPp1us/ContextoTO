package site.arcol.contextoto

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class LearningStore(private val store: UserStore, private val content: Content) {
    private var poolBanks: List<WordBank>? = null
    private var dictionaryPool = DistractorIndex(emptyList())
    val revision get() = store.learningRevision
    private val db get() = store.writableDatabase
    private fun changed() { store.bumpLearningRevision() }
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private fun insert(table: String, values: ContentValues, replace: Boolean = false) = db.insertWithOnConflict(table, null, values,
        if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
    private fun day(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun refreshContent() {
        val articles = db.rawQuery("SELECT payload FROM imported_article ORDER BY created_at,id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val o = JSONObject(cursor.getString(0)); val p = o.getJSONArray("paragraphs")
                add(Article(o.getString("id"), "导入", o.getString("title"), (0 until p.length()).map(p::getString)))
            } }
        }
        val banks = db.rawQuery("SELECT id,name,payload FROM word_bank ORDER BY created_at,id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val words = JSONArray(cursor.getString(2)); val entries = (0 until words.length()).map { i ->
                    val o = words.getJSONObject(i)
                    Lexeme(o.getString("word"), o.getString("translation"), o.optString("band").ifBlank { null },
                        o.optInt("exam_freq").takeIf { it > 0 }, o.optInt("rank").takeIf { it > 0 }, false,
                        o.optString("ipa_uk"), o.optString("ipa_us"))
                }
                add(WordBank(cursor.getString(0), cursor.getString(1), entries.associateBy { it.word }))
            } }
        }
        val aliases = db.rawQuery("SELECT surface,word FROM word_alias", null).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        } }
        content.refresh(articles, banks, aliases)
    }
    fun commitImport(preview: ImportPreview, name: String, mergeBank: String? = null): Pair<Int, Int> {
        var articleCount = 0; var wordCount = 0
        transaction {
            preview.articles.forEach { article ->
                if (content.articles.none { Content.sha256(it.paragraphs.joinToString("\n\n")) == Content.sha256(article.paragraphs.joinToString("\n\n")) }) {
                    val values = ContentValues().apply { put("id", article.id); put("created_at", System.currentTimeMillis())
                        put("payload", JSONObject().put("id", article.id).put("title", if (preview.articles.size == 1) name.ifBlank { article.title } else article.title)
                            .put("paragraphs", JSONArray(article.paragraphs)).toString()) }
                    if (insert("imported_article", values) >= 0) articleCount++
                }
            }
            if (preview.words.isNotEmpty()) {
                val previous = content.banks.firstOrNull { it.id == mergeBank && it.id != "builtin" }
                val id = previous?.id ?: "bank-${UUID.randomUUID()}"
                val words = (previous?.words?.values.orEmpty() + preview.words).groupBy { it.word }.map { (_, entries) ->
                    entries.last().copy(translation = entries.map { it.translation }.distinct().joinToString(" / "))
                }
                wordCount = preview.words.map { it.word }.distinct().count { it !in previous?.words.orEmpty() }
                val values = ContentValues().apply { put("id", id); put("name", name.ifBlank { preview.suggestedName }); put("created_at", System.currentTimeMillis())
                    put("payload", JSONArray(words.map { word -> JSONObject().put("word", word.word).put("translation", word.translation)
                        .put("band", word.band).put("exam_freq", word.examFreq).put("rank", word.rank)
                        .put("ipa_uk", word.ipaUk).put("ipa_us", word.ipaUs) }).toString()) }
                insert("word_bank", values, true)
            }
        }
        refreshContent(); changed()
        return articleCount to wordCount
    }
    fun studyWords(): List<StudyWord> = db.rawQuery("SELECT word,archived,memory FROM study_word ORDER BY word COLLATE NOCASE", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(StudyWord(cursor.getString(0), cursor.getInt(1) != 0, Memory.parse(JSONObject(cursor.getString(2))))) }
    }
    fun studyWord(word: String): StudyWord? = db.rawQuery("SELECT archived,memory FROM study_word WHERE word=?", arrayOf(word)).use {
        if (it.moveToFirst()) StudyWord(word, it.getInt(0) != 0, Memory.parse(JSONObject(it.getString(1)))) else null
    }
    fun legacyArticles(word: String): List<String> = db.rawQuery(
        "SELECT article_id FROM lookup_event WHERE kind='word' AND item_id=? AND article_id NOT IN (SELECT article_id FROM occurrence WHERE word=?)", arrayOf(word, word)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    fun invalidateQuestion(word: String) {
        db.rawQuery("SELECT id,payload FROM review_question WHERE word=? AND closed=0", arrayOf(word)).use { cursor ->
            while (cursor.moveToNext()) if (!ReviewQuestion.parse(JSONObject(cursor.getString(1))).answered)
                db.update("review_question", ContentValues().apply { put("closed", 1) }, "id=?", arrayOf(cursor.getString(0)))
        }
        changed()
    }
    fun refreshAppearanceMeanings(word: String) {
        appearances(word).forEach { occurrence ->
            val article = content.articles.firstOrNull { it.id == occurrence.articleId } ?: return@forEach
            val paragraph = if (occurrence.paragraph < 0) article.title else article.paragraphs.getOrNull(occurrence.paragraph) ?: return@forEach
            val sentence = Content.sentenceAt(paragraph, occurrence.start)
            val key = Content.sha256("v3|word-context-module|$word|${sentence.text}|${occurrence.start - sentence.start}")
            store.getAnalysis(key)?.let { JSONObject(it).optJSONObject("context_sense") }?.let { sense ->
                db.update("occurrence", ContentValues().apply { put("meaning", sense.optString("zh")); put("part", sense.optString("part_of_speech")) }, "id=?", arrayOf(occurrence.id))
            }
        }
        invalidateQuestion(word)
    }
    fun addWord(word: String, now: Long = System.currentTimeMillis()): Boolean = insert("study_word", ContentValues().apply {
        put("word", word); put("archived", 0); put("memory", Memory().json().toString()); put("created_at", now)
    }) >= 0
    fun randomImport(bank: WordBank, count: Int = 10): Int {
        val existing = studyWords().map { it.word }.toSet()
        val selected = bank.words.keys.filter { it !in existing }.shuffled().take(count)
        transaction { selected.forEach { addWord(it) } }; changed(); return selected.size
    }
    fun archive(word: String, value: Boolean) {
        addWord(word); db.update("study_word", ContentValues().apply { put("archived", if (value) 1 else 0) }, "word=?", arrayOf(word)); changed()
    }
    fun recordAppearance(article: Article, paragraphIndex: Int, token: Token, analysis: JSONObject?, now: Long = System.currentTimeMillis()) {
        val paragraph = if (paragraphIndex < 0) article.title else article.paragraphs.getOrNull(paragraphIndex) ?: return
        val suggested = content.lemma(token.text)
        val surface = token.text.lowercase(java.util.Locale.US)
        val word = db.rawQuery("SELECT word FROM word_alias WHERE surface=?", arrayOf(surface)).use { if (it.moveToFirst()) it.getString(0) else suggested }
        val sentence = Content.sentenceAt(paragraph, token.start)
        val id = Content.sha256("${article.id}|${Content.sha256(paragraph)}|$paragraphIndex|${token.start}|${token.end}")
        val sense = analysis?.optJSONObject("context_sense")
        transaction {
            val prior = db.rawQuery("SELECT meaning,part FROM occurrence WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.getString(0) to it.getString(1) else "" to ""
            }
            insert("word_alias", ContentValues().apply { put("surface", surface); put("word", word) })
            content.rememberLemma(surface, word)
            store.recordLookup(article.id, "word", word)
            addWord(word, now)
            insert("occurrence", ContentValues().apply {
                put("id", id); put("word", word); put("article_id", article.id); put("paragraph_index", paragraphIndex)
                put("start", token.start); put("end", token.end); put("sentence", sentence.text); put("surface", token.text)
                put("meaning", sense?.optString("zh")?.takeIf { it.isNotBlank() } ?: prior.first)
                put("part", sense?.optString("part_of_speech")?.takeIf { it.isNotBlank() } ?: prior.second); put("queried_at", now)
            }, true)
            activity("lookup", id, now)
        }
        changed()
    }
    fun appearances(word: String): List<Appearance> = db.rawQuery(
        "SELECT id,article_id,paragraph_index,start,end,sentence,surface,meaning,part,queried_at FROM occurrence WHERE word=? ORDER BY queried_at DESC", arrayOf(word)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(Appearance(cursor.getString(0), word, cursor.getString(1), cursor.getInt(2),
        cursor.getInt(3), cursor.getInt(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8), cursor.getLong(9))) } }
    fun queried(): Set<String> = db.rawQuery("SELECT DISTINCT item_id FROM lookup_event WHERE kind='word'", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    fun meanings(word: String): List<Meaning> = buildList {
        appearances(word).forEach { if (it.meaning.isNotBlank()) add(Meaning(it.meaning, it.part, "原句", it.id)) }
        content.banks.forEach { bank -> bank.words[word]?.let { addAll(dictionaryMeanings(it.translation, bank.name, bank.id)) } }
        val common = store.getAnalysis(Content.sha256("v3|word-common|$word"))?.let { JSONObject(it).optJSONArray("common_senses") }
        for (i in 0 until (common?.length() ?: 0)) common?.optJSONObject(i)?.let {
            if (it.optString("zh").isNotBlank()) add(Meaning(it.optString("zh"), it.optString("part_of_speech"), "通用解析", "$word:$i"))
        }
    }.distinctBy { meaningIdentity(it.text) }

    fun currentQuestion(): ReviewQuestion? = db.rawQuery("SELECT payload FROM review_question WHERE closed=0 ORDER BY created_at DESC LIMIT 1", null).use {
        if (it.moveToFirst()) ReviewQuestion.parse(JSONObject(it.getString(0))) else null
    }
    fun nextQuestion(early: Boolean = false, now: Long = System.currentTimeMillis(), excluded: Set<String> = emptySet(),
                     dailyLimit: Int = 0, onProgress: (QuestionPreparation) -> Unit = {}): ReviewQuestion? {
        currentQuestion()?.let { return it }
        return prepareQuestion(early, now, excluded, dailyLimit, onProgress)?.let(::activateQuestion)
    }
    fun dailyReviewedCount(now: Long = System.currentTimeMillis()): Int = db.rawQuery(
        "SELECT COUNT(DISTINCT word) FROM review_attempt WHERE day=? AND valid=1", arrayOf(day(now))
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    /** Read-only preparation can run while the preceding answer is on screen. */
    @Synchronized
    fun prepareQuestion(early: Boolean = false, now: Long = System.currentTimeMillis(), excluded: Set<String> = emptySet(),
                        dailyLimit: Int = 0, onProgress: (QuestionPreparation) -> Unit = {}): ReviewQuestion? {
        onProgress(QuestionPreparation("读取复习队列", .08f))
        val active = studyWords().filter { !it.archived && it.word !in excluded }
        val frequency = db.rawQuery("SELECT word,COUNT(*) FROM occurrence GROUP BY word", null).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
        } }
        val todayCount = dailyReviewedCount(now)
        if (dailyLimit > 0 && todayCount >= dailyLimit) return null
        val due = active.filter { it.memory.reviews == 0 || early || it.memory.due <= now }.shuffled()
            .sortedWith(compareBy<StudyWord> { it.memory.reviews != 0 }.thenByDescending {
                val overdue = ((now - it.memory.due).toDouble() / MemoryCurve.DAY).coerceAtLeast(0.0)
                overdue * 2 + it.memory.difficulty * .2 + kotlin.math.ln(1.0 + (frequency[it.word] ?: 0)) +
                    1.0 / (1 + (content.lexicon[it.word]?.rank ?: 10000) / 500.0)
            })
        if (poolBanks !== content.banks) {
            onProgress(QuestionPreparation("索引词库释义与词性", .25f))
            dictionaryPool = DistractorIndex(content.banks.flatMap { bank -> bank.words.values.flatMap { entry ->
                dictionaryMeanings(entry.translation, bank.name, bank.id).map { entry.word to it }
            } })
            poolBanks = content.banks
        }
        val pool = dictionaryPool
        for ((index, candidate) in due.withIndex()) {
            onProgress(QuestionPreparation("筛选可出题词条 · ${index + 1} / ${due.size}", .5f + .35f * index / due.size.coerceAtLeast(1)))
            val question = makeQuestion(candidate.word, meanings(candidate.word), pool, now) ?: continue
            onProgress(QuestionPreparation("选项组装完成", 1f)); return question
        }
        return null
    }
    fun activateQuestion(question: ReviewQuestion): ReviewQuestion? = transaction {
        currentQuestion()?.let { return@transaction it }
        if (studyWord(question.word)?.archived != false) return@transaction null
        // Prepared snapshots may outlive a cache/import update. Never activate a
        // vanished answer or an option that has meanwhile become a true sense.
        val currentMeanings = meanings(question.word).map { meaningIdentity(it.text) }.toSet()
        if (meaningIdentity(question.answer.text) !in currentMeanings || question.options.withIndex().any {
            it.index != question.correct && meaningIdentity(it.value) in currentMeanings
        }) return@transaction null
        insert("review_question", ContentValues().apply { put("id", question.id); put("word", question.word); put("payload", question.json().toString())
            put("created_at", question.created); put("closed", 0) })
        question
    }
    fun closeQuestion(id: String) { db.update("review_question", ContentValues().apply { put("closed", 1) }, "id=?", arrayOf(id)); changed() }
    fun answer(question: ReviewQuestion, action: String, selected: Int? = null, now: Long = System.currentTimeMillis()): ReviewQuestion {
        val answered = transaction {
            val persisted = db.rawQuery("SELECT payload FROM review_question WHERE id=?", arrayOf(question.id)).use {
                require(it.moveToFirst()) { "题目已不存在" }; ReviewQuestion.parse(JSONObject(it.getString(0)))
            }
            if (persisted.answered) return@transaction persisted
            val result = persisted.copy(action = action, selected = selected)
            if (action == "archive") archive(question.word, true)
            else grade(question.id, question.word, action == "remember" || (action == "choice" && selected == question.correct),
                "question", result.json(), now)
            db.update("review_question", ContentValues().apply { put("payload", result.json().toString()) }, "id=?", arrayOf(question.id))
            result
        }
        changed(); return answered
    }
    fun selfGrade(word: String, remembered: Boolean, now: Long = System.currentTimeMillis()) {
        transaction { grade(UUID.randomUUID().toString(), word, remembered, "self", JSONObject(), now) }; changed()
    }
    /** Change the original attempt, preserving its timestamp/id/heat; never add a second score. */
    fun reviseAnswer(question: ReviewQuestion, action: String, now: Long = System.currentTimeMillis()): ReviewQuestion {
        require(action in setOf("remember", "forget", "archive"))
        val revised = transaction {
            val previous = db.rawQuery("SELECT payload FROM review_question WHERE id=?", arrayOf(question.id)).use {
                require(it.moveToFirst()); ReviewQuestion.parse(JSONObject(it.getString(0)))
            }
            val result = previous.copy(action = action, selected = null)
            if (action == "archive") {
                archive(question.word, true)
                db.update("review_attempt", ContentValues().apply { put("valid", 0) }, "id=?", arrayOf(question.id))
            } else {
                archive(question.word, false)
                // Issue-retracted questions must not silently regain a score.
                val retracted = db.rawQuery("SELECT COUNT(*) FROM question_issue WHERE question_id=?", arrayOf(question.id)).use { it.moveToFirst(); it.getInt(0) > 0 }
                if (!retracted) {
                    val exists = db.rawQuery("SELECT COUNT(*) FROM review_attempt WHERE id=?", arrayOf(question.id)).use { it.moveToFirst(); it.getInt(0) > 0 }
                    if (!exists) grade(question.id, question.word, action == "remember", "question", result.json(), now)
                    else db.update("review_attempt", ContentValues().apply { put("remembered", if (action == "remember") 1 else 0)
                        put("valid", 1); put("snapshot", result.json().toString()) }, "id=?", arrayOf(question.id))
                }
            }
            replay(question.word)
            db.update("review_question", ContentValues().apply { put("payload", result.json().toString()) }, "id=?", arrayOf(question.id))
            result
        }
        changed(); return revised
    }
    private fun replay(word: String) {
        var memory = Memory()
        db.rawQuery("SELECT remembered,created_at,day FROM review_attempt WHERE word=? AND valid=1 ORDER BY created_at,rowid", arrayOf(word)).use { cursor ->
            while (cursor.moveToNext()) memory = MemoryCurve.grade(memory, cursor.getInt(0) == 1, cursor.getLong(1), cursor.getString(2))
        }
        db.update("study_word", ContentValues().apply { put("memory", memory.json().toString()) }, "word=?", arrayOf(word))
    }
    private fun grade(id: String, word: String, remembered: Boolean, source: String, snapshot: JSONObject, now: Long) {
        addWord(word, now)
        val old = studyWord(word)!!.memory
        val next = MemoryCurve.grade(old, remembered, now, day(now))
        val inserted = insert("review_attempt", ContentValues().apply { put("id", id); put("word", word); put("remembered", if (remembered) 1 else 0)
            put("source", source); put("snapshot", snapshot.toString()); put("created_at", now); put("day", day(now)); put("valid", 1)
            put("was_new", if (old.reviews == 0) 1 else 0); put("scheduler_version", MemoryCurve.VERSION) })
        if (inserted >= 0) {
            db.update("study_word", ContentValues().apply { put("memory", next.json().toString()) }, "word=?", arrayOf(word))
            activity("review", id, now)
        }
    }
    /** Atomic, idempotent scoring retraction; replay newer valid attempts, never restore a stale state. */
    fun reportIssue(question: ReviewQuestion, type: String, now: Long = System.currentTimeMillis()) {
        transaction {
            insert("question_issue", ContentValues().apply { put("question_id", question.id); put("type", type); put("snapshot", question.json().toString())
                put("created_at", now); put("score_retracted", if (question.action != "archive") 1 else 0) })
            db.update("review_attempt", ContentValues().apply { put("valid", 0) }, "id=? AND valid=1", arrayOf(question.id))
            replay(question.word)
        }
        changed()
    }
    fun activity(kind: String, key: String, now: Long = System.currentTimeMillis()) {
        val localDay = day(now)
        insert("activity_event", ContentValues().apply { put("id", "$kind|$key|$localDay"); put("kind", kind); put("day", localDay); put("created_at", now) })
        changed()
    }
    fun heatmap(): Map<String, Map<String, Int>> = db.rawQuery("SELECT day,kind,COUNT(*) FROM activity_event GROUP BY day,kind", null).use { cursor ->
        val result = mutableMapOf<String, MutableMap<String, Int>>()
        while (cursor.moveToNext()) result.getOrPut(cursor.getString(0)) { mutableMapOf() }[cursor.getString(1)] = cursor.getInt(2)
        result
    }
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS lookup_event(article_id TEXT NOT NULL,kind TEXT NOT NULL,item_id TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(article_id,kind,item_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS imported_article(id TEXT PRIMARY KEY,payload TEXT NOT NULL,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS word_bank(id TEXT PRIMARY KEY,name TEXT NOT NULL,payload TEXT NOT NULL,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS word_alias(surface TEXT PRIMARY KEY,word TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS study_word(word TEXT PRIMARY KEY,archived INTEGER NOT NULL DEFAULT 0,memory TEXT NOT NULL,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS occurrence(id TEXT PRIMARY KEY,word TEXT NOT NULL,article_id TEXT NOT NULL,paragraph_index INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,sentence TEXT NOT NULL,surface TEXT NOT NULL,meaning TEXT NOT NULL,part TEXT NOT NULL,queried_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS occurrence_word ON occurrence(word,queried_at)")
            db.execSQL("CREATE TABLE IF NOT EXISTS review_question(id TEXT PRIMARY KEY,word TEXT NOT NULL,payload TEXT NOT NULL,created_at INTEGER NOT NULL,closed INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS review_attempt(id TEXT PRIMARY KEY,word TEXT NOT NULL,remembered INTEGER NOT NULL,source TEXT NOT NULL,snapshot TEXT NOT NULL,created_at INTEGER NOT NULL,day TEXT NOT NULL,valid INTEGER NOT NULL,was_new INTEGER NOT NULL,scheduler_version INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS attempts_word ON review_attempt(word,created_at)")
            db.execSQL("CREATE TABLE IF NOT EXISTS question_issue(question_id TEXT NOT NULL,type TEXT NOT NULL,snapshot TEXT NOT NULL,created_at INTEGER NOT NULL,score_retracted INTEGER NOT NULL,PRIMARY KEY(question_id,type))")
            db.execSQL("CREATE TABLE IF NOT EXISTS activity_event(id TEXT PRIMARY KEY,kind TEXT NOT NULL,day TEXT NOT NULL,created_at INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO study_word(word,memory,created_at) SELECT item_id,'{}',MIN(created_at) FROM lookup_event WHERE kind='word' GROUP BY item_id")
            db.execSQL("INSERT OR IGNORE INTO word_alias(surface,word) SELECT item_id,item_id FROM lookup_event WHERE kind='word'")
        }
    }
}
