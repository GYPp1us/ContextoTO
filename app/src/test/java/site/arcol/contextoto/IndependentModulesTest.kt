package site.arcol.contextoto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IndependentModulesTest {
    @Test fun onlyThreeModulesEnableThinkingAndAllUseMax() {
        assertEquals(setOf(QueryModule.DERIVATIVES, QueryModule.TRANSLATION, QueryModule.CLAUSES), QueryModule.entries.filter { it.effort == "max" }.toSet())
        assertTrue(QueryModule.entries.all { it.effort in setOf("max", "off") })
        val ipa = moduleInstruction(QueryModule.PRONUNCIATION)
        assertFalse(ipa.contains("context_sense")); assertFalse(ipa.contains("common_senses")); assertFalse(ipa.contains("derivatives"))
        val context = moduleInstruction(QueryModule.CONTEXT)
        assertFalse(context.contains("phonetics")); assertFalse(context.contains("common_senses")); assertFalse(context.contains("derivatives"))
    }
    @Test fun combinedTemplateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { wordModuleInstruction("bank", "bank", setOf(WordModule.CONTEXT, WordModule.PRONUNCIATION)) }
    }
    @Test fun maxIsNeverSilentlyMappedToLowerSupportedEffort() {
        val provider = Provider("自定义", "https://example.com/v1", "reasoning", "fixture", listOf("low", "high"), "high")
        assertThrows(IllegalArgumentException::class.java) { completionBody(provider, "JSON", "test", "max") }
        assertThrows(IllegalArgumentException::class.java) { completionBody(provider, "JSON", "test", "high") }
    }
    @Test fun officialAndCcOffUseTheirOwnWireToggle() {
        val official = completionBody(Provider("DeepSeek 官方", "https://api.deepseek.com", "deepseek-flash", "fixture"), "JSON", "bank", "off")
        assertEquals("disabled", official.getJSONObject("thinking").getString("type"))
        val cc = completionBody(Provider("Command Code GOAT", "https://api.commandcode.ai/provider/v1", "deepseek/deepseek-v4.1-flash", "fixture"), "JSON", "bank", "off")
        assertEquals("off", cc.getString("reasoning_effort"))
        val enabled = completionBody(Provider("DeepSeek 官方", "https://api.deepseek.com", "deepseek-flash", "fixture"), "JSON", "bank", "max")
        assertEquals("max", enabled.getString("reasoning_effort"))
    }
    @Test fun derivativeDuplicatesAndTheLemmaAreRemoved() {
        val raw = JSONObject().put("derivatives", JSONArray(listOf("advance", "advancement", "advancement").map {
            JSONObject().put("word", it).put("zh", "推进").put("relation", "派生")
        }))
        val result = decodeModule(QueryModule.DERIVATIVES, raw, "advance", "advance").getJSONArray("derivatives")
        assertEquals(1, result.length()); assertEquals("advancement", result.getJSONObject(0).getString("word"))
    }
    @Test fun clauseOffsetsAreLocalAndInvalidQuotesAreRejected() {
        val raw = JSONObject().put("clauses", JSONArray().put(JSONObject().put("quote", "it rains").put("kind", "条件从句")))
        val result = decodeModule(QueryModule.CLAUSES, raw, "If it rains, stay home.").getJSONArray("clauses").getJSONObject(0)
        assertEquals(3, result.getInt("start")); assertEquals(11, result.getInt("end"))
        assertThrows(IllegalArgumentException::class.java) { decodeModule(QueryModule.CLAUSES, raw, "It is sunny.") }
    }
    @Test fun newDragDistanceThresholdIsEightyPercentOfMidpoint() {
        assertEquals(0f, deckTarget(.39f, 0f, origin = 0f), .001f)
        assertEquals(1f, deckTarget(.41f, 0f, origin = 0f), .001f)
        assertEquals(1.6f, deckTarget(1.25f, 0f, origin = 1f), .001f)
        assertEquals(-.6f, deckTarget(-.25f, 0f, origin = 0f), .001f)
    }
}
