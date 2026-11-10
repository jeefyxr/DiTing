package io.github.jeefyxr.diting

import io.github.jeefyxr.diting.reader.Sentences
import io.github.jeefyxr.diting.tts.AudioCache
import io.github.jeefyxr.diting.tts.ChapterDownloads
import io.github.jeefyxr.diting.tts.SpeechLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Whole chapters stored for offline listening. */
class DownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chapter = "第三章 夜雨\n　　夜深了。他合上书；窗外下起了小雨。\n　　……\n　　“明天再来吧！”她说。"
    private fun clip(text: String) = AudioCache.Clip(ShortArray(100 + text.length) { text.length.toShort() }, 24000)
    private fun key(text: String) = AudioCache.key("voice", 0, 1f, text)

    private fun await(book: String, ch: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (ChapterDownloads.job(book, ch)?.finished == false && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("download did not end", ChapterDownloads.job(book, ch)?.finished != false)
    }

    @Test fun sentencesOfAChapterAreWhatTheVoiceIsAsked() {
        assertEquals(listOf("第三章 夜雨", "夜深了。", "他合上书；窗外下起了小雨。", "“明天再来吧！”", "她说。"), Sentences.speakable(chapter))
    }

    @Test fun downloadsEverySentenceOnceAndResumes() {
        val library = SpeechLibrary(tmp.newFolder("lib"))
        val sentences = Sentences.speakable(chapter)
        val asked = Collections.synchronizedList(ArrayList<String>())

        // First attempt fails on one sentence, as when the network drops.
        ChapterDownloads.start(library, "book", 3, "voice", sentences, ::key) { text, _ ->
            if (text == "她说。") throw java.io.IOException("网络中断")
            asked.add(text); clip(text)
        }
        await("book", 3)
        assertEquals("网络中断", ChapterDownloads.job("book", 3)!!.error)
        assertFalse(library.isComplete("book", 3, "voice"))

        // The retry asks only for what is still missing.
        val before = asked.size
        ChapterDownloads.start(library, "book", 3, "voice", sentences, ::key) { text, _ -> asked.add(text); clip(text) }
        await("book", 3)
        assertNull(ChapterDownloads.job("book", 3))
        assertTrue(library.isComplete("book", 3, "voice"))
        assertEquals(sentences.size, asked.size)
        assertEquals(sentences.size, asked.toSet().size)
        assertTrue(asked.size - before in 1..sentences.size)

        for (s in sentences) assertEquals(100 + s.length, library.get("book", 3, key(s))!!.samples.size)
        assertNull(library.get("book", 4, key(sentences[0])))
        // Downloaded with another voice does not count as available for this one.
        assertFalse(library.isComplete("book", 3, "other-voice"))

        val stats = library.chapters("book")
        assertEquals(setOf(3), stats.keys)
        assertTrue(stats.getValue(3) > sentences.size * 200L)
        library.delete("book", 3)
        assertFalse(library.isComplete("book", 3, "voice"))
        assertTrue(library.chapters("book").isEmpty())
    }

    @Test fun cancellingStopsTheDownloadAndKeepsItIncomplete() {
        val library = SpeechLibrary(tmp.newFolder("lib"))
        val sentences = (1..40).map { "第${it}句话。" }
        val started = AtomicInteger(0)
        ChapterDownloads.start(library, "b", 0, "voice", sentences, ::key) { text, cancelled ->
            started.incrementAndGet()
            repeat(200) { if (cancelled()) throw InterruptedException(); Thread.sleep(5) }
            clip(text)
        }
        Thread.sleep(100)
        val job = ChapterDownloads.job("b", 0)!!
        ChapterDownloads.cancel("b", 0)
        val deadline = System.currentTimeMillis() + 5000
        while (!job.finished && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(job.finished)
        assertTrue("only the sentences in flight were started: ${started.get()}", started.get() <= 6)
        assertFalse(library.isComplete("b", 0, "voice"))
        assertNull(ChapterDownloads.job("b", 0))

        // Deleting a book removes all of its chapters, and only its own.
        library.put("b", 1, "k", clip("x")); library.put("other", 1, "k", clip("x"))
        library.deleteBook("b")
        assertTrue(library.chapters("b").isEmpty())
        assertEquals(setOf(1), library.chapters("other").keys)
        assertTrue(File(tmp.root, "lib").exists())
    }
}
