package io.github.jeefyxr.diting

import io.github.jeefyxr.diting.reader.Sentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        while (pos < text.length) {
            val end = Sentences.end(text, pos)
            assertTrue(end > pos)
            out.add(text.substring(pos, end))
            pos = end
        }
        return out
    }

    @Test fun sentencesKeepClosingQuotesAndCoverAllText() {
        val text = "第一章\n　　他说：“走吧！”她没有回答……天黑了。Really? Yes.\n　　最后一句没有句号"
        val parts = split(text)
        assertEquals(text, parts.joinToString(""))
        assertEquals(
            listOf("第一章\n", "　　他说：“走吧！”", "她没有回答……", "天黑了。", "Really?", " Yes.", "\n", "　　最后一句没有句号"),
            parts,
        )
        assertFalse(Sentences.isSpeakable("……\n"))
        assertTrue(Sentences.isSpeakable("　　好。"))
    }

    @Test fun previousSentence() {
        val text = "第一句。第二句！\n……\n　　第三句？第四句"
        val starts = listOf(0, 4, text.indexOf("　　第三句"), text.indexOf("第四句"))
        assertEquals(0, Sentences.previousStart(text, 0))
        assertEquals(0, Sentences.previousStart(text, 2))
        assertEquals(starts[0], Sentences.previousStart(text, starts[1]))
        // Unspeakable pieces (the line of dots) are skipped over.
        assertEquals(starts[1], Sentences.previousStart(text, starts[2] + 3))
        assertEquals(starts[2], Sentences.previousStart(text, text.length))
    }

    @Test fun overlongSentenceBreaksAtComma() {
        val text = ("很长的一句话没有句号，" + "字".repeat(30)).repeat(10)
        val parts = split(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= Sentences.MAX_LENGTH })
        assertTrue(parts.first().endsWith("，"))
    }
}
