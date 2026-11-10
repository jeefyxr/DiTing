package io.github.jeefyxr.diting

import io.github.jeefyxr.diting.reader.Sentences
import io.github.jeefyxr.diting.tts.SherpaModel
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpeechTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        while (pos < text.length) {
            val end = Sentences.end(text, pos)
            assertTrue(end > pos)
            out.add(text.substring(pos, end))
            pos = end
        }
        return out
    }

    @Test fun sentencesKeepClosingQuotesAndCoverAllText() {
        val text = "第一章\n　　他说：“走吧！”她没有回答……天黑了。Really? Yes.\n　　最后一句没有句号"
        val parts = split(text)
        assertEquals(text, parts.joinToString(""))
        assertEquals(
            listOf("第一章\n", "　　他说：“走吧！”", "她没有回答……", "天黑了。", "Really?", " Yes.", "\n", "　　最后一句没有句号"),
            parts,
        )
        assertFalse(Sentences.isSpeakable("……\n"))
        assertTrue(Sentences.isSpeakable("　　好。"))
    }

    @Test fun overlongSentenceBreaksAtComma() {
        val text = ("很长的一句话没有句号，" + "字".repeat(30)).repeat(10)
        val parts = split(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= Sentences.MAX_LENGTH })
        assertTrue(parts.first().endsWith("，"))
    }

    private fun tarEntry(name: String, data: ByteArray, type: Char = '0'): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%011o".format(data.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257)
        return h + data + ByteArray((512 - data.size % 512) % 512)
    }

    private fun tar(): ByteArray {
        val longName = "vits-model/" + "d".repeat(120) + "/number.fst"
        return tarEntry("vits-model/", ByteArray(0), '5') +
            tarEntry("vits-model/model.onnx", ByteArray(1500) { 7 }) +
            tarEntry("vits-model/rule.far", ByteArray(5000) { 1 }) +
            tarEntry("vits-model/tokens.txt", "a 1\n".toByteArray()) +
            tarEntry("vits-model/lexicon.txt", "你 n i3\n".toByteArray()) +
            tarEntry("././@LongLink", (longName + "\u0000").toByteArray(), 'L') +
            tarEntry(longName.take(99), "fst".toByteArray()) +
            tarEntry("vits-model/README.md", "hi".toByteArray()) +
            ByteArray(1024)
    }

    private fun checkInstalled(dir: File) {
        assertTrue(SherpaModel.isInstalled(dir))
        assertEquals(setOf("model.onnx", "tokens.txt", "lexicon.txt", "number.fst"), dir.list()!!.toSet())
        assertEquals(1500L, File(dir, "model.onnx").length())
        assertEquals("fst", File(dir, "number.fst").readText())
        assertFalse(File(dir.parentFile, dir.name + ".tmp").exists())
    }

    @Test fun installsFromTarBz2() {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out).use { it.write(tar()) }
        val dir = File(tmp.root, "model")
        SherpaModel.install(out.toByteArray().inputStream(), dir)
        checkInstalled(dir)
    }

    @Test fun installsFromTarGzAndZip() {
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar()) }
        val dir = File(tmp.root, "model")
        SherpaModel.install(gz.toByteArray().inputStream(), dir)
        checkInstalled(dir)

        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            for ((n, d) in listOf("m/model.onnx" to ByteArray(1500), "m/tokens.txt" to ByteArray(3), "m/lexicon.txt" to ByteArray(3), "m/number.fst" to "fst".toByteArray(), "m/x.far" to ByteArray(9))) {
                z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry()
            }
        }
        // Re-importing replaces the previous model.
        SherpaModel.install(zip.toByteArray().inputStream(), dir)
        checkInstalled(dir)
    }

    @Test fun badArchiveLeavesExistingModelAlone() {
        val dir = File(tmp.root, "model")
        val good = ByteArrayOutputStream()
        GZIPOutputStream(good).use { it.write(tar()) }
        SherpaModel.install(good.toByteArray().inputStream(), dir)

        assertThrows(IOException::class.java) { SherpaModel.install("plain text".toByteArray().inputStream(), dir) }
        val noModel = ByteArrayOutputStream()
        GZIPOutputStream(noModel).use { it.write(tarEntry("a/readme.txt", "x".toByteArray()) + ByteArray(1024)) }
        assertThrows(IOException::class.java) { SherpaModel.install(noModel.toByteArray().inputStream(), dir) }
        val truncated = good.toByteArray().copyOf(good.size() / 2)
        assertThrows(IOException::class.java) { SherpaModel.install(truncated.inputStream(), dir) }
        checkInstalled(dir)
    }

    /** Runs only when DITING_MODEL_ARCHIVE points at the real vits-icefall-zh-aishell3.tar.bz2. */
    @Test fun installsRealAishell3Archive() {
        val archive = System.getenv("DITING_MODEL_ARCHIVE")?.let(::File)
        assumeTrue(archive != null && archive.isFile)
        val dir = File(tmp.root, "model")
        archive!!.inputStream().use { SherpaModel.install(it, dir) }
        assertTrue(SherpaModel.isInstalled(dir))
        assertEquals(
            setOf("model.onnx", "tokens.txt", "lexicon.txt", "phone.fst", "date.fst", "number.fst", "new_heteronym.fst"),
            dir.list()!!.toSet(),
        )
        assertEquals(30482262L, File(dir, "model.onnx").length())
        assertEquals(2042943L, File(dir, "lexicon.txt").length())
    }
}
