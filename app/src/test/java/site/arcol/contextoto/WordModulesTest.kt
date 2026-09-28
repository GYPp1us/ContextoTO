package site.arcol.contextoto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WordModulesTest {
    private val context = JSONObject("""{"context_sense":{"zh":"河岸","evidence":"river bank"}}""")
    private val common = JSONObject("""{"common_senses":[{"zh":"银行","part_of_speech":"n."}],"derivatives":[]}""")
    private val pronunciation = JSONObject("""{"phonetics":{"uk":"/bæŋk/","us":"/bæŋk/"}}""")

    @Test fun rc1CacheOnlyNeedsPronunciation() {
        assertEquals(setOf(WordModule.PRONUNCIATION), missingWordModules(context, common, null))
        val result = assembleWordModules("bank", "bank", context, common, pronunciation)
        assertEquals("河岸", result.getJSONObject("context_sense").getString("zh"))
        assertEquals("银行", result.getJSONArray("common_senses").getJSONObject(0).getString("zh"))
        assertEquals(0, result.getJSONArray("_missing_modules").length())
    }

    @Test fun aNewOccurrenceOnlyAsksForContext() {
        val missing = missingWordModules(null, common, pronunciation)
        assertEquals(setOf(WordModule.CONTEXT), missing)
        val prompt = wordModuleInstruction("bank", "bank", missing)
        assertTrue(prompt.contains("context_sense 对象"))
        assertFalse(prompt.contains("common_senses 数组"))
        assertFalse(prompt.contains("derivatives 数组"))
        assertFalse(prompt.contains("phonetics 对象"))
    }

    @Test fun moduleMergeDoesNotDestroyPreviouslyCachedFields() {
        val existing = mergeAnalysisPayload(context, common)
        val result = mergeAnalysisPayload(existing, JSONObject("""{"context_sense":{"part_of_speech":"n."},"phonetics":{"uk":"/bæŋk/"}}"""))
        assertEquals("河岸", result.getJSONObject("context_sense").getString("zh"))
        assertEquals("river bank", result.getJSONObject("context_sense").getString("evidence"))
        assertEquals("n.", result.getJSONObject("context_sense").getString("part_of_speech"))
        assertEquals(1, result.getJSONArray("common_senses").length())
        assertEquals(0, result.getJSONArray("derivatives").length())
        assertFalse(existing.has("phonetics"))
    }

    @Test fun nullReplyDoesNotEraseModuleAndEmptyDerivativesAreValid() {
        val result = mergeAnalysisPayload(common, JSONObject().put("derivatives", JSONObject.NULL))
        assertEquals(0, result.getJSONArray("derivatives").length())
        assertEquals(emptySet<WordModule>(), missingWordModules(context, result, pronunciation))
    }

    @Test fun missingSingleCommonModuleIsIndependent() {
        val partial = JSONObject().put("common_senses", JSONArray())
        assertEquals(setOf(WordModule.DERIVATIVES), missingWordModules(context, partial, pronunciation))
    }

    @Test fun percentDoesNotClaimFinishedUntilCacheCommit() {
        val progress = QueryProgress(QueryPhase.COMPLETE)
        assertEquals(97, QueryTask("key", "n01", "bank", false, progress).percent)
        assertEquals(100, QueryTask("key", "n01", "bank", false, progress, QueryStatus.COMPLETE).percent)
        assertTrue(queryPercent(QueryProgress(QueryPhase.FIRST, 1000)) < queryPercent(QueryProgress(QueryPhase.STREAM, receivedChars = 10)))
    }
}
