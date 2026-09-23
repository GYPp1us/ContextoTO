package site.arcol.contextoto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class QueryPhase { CONTEXT, FIRST, STREAM, COMPLETE }
data class QueryProgress(val phase: QueryPhase, val approximateReasoningTokens: Int = 0, val receivedChars: Int = 0, val exactReasoningTokens: Int? = null)

class AnalysisEngine(private val content: Content, private val store: UserStore) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS).callTimeout(480, TimeUnit.SECONDS).build()
    private val promptVersion = "v2"

    private fun cacheId(kind: String, provider: Provider, source: String): String =
        Content.sha256("$promptVersion|$kind|${provider.baseUrl}|${provider.model}|$source")

    fun cachedSentence(provider: Provider, sentence: String): JSONObject? = store.getAnalysis(
        cacheId("sentence", provider, sentence)
    )?.let(::JSONObject)

    fun cachedWord(provider: Provider, paragraph: String, token: Token): JSONObject? {
        val lemma = content.lemma(token.text)
        val contextualKey = cacheId("word-context", provider, "$lemma|$paragraph|${token.start}")
        val contextJson = store.getAnalysis(contextualKey) ?: return null
        val commonKey = cacheId("word-common", provider, lemma)
        return mergeWord(JSONObject(contextJson), store.getAnalysis(commonKey)?.let(::JSONObject))
    }

    suspend fun sentence(
        provider: Provider, article: Article, sentence: Sentence, onProgress: (QueryProgress) -> Unit
    ): JSONObject {
        val text = sentence.text
        val key = cacheId("sentence", provider, text)
        store.getAnalysis(key)?.let { return JSONObject(it) }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            store.getAnalysis(key)?.let { return@withLock JSONObject(it) }
            onProgress(QueryProgress(QueryPhase.CONTEXT))
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
            val response = request(provider, instruction, "文章：${article.title}\n句子原文：\n$text", "medium", onProgress)
            val result = JSONObject(response.text)
            require(result.optString("translation_zh").isNotBlank()) { "句子结果缺少中文释义" }
            result.put("clauses", validatedRanges(result.optJSONArray("clauses"), text))
            result.put("glosses", validatedRanges(result.optJSONArray("glosses"), text))
            store.putAnalysis(key, "sentence", result.toString())
            result
        }
    }

    suspend fun word(
        provider: Provider, article: Article, paragraphIndex: Int, token: Token,
        onProgress: (QueryProgress) -> Unit
    ): JSONObject {
        val paragraph = article.paragraphs[paragraphIndex]
        val lemma = content.lemma(token.text)
        val contextKey = cacheId("word-context", provider, "$lemma|$paragraph|${token.start}")
        cachedWord(provider, paragraph, token)?.let { return it }
        val lock = locks.getOrPut(contextKey) { Mutex() }
        return lock.withLock {
            cachedWord(provider, paragraph, token)?.let { return@withLock it }
            onProgress(QueryProgress(QueryPhase.CONTEXT))
            val commonKey = cacheId("word-common", provider, lemma)
            val common = store.getAnalysis(commonKey)?.let(::JSONObject)
            val sentence = Content.sentenceAt(paragraph, token.start)
            val lexeme = content.lexeme(token.text)
            val instruction = """
                你是中文母语者的英语词汇教师。只返回一个严格 JSON 对象，不要 Markdown。
                schema_version=1; type=word_analysis; target=${token.text}; lemma=$lemma。
                必须返回 context_sense 对象，含 zh（此处最贴切的简短中文释义）、part_of_speech（英文词性）、evidence（原句中的短证据）。
                ${if (common == null) "同时返回 common_senses 数组（最多 5 个常见义项，每项 zh、part_of_speech），derivatives 数组（最多 4 个真正的派生词，每项 word、relation、zh）。" else "通用义项已经有缓存，不要重复生成；只返回 context_sense。"}
                上下文义与通用义可以不同；不能机械地选择词典第一义。不要编造派生词。
            """.trimIndent()
            val user = "文章：${article.title}\n段落：$paragraph\n目标词：${token.text}（段内索引 ${token.start}-${token.end}）\n所在句：${sentence.text}\n预置词典：${lexeme?.translation.orEmpty()}"
            val response = request(provider, instruction, user, "high", onProgress)
            val parsed = JSONObject(response.text)
            require(parsed.optJSONObject("context_sense")?.optString("zh")?.isNotBlank() == true) { "单词结果缺少本句释义" }
            val context = JSONObject().put("target", token.text).put("lemma", lemma)
                .put("context_sense", parsed.getJSONObject("context_sense"))
            val newCommon = if (common == null) JSONObject().put("common_senses", parsed.optJSONArray("common_senses") ?: JSONArray())
                .put("derivatives", parsed.optJSONArray("derivatives") ?: JSONArray()) else null
            if (newCommon != null) store.putAnalysis(commonKey, "word-common", newCommon.toString())
            store.putAnalysis(contextKey, "word-context", context.toString())
            mergeWord(context, common ?: newCommon)
        }
    }

    private fun mergeWord(context: JSONObject, common: JSONObject?): JSONObject = JSONObject(context.toString()).apply {
        put("common_senses", common?.optJSONArray("common_senses") ?: JSONArray())
        put("derivatives", common?.optJSONArray("derivatives") ?: JSONArray())
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
    ): StreamResult = withContext(Dispatchers.IO) {
        require(provider.key.isNotBlank()) { "请先在设置中填写 API Key" }
        require(provider.baseUrl.startsWith("https://")) { "接口地址必须使用 HTTPS" }
        val body = JSONObject().put("model", provider.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("reasoning_effort", effort)
        if (provider.official) body.put("thinking", JSONObject().put("type", "enabled"))
        val request = Request.Builder().url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${provider.key}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        onProgress(QueryProgress(QueryPhase.FIRST))
        val response = client.newCall(request).execute()
        response.use { http ->
            if (!http.isSuccessful) throw IllegalStateException("模型接口返回 HTTP ${http.code}")
            val source = http.body?.source() ?: throw IllegalStateException("模型没有返回内容")
            val output = StringBuilder()
            var reasoningChars = 0
            var exactTokens: Int? = null
            var lastReport = 0
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                val reasoning = delta?.optString("reasoning_content").orEmpty()
                if (reasoning.isNotBlank()) reasoningChars += reasoning.length
                val added = delta?.optString("content").orEmpty()
                if (added.isNotEmpty()) output.append(added)
                val usage = chunk.optJSONObject("usage")
                if (usage != null) exactTokens = usage.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens")
                if (reasoningChars + output.length - lastReport >= 24 || added.isNotEmpty() && output.length < 24) {
                    lastReport = reasoningChars + output.length
                    onProgress(QueryProgress(if (output.isEmpty()) QueryPhase.FIRST else QueryPhase.STREAM,
                        (reasoningChars / 4.0).toInt(), output.length, exactTokens))
                }
            }
            require(output.isNotBlank()) { "模型没有生成有效内容" }
            onProgress(QueryProgress(QueryPhase.COMPLETE, (reasoningChars / 4.0).toInt(), output.length, exactTokens))
            StreamResult(output.toString(), exactTokens)
        }
    }
}
