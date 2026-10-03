package site.arcol.contextoto

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphStyleTest {
    @Test fun queriedBoldPreservesClauseColorAndSmallCapsSize() {
        val text = buildAnnotatedString {
            append("LAW")
            addStyle(SpanStyle(color = Color.Green, fontSize = 32.76.sp), 0, 3)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 3)
        }
        assertEquals(FontWeight.Bold, resolveGlyphWeight(text, 1))
        assertEquals(Color.Green, resolveGlyphColor(text, 1, Color.White))
        assertEquals(32.76f, resolveGlyphSize(text, 1, 42f), .001f)
    }
    @Test fun aSizeOnlySmallCapsSpanDoesNotBecomeBlack() {
        val text = buildAnnotatedString {
            append("LAW")
            addStyle(SpanStyle(fontSize = 32.76.sp), 0, 3)
        }
        assertEquals(Color.White, resolveGlyphColor(text, 0, Color.White))
        assertEquals(32.76f, resolveGlyphSize(text, 0, 42f), .001f)
    }

    @Test fun independentColorsAndMixedSizesArePreserved() {
        val text = buildAnnotatedString {
            append("LAW")
            addStyle(SpanStyle(color = Color.Green), 0, 3)
            addStyle(SpanStyle(fontSize = 32.76.sp), 1, 3)
            addStyle(SpanStyle(color = Color.Transparent), 0, 3)
        }
        assertEquals(Color.Green, resolveGlyphColor(text, 2, Color.White))
        assertEquals(42f, resolveGlyphSize(text, 0, 42f), .001f)
        assertEquals(32.76f, resolveGlyphSize(text, 2, 42f), .001f)
    }
}
