package site.arcol.contextoto

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LlmProtocolTest {
    private val provider = Provider("自定义", "https://example.com/v1", "gpt-5", "fixture-token")
    private fun http(text: String, type: String) = Response.Builder()
        .request(Request.Builder().url("https://example.com/v1/responses").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(text.toResponseBody(type.toMediaType())).build()
    @Test fun requestSchemasMatchSelectedProtocolAndDoNotPutKeysIntoJson() {
        val chat = completionBody(provider, "Return JSON", "Test", "high")
        assertTrue(chat.has("messages")); assertFalse(chat.has("input"))
        assertEquals("json_object", chat.getJSONObject("response_format").getString("type"))
        val responses = completionBody(provider.copy(protocol = ApiProtocol.RESPONSES), "Return JSON", "Test", "high")
        assertTrue(responses.has("input")); assertFalse(responses.has("messages"))
        assertEquals("json_object", responses.getJSONObject("text").getJSONObject("format").getString("type"))
        assertEquals("high", responses.getJSONObject("reasoning").getString("effort"))
        assertFalse(responses.getBoolean("store"))
        assertFalse(responses.toString().contains("fixture-token"))
        assertFalse(completionBody(provider.copy(model = "gpt-4.1"), "JSON", "Test", "high").has("reasoning_effort"))
    }
    @Test fun responsesStreamCollectsTextAndExactUsage() {
        val stream = CompletionStream(ApiProtocol.RESPONSES)
        stream.accept("""{"type":"response.reasoning_text.delta","delta":"thinking"}""")
        stream.accept("""{"type":"response.output_text.delta","delta":"{\"ok\":"}""")
        stream.accept("""{"type":"response.output_text.delta","delta":"true}"}""")
        stream.accept("""{"type":"response.completed","response":{"status":"completed","usage":{"output_tokens_details":{"reasoning_tokens":28}}}}""")
        assertEquals("{\"ok\":true}", stream.finish().text)
        assertEquals(28, stream.finish().reasoningTokens)
        assertEquals(8, stream.reasoningChars)
    }
    @Test fun completeFallbackOutputIsAcceptedButIncompleteAndErrorAreNot() {
        val stream = CompletionStream(ApiProtocol.RESPONSES)
        stream.accept("""{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"ok\":true}"}]}]}}""")
        assertEquals("{\"ok\":true}", stream.finish().text)
        val interrupted = CompletionStream(ApiProtocol.RESPONSES)
        interrupted.accept("""{"type":"response.output_text.delta","delta":"{}"}""")
        assertTrue(runCatching { interrupted.finish() }.isFailure)
        assertTrue(runCatching { interrupted.accept("""{"type":"response.incomplete"}""") }.isFailure)
        assertTrue(runCatching { interrupted.accept("""{"type":"error","message":"failed"}""") }.isFailure)
    }
    @Test fun chatStreamPreservesCompletionAndRejectsTruncation() {
        val stream = CompletionStream(ApiProtocol.CHAT)
        stream.accept("""{"choices":[{"delta":{"content":"{}"},"finish_reason":null}]}""")
        assertTrue(runCatching { stream.finish() }.isFailure)
        stream.accept("[DONE]")
        assertEquals("{}", stream.finish().text)
        assertTrue(runCatching { CompletionStream(ApiProtocol.CHAT).accept("""{"choices":[{"delta":{"content":"{}"},"finish_reason":"length"}]}""") }.isFailure)
    }
    @Test fun multilineSseEventsAreParsedAndPrematureEofRejected() {
        val events = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\n" +
            "data: \"delta\":\"{}\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        http(events, "text/event-stream").use {
            assertEquals("{}", readCompletion(it, ApiProtocol.RESPONSES) {}.text)
        }
        http(events.substringBefore("event: response.completed"), "text/event-stream").use {
            assertTrue(runCatching { readCompletion(it, ApiProtocol.RESPONSES) {} }.isFailure)
        }
    }
    @Test fun nonStreamingJsonFallbackWorksForBothProtocols() {
        http("""{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}""", "application/json").use {
            assertEquals("{}", readCompletion(it, ApiProtocol.CHAT) {}.text)
        }
        http("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{}"}]}]}""", "application/json").use {
            assertEquals("{}", readCompletion(it, ApiProtocol.RESPONSES) {}.text)
        }
        http("""{"status":"incomplete","output":[]}""", "application/json").use {
            assertTrue(runCatching { readCompletion(it, ApiProtocol.RESPONSES) {} }.isFailure)
        }
    }
}
