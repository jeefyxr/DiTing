package io.github.jeefyxr.diting.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * A [Speaker] for any voice that turns a sentence into an audio clip: it keeps clips in [cache],
 * synthesizes upcoming sentences on [workers] background threads while the current one plays, and
 * plays clips through one audio track with a pause after each.
 *
 * [synthesize] runs on a worker thread; its second argument tells it when the result is no longer
 * wanted. [close] runs on a worker thread once nothing more will be synthesized.
 */
class ClipSpeaker(
    private val cache: AudioCache,
    override val lookAhead: Int,
    workers: Int,
    private val keyOf: (text: String) -> String,
    private val synthesize: (text: String, cancelled: () -> Boolean) -> AudioCache.Clip,
    private val close: () -> Unit = {},
    /** Clips the user downloaded for keeps; looked up before the cache and never written here. */
    private val library: (key: String) -> AudioCache.Clip? = { null },
    /** Speed applied while playing (pitch kept), for voices whose clips are stored at normal speed. */
    private val playbackSpeed: Float = 1f,
) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val synth = Executors.newFixedThreadPool(workers)
    private val player = Executors.newSingleThreadExecutor()
    private val pending = HashMap<String, Future<AudioCache.Clip>>()   // main thread only

    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile override var lastError: String? = null
        private set
    private var trackRate = 0        // player thread only
    private var framesWritten = 0L   // player thread only

    private fun submit(text: String): Future<AudioCache.Clip> = pending.getOrPut(text) {
        val gen = generation
        synth.submit(Callable {
            val key = keyOf(text)
            library(key) ?: cache.get(key) ?: run {
                // Queued look-ahead work is pointless once the listener has moved elsewhere.
                if (gen != generation) throw InterruptedException()
                synthesize(text) { gen != generation }.also { cache.put(key, it) }
            }
        })
    }

    override fun prefetch(text: String) {
        submit(text)
    }

    override fun speak(text: String, pauseMs: Int, onDone: (Boolean) -> Unit) {
        val gen = generation
        val audio = submit(text)
        pending.remove(text)
        player.execute {
            val ok = try {
                play(audio.get(), pauseMs, gen)
                true
            } catch (e: Throwable) {
                // Includes UnsatisfiedLinkError when a native engine cannot be loaded on this device.
                val cause = if (e is ExecutionException) e.cause ?: e else e
                if (gen == generation) lastError = cause.message ?: cause.javaClass.simpleName
                false
            }
            main.post { if (gen == generation) onDone(ok) }
        }
    }

    private fun play(clip: AudioCache.Clip, pauseMs: Int, gen: Int) {
        if (gen != generation || clip.samples.isEmpty()) return
        var t = track
        if (t == null || trackRate != clip.sampleRate) {
            t?.release()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(clip.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, clip.sampleRate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            trackRate = clip.sampleRate
            framesWritten = 0
        }
        if (playbackSpeed != 1f) t.playbackParams = android.media.PlaybackParams().setSpeed(playbackSpeed).setPitch(1f)
        t.play()
        write(t, clip.samples, gen)
        // A breath between sentences; without it consecutive sentences run into each other.
        if (pauseMs > 0) write(t, ShortArray(clip.sampleRate * pauseMs / 1000), gen)
        // Let the tail of the buffer play out before reporting the sentence as finished.
        val deadline = System.currentTimeMillis() + 3000
        while (gen == generation && System.currentTimeMillis() < deadline) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= framesWritten) break
            Thread.sleep(20)
        }
        if (gen != generation) {
            t.pause()
            t.flush()
            framesWritten = 0
        }
    }

    private fun write(t: AudioTrack, samples: ShortArray, gen: Int) {
        var pos = 0
        while (pos < samples.size && gen == generation) {
            val n = t.write(samples, pos, minOf(2048, samples.size - pos), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            pos += n
            framesWritten += n
        }
    }

    override fun stop() {
        generation++
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        try {
            track?.pause()   // makes a blocking write on the player thread return
        } catch (e: IllegalStateException) {
        }
    }

    override fun release() {
        stop()
        player.execute {
            track?.release()
            track = null
        }
        synth.execute { close() }
        player.shutdown()
        synth.shutdown()
    }
}
