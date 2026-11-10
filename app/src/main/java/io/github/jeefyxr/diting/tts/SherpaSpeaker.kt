package io.github.jeefyxr.diting.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Offline neural speech with sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx). Synthesis runs
 * on one thread and playback on another, so upcoming sentences are generated while the current one
 * plays. Every generated sentence goes through [cache], so a passage heard before plays at once.
 */
class SherpaSpeaker(
    private val voice: OfflineVoice,
    private val speakerId: Int,
    private val speed: Float,
    private val cache: AudioCache,
) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val synth = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private val pending = HashMap<String, Future<AudioCache.Clip>>()   // main thread only

    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    private var engine: OfflineTts? = null   // synth thread only
    private var trackRate = 0                // player thread only
    private var framesWritten = 0L           // player thread only

    private fun submit(text: String): Future<AudioCache.Clip> = pending.getOrPut(text) {
        val gen = generation
        synth.submit(Callable {
            val key = AudioCache.key(VOICE_ID, speakerId, speed, text)
            cache.get(key) ?: run {
                // Queued look-ahead work is pointless once the listener has moved elsewhere.
                if (gen != generation) throw InterruptedException()
                val e = engine ?: run {
                    voice.load()
                    OfflineTts(null, SherpaModel.config(voice.modelDir)).also { engine = it }
                }
                val audio = e.generate(text, speakerId.coerceIn(0, maxOf(0, e.numSpeakers() - 1)), speed)
                val samples = ShortArray(audio.samples.size) { i -> (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                AudioCache.Clip(samples, audio.sampleRate).also { cache.put(key, it) }
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
                // Includes UnsatisfiedLinkError when the engine cannot be loaded on this device.
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
        synth.execute {
            engine?.release()
            engine = null
        }
        player.shutdown()
        synth.shutdown()
    }

    private companion object {
        /** Part of every cache key: clips from another model or engine version must not be reused. */
        const val VOICE_ID = "zh-ll/${OfflineVoice.ENGINE_VERSION}"
    }
}
