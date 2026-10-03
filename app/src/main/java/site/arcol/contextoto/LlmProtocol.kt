package site.arcol.contextoto

import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

enum class ApiProtocol(val label: String) { CHAT("Chat Completions"), RESPONSES("Responses") }
internal data class CompletionReply(val text: String, val reasoningTokens: Int?)

internal fun completionBody(provider: Provider, system: String, user: String, effort: String): JSONObject {
    val reasoning = provider.official || provider.supportedEfforts.isNotEmpty() ||
        provider.model.contains("deepseek", true) || Regex("(?i)(?:.*/)?(?:gpt-[56].*|o[1-4](?:-.*)?)").matches(provider.model)
    val body = JSONObject().put("model", provider.model).put("stream", true)
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))
    if (provider.protocol == ApiProtocol.RESPONSES) {
        body.put("input", messages).put("store", false)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
        if (reasoning) body.put("reasoning", JSONObject().put("effort", modelEffort(provider, effort)))
    } else {
        body.put("messages", messages).put("stream_options", JSONObject().put("include_usage", true))
            .put("response_format", JSONObject().put("type", "json_object"))
        if (reasoning) body.put("reasoning_effort", modelEffort(provider, effort))
        if (provider.official) body.put("thinking", JSONObject().put("type", "enabled"))
    }
    return body
}

private fun JSONObject.text(field: String): String = if (isNull(field)) "" else optString(field)
internal fun responseOutput(response: JSONObject): String = buildString {
    val items = response.optJSONArray("output") ?: return@buildString
    for (i in 0 until items.length()) {
        val content = items.optJSONObject(i)?.optJSONArray("content") ?: continue
        for (j in 0 until content.length()) content.optJSONObject(j)?.let {
            if (it.optString("type") == "output_text") append(it.text("text"))
            if (it.optString("type") == "refusal") throw IllegalStateException("模型未提供分析，请重试")
        }
    }
}

/** Shared stream accumulator: incomplete/error events never become a successful cache record. */
internal class CompletionStream(private val protocol: ApiProtocol) {
    val output = StringBuilder()
    var reasoningChars = 0; private set
    var reasoningTokens: Int? = null; private set
    var complete = false; private set
    var ended = false; private set
    fun accept(data: String) {
        if (data == "[DONE]") {
            if (protocol == ApiProtocol.CHAT) complete = true
            ended = true
            return
        }
        val json = JSONObject(data)
        if (json.has("error")) throw IllegalStateException("模型响应失败，请重试")
        if (protocol == ApiProtocol.CHAT) {
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            output.append(delta?.text("content").orEmpty())
            reasoningChars += delta?.text("reasoning_content").orEmpty().length
            val finish = choice?.text("finish_reason").orEmpty()
            if (finish in listOf("length", "content_filter")) throw IllegalStateException("模型响应未完成，请重试")
            if (finish.isNotBlank()) complete = true
            val usage = json.optJSONObject("usage")?.optJSONObject("completion_tokens_details")
            if (usage?.has("reasoning_tokens") == true) reasoningTokens = usage.optInt("reasoning_tokens")
        } else when (json.optString("type")) {
            "response.output_text.delta" -> output.append(json.text("delta"))
            "response.output_text.done" -> if (output.isEmpty()) output.append(json.text("text"))
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> reasoningChars += json.text("delta").length
            "response.completed" -> {
                val response = json.optJSONObject("response") ?: JSONObject()
                if (response.optString("status") in listOf("failed", "incomplete", "cancelled"))
                    throw IllegalStateException("模型响应未完成，请重试")
                if (output.isEmpty()) output.append(responseOutput(response))
                val usage = response.optJSONObject("usage")?.optJSONObject("output_tokens_details")
                if (usage?.has("reasoning_tokens") == true) reasoningTokens = usage.optInt("reasoning_tokens")
                complete = true; ended = true
            }
            "response.failed", "response.incomplete", "error" -> throw IllegalStateException("模型响应未完成，请重试")
            "response.refusal.delta", "response.refusal.done" -> throw IllegalStateException("模型未提供分析，请重试")
        }
    }
    fun finish(): CompletionReply {
        require(complete) { "模型连接提前结束，未完成内容不会写入缓存" }
        require(output.isNotBlank()) { "模型没有生成有效内容" }
        return CompletionReply(output.toString(), reasoningTokens)
    }
}

internal fun readCompletion(http: Response, protocol: ApiProtocol, report: (QueryProgress) -> Unit): CompletionReply {
    check(http.isSuccessful) { "模型接口返回 HTTP ${http.code}" }
    val body = http.body ?: throw IllegalStateException("模型没有返回内容")
    if (body.contentType()?.subtype?.contains("json") == true) {
        val json = JSONObject(body.string())
        if (json.has("error") || json.optString("status") in listOf("failed", "incomplete", "cancelled"))
            throw IllegalStateException("模型响应未完成，请重试")
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        if (choice?.text("finish_reason") in listOf("length", "content_filter"))
            throw IllegalStateException("模型响应未完成，请重试")
        val text = if (protocol == ApiProtocol.CHAT) choice?.optJSONObject("message")?.text("content").orEmpty()
            else responseOutput(json)
        require(text.isNotBlank()) { "模型没有生成有效内容" }
        report(QueryProgress(QueryPhase.COMPLETE, receivedChars = text.length))
        return CompletionReply(text, null)
    }
    val stream = CompletionStream(protocol)
    val source = body.source()
    val data = StringBuilder()
    fun flush() {
        if (data.isEmpty()) return
        stream.accept(data.toString().trimEnd())
        data.clear()
        report(QueryProgress(if (stream.output.isEmpty()) QueryPhase.FIRST else QueryPhase.STREAM,
            stream.reasoningChars / 4, stream.output.length, stream.reasoningTokens))
    }
    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: break
        if (line.isBlank()) { flush(); if (stream.ended) break }
        else if (line.startsWith("data:")) data.append(line.removePrefix("data:").trimStart()).append('\n')
    }
    flush()
    val reply = stream.finish()
    report(QueryProgress(QueryPhase.COMPLETE, stream.reasoningChars / 4, reply.text.length, reply.reasoningTokens))
    return reply
}
