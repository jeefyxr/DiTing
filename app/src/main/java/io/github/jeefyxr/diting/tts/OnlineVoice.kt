package io.github.jeefyxr.diting.tts

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech from a self-hosted generation service that exposes CosyVoice as queued jobs
 * (`POST /jobs`, `GET /jobs/{id}`, `GET /jobs/{id}/output`, `GET /voices`, bearer token).
 * The server is slower than playback on its CPU lane, so this is only pleasant together with
 * look-ahead synthesis and the clip cache. Plain JVM code, no Android classes.
 */
class OnlineVoice(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')

    class VoiceInfo(val id: String, val name: String, val description: String)

    /** The voices registered on the server. */
    fun voices(): List<VoiceInfo> {
        val arr = JSONObject(String(request("GET", "/voices", null), Charsets.UTF_8)).optJSONArray("voices")
            ?: throw IOException("服务器没有返回音色列表")
        return List(arr.length()) { i ->
            val v = arr.getJSONObject(i)
            VoiceInfo(v.getString("id"), v.optString("name", v.getString("id")), v.optString("description"))
        }
    }

    /**
     * Synthesizes one sentence and waits for it. [clientRef] makes a retried request return the
     * same job instead of queueing the sentence again. Throws InterruptedException once [cancelled].
     */
    fun synthesize(text: String, voiceId: String, speed: Float, clientRef: String, cancelled: () -> Boolean): AudioCache.Clip {
        val body = JSONObject()
            .put("provider", "cosyvoice")
            .put("workflow", "t2a")
            .put("inputs", JSONObject().put("text", text).put("voice_id", voiceId))
            // The model's native rate: asking for more only makes the clip, and the cache, bigger.
            .put("generation", JSONObject().put("speed", speed.toDouble()).put("sample_rate", CLIP_RATE))
            .put("client_ref", clientRef)
        var job = JSONObject(String(request("POST", "/jobs", body.toString().toByteArray()), Charsets.UTF_8))
        val id = job.getString("id")
        val deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS
        while (true) {
            when (job.optString("status")) {
                "completed" -> break
                "failed", "cancelled" -> throw IOException("在线合成失败：" + job.optString("error").ifBlank { job.optString("status") })
            }
            if (cancelled()) {
                try { request("DELETE", "/jobs/$id", null) } catch (e: IOException) { /* best effort */ }
                throw InterruptedException()
            }
            if (System.currentTimeMillis() > deadline) throw IOException("在线合成超时")
            Thread.sleep(POLL_MS)
            job = JSONObject(String(request("GET", "/jobs/$id", null), Charsets.UTF_8))
        }
        val outputs = job.optJSONArray("outputs")
        val audio = (0 until (outputs?.length() ?: 0)).map { outputs!!.getJSONObject(it) }.firstOrNull { it.optString("kind") == "audio" }
            ?: throw IOException("在线合成没有返回音频")
        val wav = request("GET", "/jobs/$id/output?asset_id=" + URLEncoder.encode(audio.getString("id"), "UTF-8"), null)
        return decodeWav(wav)
    }

    private fun request(method: String, path: String, body: ByteArray?): ByteArray {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("User-Agent", "DiDu-Reader")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.use { it.readBytes() }
            val detail = try { c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty().take(200) } catch (e: IOException) { "" }
            throw IOException(
                when (code) {
                    401, 403 -> "访问令牌无效或没有权限（HTTP $code）"
                    404 -> "服务器地址不对（HTTP 404）"
                    429 -> "服务器排队已满，请稍后再试"
                    // The gateway answers 5xx for a moment while the speech runtime starts up.
                    else -> "服务器返回 HTTP $code $detail"
                }
            )
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val CLIP_RATE = 24000
        private const val POLL_MS = 700L
        private const val JOB_TIMEOUT_MS = 5 * 60_000L

        /** Reads a 16-bit PCM WAV file into a mono clip. */
        fun decodeWav(wav: ByteArray): AudioCache.Clip {
            val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(): String = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)
            if (wav.size < 12 || tag() != "RIFF") throw IOException("服务器返回的不是 WAV 音频")
            buf.int
            if (tag() != "WAVE") throw IOException("服务器返回的不是 WAV 音频")
            var channels = 0
            var rate = 0
            var bits = 0
            while (buf.remaining() >= 8) {
                val name = tag()
                val size = buf.int
                // A streamed WAV may declare an unknown data size; take what is there.
                val length = if (size < 0 || size > buf.remaining()) buf.remaining() else size
                when (name) {
                    "fmt " -> {
                        val start = buf.position()
                        val format = buf.short.toInt()
                        channels = buf.short.toInt()
                        rate = buf.int
                        buf.int; buf.short
                        bits = buf.short.toInt()
                        if (format != 1 && format != -2 || bits != 16 || channels !in 1..2) throw IOException("不支持的音频格式")
                        buf.position(start + length)
                    }
                    "data" -> {
                        if (channels == 0) throw IOException("音频文件已损坏")
                        val frames = length / (2 * channels)
                        val samples = ShortArray(frames) {
                            if (channels == 1) buf.short else ((buf.short + buf.short) / 2).toShort()
                        }
                        return AudioCache.Clip(samples, rate)
                    }
                    else -> buf.position(buf.position() + length + (length and 1).coerceAtMost(buf.remaining() - length))
                }
            }
            throw IOException("音频文件里没有数据")
        }
    }

    /** Identifies a clip of [text] in [voiceId]. Clips are made at normal speed; speed is applied on playback. */
    fun keyOf(voiceId: String, text: String) = AudioCache.key("online/$base/$voiceId", 0, 1f, text)

    /** One sentence at normal speed. Repeating the call for the same sentence reuses the server's job. */
    fun fetch(voiceId: String, text: String, cancelled: () -> Boolean): AudioCache.Clip =
        synthesize(text, voiceId, 1f, "didu-" + keyOf(voiceId, text), cancelled)

    /** A speaker reading with [voiceId]. Several sentences are requested ahead: the server queues them. */
    fun speaker(voiceId: String, speed: Float, cache: AudioCache, library: (String) -> AudioCache.Clip?): Speaker = ClipSpeaker(
        cache = cache,
        lookAhead = 6,
        workers = 3,
        keyOf = { text -> keyOf(voiceId, text) },
        synthesize = { text, cancelled -> fetch(voiceId, text, cancelled) },
        library = library,
        playbackSpeed = speed,
    )
}
