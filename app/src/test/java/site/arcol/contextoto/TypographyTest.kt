package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue

class TypographyTest {
    @Test fun abbreviatesLongPartOfSpeechLabels() {
        assertEquals("PREP.", abbreviatePartOfSpeech("preposition"))
        assertEquals("ADJ.", abbreviatePartOfSpeech("Adjective"))
        assertEquals("PHR.V.", abbreviatePartOfSpeech("phrasal verb"))
        assertEquals("VT.", abbreviatePartOfSpeech("vt."))
    }
    @Test fun uiHeadingsKeepUppercaseButUseSmallCapsWithinEachWord() {
        val text = uiSmallCaps("WORD BANKS", 25f)
        assertEquals("WORD BANKS", text.text)
        assertEquals(25f, resolveGlyphSize(text, 0, 25f), .001f)
        assertEquals(19.5f, resolveGlyphSize(text, 1, 25f), .001f)
        assertEquals(25f, resolveGlyphSize(text, 5, 25f), .001f)
        assertEquals(19.5f, resolveGlyphSize(text, 6, 25f), .001f)
        assertEquals(Color.White, resolveGlyphColor(text, 2, Color.White))
    }
    @Test fun uiSmallCapsPreserveDigitsSeparatorsAndApostrophes() {
        val text = uiSmallCaps("01 IN CONTEXT / AUTHOR'S", 20f)
        assertEquals("01 IN CONTEXT / AUTHOR'S", text.text)
        assertEquals(20f, resolveGlyphSize(text, 0, 20f), .001f)
        assertEquals(20f, resolveGlyphSize(text, 3, 20f), .001f)
        assertEquals(15.6f, resolveGlyphSize(text, 4, 20f), .001f)
        assertEquals(15.6f, resolveGlyphSize(text, text.length - 1, 20f), .001f)
        assertTrue(text.spanStyles.all { it.item.color == Color.Unspecified })
    }
}
