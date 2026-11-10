package io.github.jeefyxr.diting.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Offline neural speech with sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx) and a VITS model
 * the user imported. Synthesis runs on one thread and playback on another, so the next sentence
 * is generated while the current one is still playing.
 */
class SherpaSpeaker(private val modelDir: File, private val speakerId: Int, private val speed: Float) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val synth = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private val cache = HashMap<String, Future<GeneratedAudio>>()

    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    private var engine: OfflineTts? = null   // synth thread only
    private var trackRate = 0                // player thread only
    private var framesWritten = 0L           // player thread only

    private fun submit(text: String): Future<GeneratedAudio> = cache.getOrPut(text) {
        synth.submit(Callable {
            val e = engine ?: OfflineTts(null, SherpaModel.config(modelDir)).also { engine = it }
            e.generate(text, speakerId.coerceIn(0, maxOf(0, e.numSpeakers() - 1)), speed)
        })
    }

    override fun prefetch(text: String) {
        submit(text)
    }

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        val gen = generation
        val audio = submit(text)
        cache.remove(text)
        player.execute {
            val ok = try {
                play(audio.get(), gen)
                true
            } catch (e: Throwable) {
                // Includes UnsatisfiedLinkError when the native library does not support this device.
                false
            }
            main.post { if (gen == generation) onDone(ok) }
        }
    }

    private fun play(audio: GeneratedAudio, gen: Int) {
        val samples = audio.samples
        if (gen != generation || samples.isEmpty()) return
        var t = track
        if (t == null || trackRate != audio.sampleRate) {
            t?.release()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(audio.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, audio.sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            trackRate = audio.sampleRate
            framesWritten = 0
        }
        t.play()
        var pos = 0
        while (pos < samples.size && gen == generation) {
            val n = t.write(samples, pos, minOf(2048, samples.size - pos), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            pos += n
            framesWritten += n
        }
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

    override fun stop() {
        generation++
        cache.values.forEach { it.cancel(false) }
        cache.clear()
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
}
