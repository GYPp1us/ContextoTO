package site.arcol.contextoto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class Rc6IntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Provider("fixture", "https://fixture.example/v1", "fixture", "not-a-real-key")
    private fun isolated(name: String, block: (UserStore, Content, LearningStore) -> Unit) {
        val database = "rc6-$name.db"; context.deleteDatabase(database)
        try { UserStore(context, database).use { store ->
            val content = Content(context); block(store, content, LearningStore(store, content))
        } } finally { context.deleteDatabase(database) }
    }
    private fun fixture(module: QueryModule): String = when (module) {
        QueryModule.IDENTITY -> """{"lemma":"research","form":"","part_of_speech":"n."}"""
        QueryModule.PRONUNCIATION -> """{"uk":"/test/","us":"/test/"}"""
        QueryModule.CONTEXT -> """{"zh":"研究"}"""
        QueryModule.SENSES -> """{"common_senses":[{"zh":"研究","part_of_speech":"n."}]}"""
        QueryModule.DERIVATIVES -> """{"derivatives":[]}"""
        QueryModule.TRANSLATION -> """{"zh":"研究很重要。"}"""
        QueryModule.CLAUSES -> """{"clauses":[]}"""
    }
    private fun module(prompt: String) = QueryModule.entries.firstOrNull { prompt.endsWith(it.instruction) }
        ?: QueryModule.PRONUNCIATION.also { assertTrue(prompt.contains("IPA/")) }

    @Test fun independentRequestsAreMinimalParallelAndPartialFailureOnlyRetriesMissing() = isolated("parallel") { store, content, _ -> runBlocking {
        val started = ConcurrentHashMap.newKeySet<QueryModule>(); val barrier = CompletableDeferred<Unit>()
        val calls = ConcurrentHashMap<QueryModule, AtomicInteger>()
        val article = Article("fixture", "test", "Fixture", listOf("Research matters."))
        val token = Content.tokens(article.paragraphs[0]).first()
        val engine = AnalysisEngine(content, store, AnalysisTransport { _, prompt, input, effort, report ->
            val module = module(prompt); val count = calls.computeIfAbsent(module) { AtomicInteger() }.incrementAndGet()
            assertEquals(module.effort, effort)
            if (module in setOf(QueryModule.PRONUNCIATION, QueryModule.CONTEXT)) {
                started.add(module); if (started.size >= 2) barrier.complete(Unit)
                withTimeout(5000) { barrier.await() }
            }
            when (module) {
                QueryModule.PRONUNCIATION -> assertEquals("Research", input)
                QueryModule.SENSES, QueryModule.DERIVATIVES -> assertEquals("research", input)
                QueryModule.CONTEXT -> { val data = JSONObject(input); assertEquals("Research matters.", data.getString("sentence")); assertEquals(3, data.length()) }
                else -> Unit
            }
            report(QueryProgress(QueryPhase.STREAM, receivedChars = 30))
            if (module == QueryModule.PRONUNCIATION && count == 1) "{}" else fixture(module)
        })
        assertTrue(runCatching { engine.word(provider, article, 0, token) {} }.isFailure)
        val partial = engine.cachedWord(provider, article.paragraphs[0], token)!!
        assertEquals("研究", partial.getJSONObject("context_sense").getString("zh"))
        assertTrue(partial.has("common_senses")); assertTrue(partial.has("derivatives"))
        val before = calls.values.sumOf { it.get() }
        engine.word(provider.copy(baseUrl = "https://another.example", protocol = ApiProtocol.RESPONSES), article, 0, token) {}
        assertEquals(before + 1, calls.values.sumOf { it.get() })
        assertTrue(engine.wordComplete(provider, article.paragraphs[0], token))
    } }

    @Test fun regenerationRetryDoesNotRepeatSuccessfulSibling() = isolated("regen") { store, content, _ -> runBlocking {
        val calls = ConcurrentHashMap<QueryModule, AtomicInteger>()
        val article = Article("fixture", "test", "Fixture", listOf("Research matters."))
        val token = Content.tokens(article.paragraphs[0]).first()
        val engine = AnalysisEngine(content, store, AnalysisTransport { _, prompt, _, _, _ ->
            val module = module(prompt); val count = calls.computeIfAbsent(module) { AtomicInteger() }.incrementAndGet()
            if (module == QueryModule.DERIVATIVES && count == 2) "{}" else fixture(module)
        })
        engine.word(provider, article, 0, token) {}
        val forced = setOf(WordModule.SENSES, WordModule.DERIVATIVES)
        assertTrue(runCatching { engine.word(provider, article, 0, token, forced) {} }.isFailure)
        engine.word(provider, article, 0, token, forced) {}
        assertEquals(2, calls.getValue(QueryModule.SENSES).get()); assertEquals(3, calls.getValue(QueryModule.DERIVATIVES).get())
    } }

    @Test fun oneMissingAccentDoesNotSendSentenceOrReplaceExistingIpa() = isolated("accent") { store, content, _ -> runBlocking {
        val article = Article("fixture", "test", "Fixture", listOf("The bank opened.")); val token = Content.tokens(article.paragraphs[0]).first()
        store.putAnalysis(Content.sha256("v3|word-pronunciation|the"), "word-pronunciation", """{"phonetics":{"uk":"/ðə/"}}""")
        var checked = false
        val engine = AnalysisEngine(content, store, AnalysisTransport { _, prompt, input, _, _ ->
            if (prompt.contains("IPA/")) { checked = true; assertEquals("The", input); assertTrue(prompt.contains("\"us\"")); assertFalse(prompt.contains("\"uk\"")); """{"us":"/ði/"}""" }
            else fixture(module(prompt))
        })
        val result = engine.word(provider, article, 0, token) {}
        assertTrue(checked); assertEquals("/ðə/", result.getJSONObject("phonetics").getString("uk"))
        assertEquals("/ði/", result.getJSONObject("phonetics").getString("us"))
    } }

    @Test fun zipRoundTripSupplementsCacheButNeverCopiesPrivateState() = isolated("zip") { source, content, learning ->
        val article = content.articles.first(); val text = article.paragraphs.first(); val token = Content.tokens(text).first()
        val engine = AnalysisEngine(content, source); val sentence = Content.sentenceAt(text, token.start)
        source.putAnalysis(engine.occurrenceKey(sentence, token), "word-context", """{"context_sense":{"zh":"用户原义"}}""")
        source.putAnalysis(Content.sha256("v4|sentence|${sentence.text}"), "sentence", """{"translation_zh":"原句译文","clauses":[],"glosses":[],"private_secret":"not-exported"}""")
        learning.addWord("research"); learning.selfGrade("research", true); source.savePlace(article.id, 8, 15)
        source.advanceSentenceBookmark(article.id, BookmarkPlace(3, 10)); source.recordLookup(article.id, "word", "private-event")
        val bytes = ByteArrayOutputStream().also { AnalysisArchive.export(content, engine, provider, listOf(article), it) }.toByteArray()
        val bundle = AnalysisArchive.read(ByteArrayInputStream(bytes))
        assertEquals(1, bundle.articles.length()); assertFalse(bundle.articles.toString().contains("private_secret")); assertFalse(bundle.articles.toString().contains("private-event"))
        isolated("zip-target") { target, targetContent, targetLearning ->
            val targetEngine = AnalysisEngine(targetContent, target)
            target.putAnalysis(targetEngine.occurrenceKey(sentence, token), "word-context", """{"context_sense":{"zh":"本机已有义"}}""")
            AnalysisArchive.import(bundle, targetContent, target)
            assertEquals("本机已有义", targetEngine.cachedWord(provider, text, token)!!.getJSONObject("context_sense").getString("zh"))
            assertEquals("原句译文", targetEngine.cachedSentence(provider, sentence.text)!!.getString("translation_zh"))
            assertTrue(targetLearning.studyWords().isEmpty()); assertTrue(targetLearning.heatmap().isEmpty())
            assertNull(target.getPlace()); assertEquals(0, target.countLookups(article.id, "word")); assertEquals(BookmarkPlace(), target.bookmarkPlace(article.id))
            AnalysisArchive.import(bundle, targetContent, target); assertEquals(48, targetContent.articles.size)
        }
        val bad = ByteArrayOutputStream(); ZipOutputStream(bad).use { zip -> zip.putNextEntry(ZipEntry("../escape")); zip.write("{}".toByteArray()) }
        assertTrue(runCatching { AnalysisArchive.read(ByteArrayInputStream(bad.toByteArray())) }.isFailure)
        val corrupt = ByteArrayOutputStream(); ZipOutputStream(corrupt).use { zip ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) { val entry = input.nextEntry ?: break; zip.putNextEntry(ZipEntry(entry.name)); val data = input.readBytes()
                    zip.write(if (entry.name == "analyses.json") "{}".toByteArray() else data); zip.closeEntry() }
            }
        }
        assertTrue(runCatching { AnalysisArchive.read(ByteArrayInputStream(corrupt.toByteArray())) }.isFailure)
    }

    @Test fun bankManagementKeepsLearnedWordsAndDeletedSourceMeanings() = isolated("banks") { _, content, learning ->
        learning.commitImport(ContentImport.parseText("bank.csv", "word,translation\nresearch,n. 特殊研究义\nbank,n. 银行", true), "Custom")
        val bank = content.banks.last(); learning.addWord("research"); learning.selfGrade("research", true)
        learning.renameBank(bank.id, "Renamed"); assertEquals("Renamed", content.banks.last().name)
        val csv = WordBankCsv.encode(content.banks.last()); assertEquals(2, ContentImport.parseText("export.csv", csv, true).words.size)
        learning.deleteBank(bank.id); assertEquals(1, learning.studyWord("research")!!.memory.reviews)
        assertTrue(learning.meanings("research").any { it.text.contains("特殊研究义") })
        assertTrue(content.banks.any { it.id == "general" && it.words.size == 10000 })
        assertTrue(runCatching { learning.deleteBank("general") }.isFailure)
    }

    @Test fun rc5SchemaUpgradePreservesLearningAndReading() {
        val name = "rc6-schema.db"; context.deleteDatabase(name)
        UserStore(context, name).use { store ->
            store.savePlace("n02", 3, 18); store.putAnalysis("keep", "word", "{}"); store.advanceSentenceBookmark("n02", BookmarkPlace(4, 19))
            val learning = LearningStore(store, Content(context)); learning.addWord("research"); learning.selfGrade("research", false)
            store.writableDatabase.execSQL("DROP TABLE analysis_seed_install"); store.writableDatabase.execSQL("DROP TABLE retained_bank_meaning")
            store.writableDatabase.version = 4
        }
        UserStore(context, name).use { store ->
            assertEquals(5, store.readableDatabase.version); assertEquals(ReadingPlace("n02", 3, 18), store.getPlace())
            assertEquals(BookmarkPlace(4, 19), store.bookmarkPlace("n02")); assertNotNull(store.getAnalysis("keep"))
            assertEquals(1, LearningStore(store, Content(context)).studyWord("research")!!.memory.reviews); assertFalse(store.hasSeed("not-installed"))
        }
        context.deleteDatabase(name)
    }

    @Test fun presetCoversEveryNativeSentenceAndTokenAndAddsNoLearningEvents() = isolated("preset") { store, content, learning ->
        val bundle = context.assets.open("first_two_analyses.zip").use(AnalysisArchive::read)
        assertEquals(2, bundle.articles.length()); assertEquals(2582, bundle.wordCount); assertEquals(120, bundle.sentenceCount)
        val first = content.articles.first(); val token = Content.tokens(first.paragraphs.first()).first()
        val engine = AnalysisEngine(content, store, AnalysisTransport { _, _, _, _, _ -> error("Preset must be offline") })
        val sentence = Content.sentenceAt(first.paragraphs.first(), token.start)
        store.putAnalysis(engine.occurrenceKey(sentence, token), "word-context", """{"context_sense":{"zh":"用户保留译文"}}""")
        AnalysisArchive.installPreset(context, content, store)
        AnalysisArchive.installPreset(context, content, store)
        var words = 0; var sentences = 0
        content.articles.take(2).forEach { article ->
            (listOf(article.title) + article.paragraphs).forEach { paragraph ->
                Content.tokens(paragraph).forEach { word -> assertTrue("${article.id}: ${word.text}", engine.wordComplete(provider, paragraph, word)); words++ }
                Content.sentences(paragraph).forEach { text -> assertTrue("${article.id}: ${text.text}", engine.sentenceComplete(provider, text.text)); sentences++ }
            }
        }
        assertEquals(2582, words); assertEquals(120, sentences)
        assertEquals("用户保留译文", engine.cachedWord(provider, first.paragraphs.first(), token)!!.getJSONObject("context_sense").getString("zh"))
        assertTrue(learning.studyWords().isEmpty()); assertTrue(learning.heatmap().isEmpty())
        assertEquals(0, store.countLookups(first.id, "word")); assertNull(store.getPlace())
    }
}
