package io.github.jeefyxr.diting.tts

import com.k2fsa.sherpa.onnx.OfflineTts

/** Offline neural speech with sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx). */
object SherpaSpeaker {
    /** Part of every cache key: clips from another model or engine version must not be reused. */
    private const val VOICE_ID = "zh-ll/${OfflineVoice.ENGINE_VERSION}"

    fun create(voice: OfflineVoice, speakerId: Int, speed: Float, cache: AudioCache): Speaker {
        // The engine is not thread-safe and is slow to load: one worker thread owns it.
        var engine: OfflineTts? = null
        return ClipSpeaker(
            cache = cache,
            lookAhead = 3,
            workers = 1,
            keyOf = { text -> AudioCache.key(VOICE_ID, speakerId, speed, text) },
            synthesize = { text, _ ->
                val e = engine ?: run {
                    voice.load()
                    OfflineTts(null, SherpaModel.config(voice.modelDir)).also { engine = it }
                }
                val audio = e.generate(text, speakerId.coerceIn(0, maxOf(0, e.numSpeakers() - 1)), speed)
                val samples = ShortArray(audio.samples.size) { i -> (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                AudioCache.Clip(samples, audio.sampleRate)
            },
            close = {
                engine?.release()
                engine = null
            },
        )
    }
}
