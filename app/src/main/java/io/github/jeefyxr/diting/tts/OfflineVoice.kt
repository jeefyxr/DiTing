package io.github.jeefyxr.diting.tts

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Offline voices. Neither the speech engine (about 27 MB of native code) nor the voice model is
 * part of the APK: both are fetched after installation, by download or from files the user picks,
 * and kept under [root] (the app's private files directory).
 *
 * The engine is native code, so it is only ever loaded after its SHA-256 matched the values below.
 */
class OfflineVoice(private val root: File) {
    val engineDir = File(root, "tts-engine")
    val modelDir = File(root, "tts-model")

    fun engineInstalled() = ENGINE_LIBS.keys.all { File(engineDir, it).isFile }
    fun modelInstalled() = SherpaModel.isExpectedModel(modelDir)
    fun isInstalled() = engineInstalled() && modelInstalled()

    fun speaker(male: Boolean, rate: Float): Speaker =
        SherpaSpeaker(this, if (male) SherpaModel.MALE_SPEAKER else SherpaModel.FEMALE_SPEAKER, rate)

    /** Loads the engine into this process. Safe to call repeatedly. */
    fun load() = synchronized(LOCK) {
        if (loaded) return
        // Order matters: the JNI library depends on onnxruntime and finds it already loaded.
        for (name in ENGINE_LIBS.keys) System.load(File(engineDir, name).absolutePath)
        loaded = true
    }

    /**
     * Installs the engine from a zip stream: the official `sherpa-onnx-2.0.1.aar`, or any zip that
     * holds the two arm64 libraries. Stops reading as soon as both are found, so when the stream
     * is a download of the 47 MB AAR only its first quarter is transferred.
     */
    fun installEngine(input: InputStream) {
        val tmp = File(root, "tts-engine.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("无法创建引擎目录")
        try {
            val zin = ZipInputStream(input)
            val found = HashSet<String>()
            while (found.size < ENGINE_LIBS.size) {
                val e = zin.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                val otherAbi = OTHER_ABIS.any { "/$it/" in "/${e.name}" }
                if (e.isDirectory || otherAbi || name !in ENGINE_LIBS || name in found) continue
                val digest = MessageDigest.getInstance("SHA-256")
                File(tmp, name).outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_LIB_BYTES) throw IOException("引擎文件异常")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != ENGINE_LIBS[name]) throw IOException("引擎文件校验失败，需要 sherpa-onnx $ENGINE_VERSION 的 arm64 版本")
                found.add(name)
            }
            if (found.size < ENGINE_LIBS.size) throw IOException("文件里没有找到语音引擎（arm64）")
            tmp.listFiles()?.forEach { it.setReadOnly() }
            engineDir.deleteRecursively()
            if (!tmp.renameTo(engineDir)) throw IOException("无法保存引擎")
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun installModel(input: InputStream) = SherpaModel.install(input, modelDir, SherpaModel::isExpectedModel)

    /**
     * Installs whatever a user-picked file turns out to be. Returns [ENGINE] or [MODEL].
     * [scratch] is a file the method may use and delete.
     */
    fun importFile(input: InputStream, scratch: File): Int {
        try {
            scratch.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            val isZip = scratch.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
            if (isZip) {
                val hasEngine = ZipInputStream(BufferedInputStream(scratch.inputStream())).use { zin ->
                    generateSequence { zin.nextEntry }.any { it.name.substringAfterLast('/') in ENGINE_LIBS }
                }
                if (hasEngine) {
                    scratch.inputStream().use { installEngine(BufferedInputStream(it, 1 shl 16)) }
                    return ENGINE
                }
            }
            scratch.inputStream().use(::installModel)
            return MODEL
        } finally {
            scratch.delete()
        }
    }

    /** Progress of a download: bytes so far, and the total if the server announced one (else -1). */
    fun interface Progress {
        fun update(bytes: Long, total: Long)
    }

    /** A running download that can be cancelled from another thread. */
    class Transfer {
        @Volatile private var connection: HttpURLConnection? = null
        @Volatile var cancelled = false; private set

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }

        internal fun <T> stream(url: String, progress: Progress, use: (InputStream) -> T): T {
            val c = URL(url).openConnection() as HttpURLConnection
            connection = c
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "DiDu-Reader")
                if (cancelled) throw IOException("已取消")
                if (c.responseCode != 200) throw IOException("下载失败（HTTP ${c.responseCode}）")
                val total = c.contentLengthLong
                val counting = object : java.io.FilterInputStream(c.inputStream) {
                    var count = 0L
                    override fun read(): Int = super.read().also { if (it >= 0) report(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) report(it) }
                    private fun report(n: Int) {
                        count += n
                        progress.update(count, total)
                    }
                }
                return use(BufferedInputStream(counting, 1 shl 16))
            } catch (e: IOException) {
                throw if (cancelled) IOException("已取消") else e
            } finally {
                c.disconnect()
            }
        }
    }

    /** Downloads the engine from the sherpa-onnx release page. Blocks; run on a worker thread. */
    fun downloadEngine(transfer: Transfer, progress: Progress) =
        transfer.stream(ENGINE_URL, { bytes, _ -> progress.update(bytes, ENGINE_DOWNLOAD_BYTES) }, ::installEngine)

    /** Downloads the voice model. Blocks; run on a worker thread. */
    fun downloadModel(transfer: Transfer, progress: Progress) = transfer.stream(MODEL_URL, progress, ::installModel)

    companion object {
        const val ENGINE = 1
        const val MODEL = 2

        const val ENGINE_VERSION = "2.0.1"
        const val ENGINE_FILE = "sherpa-onnx-$ENGINE_VERSION.aar"
        const val MODEL_FILE = "sherpa-onnx-vits-zh-ll.tar.bz2"
        const val ENGINE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ENGINE_VERSION/$ENGINE_FILE"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_FILE"
        /** The arm64 libraries sit at the front of the AAR; this much of it is read to get both. */
        const val ENGINE_DOWNLOAD_BYTES = 12_200_000L

        /** arm64-v8a libraries of sherpa-onnx 2.0.1, in load order, with their SHA-256. */
        val ENGINE_LIBS = linkedMapOf(
            "libonnxruntime.so" to "33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1",
            "libsherpa-onnx-jni.so" to "5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575",
        )
        private val OTHER_ABIS = listOf("armeabi-v7a", "x86", "x86_64")
        private const val MAX_LIB_BYTES = 64L * 1024 * 1024
        private val LOCK = Any()
        private var loaded = false

        /** The pinned engine build is arm64 only. */
        fun supported(abis: Array<String>) = "arm64-v8a" in abis
    }
}
