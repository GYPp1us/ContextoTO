package site.arcol.contextoto

import org.json.JSONArray
import org.json.JSONObject

enum class WordModule(val field: String, val label: String) {
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
        if (phonetics == null || (phonetics.optString("uk").isBlank() && phonetics.optString("us").isBlank()))
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
    appendLine("你是中文母语者的英语词汇教师。只返回一个严格 JSON 对象，不要 Markdown。")
    appendLine("schema_version=2; type=word_modules; target=$target; lemma=$lemma。")
    appendLine("仅返回以下缺失模块，其余模块已缓存，禁止重复生成：")
    if (WordModule.CONTEXT in missing) appendLine("context_sense 对象：zh（本句最贴切的简短释义）、part_of_speech（英文词性缩写）、evidence（原句短证据）。")
    if (WordModule.SENSES in missing) appendLine("common_senses 数组：最多 5 个词元常见义项，每项 zh、part_of_speech。")
    if (WordModule.DERIVATIVES in missing) appendLine("derivatives 数组：最多 4 个真正派生词，每项 word、relation、zh；没有则返回空数组。")
    if (WordModule.PRONUNCIATION in missing) appendLine("phonetics 对象：uk、us 为目标词当前拼写的英式、美式 IPA 音标，使用 /…/；不是只给词元的发音。")
    append("上下文义不能机械选择词典第一义；不编造派生词。必须完整返回每个要求的模块。")
}

internal fun queryPercent(progress: QueryProgress): Int = when (progress.phase) {
    QueryPhase.CONTEXT -> 5
    QueryPhase.FIRST -> (15 + 30 * progress.approximateReasoningTokens.toFloat() /
        (progress.approximateReasoningTokens + 1500)).toInt()
    QueryPhase.STREAM -> (55 + 40 * progress.receivedChars.toFloat() / (progress.receivedChars + 1800)).toInt()
    QueryPhase.COMPLETE -> 100
}.coerceIn(0, 100)
