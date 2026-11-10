package io.github.jeefyxr.diting

import io.github.jeefyxr.diting.tts.OnlineVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnlineVoiceTest {
    private fun wav(rate: Int, channels: Int, samples: ShortArray, extraChunk: Boolean = false, declaredDataSize: Int? = null): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(samples) }.array()
        val out = ByteArrayOutputStream()
        fun le(v: Int, n: Int) = repeat(n) { out.write(v shr (8 * it) and 0xFF) }
        out.write("RIFF".toByteArray()); le(0, 4); out.write("WAVE".toByteArray())
        if (extraChunk) { out.write("LIST".toByteArray()); le(3, 4); out.write(byteArrayOf(1, 2, 3, 0)) }   // odd size + pad byte
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(channels, 2); le(rate, 4); le(rate * channels * 2, 4); le(channels * 2, 2); le(16, 2)
        out.write("data".toByteArray()); le(declaredDataSize ?: data.size, 4); out.write(data)
        return out.toByteArray()
    }

    @Test fun decodesMonoAndStereoWav() {
        val mono = OnlineVoice.decodeWav(wav(24000, 1, shortArrayOf(1, -2, 300, -32768, 32767)))
        assertEquals(24000, mono.sampleRate)
        assertEquals(listOf<Short>(1, -2, 300, -32768, 32767), mono.samples.toList())

        // Stereo is mixed down; unknown chunks before the audio are skipped.
        val stereo = OnlineVoice.decodeWav(wav(48000, 2, shortArrayOf(100, 300, -50, -150), extraChunk = true))
        assertEquals(48000, stereo.sampleRate)
        assertEquals(listOf<Short>(200, -100), stereo.samples.toList())

        // A streamed file that declares no length still yields its samples.
        assertEquals(3, OnlineVoice.decodeWav(wav(24000, 1, shortArrayOf(7, 8, 9), declaredDataSize = -1)).samples.size)
    }

    @Test fun rejectsWhatIsNotWav() {
        assertThrows(IOException::class.java) { OnlineVoice.decodeWav("{\"detail\":\"Missing authorization\"}".toByteArray()) }
        assertThrows(IOException::class.java) { OnlineVoice.decodeWav(ByteArray(0)) }
        assertThrows(IOException::class.java) { OnlineVoice.decodeWav("RIFF....WAVE".toByteArray()) }
    }

    /** Runs only when DITING_ONLINE_TOKEN is set: one real sentence through the real service. */
    @Test fun speaksThroughTheRealService() {
        val token = System.getenv("DITING_ONLINE_TOKEN").orEmpty()
        assumeTrue(token.isNotEmpty())
        val url = System.getenv("DITING_ONLINE_URL") ?: Prefs.DEFAULT_ONLINE_URL

        // The voice list is public on this service; a bad token only shows when speech is requested.
        val error = assertThrows(IOException::class.java) {
            OnlineVoice(url, "wrong-token").synthesize("测试。", "demo-zh-f", 1.0f, "didu-test-${System.nanoTime()}") { false }
        }
        assertTrue(error.message!!, error.message!!.contains("令牌"))

        val service = OnlineVoice(url, token)
        val voices = service.voices()
        assertTrue(voices.isNotEmpty())
        val voice = voices.first { it.id == "demo-zh-f" }

        val started = System.currentTimeMillis()
        val clip = service.synthesize("他合上书，听见窗外下起了小雨。", voice.id, 1.0f, "didu-test-${System.nanoTime()}") { false }
        val seconds = clip.samples.size / clip.sampleRate.toFloat()
        println("online: ${"%.1f".format(seconds)}s of speech in ${(System.currentTimeMillis() - started) / 1000}s at ${clip.sampleRate} Hz")
        assertEquals(OnlineVoice.CLIP_RATE, clip.sampleRate)
        assertTrue("clip is $seconds s", seconds in 1.5f..15f)
        assertTrue("clip must not be silence", clip.samples.any { it > 1000 || it < -1000 })

        // A cancelled request stops waiting and reports it as an interruption, not a failure.
        assertThrows(InterruptedException::class.java) {
            service.synthesize("这一句不需要了。", voice.id, 1.0f, "didu-test-${System.nanoTime()}") { true }
        }
    }
}
