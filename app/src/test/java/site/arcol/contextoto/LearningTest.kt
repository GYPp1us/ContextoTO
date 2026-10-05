package site.arcol.contextoto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LearningTest {
    @Test fun excludesEveryKnownTrueSenseAndPreservesSource() {
        val senses = listOf(Meaning("银行", "n.", "原句", "occ-1"), Meaning("河岸", "n.", "词库", "bank-1"))
        val pool = listOf("other" to Meaning("河岸", "n.", "词库"), "a" to Meaning("苹果", "n.", "词库"),
            "b" to Meaning("铁路", "n.", "词库"), "c" to Meaning("蜜蜂", "n.", "词库"))
        repeat(50) { seed ->
            val q = makeQuestion("bank", senses, pool, 100L, Random(seed))!!
            assertEquals(4, q.options.distinct().size)
            assertEquals(q.answer.text, q.options[q.correct])
            assertFalse(q.options.filterIndexed { i, _ -> i != q.correct }.any { it == "河岸" || it == "银行" })
            assertEquals(q, ReviewQuestion.parse(JSONObject(q.json().toString())))
        }
    }
    @Test fun insufficientDistractorsDefersWithoutFabricating() {
        assertNull(makeQuestion("test", listOf(Meaning("测试", source = "词库")), emptyList(), 0))
    }
    @Test fun curveRespectsElapsedTimeAndAgainShortensInterval() {
        val start = 1_000L
        val first = MemoryCurve.grade(Memory(), true, start, "2026-10-01")
        assertEquals(1.0, first.stability, .0001)
        assertEquals(start + MemoryCurve.DAY, first.due)
        assertEquals(.9, MemoryCurve.retention(first, first.due), .0001)
        val later = MemoryCurve.grade(first, true, first.due, "2026-10-02")
        assertTrue(later.stability > first.stability)
        assertEquals(2, later.successes)
        val sameDay = MemoryCurve.grade(later, true, later.last + 1000, "2026-10-02")
        assertEquals(later.successes, sameDay.successes)
        val failed = MemoryCurve.grade(later, false, later.due, "2026-10-05")
        assertEquals(later.due + 600_000, failed.due)
        assertTrue(failed.stability < later.stability)
    }
    @Test fun quotedCsvBomAndMultilineAreParsed() {
        val p = ContentImport.parseText("words.csv", "\uFEFFword,translation,pos,ipa_uk\n\"bank\",\"银行, 河岸\",noun,/bæŋk/\nrail,\"铁轨\n铁路\",noun,\n", true)
        assertEquals(2, p.words.size)
        assertEquals("/bæŋk/", p.words[0].ipaUk)
        assertTrue(p.words[0].translation.startsWith("n.", ignoreCase = true))
        assertTrue(p.words[1].translation.contains("铁路"))
    }
    @Test fun markdownTitleAndFingerprintStableAcrossFilename() {
        val a = ContentImport.parseText("first.md", "# My article\n\nThe first sentence.\n\nAnother sentence.", false).articles.single()
        val b = ContentImport.parseText("renamed.txt", "The first sentence.\n\nAnother sentence.", false).articles.single()
        assertEquals("My article", a.title)
        assertEquals(2, a.paragraphs.size)
        assertEquals(a.id, b.id)
    }
    @Test fun invalidCsvRejectedRatherThanSilentlyCorrupting() {
        assertTrue(runCatching { ContentImport.parseText("bad.csv", "word,translation\nword,\"unclosed", true) }.isFailure)
        assertTrue(runCatching { ContentImport.parseText("bad.csv", "wrong,headers\na,b", true) }.isFailure)
    }
    @Test fun zipTraversalAndOversizedInputsRejected() {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("../escape.txt")); zip.write("Hello.".toByteArray()); zip.closeEntry()
        }
        assertTrue(runCatching { ContentImport.parse("evil.zip", output.toByteArray().inputStream(), false) }.isFailure)
        assertTrue(runCatching { ContentImport.parse("huge.txt", ByteArray(ContentImport.MAX_FILE + 1).inputStream(), false) }.isFailure)
    }
}
