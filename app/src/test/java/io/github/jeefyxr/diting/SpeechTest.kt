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

    @Test fun semicolonsDoNotSplitASentence() {
        assertEquals(listOf("他来了；她走了。", "天黑了。"), split("他来了；她走了。天黑了。"))
    }

    @Test fun cacheKeepsRecentlyUsedClipsWithinItsLimit() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "diting-cache-${System.nanoTime()}")
        try {
            fun clip(seconds: Int, fill: Int) = io.github.jeefyxr.diting.tts.AudioCache.Clip(ShortArray(16000 * seconds) { (fill + it % 7).toShort() }, 16000)
            val oneSecond = 8L + 32000
            var cache = io.github.jeefyxr.diting.tts.AudioCache(dir, 3 * oneSecond)
            cache.put("a", clip(1, 10)); cache.put("b", clip(1, 20)); cache.put("c", clip(1, 30))
            assertEquals(3 * oneSecond, cache.size())
            val a = cache.get("a")!!                      // "a" is now the most recently used
            assertEquals(16000, a.sampleRate)
            assertEquals(16000, a.samples.size)
            assertEquals((10 + 5).toShort(), a.samples[5])
            cache.put("d", clip(1, 40))                   // evicts "b", the least recently used
            assertEquals(null, cache.get("b"))
            assertTrue(cache.get("a") != null && cache.get("c") != null && cache.get("d") != null)
            assertEquals(3, cache.count())

            // Survives a restart, drops clips when the limit shrinks, ignores damaged files.
            cache = io.github.jeefyxr.diting.tts.AudioCache(dir, 3 * oneSecond)
            assertEquals(3 * oneSecond, cache.size())
            cache.limitBytes = oneSecond
            assertEquals(1, cache.count())
            assertEquals(1, dir.list()!!.size)
            val survivor = dir.list()!![0]
            java.io.File(dir, survivor).writeText("damaged")
            assertEquals(null, cache.get(survivor))
            assertEquals(0L, cache.size())
            cache.put("too-big", clip(5, 1))              // larger than the whole cache: not stored
            assertEquals(0, cache.count())
            cache.limitBytes = 100 * oneSecond
            cache.put("x", clip(2, 1)); cache.clear()
            assertEquals(0L, cache.size())
            assertEquals(0, dir.list()!!.size)

            val key = io.github.jeefyxr.diting.tts.AudioCache.key("v", 1, 1.0f, "你好")
            assertEquals(32, key.length)
            assertTrue(key != io.github.jeefyxr.diting.tts.AudioCache.key("v", 2, 1.0f, "你好"))
            assertTrue(key != io.github.jeefyxr.diting.tts.AudioCache.key("v", 1, 1.1f, "你好"))
            assertEquals(key, io.github.jeefyxr.diting.tts.AudioCache.key("v", 1, 1.0f, "你好"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun listeningResumesOnlyOnThePageWhereItStopped() {
        val saved = 3 to 250
        assertEquals(250, Sentences.resumeOffset(saved, chapter = 3, pageStart = 200, pageEnd = 400))
        assertEquals(250, Sentences.resumeOffset(saved, 3, 250, 400))
        // The reader turned the page, or went to another chapter, by hand: start from what they see.
        assertEquals(null, Sentences.resumeOffset(saved, 3, 400, 600))
        assertEquals(null, Sentences.resumeOffset(saved, 3, 0, 250))
        assertEquals(null, Sentences.resumeOffset(saved, 4, 200, 400))
        assertEquals(null, Sentences.resumeOffset(null, 3, 200, 400))
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
