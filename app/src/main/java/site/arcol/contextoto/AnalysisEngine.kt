package site.arcol.contextoto

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class QueryPhase { CONTEXT, FIRST, STREAM, COMPLETE }
data class QueryProgress(val phase: QueryPhase, val approximateReasoningTokens: Int = 0, val receivedChars: Int = 0, val exactReasoningTokens: Int? = null)

fun interface AnalysisTransport {
    suspend fun generate(provider: Provider, system: String, user: String, effort: String,
                         onProgress: (QueryProgress) -> Unit): String
}

class AnalysisEngine(private val content: Content, private val store: UserStore,
                     private val transport: AnalysisTransport? = null,
                     private val legacyProviders: () -> List<Provider> = { emptyList() }) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val retained = RetainedQueries()
    val tasks = retained.tasks
    private val revision = MutableStateFlow(0)
    val cacheRevision = revision.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS).callTimeout(480, TimeUnit.SECONDS).build()
    // Keep the existing namespace: adding a module must not invalidate already paid-for data.
    private val promptVersion = "v2"

    private fun cacheId(kind: String, provider: Provider, source: String): String =
        Content.sha256("$promptVersion|$kind|${provider.baseUrl}|${provider.model}|$source")

    private fun parsedAnalysis(key: String): JSONObject? = store.getAnalysis(key)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()
    }

    fun cachedSentence(provider: Provider, sentence: String): JSONObject? = parsedAnalysis(
        cacheId("sentence", provider, sentence)
    )

    private data class WordSnapshot(val context: JSONObject?, val common: JSONObject?, val pronunciation: JSONObject?,
                                    val contextKey: String, val commonKey: String, val pronunciationKey: String) {
        val missing get() = missingWordModules(context, common, pronunciation)
    }

    private fun contextKey(lemma: String, sentence: Sentence, tokenStart: Int): String =
        wordCacheId("word-context-module", "$lemma|${sentence.text}|${tokenStart - sentence.start}")

    private fun wordCacheId(kind: String, source: String): String = Content.sha256("v3|$kind|$source")

    private fun wordSnapshot(provider: Provider, paragraph: String, token: Token): WordSnapshot {
        val lemma = content.lemma(token.text)
        val sentence = Content.sentenceAt(paragraph, token.start)
        val contextualKey = contextKey(lemma, sentence, token.start)
        val commonKey = wordCacheId("word-common", lemma)
        val surface = token.text.lowercase(java.util.Locale.US)
        val pronunciationKey = wordCacheId("word-pronunciation", surface)
        var contextJson = parsedAnalysis(contextualKey)
        var common = parsedAnalysis(commonKey)
        var pronunciation = parsedAnalysis(pronunciationKey)
        if (missingWordModules(contextJson, common, pronunciation).isEmpty())
            return WordSnapshot(contextJson, common, pronunciation, contextualKey, commonKey, pronunciationKey)
        val sources = (listOf(provider) + legacyProviders()).distinctBy { it.baseUrl to it.model }
        // Semantic word modules are independent of the service, model and wire protocol.
        // Old opaque keys are recovered using remembered endpoint/model pairs; originals stay intact.
        sources.forEach { source ->
            val oldCommon = parsedAnalysis(cacheId("word-common", source, lemma))
            val additions = JSONObject()
            listOf("common_senses", "derivatives").forEach { field ->
                if (common?.optJSONArray(field) == null) oldCommon?.optJSONArray(field)?.let { additions.put(field, it) }
            }
            if (additions.length() > 0) {
                store.mergeAnalyses(listOf(AnalysisRecord(commonKey, "word-common", additions)))
                common = mergeAnalysisPayload(common, additions)
            }
            if (pronunciation == null) parsedAnalysis(cacheId("word-pronunciation", source, surface))?.let {
                store.mergeAnalyses(listOf(AnalysisRecord(pronunciationKey, "word-pronunciation", it)))
                pronunciation = it
            }
            if (contextJson == null) parsedAnalysis(cacheId("word-context-module", source,
                "$lemma|${sentence.text}|${token.start - sentence.start}"))?.let {
                store.mergeAnalyses(listOf(AnalysisRecord(contextualKey, "word-context", it)))
                contextJson = it
            }
            if (contextJson == null) {
                // Lazy, non-destructive migration of the rc1 paragraph/offset cache.
                val legacy = parsedAnalysis(cacheId("word-context", source, "$lemma|$paragraph|${token.start}"))
                if (legacy != null) {
                    contextJson = JSONObject().put("context_sense", legacy.optJSONObject("context_sense"))
                    val commonAdditions = JSONObject()
                    listOf("common_senses", "derivatives").forEach { field ->
                        if (common?.optJSONArray(field) == null) legacy.optJSONArray(field)?.let { commonAdditions.put(field, it) }
                    }
                    store.mergeAnalyses(listOf(AnalysisRecord(contextualKey, "word-context", contextJson),
                        AnalysisRecord(commonKey, "word-common", commonAdditions)))
                    common = mergeAnalysisPayload(common, commonAdditions)
                } else {
                    val glosses = cachedSentence(source, sentence.text)?.optJSONArray("glosses")
                    val gloss = (0 until (glosses?.length() ?: 0)).mapNotNull { glosses?.optJSONObject(it) }
                        .firstOrNull { it.optInt("start", -1) == token.start - sentence.start &&
                            it.optInt("end", -1) == token.end - sentence.start && it.optString("brief_zh").isNotBlank() }
                    if (gloss != null) {
                        contextJson = JSONObject().put("context_sense", JSONObject().put("zh", gloss.optString("brief_zh"))
                            .put("part_of_speech", gloss.optString("part_of_speech")).put("evidence", sentence.text))
                        store.mergeAnalyses(listOf(AnalysisRecord(contextualKey, "word-context", contextJson)))
                    }
                }
            }
        }
        return WordSnapshot(contextJson, common, pronunciation, contextualKey, commonKey, pronunciationKey)
    }

    fun cachedWord(provider: Provider, paragraph: String, token: Token): JSONObject? {
        val snapshot = wordSnapshot(provider, paragraph, token)
        if (snapshot.context == null && snapshot.common == null && snapshot.pronunciation == null) return null
        return assembleWordModules(token.text, content.lemma(token.text), snapshot.context, snapshot.common, snapshot.pronunciation)
    }

    fun wordComplete(provider: Provider, paragraph: String, token: Token): Boolean = wordSnapshot(provider, paragraph, token).missing.isEmpty()

    suspend fun sentence(
        provider: Provider, article: Article, sentence: Sentence, silent: Boolean = false,
        onProgress: (QueryProgress) -> Unit
    ): JSONObject {
        val text = sentence.text
        val key = cacheId("sentence", provider, text)
        parsedAnalysis(key)?.let { return it }
        return retained.await(key, article.id, sentence.text, silent, onProgress) { report ->
        val lock = locks.getOrPut(key) { Mutex() }
        lock.withLock {
            parsedAnalysis(key)?.let { return@withLock it }
            report(QueryProgress(QueryPhase.CONTEXT))
            val matched = Content.tokens(text).mapNotNull { token ->
                content.lexeme(token.text)?.let { "${token.text}@${token.start}" }
            }.distinct().joinToString(", ")
            val instruction = """
                你是面向中文母语者的英语阅读教师。只返回一个严格 JSON 对象，不要 Markdown。
                schema_version=1; type=sentence_analysis。
                字段：translation_zh（忠实、自然的整句中文释义）；clauses 数组（每项 start 整数、end 整数、quote 原文子串、kind 从句类型中文、brief_zh 简短说明）；glosses 数组（每项 start、end、quote、brief_zh）。
                start/end 是这一个句子原文的 UTF-16 起止索引，左闭右开。不要编造文本。标出主句和有教学价值的从句，嵌套时允许重叠。
                从句最多列 5 个；glosses 最多列 8 个词，只解释下列命中词库的原文出现，释义必须符合本句：$matched
            """.trimIndent()
            val response = request(provider, instruction, "文章：${article.title}\n句子原文：\n$text", "medium", report)
            val result = JSONObject(response.text)
            require(result.optString("translation_zh").isNotBlank()) { "句子结果缺少中文释义" }
            result.put("clauses", validatedRanges(result.optJSONArray("clauses"), text))
            result.put("glosses", validatedRanges(result.optJSONArray("glosses"), text))
            store.putAnalysis(key, "sentence", result.toString())
            revision.update { it + 1 }
            result
        }
        }
    }

    suspend fun word(
        provider: Provider, article: Article, paragraphIndex: Int, token: Token,
        onProgress: (QueryProgress) -> Unit
    ): JSONObject {
        val paragraph = if (paragraphIndex < 0) article.title else article.paragraphs[paragraphIndex]
        val lemma = content.lemma(token.text)
        if (wordComplete(provider, paragraph, token)) return cachedWord(provider, paragraph, token)!!
        val key = wordSnapshot(provider, paragraph, token).contextKey
        store.recordLookup(article.id, "word", lemma)
        return retained.await(key, article.id, token.text, false, onProgress) { report ->
        // Different occurrences share a lemma lock, preventing concurrent duplicate common-module requests.
        val lock = locks.getOrPut(wordCacheId("word-lexeme-lock", lemma)) { Mutex() }
        lock.withLock {
            val snapshot = wordSnapshot(provider, paragraph, token)
            if (snapshot.missing.isEmpty()) return@withLock cachedWord(provider, paragraph, token)!!
            report(QueryProgress(QueryPhase.CONTEXT))
            val sentence = Content.sentenceAt(paragraph, token.start)
            val lexeme = content.lexeme(token.text)
            val instruction = wordModuleInstruction(token.text, lemma, snapshot.missing)
            val user = "文章：${article.title}\n${if (paragraphIndex < 0) "标题" else "段落"}：$paragraph\n目标词：${token.text}（原文索引 ${token.start}-${token.end}）\n所在句：${sentence.text}\n预置词典：${lexeme?.translation.orEmpty()}"
            val response = request(provider, instruction, user, "high", report)
            val parsed = JSONObject(response.text)
            val additions = mutableListOf<AnalysisRecord>()
            if (WordModule.CONTEXT in snapshot.missing) parsed.optJSONObject("context_sense")?.takeIf {
                it.optString("zh").isNotBlank()
            }?.let { additions += AnalysisRecord(snapshot.contextKey, "word-context", JSONObject().put("context_sense", it)) }
            val common = JSONObject()
            listOf(WordModule.SENSES, WordModule.DERIVATIVES).filter { it in snapshot.missing }.forEach { module ->
                parsed.optJSONArray(module.field)?.let { common.put(module.field, it) }
            }
            if (common.length() > 0) additions += AnalysisRecord(snapshot.commonKey, "word-common", common)
            if (WordModule.PRONUNCIATION in snapshot.missing) parsed.optJSONObject("phonetics")?.takeIf {
                it.optString("uk").isNotBlank() || it.optString("us").isNotBlank()
            }?.let { additions += AnalysisRecord(snapshot.pronunciationKey, "word-pronunciation", JSONObject().put("phonetics", it)) }
            store.mergeAnalyses(additions)
            revision.update { it + 1 }
            val remaining = wordSnapshot(provider, paragraph, token).missing
            require(remaining.isEmpty()) { "已保存有效模块，仍缺少${remaining.joinToString("、") { it.label }}；重试只补缺项。" }
            cachedWord(provider, paragraph, token)!!
        }
        }
    }

    private fun validatedRanges(items: JSONArray?, text: String): JSONArray {
        val valid = JSONArray()
        if (items == null) return valid
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            var start = item.optInt("start", -1)
            var end = item.optInt("end", -1)
            val quote = item.optString("quote")
            if (quote.isBlank()) continue
            if (start < 0 || end > text.length || end <= start || text.substring(start, end) != quote) {
                val first = text.indexOf(quote)
                if (first < 0 || text.indexOf(quote, first + quote.length) >= 0) continue
                start = first; end = first + quote.length
            }
            item.put("start", start); item.put("end", end)
            valid.put(item)
        }
        return valid
    }

    private data class StreamResult(val text: String, val exactReasoningTokens: Int?)

    private suspend fun request(
        provider: Provider, system: String, user: String, effort: String, onProgress: (QueryProgress) -> Unit
    ): StreamResult {
        transport?.let { return StreamResult(it.generate(provider, system, user, effort, onProgress), null) }
        require(provider.key.isNotBlank()) { "请先在设置中填写 API Key" }
        require(provider.baseUrl.startsWith("https://")) { "接口地址必须使用 HTTPS" }
        val body = completionBody(provider, system, user, effort)
        val request = Request.Builder().url(apiUrl(provider.baseUrl,
            if (provider.protocol == ApiProtocol.RESPONSES) "responses" else "chat/completions"))
            .header("Authorization", "Bearer ${provider.key}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        onProgress(QueryProgress(QueryPhase.FIRST))
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val parsed = response.use { http -> readCompletion(http, provider.protocol, onProgress)
                            .let { StreamResult(it.text, it.reasoningTokens) } }
                        if (continuation.isActive) continuation.resume(parsed)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

}
