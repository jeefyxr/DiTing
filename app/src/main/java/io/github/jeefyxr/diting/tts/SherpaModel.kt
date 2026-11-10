package io.github.jeefyxr.diting.tts

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * A sherpa-onnx VITS voice model unpacked from its release archive. The app uses
 * `sherpa-onnx-vits-zh-ll.tar.bz2` from the sherpa-onnx "tts-models" release page:
 * 16 kHz, five speakers, of which one female and one male voice are offered.
 */
object SherpaModel {
    /** Speakers of the zh-ll model chosen by ear (measured pitch about 225 Hz and 92 Hz). */
    const val FEMALE_SPEAKER = 2
    const val MALE_SPEAKER = 1
    /** Size of zh-ll's model.onnx, used to tell it from any other VITS model. */
    const val MODEL_BYTES = 121_100_803L

    private val RULE_FSTS = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst")
    private const val MAX_FILE = 400L * 1024 * 1024

    fun isInstalled(dir: File) = modelFile(dir) != null && File(dir, "tokens.txt").isFile

    /** Whether [dir] holds the zh-ll model in particular, rather than just some VITS model. */
    fun isExpectedModel(dir: File) = isInstalled(dir) && modelFile(dir)?.length() == MODEL_BYTES

    private fun modelFile(dir: File): File? =
        dir.listFiles()?.filter { it.name.endsWith(".onnx") }?.minByOrNull { it.name }

    fun config(dir: File): OfflineTtsConfig {
        val model = modelFile(dir) ?: throw IOException("model not installed")
        val lexicon = File(dir, "lexicon.txt")
        val fsts = RULE_FSTS.map { File(dir, it) }.filter { it.isFile }.joinToString(",") { it.path }
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    lexicon = if (lexicon.isFile) lexicon.path else "",
                    tokens = File(dir, "tokens.txt").path,
                ),
                // This model is the slowest part of listening; use the big cores, but not all cores.
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
            ),
            ruleFsts = fsts,
            // One sentence per call keeps memory flat; the reader already feeds single sentences.
            maxNumSentences = 1,
        )
    }

    /**
     * Unpacks a model archive (.tar.bz2, .tar.gz or .zip) into [dest], keeping only the files the
     * engine needs. [accept] can veto the unpacked model before it replaces the installed one.
     */
    fun install(input: InputStream, dest: File, accept: (File) -> Boolean = { true }) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("无法创建模型目录")
        try {
            val buffered = BufferedInputStream(input, 1 shl 16)
            buffered.mark(4)
            val magic = ByteArray(3)
            val got = buffered.read(magic)
            buffered.reset()
            fun m(i: Int) = magic[i].toInt() and 0xFF
            when {
                got == 3 && m(0) == 'B'.code && m(1) == 'Z'.code && m(2) == 'h'.code ->
                    untar(BZip2CompressorInputStream(buffered), tmp)
                got == 3 && m(0) == 0x1F && m(1) == 0x8B -> untar(GZIPInputStream(buffered), tmp)
                got == 3 && m(0) == 'P'.code && m(1) == 'K'.code -> unzip(ZipInputStream(buffered), tmp)
                else -> throw IOException("不支持的压缩格式（需要 .tar.bz2、.tar.gz 或 .zip）")
            }
            if (!isInstalled(tmp)) throw IOException("压缩包里没有找到 .onnx 模型和 tokens.txt")
            if (!accept(tmp)) throw IOException("这不是所需的语音模型（需要 sherpa-onnx-vits-zh-ll）")
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("无法保存模型")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun wanted(path: String): String? {
        val name = path.substringAfterLast('/')
        val keep = name.endsWith(".onnx") || name.endsWith(".fst") || name == "tokens.txt" || name == "lexicon.txt"
        return if (keep && !name.startsWith(".")) name else null
    }

    private fun unzip(zin: ZipInputStream, dir: File) {
        while (true) {
            val e = zin.nextEntry ?: break
            val name = if (e.isDirectory) null else wanted(e.name)
            if (name != null) File(dir, name).outputStream().use { copy(zin, it, Long.MAX_VALUE) }
        }
    }

    /** Small tar reader (ustar and GNU long names); enough for model archives. */
    private fun untar(input: InputStream, dir: File) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header) || header.all { it == 0.toByte() }) break
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            var path = longName ?: (cString(header, 345, 155).let { if (it.isEmpty()) "" else "$it/" } + cString(header, 0, 100))
            longName = null
            val padded = (size + 511) / 512 * 512
            when {
                type == 'L' -> {
                    val data = ByteArray(size.toInt())
                    if (!readFully(input, data)) throw EOFException()
                    skip(input, padded - size)
                    longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
                }
                (type == '0' || type == '\u0000') && wanted(path) != null -> {
                    File(dir, wanted(path)!!).outputStream().use { copy(input, it, size) }
                    skip(input, padded - size)
                }
                else -> skip(input, padded)
            }
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long =
        cString(b, off, len).trim().ifEmpty { "0" }.toLongOrNull(8) ?: throw IOException("压缩包已损坏")

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun skip(input: InputStream, count: Long) {
        val buf = ByteArray(1 shl 16)
        var left = count
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException()
            left -= r
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, limit: Long) {
        val buf = ByteArray(1 shl 16)
        var left = limit
        var total = 0L
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) {
                if (limit == Long.MAX_VALUE) return
                throw EOFException()
            }
            out.write(buf, 0, r)
            left -= r
            total += r
            if (total > MAX_FILE) throw IOException("模型文件过大")
        }
    }
}
