package site.arcol.contextoto

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
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
data class QueryProgress(val phase: QueryPhase, val approximateReasoningTokens: Int = 0, val receivedChars: Int = 0,
                         val exactReasoningTokens: Int? = null, val estimatedPercent: Int? = null, val detail: String? = null)

/** Parent progress is the aggregate of independent modules, not whichever stream spoke last. */
internal class ModuleProgress(names: List<String>, private val report: (QueryProgress) -> Unit) {
    private val values = names.associateWith { QueryProgress(QueryPhase.CONTEXT, estimatedPercent = 0) }.toMutableMap()
    @Synchronized fun update(name: String, progress: QueryProgress) {
        val old = values.getValue(name)
        values[name] = progress.copy(estimatedPercent = maxOf(queryPercent(old), queryPercent(progress)),
            approximateReasoningTokens = maxOf(old.approximateReasoningTokens, progress.approximateReasoningTokens, progress.exactReasoningTokens ?: 0),
            receivedChars = maxOf(old.receivedChars, progress.receivedChars),
            exactReasoningTokens = progress.exactReasoningTokens ?: old.exactReasoningTokens)
        val done = values.values.count { queryPercent(it) == 100 }
        val pending = values.values.filter { queryPercent(it) < 100 }
        val phase = when { pending.isEmpty() -> QueryPhase.COMPLETE
            pending.any { it.phase == QueryPhase.STREAM } -> QueryPhase.STREAM
            pending.any { it.phase == QueryPhase.FIRST } -> QueryPhase.FIRST
            else -> QueryPhase.CONTEXT }
        report(QueryProgress(phase,
            values.values.sumOf { it.approximateReasoningTokens }, values.values.sumOf { it.receivedChars },
            if (values.values.all { it.exactReasoningTokens != null }) values.values.sumOf { it.exactReasoningTokens!! } else null,
            values.values.sumOf(::queryPercent) / values.size,
            "$done / ${values.size} 模块已写入 · $name"))
    }
    fun callback(name: String): (QueryProgress) -> Unit = { update(name, it) }
    fun done(name: String) = update(name, QueryProgress(QueryPhase.COMPLETE, estimatedPercent = 100))
}

fun interface AnalysisTransport {
    suspend fun generate(provider: Provider, system: String, user: String, effort: String,
                         onProgress: (QueryProgress) -> Unit): String
}

class AnalysisEngine(private val content: Content, private val store: UserStore,
                     private val transport: AnalysisTransport? = null,
                     private val legacyProviders: () -> List<Provider> = { emptyList() }) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val retained = RetainedQueries()
    private val permits = Semaphore(4)
    private val regenerated = ConcurrentHashMap<String, MutableSet<String>>()
    val tasks = retained.tasks
    private val revision = MutableStateFlow(0)
    val cacheRevision = revision.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS).callTimeout(480, TimeUnit.SECONDS).build()
    // Keep the existing namespace: adding a module must not invalidate already paid-for data.
    private val promptVersion = "v2"
    private val learning = LearningStore(store, content)
    private fun usable(result: JSONObject?, output: String): Boolean = when (output) {
        "phonetics" -> result?.optJSONObject(output)?.let { it.optString("uk").isNotBlank() && it.optString("us").isNotBlank() } == true
        "context_sense" -> !result?.optJSONObject(output)?.optString("zh").isNullOrBlank()
        else -> result?.has(output) == true
    }

    private suspend fun field(provider: Provider, key: String, kind: String, output: String, module: QueryModule,
                              input: String, article: String, label: String, silent: Boolean, force: Boolean = false,
                              lemma: String = "", regenGroup: String = "", report: (QueryProgress) -> Unit): JSONObject {
        val effectiveForce = force && (regenGroup.isBlank() || regenerated[regenGroup]?.contains("$key|$output") != true)
        if (!effectiveForce) parsedAnalysis(key)?.takeIf { usable(it, output) }?.let {
            report(QueryProgress(QueryPhase.COMPLETE, estimatedPercent = 100)); return it
        }
        return retained.await("$key|$output${if (effectiveForce) "|regen" else ""}", article, "$label · ${module.label}", silent, report) { update ->
            locks.getOrPut("$key|$output") { Mutex() }.withLock {
                if (!effectiveForce) parsedAnalysis(key)?.takeIf { usable(it, output) }?.let {
                    update(QueryProgress(QueryPhase.COMPLETE, estimatedPercent = 100)); return@withLock it
                }
                val oldIpa = if (module == QueryModule.PRONUNCIATION && !effectiveForce) parsedAnalysis(key)?.optJSONObject("phonetics") else null
                val accents = listOf("uk", "us").filter { oldIpa?.optString(it).isNullOrBlank() }
                val instruction = if (module == QueryModule.PRONUNCIATION && accents.size == 1)
                    "输入是一个英文词的实际拼写。仅返回 JSON {\"${accents.single()}\":\"/${if (accents.single() == "uk") "英式" else "美式"}IPA/\"}，不能返回其他口音或字段，不能使用词元发音代替实际词形。"
                else module.instruction
                val reply = permits.withPermit { request(provider, moduleInstruction(module, instruction), input, module.effort) {
                    update(it.copy(estimatedPercent = queryPercent(it).coerceAtMost(97)))
                } }
                val source = if (module == QueryModule.CONTEXT) JSONObject(input).getString("sentence") else input
                val raw = JSONObject(reply.text)
                val decodedInput = if (oldIpa != null) {
                    val supplied = raw.optJSONObject("phonetics") ?: raw
                    val additions = JSONObject(); accents.forEach { additions.put(it, supplied.optString(it)) }
                    mergeAnalysisPayload(oldIpa, additions)
                } else raw
                val result = decodeModule(module, decodedInput, source, lemma)
                if (module == QueryModule.DERIVATIVES) {
                    val values = result.getJSONArray("derivatives")
                    result.put("derivatives", JSONArray((0 until values.length()).map(values::getJSONObject)
                        .filter { content.knownWord(it.getString("word")) }))
                }
                store.mergeAnalyses(listOf(AnalysisRecord(key, kind, result)))
                if (force && regenGroup.isNotBlank()) regenerated[regenGroup]?.add("$key|$output")
                revision.update { it + 1 }
                update(QueryProgress(QueryPhase.COMPLETE, exactReasoningTokens = reply.exactReasoningTokens,
                    receivedChars = reply.text.length, estimatedPercent = 100))
                parsedAnalysis(key)!!
            }
        }
    }

    internal fun identityKey(sentence: Sentence, token: Token) = Content.sha256("v5|word-identity|${token.text.lowercase()}|${sentence.text}|${token.start - sentence.start}")
    internal fun occurrenceKey(sentence: Sentence, token: Token) = Content.sha256("v5|word-context|${token.text.lowercase()}|${sentence.text}|${token.start - sentence.start}")
    private fun identity(sentence: Sentence, token: Token): JSONObject? = parsedAnalysis(identityKey(sentence, token))
        ?: content.localIdentity(token.text)

    private suspend fun resolveIdentity(provider: Provider, sentence: Sentence, token: Token, article: String, silent: Boolean,
                                        force: Boolean = false, regenGroup: String = "", report: (QueryProgress) -> Unit): JSONObject {
        if (!force) identity(sentence, token)?.let { report(QueryProgress(QueryPhase.COMPLETE, estimatedPercent = 100)); return it }
        val input = JSONObject().put("word", token.text).put("sentence", sentence.text).put("start", token.start - sentence.start).toString()
        val key = identityKey(sentence, token)
        return field(provider, key, "word-identity", "lemma", QueryModule.IDENTITY, input, article, token.text, silent, force, regenGroup = regenGroup, report = report)
    }

    private suspend fun contextOnly(provider: Provider, sentence: Sentence, token: Token, article: String, silent: Boolean,
                                    force: Boolean = false, regenGroup: String = "", report: (QueryProgress) -> Unit): JSONObject {
        val key = occurrenceKey(sentence, token)
        val input = JSONObject().put("sentence", sentence.text).put("word", token.text).put("start", token.start - sentence.start).toString()
        return field(provider, key, "word-context", "context_sense", QueryModule.CONTEXT, input, article, token.text, silent, force, regenGroup = regenGroup, report = report)
    }

    private suspend fun parallel(jobs: List<suspend () -> Unit>) {
        val failures = supervisorScope { jobs.map { job -> async {
            try { job(); null } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { error.message ?: "模块查询失败" }
        } }.awaitAll().filterNotNull() }
        require(failures.isEmpty()) { "已保留成功模块；${failures.distinct().joinToString("；")}" }
    }

    private fun cacheId(kind: String, provider: Provider, source: String): String =
        Content.sha256("$promptVersion|$kind|${provider.baseUrl}|${provider.model}|$source")

    private fun parsedAnalysis(key: String): JSONObject? = store.getAnalysis(key)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun sentenceKey(sentence: String) = Content.sha256("v4|sentence|$sentence")
    fun cachedSentence(provider: Provider, sentence: String): JSONObject? {
        val shared = sentenceKey(sentence)
        parsedAnalysis(shared)?.let { return it }
        // Old hashes remain recoverable; switching a service does not create another semantic cache.
        val sources = (listOf(provider) + legacyProviders()).distinctBy { it.baseUrl to it.model }
        val recovered = sources.mapNotNull { source ->
            val key = cacheId("sentence", source, sentence)
            parsedAnalysis(key)?.takeIf { it.optString("translation_zh").isNotBlank() }?.let { it to store.analysisTime(key) }
        }.maxByOrNull { it.second }?.first ?: return null
        val valid = JSONObject(recovered.toString()).put("clauses", validatedRanges(recovered.optJSONArray("clauses"), sentence))
            .put("glosses", validatedRanges(recovered.optJSONArray("glosses"), sentence))
        store.putAnalysis(shared, "sentence", valid.toString())
        return valid
    }

    fun sentenceComplete(provider: Provider, text: String): Boolean = cachedSentence(provider, text)?.let {
        it.optString("translation_zh").isNotBlank() && it.optJSONArray("clauses") != null && it.optJSONArray("glosses") != null
    } == true

    fun notifyImportedCaches() { revision.update { it + 1 } }

    /** Export never generates queries, seeds dictionary modules, or writes lookup/learning events. */
    internal fun archiveWord(provider: Provider, paragraph: String, token: Token): JSONObject? {
        val sentence = Content.sentenceAt(paragraph, token.start)
        val info = identity(sentence, token)
        val lemma = info?.optString("lemma") ?: content.lemma(token.text)
        var context = parsedAnalysis(occurrenceKey(sentence, token)) ?: parsedAnalysis(contextKey(lemma, sentence, token.start))
        var common = parsedAnalysis(wordCacheId("word-common", lemma))
        var pronunciation = parsedAnalysis(wordCacheId("word-pronunciation", token.text.lowercase()))
        (listOf(provider) + legacyProviders()).forEach { old ->
            val combined = parsedAnalysis(cacheId("word-context", old, "$lemma|$paragraph|${token.start}"))
            if (context == null) context = combined ?: parsedAnalysis(cacheId("word-context-module", old, "$lemma|${sentence.text}|${token.start - sentence.start}"))
            if (common == null) common = combined ?: parsedAnalysis(cacheId("word-common", old, lemma))
            if (pronunciation == null) pronunciation = parsedAnalysis(cacheId("word-pronunciation", old, token.text.lowercase()))
        }
        if (context == null && common == null && pronunciation == null) return null
        val assembled = assembleWordModules(token.text, lemma, context, common, pronunciation).apply {
            remove("_missing_modules"); info?.let { put("lexical_identity", it).put("form", it.optString("form")) }
        }
        return JSONObject().apply {
            listOf("target", "lemma", "form").forEach { if (assembled.has(it)) put(it, assembled.get(it)) }
            mapOf("lexical_identity" to listOf("lemma", "form", "part_of_speech"),
                "context_sense" to listOf("zh", "part_of_speech", "evidence"), "phonetics" to listOf("uk", "us")).forEach { (field, names) ->
                assembled.optJSONObject(field)?.let { value -> put(field, JSONObject().apply { names.forEach { if (value.has(it)) put(it, value.get(it)) } }) }
            }
            mapOf("common_senses" to listOf("zh", "part_of_speech"), "derivatives" to listOf("word", "relation", "zh")).forEach { (field, names) ->
                assembled.optJSONArray(field)?.let { array -> put(field, JSONArray((0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { value -> JSONObject().apply { names.forEach { if (value.has(it)) put(it, value.get(it)) } } }
                })) }
            }
        }
    }

    fun cachedLexeme(word: String): JSONObject {
        val commonKey = wordCacheId("word-common", word)
        var common = parsedAnalysis(commonKey)
        if (common == null) legacyProviders().forEach { source ->
            parsedAnalysis(cacheId("word-common", source, word))?.let { recovered ->
                store.mergeAnalyses(listOf(AnalysisRecord(commonKey, "word-common", recovered))); common = recovered
            }
        }
        var pronunciation = parsedAnalysis(wordCacheId("word-pronunciation", word))
        legacyProviders().forEach { source ->
            parsedAnalysis(cacheId("word-pronunciation", source, word))?.optJSONObject("phonetics")?.let { old ->
                val additions = JSONObject()
                listOf("uk", "us").forEach { accent ->
                    if (pronunciation?.optJSONObject("phonetics")?.optString(accent).isNullOrBlank() && old.optString(accent).isNotBlank()) additions.put(accent, old.getString(accent))
                }
                if (additions.length() > 0) {
                    val incoming = JSONObject().put("phonetics", additions)
                    store.mergeAnalyses(listOf(AnalysisRecord(wordCacheId("word-pronunciation", word), "word-pronunciation", incoming)))
                    pronunciation = mergeAnalysisPayload(pronunciation, incoming)
                }
            }
        }
        if (pronunciation == null) content.banks.firstNotNullOfOrNull { it.words[word]?.takeIf { lex -> lex.ipaUk.isNotBlank() || lex.ipaUs.isNotBlank() } }?.let {
            pronunciation = JSONObject().put("phonetics", JSONObject().put("uk", it.ipaUk).put("us", it.ipaUs))
            store.mergeAnalyses(listOf(AnalysisRecord(wordCacheId("word-pronunciation", word), "word-pronunciation", pronunciation!!)))
        }
        return assembleWordModules(word, word, null, common, pronunciation)
    }

    suspend fun lexeme(provider: Provider, word: String, onProgress: (QueryProgress) -> Unit): JSONObject {
        val key = wordCacheId("word-common", word)
        return retained.await("$key|study", "study", word, false, onProgress) { report ->
            cachedLexeme(word) // Recover legacy data before checking independent fields.
            val progress = ModuleProgress(listOf("常见义项", "派生词", "音标"), report)
            parallel(listOf(
                { field(provider, key, "word-common", "common_senses", QueryModule.SENSES, word, "study", word, false, lemma = word, report = progress.callback("常见义项")); Unit },
                { field(provider, key, "word-common", "derivatives", QueryModule.DERIVATIVES, word, "study", word, false, lemma = word, report = progress.callback("派生词")); Unit },
                { field(provider, wordCacheId("word-pronunciation", word), "word-pronunciation", "phonetics", QueryModule.PRONUNCIATION, word, "study", word, false, report = progress.callback("音标")); Unit }
            ))
            cachedLexeme(word)
        }
    }

    private data class WordSnapshot(val context: JSONObject?, val common: JSONObject?, val pronunciation: JSONObject?,
                                    val contextKey: String, val commonKey: String, val pronunciationKey: String, val identity: JSONObject?) {
        val missing get() = missingWordModules(context, common, pronunciation) + if (identity == null) setOf(WordModule.IDENTITY) else emptySet()
    }

    private fun contextKey(lemma: String, sentence: Sentence, tokenStart: Int): String =
        wordCacheId("word-context-module", "$lemma|${sentence.text}|${tokenStart - sentence.start}")

    private fun wordCacheId(kind: String, source: String): String = Content.sha256("v3|$kind|$source")

    private fun wordSnapshot(provider: Provider, paragraph: String, token: Token): WordSnapshot {
        val sentence = Content.sentenceAt(paragraph, token.start)
        val identityInfo = identity(sentence, token)
        val lemma = identityInfo?.optString("lemma") ?: content.lemma(token.text)
        val contextualKey = occurrenceKey(sentence, token)
        val commonKey = wordCacheId("word-common", lemma)
        val surface = token.text.lowercase(java.util.Locale.US)
        val pronunciationKey = wordCacheId("word-pronunciation", surface)
        var contextJson = parsedAnalysis(contextualKey) ?: listOf(lemma, content.lemma(token.text)).distinct()
            .firstNotNullOfOrNull { parsedAnalysis(contextKey(it, sentence, token.start)) }
        if (contextJson != null && parsedAnalysis(contextualKey) == null)
            store.mergeAnalyses(listOf(AnalysisRecord(contextualKey, "word-context", contextJson)))
        var common = parsedAnalysis(commonKey)
        var pronunciation = parsedAnalysis(pronunciationKey)
        if (missingWordModules(contextJson, common, pronunciation).isEmpty())
            return WordSnapshot(contextJson, common, pronunciation, contextualKey, commonKey, pronunciationKey, identityInfo)
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
            parsedAnalysis(cacheId("word-pronunciation", source, surface))?.optJSONObject("phonetics")?.let { old ->
                val additions = JSONObject()
                listOf("uk", "us").forEach { accent ->
                    if (pronunciation?.optJSONObject("phonetics")?.optString(accent).isNullOrBlank() && old.optString(accent).isNotBlank()) additions.put(accent, old.getString(accent))
                }
                if (additions.length() > 0) {
                    val incoming = JSONObject().put("phonetics", additions)
                    store.mergeAnalyses(listOf(AnalysisRecord(pronunciationKey, "word-pronunciation", incoming)))
                    pronunciation = mergeAnalysisPayload(pronunciation, incoming)
                }
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
        content.lexicon[surface]?.let { local ->
            val additions = JSONObject()
            listOf("uk" to local.ipaUk, "us" to local.ipaUs).forEach { (accent, ipa) ->
                if (ipa.isNotBlank() && pronunciation?.optJSONObject("phonetics")?.optString(accent).isNullOrBlank()) additions.put(accent, ipa)
            }
            if (additions.length() > 0) {
                val incoming = JSONObject().put("phonetics", additions)
                store.mergeAnalyses(listOf(AnalysisRecord(pronunciationKey, "word-pronunciation", incoming)))
                pronunciation = mergeAnalysisPayload(pronunciation, incoming)
            }
        }
        return WordSnapshot(contextJson, common, pronunciation, contextualKey, commonKey, pronunciationKey, identityInfo)
    }

    fun cachedWord(provider: Provider, paragraph: String, token: Token): JSONObject? {
        val snapshot = wordSnapshot(provider, paragraph, token)
        if (snapshot.context == null && snapshot.common == null && snapshot.pronunciation == null) return null
        val result = assembleWordModules(token.text, snapshot.identity?.optString("lemma") ?: content.lemma(token.text), snapshot.context, snapshot.common, snapshot.pronunciation)
        result.put("_missing_modules", JSONArray(snapshot.missing.map { it.label }))
        snapshot.identity?.let { info ->
            result.put("lexical_identity", info).put("form", info.optString("form"))
            result.optJSONObject("context_sense")?.let { sense -> if (sense.optString("part_of_speech").isBlank()) sense.put("part_of_speech", info.optString("part_of_speech")) }
        }
        return result
    }

    fun wordComplete(provider: Provider, paragraph: String, token: Token): Boolean = wordSnapshot(provider, paragraph, token).missing.isEmpty()

    suspend fun sentence(
        provider: Provider, article: Article, sentence: Sentence, silent: Boolean = false, forceModules: Set<String> = emptySet(),
        onProgress: (QueryProgress) -> Unit
    ): JSONObject {
        val text = sentence.text
        val key = sentenceKey(text)
        val existing = cachedSentence(provider, text)
        val missing = setOf("translation_zh", "clauses", "glosses").filter {
            if (it == "translation_zh") existing?.optString(it).isNullOrBlank() else existing?.optJSONArray(it) == null
        }.toSet() + forceModules
        if (missing.isEmpty()) return existing!!
        val group = if (forceModules.isEmpty()) key else "$key|regen|${forceModules.sorted()}"
        return retained.await(group, article.id, sentence.text, silent, onProgress) { report ->
        if (forceModules.isNotEmpty()) regenerated.getOrPut(group) { ConcurrentHashMap.newKeySet() }
        val progress = ModuleProgress(missing.toList(), report)
        val jobs = mutableListOf<suspend () -> Unit>()
        if ("translation_zh" in missing) jobs += {
            field(provider, key, "sentence", "translation_zh", QueryModule.TRANSLATION, text, article.id, "句子", silent,
                "translation_zh" in forceModules, regenGroup = group, report = progress.callback("translation_zh")); Unit
        }
        if ("clauses" in missing) jobs += {
            field(provider, key, "sentence", "clauses", QueryModule.CLAUSES, text, article.id, "句子", silent,
                "clauses" in forceModules, regenGroup = group, report = progress.callback("clauses")); Unit
        }
        if ("glosses" in missing) jobs += {
            val glosses = JSONArray()
            val words = Content.tokens(text).filter { content.lexeme(it.text) != null }.take(8)
            val glossProgress = if (words.isEmpty()) null else ModuleProgress(words.map { it.start.toString() }, progress.callback("glosses"))
            parallel(words.map { token -> suspend {
                wordSnapshot(provider, text, token) // Recover old paid context modules without querying definitions.
                val sentenceTarget = Sentence(text, 0, text.length)
                val localProgress = ModuleProgress(listOf("词形", "句义"), glossProgress!!.callback(token.start.toString()))
                var info: JSONObject? = null; var meaning: JSONObject? = null
                parallel(listOf(
                    { info = resolveIdentity(provider, sentenceTarget, token, article.id, silent, report = localProgress.callback("词形")); Unit },
                    { meaning = contextOnly(provider, sentenceTarget, token, article.id, silent, "glosses" in forceModules,
                        group, localProgress.callback("句义")); Unit }
                ))
                val sense = meaning!!.getJSONObject("context_sense")
                synchronized(glosses) { glosses.put(JSONObject().put("start", token.start).put("end", token.end)
                    .put("quote", token.text).put("brief_zh", sense.getString("zh"))
                    .put("lemma", info!!.getString("lemma")).put("form", info!!.optString("form"))) }
                Unit
            } })
            store.mergeAnalyses(listOf(AnalysisRecord(key, "sentence", JSONObject().put("glosses", glosses))))
            revision.update { it + 1 }
            progress.done("glosses")
        }
        parallel(jobs)
        regenerated.remove(group)
        parsedAnalysis(key)!!
        }
    }

    suspend fun word(
        provider: Provider, article: Article, paragraphIndex: Int, token: Token, forceModules: Set<WordModule> = emptySet(),
        onProgress: (QueryProgress) -> Unit
    ): JSONObject {
        val paragraph = if (paragraphIndex < 0) article.title else article.paragraphs[paragraphIndex]
        if (forceModules.isEmpty() && wordComplete(provider, paragraph, token)) return cachedWord(provider, paragraph, token)!!.also {
            learning.recordAppearance(article, paragraphIndex, token, it)
        }
        val key = wordSnapshot(provider, paragraph, token).contextKey
        val group = "$key|word${if (forceModules.isEmpty()) "" else "|regen|${forceModules.sortedBy { it.name }}"}"
        return retained.await(group, article.id, token.text, false, onProgress) { report ->
            if (forceModules.isNotEmpty()) regenerated.getOrPut(group) { ConcurrentHashMap.newKeySet() }
            val progress = ModuleProgress(WordModule.entries.map { it.label }, report)
            val sentence = Content.sentenceAt(paragraph, token.start)
            supervisorScope {
                val identified = async { resolveIdentity(provider, sentence, token, article.id, false, WordModule.IDENTITY in forceModules, group, progress.callback("原型")) }
                parallel(listOf(
                    { contextOnly(provider, sentence, token, article.id, false, WordModule.CONTEXT in forceModules, group, progress.callback("本句释义")); Unit },
                    { field(provider, wordCacheId("word-pronunciation", token.text.lowercase()), "word-pronunciation", "phonetics", QueryModule.PRONUNCIATION,
                        token.text, article.id, token.text, false, WordModule.PRONUNCIATION in forceModules, regenGroup = group, report = progress.callback("音标")); Unit },
                    {
                        val info = identified.await(); val lemma = info.getString("lemma")
                        parallel(listOf(
                            { field(provider, wordCacheId("word-common", lemma), "word-common", "common_senses", QueryModule.SENSES,
                                lemma, article.id, token.text, false, WordModule.SENSES in forceModules, lemma, group, progress.callback("常见义项")); Unit },
                            { field(provider, wordCacheId("word-common", lemma), "word-common", "derivatives", QueryModule.DERIVATIVES,
                                lemma, article.id, token.text, false, WordModule.DERIVATIVES in forceModules, lemma, group, progress.callback("派生词")); Unit }
                        ))
                    }
                ))
            }
            val result = cachedWord(provider, paragraph, token)!!
            // Keep the old semantic context address available to existing appearance refresh logic.
            val lemma = result.getString("lemma")
            result.optJSONObject("context_sense")?.let { store.mergeAnalyses(listOf(AnalysisRecord(contextKey(lemma, sentence, token.start),
                "word-context", JSONObject().put("context_sense", it)))) }
            if (forceModules.isNotEmpty()) learning.refreshAppearanceMeanings(lemma)
            else learning.recordAppearance(article, paragraphIndex, token, result)
            regenerated.remove(group)
            result
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
                        val parsed = response.use { http -> readCompletion(http, provider.protocol, onProgress).let {
                            require(effort != "off" || ((it.reasoningTokens ?: 0) == 0 && it.reasoningChars == 0)) {
                                "接口未遵守关闭思考，请检查模型兼容性；未写入该模块"
                            }
                            StreamResult(it.text, it.reasoningTokens)
                        } }
                        if (continuation.isActive) continuation.resume(parsed)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

}
