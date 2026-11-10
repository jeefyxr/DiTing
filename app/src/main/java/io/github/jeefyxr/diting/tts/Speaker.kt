package io.github.jeefyxr.diting.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Speaks one sentence at a time. All methods are called on, and call back on, the main thread. */
interface Speaker {
    /**
     * Speaks [text], then stays silent for [pauseMs]; [onDone] receives false if speech is not
     * possible. Not called after [stop].
     */
    fun speak(text: String, pauseMs: Int, onDone: (ok: Boolean) -> Unit)

    /** Hint that [text] will be spoken soon, so it can be synthesized ahead of time. */
    fun prefetch(text: String) {}

    /** How many sentences ahead are worth passing to [prefetch]. */
    val lookAhead: Int get() = 0

    /** Why the last [speak] reported failure, if a reason is known. */
    val lastError: String? get() = null

    fun stop()
    fun release()
}

/** The phone's own text-to-speech engine. Voice and quality depend on the device. */
class SystemSpeaker(context: Context, private val rate: Float) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var ready: Boolean? = null
    private var pending: (() -> Unit)? = null
    private var generation = 0
    private val callbacks = HashMap<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        var ok = status == TextToSpeech.SUCCESS
        if (ok) {
            val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (ok) {
            tts.setSpeechRate(rate)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) = finish(utteranceId, true)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = finish(utteranceId, false)
                override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId, false)
            })
        }
        ready = ok
        pending?.invoke()
        pending = null
    }

    private fun finish(id: String, ok: Boolean) {
        main.post { callbacks.remove(id)?.invoke(ok) }
    }

    // The phone's engine paces sentences itself, so the extra pause is not applied here.
    override fun speak(text: String, pauseMs: Int, onDone: (Boolean) -> Unit) {
        when (ready) {
            null -> pending = { speak(text, pauseMs, onDone) }
            false -> onDone(false)
            true -> {
                val id = "u${++generation}"
                callbacks[id] = onDone
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) {
                    callbacks.remove(id)
                    onDone(false)
                }
            }
        }
    }

    override fun stop() {
        pending = null
        callbacks.clear()
        if (ready == true) tts.stop()
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}
