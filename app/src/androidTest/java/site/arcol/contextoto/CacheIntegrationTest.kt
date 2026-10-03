package site.arcol.contextoto

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Provider("DeepSeek 官方", "https://api.deepseek.com", "deepseek-flash", "fixture-not-a-key")
    private fun key(kind: String, source: String) = Content.sha256("v2|$kind|${provider.baseUrl}|${provider.model}|$source")

    @Test fun rc1DatabaseUpgradesWithoutDroppingCacheOrReadingPlace() {
        val name = "rc2-migration-test.db"
        context.deleteDatabase(name)
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL("CREATE TABLE analysis(cache_key TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
        legacy.execSQL("CREATE TABLE lookup_event(article_id TEXT NOT NULL, kind TEXT NOT NULL, item_id TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(article_id,kind,item_id))")
        legacy.execSQL("CREATE TABLE reading_place(singleton INTEGER PRIMARY KEY, article_id TEXT NOT NULL, item_index INTEGER NOT NULL, item_offset INTEGER NOT NULL)")
        legacy.execSQL("INSERT INTO analysis VALUES('old','word-common','{\"common_senses\":[],\"derivatives\":[]}',1)")
        legacy.execSQL("INSERT INTO reading_place VALUES(1,'n01',4,28)")
        legacy.version = 1
        legacy.close()
        UserStore(context, name).use { store ->
            assertNotNull(store.getAnalysis("old"))
            assertEquals(4, store.getPlace()?.item)
            store.mergeAnalyses(listOf(AnalysisRecord("old", "word-common", JSONObject("""{"phonetics":{"uk":"/test/"}}"""))))
            val merged = JSONObject(store.getAnalysis("old")!!)
            assertTrue(merged.has("common_senses"))
            assertTrue(merged.has("derivatives"))
            assertEquals("/test/", merged.getJSONObject("phonetics").getString("uk"))
            assertEquals(5, store.advanceBookmark("n01", 5))
            assertEquals(5, store.advanceBookmark("n01", 2))
        }
        context.deleteDatabase(name)
    }

    @Test fun oldWordModulesAreMergedAndNewOccurrenceOnlyRequestsContext(): Unit = runBlocking {
        val name = "rc2-modules-test.db"
        context.deleteDatabase(name)
        UserStore(context, name).use { store ->
            val content = Content(context)
            val article = content.articles.first()
            val paragraph = article.paragraphs.first()
            val appearances = Content.tokens(paragraph).filter { it.text.equals("the", true) }
            assertTrue(appearances.size >= 2)
            val first = appearances[0]
            val second = appearances[1]
            val lemma = content.lemma(first.text)
            store.putAnalysis(key("word-common", lemma), "word-common",
                """{"common_senses":[{"zh":"这个","part_of_speech":"det."}],"derivatives":[]}""")
            store.putAnalysis(key("word-context", "$lemma|$paragraph|${first.start}"), "word-context",
                """{"context_sense":{"zh":"这次","part_of_speech":"det.","evidence":"The attack"}}""")
            val prompts = mutableListOf<String>()
            val engine = AnalysisEngine(content, store, AnalysisTransport { _, system, _, _, report ->
                prompts += system
                report(QueryProgress(QueryPhase.STREAM, receivedChars = 80))
                if (system.contains("phonetics 对象")) """{"phonetics":{"uk":"/ðə/","us":"/ðə/"}}"""
                else """{"context_sense":{"zh":"这","part_of_speech":"det.","evidence":"the United States"}}"""
            })
            val supplemented = engine.word(provider, article, 0, first) {}
            assertEquals("这次", supplemented.getJSONObject("context_sense").getString("zh"))
            assertTrue(prompts[0].contains("phonetics 对象"))
            assertFalse(prompts[0].contains("context_sense 对象"))
            assertFalse(prompts[0].contains("common_senses 数组"))
            engine.word(provider, article, 0, second) {}
            assertTrue(prompts[1].contains("context_sense 对象"))
            assertFalse(prompts[1].contains("phonetics 对象"))
            assertFalse(prompts[1].contains("common_senses 数组"))
            engine.word(provider, article, 0, first) {}
            assertEquals(2, prompts.size)
            assertTrue(engine.wordComplete(provider, paragraph, first))
        }
        context.deleteDatabase(name)
    }

    @Test fun detachedQueryStillCommitsAllModules(): Unit = runBlocking {
        val name = "rc2-background-test.db"
        context.deleteDatabase(name)
        UserStore(context, name).use { store ->
            val content = Content(context)
            val article = content.articles.first()
            val token = Content.tokens(article.paragraphs.first()).first()
            val began = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val engine = AnalysisEngine(content, store, AnalysisTransport { _, _, _, _, report ->
                report(QueryProgress(QueryPhase.FIRST, 128))
                began.complete(Unit)
                finish.await()
                """{"context_sense":{"zh":"这","part_of_speech":"det."},"common_senses":[{"zh":"这","part_of_speech":"det."}],"derivatives":[],"phonetics":{"uk":"/ðə/","us":"/ðə/"}}"""
            })
            val popup = launch { engine.word(provider, article, 0, token) {} }
            began.await()
            popup.cancelAndJoin()
            assertEquals(QueryStatus.RUNNING, engine.tasks.value.single().status)
            finish.complete(Unit)
            withTimeout(10_000) { engine.tasks.first { it.singleOrNull()?.status == QueryStatus.COMPLETE } }
            assertTrue(engine.wordComplete(provider, article.paragraphs.first(), token))
        }
        context.deleteDatabase(name)
    }

    @Test fun switchingServiceModelAndProtocolReusesLegacyWordModules(): Unit = runBlocking {
        val name = "rc3-provider-cache-test.db"
        context.deleteDatabase(name)
        UserStore(context, name).use { store ->
            val content = Content(context)
            val article = content.articles.first()
            val paragraph = article.paragraphs.first()
            val token = Content.tokens(paragraph).first()
            val lemma = content.lemma(token.text)
            store.putAnalysis(key("word-common", lemma), "word-common",
                """{"common_senses":[{"zh":"这","part_of_speech":"det."}],"derivatives":[]}""")
            store.putAnalysis(key("word-context", "$lemma|$paragraph|${token.start}"), "word-context",
                """{"context_sense":{"zh":"此处这个","part_of_speech":"det."}}""")
            store.putAnalysis(key("word-pronunciation", token.text.lowercase(java.util.Locale.US)), "word-pronunciation",
                """{"phonetics":{"uk":"/ðə/","us":"/ðə/"}}""")
            val engine = AnalysisEngine(content, store, AnalysisTransport { _, _, _, _, _ ->
                error("A cached word must not trigger another provider")
            }, legacyProviders = { listOf(provider) })
            val different = Provider("自定义", "https://another.example/v1", "another-model", "fixture-key", protocol = ApiProtocol.RESPONSES)
            val result = engine.word(different, article, 0, token) {}
            assertEquals("此处这个", result.getJSONObject("context_sense").getString("zh"))
            assertTrue(engine.wordComplete(different, paragraph, token))
            assertNotNull(store.getAnalysis(key("word-common", lemma)))
            val reopened = AnalysisEngine(content, store, AnalysisTransport { _, _, _, _, _ -> error("Should use shared cache") })
            assertTrue(reopened.wordComplete(different.copy(model = "third-model"), paragraph, token))
        }
        context.deleteDatabase(name)
    }

    @Test fun rc2BookmarkMigratesToSentenceAndPreservesOtherData() {
        val name = "rc3-bookmark-migration-test.db"
        context.deleteDatabase(name)
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL("CREATE TABLE analysis(cache_key TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
        legacy.execSQL("CREATE TABLE bookmark(article_id TEXT PRIMARY KEY, paragraph_index INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        legacy.execSQL("INSERT INTO bookmark VALUES('n01',3,123)")
        legacy.execSQL("INSERT INTO analysis VALUES('kept','word-common','{}',1)")
        legacy.version = 2; legacy.close()
        UserStore(context, name).use { store ->
            assertEquals(BookmarkPlace(3, 0), store.bookmarkPlace("n01"))
            assertEquals(BookmarkPlace(3, 90), store.advanceSentenceBookmark("n01", BookmarkPlace(3, 90)))
            assertEquals(BookmarkPlace(3, 90), store.advanceSentenceBookmark("n01", BookmarkPlace(3, 20)))
            assertNotNull(store.getAnalysis("kept"))
        }
        context.deleteDatabase(name)
    }

    /** Explicit opt-in emulator-only visual fixtures; never part of the release APK. */
    @Test fun seedVisualFixturesOnlyWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("seedVisualFixtures") != "true") return
        val content = Content(context)
        val article = content.articles.first()
        UserStore(context).use { store ->
            for (paragraph in listOf(article.title, article.paragraphs.first(), content.articles[1].title, content.articles[1].paragraphs.first())) {
                Content.tokens(paragraph).forEach { token ->
                    val lemma = content.lemma(token.text)
                    store.putAnalysis(key("word-common", lemma), "word-common",
                        """{"common_senses":[{"zh":"视觉测试义项","part_of_speech":"n."}],"derivatives":[{"word":"fixture","relation":"测试","zh":"仅模拟器测试数据"}]}""")
                    store.putAnalysis(key("word-context", "$lemma|$paragraph|${token.start}"), "word-context",
                        """{"context_sense":{"zh":"仅模拟器视觉测试","part_of_speech":"n.","evidence":"Visual fixture"}}""")
                    store.putAnalysis(key("word-pronunciation", token.text.lowercase(java.util.Locale.US)), "word-pronunciation",
                        """{"phonetics":{"uk":"/test/","us":"/test/"}}""")
                }
                Content.sentences(paragraph).forEach { sentence ->
                    val tokens = Content.tokens(sentence.text)
                    val clauses = JSONArray().put(JSONObject().put("start", 0).put("end", sentence.text.length)
                        .put("quote", sentence.text).put("kind", "主句").put("brief_zh", "仅模拟器测试数据"))
                    val glosses = JSONArray(tokens.take(4).map { token -> JSONObject().put("start", token.start)
                        .put("end", token.end).put("quote", token.text).put("brief_zh", "仅模拟器测试") })
                    store.putAnalysis(key("sentence", sentence.text), "sentence", JSONObject()
                        .put("translation_zh", "仅用于模拟器视觉检查的示例译文，不是模型结果。")
                        .put("clauses", clauses).put("glosses", glosses).toString())
                }
            }
        }
        val settings = SecureSettings(context)
        settings.saveModelCatalog("https://api.deepseek.com", settings.savedKey("DeepSeek 官方"),
            listOf(ModelOption("deepseek-flash", "DeepSeek-V4.1-Flash"), ModelOption("deepseek-v4-pro", "DeepSeek-V4-Pro")))
        assertEquals(2, settings.cachedModels("https://api.deepseek.com", settings.savedKey("DeepSeek 官方")).size)
        // Instrumentation may exit immediately: drain the asynchronous preference write before it does.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().commit()
    }

    @Test fun removeVisualFixturesOnlyWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("cleanupVisualFixtures") != "true") return
        UserStore(context).use { store ->
            store.writableDatabase.delete("analysis", "payload LIKE ? OR payload LIKE ?",
                arrayOf("%模拟器%", "%/test/%"))
        }
        val settings = SecureSettings(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("model_catalog_" +
            Content.sha256("https://api.deepseek.com|${settings.savedKey("DeepSeek 官方")}")).commit()
    }
}
