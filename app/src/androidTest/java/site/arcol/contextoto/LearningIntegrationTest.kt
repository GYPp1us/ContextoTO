package site.arcol.contextoto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LearningIntegrationTest {
    @Test fun newWordsBeatForgottenWordsAndNoTenWordLimit() = isolated("new-first") { _, content, learning ->
        learning.addWord("research"); learning.selfGrade("research", false, 1000)
        val words = content.lexicon.values.filter { it.word != "research" && it.translation.isNotBlank() }.map { it.word }.sorted().take(12)
        assertEquals(12, words.size)
        words.forEach { learning.addWord(it) }
        val seen = mutableSetOf<String>()
        repeat(words.size) {
            val q = learning.nextQuestion(now = 2 * MemoryCurve.DAY, excluded = seen)!!
            assertNotEquals("research", q.word); assertTrue(seen.add(q.word))
            learning.answer(q, "forget", now = 2 * MemoryCurve.DAY); learning.closeQuestion(q.id)
        }
        assertEquals("research", learning.nextQuestion(now = 2 * MemoryCurve.DAY, excluded = seen)!!.word)
    }
    @Test fun preparationIsReadOnlyAndOptionalDailyLimitCountsDistinctWords() = isolated("prefetch") { _, content, learning ->
        learning.addWord("research"); learning.addWord(content.lexicon.values.first { it.word != "research" && it.translation.isNotBlank() }.word)
        val q = learning.nextQuestion(now = 1000)!!
        learning.answer(q, "remember", now = 2000)
        val next = learning.prepareQuestion(now = 3000, excluded = setOf(q.word))!!
        assertEquals(q.id, learning.currentQuestion()!!.id)
        assertNull(learning.prepareQuestion(now = 3000, excluded = setOf(q.word), dailyLimit = 1))
        learning.closeQuestion(q.id)
        assertEquals(next.id, learning.activateQuestion(next)!!.id)
    }
    @Test fun revisingAnswerReplacesScoreWithoutDuplicatingHeatOrOverwritingLaterGrades() = isolated("revise") { _, _, learning ->
        learning.addWord("research"); val q = learning.nextQuestion(now = 1000)!!
        learning.answer(q, "forget", now = 2000)
        learning.selfGrade(q.word, true, now = 2 * MemoryCurve.DAY)
        learning.reviseAnswer(q, "remember"); learning.reviseAnswer(q, "remember")
        val memory = learning.studyWord(q.word)!!.memory
        assertEquals(2, memory.reviews); assertEquals(2 * MemoryCurve.DAY, memory.last)
        assertEquals(2, learning.heatmap().values.sumOf { it["review"] ?: 0 })
        learning.reportIssue(q, "多个答案成立"); learning.reviseAnswer(q, "forget")
        assertEquals(1, learning.studyWord(q.word)!!.memory.reviews)
    }
    @Test fun preparedQuestionRejectsAnOptionThatBecomesARealMeaning() = isolated("stale-prefetch") { store, _, learning ->
        learning.addWord("research")
        val prepared = learning.prepareQuestion(now = 1000)!!
        assertNull(learning.currentQuestion())
        val distractor = prepared.options.first { it != prepared.answer.text }
        store.putAnalysis(Content.sha256("v3|word-common|research"), "word", JSONObject().put("common_senses",
            org.json.JSONArray().put(JSONObject().put("zh", distractor).put("part_of_speech", "n."))).toString())
        assertNull(learning.activateQuestion(prepared))
        assertNull(learning.currentQuestion())
    }
    @Test fun revisedArchiveCanRestoreOriginalScoreWithoutAnExtraAttempt() = isolated("revise-archive") { _, _, learning ->
        learning.addWord("research"); val q = learning.nextQuestion(now = 1000)!!
        learning.answer(q, "remember", now = 2000)
        learning.reviseAnswer(q, "archive", now = 3000)
        assertTrue(learning.studyWord(q.word)!!.archived)
        assertEquals(0, learning.studyWord(q.word)!!.memory.reviews)
        learning.reviseAnswer(q, "remember", now = 4000)
        assertFalse(learning.studyWord(q.word)!!.archived)
        assertEquals(1, learning.studyWord(q.word)!!.memory.reviews)
        assertEquals(2000L, learning.studyWord(q.word)!!.memory.last)
        assertEquals(1, learning.heatmap().values.sumOf { it["review"] ?: 0 })
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> isolated(name: String, block: (UserStore, Content, LearningStore) -> T): T {
        val database = "rc4-$name.db"; context.deleteDatabase(database)
        try { return UserStore(context, database).use { store -> val content = Content(context); block(store, content, LearningStore(store, content)) } }
        finally { context.deleteDatabase(database) }
    }
    @Test fun repeatedAnswerAndIssueAreIdempotentAndNewerGradesSurvive() = isolated("scoring") { store, _, learning ->
        learning.addWord("research")
        val question = learning.nextQuestion(now = 1_000)!!
        val answer = learning.answer(question, "remember", now = 10_000)
        learning.answer(question, "forget", now = 11_000)
        assertEquals(1, learning.studyWord(question.word)!!.memory.reviews)
        learning.selfGrade(question.word, true, now = 2 * MemoryCurve.DAY)
        learning.reportIssue(answer, "多个答案成立")
        learning.reportIssue(answer, "多个答案成立")
        val actual = learning.studyWord(question.word)!!.memory
        assertEquals(1, actual.reviews)
        assertEquals(2 * MemoryCurve.DAY, actual.last)
        assertEquals(1.0, actual.stability, .0001)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM question_issue", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertEquals(2, learning.heatmap().values.sumOf { it["review"] ?: 0 })
    }
    @Test fun archiveErrorDoesNotUndoArchiveOrAddScore() = isolated("archive") { _, _, learning ->
        learning.addWord("research"); val q = learning.nextQuestion(now = 1000)!!
        val answer = learning.answer(q, "archive"); learning.reportIssue(answer, "其他")
        assertTrue(learning.studyWord(q.word)!!.archived)
        assertEquals(0, learning.studyWord(q.word)!!.memory.reviews)
    }
    @Test fun differentOccurrencesPersistAndRepeatQueryDoesNotDuplicateHeat() = isolated("occurrences") { _, content, learning ->
        val article = Article("test", "test", "Test", listOf("The bank stands on the bank."))
        val tokens = Content.tokens(article.paragraphs[0]).filter { it.text == "bank" }
        val first = JSONObject("""{"context_sense":{"zh":"银行","part_of_speech":"n."}}""")
        val second = JSONObject("""{"context_sense":{"zh":"河岸","part_of_speech":"n."}}""")
        learning.recordAppearance(article, 0, tokens[0], first)
        learning.recordAppearance(article, 0, tokens[0], first)
        learning.recordAppearance(article, 0, tokens[1], second)
        assertEquals(2, learning.appearances(content.lemma("bank")).size)
        assertEquals(setOf("银行", "河岸"), learning.appearances("bank").map { it.meaning }.toSet())
        assertEquals(2, learning.heatmap().values.sumOf { it["lookup"] ?: 0 })
        assertEquals(1, learning.studyWords().size)
    }
    @Test fun importsArePersistentDeduplicatedAndDoNotEnrollEntireBank() = isolated("import") { store, content, learning ->
        val article = ContentImport.parseText("Sample.txt", "Imported text.\n\nMore reading.", false)
        val originalCount = content.articles.size
        assertEquals(1, learning.commitImport(article, "Sample").first)
        assertEquals(0, learning.commitImport(article, "Renamed").first)
        assertEquals(originalCount + 1, content.articles.size)
        val words = ContentImport.parseText("words.csv", "word,translation\nnewword,新词\notherword,别的词", true)
        assertEquals(2, learning.commitImport(words, "My bank").second)
        assertTrue(learning.studyWords().isEmpty())
        val bank = content.banks.last(); assertEquals(2, learning.randomImport(bank))
        assertEquals(0, learning.randomImport(bank))
        val reopened = Content(context); LearningStore(store, reopened).refreshContent()
        assertEquals(originalCount + 1, reopened.articles.size)
        assertEquals("My bank", reopened.banks.last().name)
    }
    @Test fun sentenceCacheSharedAcrossServiceAndModularRegenerationRetainsOthers(): Unit = runBlocking {
        isolated("sentence") { store, content, _ -> runBlocking {
            val provider = Provider("test", "https://first.example", "old", "fixture")
            val sentence = Sentence("The bank opened.", 0, 16)
            val oldKey = Content.sha256("v2|sentence|${provider.baseUrl}|${provider.model}|${sentence.text}")
            store.putAnalysis(oldKey, "sentence", """{"translation_zh":"银行开门了。","clauses":[],"glosses":[]}""")
            var calls = 0
            val engine = AnalysisEngine(content, store, AnalysisTransport { _, system, _, _, _ ->
                calls++; assertTrue(system.endsWith(QueryModule.TRANSLATION.instruction)); """{"zh":"这家银行营业了。"}"""
            }, legacyProviders = { listOf(provider) })
            val switched = provider.copy(baseUrl = "https://second.example", model = "new", protocol = ApiProtocol.RESPONSES)
            assertEquals("银行开门了。", engine.cachedSentence(switched, sentence.text)!!.getString("translation_zh"))
            engine.sentence(switched, content.articles.first(), sentence) {}
            assertEquals(0, calls)
            val result = engine.sentence(switched, content.articles.first(), sentence, forceModules = setOf("translation_zh")) {}
            assertEquals(1, calls); assertTrue(result.has("clauses")); assertTrue(result.has("glosses"))
            assertNotNull(store.getAnalysis(oldKey))
        } }
    }
    @Test fun partialWordReplySavesValidModulesAndRetryOnlyRequestsMissing(): Unit = runBlocking {
        isolated("partial") { store, content, _ -> runBlocking {
            val provider = Provider("test", "https://test.example", "test", "fixture")
            val article = Article("partial", "test", "Partial", listOf("Research matters."))
            val token = Content.tokens(article.paragraphs[0]).first()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val ipaCalls = java.util.concurrent.atomic.AtomicInteger()
            val engine = AnalysisEngine(content, store, AnalysisTransport { _, prompt, _, _, _ ->
                calls.incrementAndGet()
                when {
                    prompt.contains("IPA/") -> if (ipaCalls.incrementAndGet() == 1) "{}" else """{"uk":"/rɪˈsɜːtʃ/","us":"/ˈriːsɜːrtʃ/"}"""
                    prompt.endsWith(QueryModule.IDENTITY.instruction) -> """{"lemma":"research","form":"","part_of_speech":"n."}"""
                    prompt.endsWith(QueryModule.CONTEXT.instruction) -> """{"zh":"研究"}"""
                    prompt.endsWith(QueryModule.SENSES.instruction) -> """{"common_senses":[{"zh":"研究","part_of_speech":"n."}]}"""
                    prompt.endsWith(QueryModule.DERIVATIVES.instruction) -> """{"derivatives":[]}"""
                    else -> error("Unexpected module")
                }
            })
            assertTrue(runCatching { engine.word(provider, article, 0, token) {} }.isFailure)
            assertEquals("研究", engine.cachedWord(provider, article.paragraphs[0], token)!!.getJSONObject("context_sense").getString("zh"))
            val firstCalls = calls.get()
            assertTrue(engine.word(provider, article, 0, token) {}.has("phonetics"))
            assertEquals(firstCalls + 1, calls.get())
            assertEquals(2, ipaCalls.get())
        } }
    }
    @Test fun failedRegenerationKeepsOldModuleAndQuestionSurvivesReopen(): Unit = runBlocking {
        isolated("failure") { store, content, learning -> runBlocking {
            val provider = Provider("test", "https://test.example", "test", "fixture")
            val article = content.articles.first(); val sentence = Content.sentences(article.paragraphs.first()).first()
            val key = Content.sha256("v4|sentence|${sentence.text}")
            store.putAnalysis(key, "sentence", """{"translation_zh":"保留原译文","clauses":[],"glosses":[]}""")
            val engine = AnalysisEngine(content, store, AnalysisTransport { _, _, _, _, _ -> "{}" })
            assertTrue(runCatching { engine.sentence(provider, article, sentence, forceModules = setOf("translation_zh")) {} }.isFailure)
            assertEquals("保留原译文", engine.cachedSentence(provider, sentence.text)!!.getString("translation_zh"))
            learning.addWord("research"); val q = learning.nextQuestion()!!
            assertEquals(q, LearningStore(store, content).currentQuestion())
            assertEquals(q, LearningStore(store, content).nextQuestion())
        } }
    }
}
