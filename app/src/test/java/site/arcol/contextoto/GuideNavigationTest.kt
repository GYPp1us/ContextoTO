package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideNavigationTest {
    @Test fun backDismissesOnlyTheNearestPracticeLayer() {
        assertEquals(GuideDismissTarget.WORD, guideDismissTarget(wordOpen = true, sentenceOpen = true))
        assertEquals(GuideDismissTarget.WORD, guideDismissTarget(wordOpen = true, sentenceOpen = false))
        assertEquals(GuideDismissTarget.SENTENCE, guideDismissTarget(wordOpen = false, sentenceOpen = true))
        assertEquals(GuideDismissTarget.NONE, guideDismissTarget(wordOpen = false, sentenceOpen = false))
    }
}
