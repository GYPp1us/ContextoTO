package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyTest {
    @Test fun abbreviatesLongPartOfSpeechLabels() {
        assertEquals("PREP.", abbreviatePartOfSpeech("preposition"))
        assertEquals("ADJ.", abbreviatePartOfSpeech("Adjective"))
        assertEquals("PHR.V.", abbreviatePartOfSpeech("phrasal verb"))
        assertEquals("VT.", abbreviatePartOfSpeech("vt."))
    }
}
