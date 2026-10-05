package site.arcol.contextoto

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class InteractionTest {
    @Test fun deckAnchorsAreBoundedAndVelocitySelectsAdjacentPage() {
        assertEquals(0f, deckTarget(.3f, 0f), .001f)
        assertEquals(1f, deckTarget(.3f, 4f), .001f)
        assertEquals(-1f, deckTarget(-5f, -20f), .001f)
        assertEquals(1.6f, deckTarget(4f, 30f), .001f)
        assertEquals(-.6f, deckTarget(-.3f, -30f, -.6f..1f), .001f)
        assertEquals(0f, deckTarget(-.3f, 30f, -.6f..0f), .001f)
        assertEquals(1f, deckTarget(1.3f, -30f, 1f..1.6f), .001f)
    }
    @Test fun directoryCollapsesAsSettingsTakesItsPlace() {
        assertEquals(0f, directoryFraction(0f), .001f)
        assertEquals(.5f, directoryFraction(-.3f), .001f)
        assertEquals(1f, directoryFraction(-.6f), .001f)
        assertEquals(.5f, directoryFraction(-.8f), .001f)
        assertEquals(0f, directoryFraction(-1f), .001f)
        assertEquals(1f, wordMenuFraction(1.6f), .001f)
    }
    @Test fun indexedDistractorsNeverIncludeHeadwordOrAnyTrueMeaning() {
        val index = DistractorIndex((0..1000).map { "w$it" to Meaning("释义$it", "n.", "bank") } +
            listOf("bank" to Meaning("额外真义", "n.", "bank"), "other" to Meaning("河岸", "n.", "bank")))
        repeat(100) {
            val q = makeQuestion("bank", listOf(Meaning("河岸", "n.", "context")), index, 0, Random(it))!!
            assertEquals(4, q.options.distinct().size)
            assertFalse(q.options.contains("额外真义"))
            assertEquals(1, q.options.count { it == "河岸" })
        }
    }
}
