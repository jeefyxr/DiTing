package io.github.jeefyxr.diting.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A small, temporary store of synthesized sentences, so that going back a few sentences or pages
 * costs nothing. Least recently used clips are deleted first once the total passes [limitBytes].
 * Clips are 16-bit mono PCM: at 24 kHz that is 48 KB per second, so 10 MB holds about 3.5 minutes.
 * Whole chapters kept for offline listening live in [SpeechLibrary] instead.
 */
class AudioCache(private val dir: File, limitBytes: Long) {
    class Clip(val samples: ShortArray, val sampleRate: Int)

    /** Entry sizes, least recently used first. */
    private val entries = LinkedHashMap<String, Long>(64, 0.75f, true)
    private var total = 0L

    var limitBytes = limitBytes
        @Synchronized set(value) {
            field = value
            trim()
        }

    init {
        dir.mkdirs()
        // Rebuild the order from file times; within one run the map itself keeps exact order.
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.lastModified() }.forEach {
            entries[it.name] = it.length()
            total += it.length()
        }
        trim()
    }

    @Synchronized fun size() = total
    @Synchronized fun count() = entries.size

    @Synchronized fun get(key: String): Clip? {
        if (entries[key] == null) return null   // also marks the entry as recently used
        val file = File(dir, key)
        val clip = read(file)
        if (clip == null) remove(key) else file.setLastModified(System.currentTimeMillis())
        return clip
    }

    @Synchronized fun put(key: String, clip: Clip) {
        val bytes = HEADER + clip.samples.size * 2L
        if (bytes > limitBytes) return
        remove(key)
        if (!write(File(dir, key), clip)) return
        entries[key] = bytes
        total += bytes
        trim()
    }

    @Synchronized fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        entries.clear()
        total = 0
    }

    private fun remove(key: String) {
        val size = entries.remove(key) ?: return
        total -= size
        File(dir, key).delete()
    }

    private fun trim() {
        // Iterate entries, not keys: looking a key up would count as a use and reorder the map.
        val oldestFirst = entries.entries.iterator()
        while (total > limitBytes && oldestFirst.hasNext()) {
            val entry = oldestFirst.next()
            total -= entry.value
            File(dir, entry.key).delete()
            oldestFirst.remove()
        }
    }

    companion object {
        private const val MAGIC = 0x31414444   // "DDA1"
        private const val HEADER = 8

        /** Reads a clip file; null if it is missing or damaged. */
        fun read(file: File): Clip? = try {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < HEADER || buf.int != MAGIC) null else {
                val rate = buf.int
                val samples = ShortArray(buf.remaining() / 2)
                buf.asShortBuffer().get(samples)
                if (rate in 4000..96000) Clip(samples, rate) else null
            }
        } catch (e: Exception) {
            null
        }

        /** Writes a clip file via a temporary name, so a crash never leaves half a clip behind. */
        fun write(file: File, clip: Clip): Boolean {
            val tmp = File(file.parentFile, file.name + ".tmp")
            return try {
                val buf = ByteBuffer.allocate(HEADER + clip.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(MAGIC).putInt(clip.sampleRate)
                buf.asShortBuffer().put(clip.samples)
                tmp.writeBytes(buf.array())
                tmp.renameTo(file) || run { tmp.delete(); false }
            } catch (e: Exception) {
                tmp.delete()
                false
            }
        }

        /** Cache key for one sentence spoken by one voice at one speed. */
        fun key(voice: String, speaker: Int, speed: Float, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$voice|$speaker|$speed|$text".toByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
