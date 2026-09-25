package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FocusReporterTest {
    @Test fun acceptsCatalogOrFrameLink() {
        val base = "https://example.com/api/focus-reporter/token"
        assertEquals(base, focusBaseUrl("$base/catalog"))
        assertEquals(base, focusBaseUrl("$base/frame/"))
        assertThrows(IllegalArgumentException::class.java) { focusBaseUrl("http://example.com/frame") }
    }

    @Test fun rejectsItemFromAnotherSubject() {
        val catalog = FocusCatalog(listOf(FocusSubject(3, "英语"), FocusSubject(2, "数学")),
            listOf(FocusItem(3, 3, "二轮", "英语 · 二轮"), FocusItem(2, 2, "二轮", "数学 · 二轮")))
        assertEquals(3, catalog.item(3, 3)?.id)
        assertNull(catalog.item(3, 2))
    }
}
