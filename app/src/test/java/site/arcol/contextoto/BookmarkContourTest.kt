package site.arcol.contextoto

import org.junit.Assert.*
import org.junit.Test

class BookmarkContourTest {
    @Test fun differentMissingCornersMorphWithoutInvertingBands() {
        val from = listOf(BookmarkBand(180f, 100f, 340f, 130f), BookmarkBand(20f, 130f, 340f, 160f), BookmarkBand(20f, 160f, 90f, 190f))
        val to = listOf(BookmarkBand(50f, 260f, 340f, 290f), BookmarkBand(20f, 290f, 250f, 320f))
        var count = 0
        for (i in 0..20) {
            val bands = interpolateBookmarkBands(from, to, i / 20f)
            if (i == 0) count = bands.size
            assertEquals(count, bands.size)
            assertTrue(bands.all { it.right > it.left && it.bottom > it.top })
            bands.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top, .001f) }
        }
        val initial = interpolateBookmarkBands(from, to, 0f)
        assertEquals(180f, initial.first().left, .001f)
        assertEquals(90f, initial.last().right, .001f)
        assertEquals(100f, initial.first().top, .001f)
        val final = interpolateBookmarkBands(from, to, 1f)
        assertEquals(50f, final.first().left, .001f)
        assertEquals(250f, final.last().right, .001f)
        assertEquals(320f, final.last().bottom, .001f)
    }
    @Test fun bookmarkOrderAdvancesWithinAParagraphWithoutMovingBackward() {
        assertTrue(BookmarkPlace(2, 90) > BookmarkPlace(2, 20))
        assertTrue(BookmarkPlace(3, 0) > BookmarkPlace(2, 1000))
        assertEquals(BookmarkPlace(2, 90), maxOf(BookmarkPlace(2, 90), BookmarkPlace(1, 300)))
    }
}
