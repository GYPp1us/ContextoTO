package site.arcol.contextoto

import org.json.JSONArray
import org.json.JSONObject

enum class WordModule(val field: String, val label: String) {
    IDENTITY("lexical_identity", "原型"),
    CONTEXT("context_sense", "本句释义"),
    SENSES("common_senses", "常见义项"),
    DERIVATIVES("derivatives", "派生词"),
    PRONUNCIATION("phonetics", "音标")
}

/** Merge additions into an existing cache record; a partial reply never erases older modules. */
internal fun mergeAnalysisPayload(existing: JSONObject?, incoming: JSONObject): JSONObject {
    val merged = JSONObject(existing?.toString() ?: "{}")
    incoming.keys().forEach { key ->
        if (!incoming.isNull(key)) {
            val value = incoming.get(key)
            merged.put(key, if (value is JSONObject)
                mergeAnalysisPayload(merged.optJSONObject(key), value) else value)
        }
    }
    return merged
}

internal fun missingWordModules(context: JSONObject?, common: JSONObject?, pronunciation: JSONObject?): Set<WordModule> =
    buildSet {
        if (context?.optJSONObject("context_sense")?.optString("zh").isNullOrBlank()) add(WordModule.CONTEXT)
        if (common?.optJSONArray("common_senses") == null) add(WordModule.SENSES)
        if (common?.optJSONArray("derivatives") == null) add(WordModule.DERIVATIVES)
        val phonetics = pronunciation?.optJSONObject("phonetics")
        if (phonetics == null || phonetics.optString("uk").isBlank() || phonetics.optString("us").isBlank())
            add(WordModule.PRONUNCIATION)
    }

internal fun assembleWordModules(target: String, lemma: String, context: JSONObject?, common: JSONObject?,
                                 pronunciation: JSONObject?): JSONObject =
    mergeAnalysisPayload(mergeAnalysisPayload(context, common ?: JSONObject()), pronunciation ?: JSONObject()).apply {
        put("target", target)
        put("lemma", lemma)
        put("_missing_modules", JSONArray(missingWordModules(context, common, pronunciation).map { it.label }))
    }

internal fun wordModuleInstruction(target: String, lemma: String, missing: Set<WordModule>): String = buildString {
    require(missing.size == 1) { "各模块必须分开调用" }
    append(moduleInstruction(when (missing.single()) {
        WordModule.IDENTITY -> QueryModule.IDENTITY
        WordModule.CONTEXT -> QueryModule.CONTEXT
        WordModule.SENSES -> QueryModule.SENSES
        WordModule.DERIVATIVES -> QueryModule.DERIVATIVES
        WordModule.PRONUNCIATION -> QueryModule.PRONUNCIATION
    }))
}

internal fun queryPercent(progress: QueryProgress): Int = progress.estimatedPercent ?: when (progress.phase) {
    QueryPhase.CONTEXT -> 5
    QueryPhase.FIRST -> (15 + 30 * progress.approximateReasoningTokens.toFloat() /
        (progress.approximateReasoningTokens + 1500)).toInt()
    QueryPhase.STREAM -> (55 + 40 * progress.receivedChars.toFloat() / (progress.receivedChars + 1800)).toInt()
    QueryPhase.COMPLETE -> 100
}.coerceIn(0, 100)
