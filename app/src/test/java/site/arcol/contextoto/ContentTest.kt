package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentTest {
    @Test fun tokenOffsetsPreservePossessivesAndHyphens() {
        val text = "Iran's long-term goals differ."
        val tokens = Content.tokens(text)
        assertEquals(listOf("Iran's", "long-term", "goals", "differ"), tokens.map { it.text })
        assertEquals("long-term", text.substring(tokens[1].start, tokens[1].end))
        assertNull(Content.tokenAt(text, 6))
    }

    @Test fun sentenceRangeContainsTappedOccurrence() {
        val text = "One state acts. Another state describes it."
        val second = text.lastIndexOf("state")
        val sentence = Content.sentenceAt(text, second)
        assertEquals("Another state describes it.", sentence.text)
        assertEquals(second - 8, sentence.start)
    }

    @Test fun enumeratedSentencesRetainOffsetsAndMatchTapSelection() {
        val text = "  One state acts.  Another state describes it.  "
        val sentences = Content.sentences(text)
        assertEquals(listOf("One state acts.", "Another state describes it."), sentences.map { it.text })
        sentences.forEach { sentence ->
            assertEquals(sentence.text, text.substring(sentence.start, sentence.end))
            assertEquals(sentence, Content.sentenceAt(text, sentence.start + 2))
        }
    }
}
