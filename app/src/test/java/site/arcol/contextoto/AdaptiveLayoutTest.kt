package site.arcol.contextoto

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun shortWideWindowsUseACompactChromeAndReadableColumns() {
        val phone = ReaderViewport(840f, 360f)
        assertTrue(phone.compact); assertTrue(phone.split)
        assertEquals(792f, phone.sheetWidth, .001f)
        assertEquals(334f, phone.sheetHeight(14f), .001f)
    }
    @Test fun portraitNarrowWindowsAndLargeTextDoNotForceColumns() {
        assertFalse(ReaderViewport(393f, 840f).compact)
        assertFalse(ReaderViewport(560f, 300f).split)
        assertFalse(ReaderViewport(840f, 360f, 1.5f).split)
        assertEquals(393f * .86f, ReaderViewport(393f, 840f).sheetWidth, .001f)
    }
    @Test fun readingMeasureAndSheetsStayBoundedWithoutIgnoringPreferences() {
        assertEquals(18f, ReaderViewport(840f, 360f).readingMargin(18f), .001f)
        assertEquals(44f, ReaderViewport(840f, 360f).readingMargin(44f), .001f)
        assertEquals(240f, ReaderViewport(1440f, 800f).readingMargin(18f), .001f)
        assertEquals(1080f, ReaderViewport(1440f, 800f).sheetWidth, .001f)
        assertEquals(1f, ReaderViewport(400f, 100f).sheetHeight(120f), .001f)
    }
}
