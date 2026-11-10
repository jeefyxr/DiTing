package io.github.jeefyxr.diting

import io.github.jeefyxr.diting.tts.OfflineVoice
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

/** Getting the offline voice (engine and model) onto the device. */
class ModelTest {
    @get:Rule val tmp = TemporaryFolder()

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

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun engineIsRejectedUnlessItMatchesThePinnedBuild() {
        val voice = OfflineVoice(tmp.root)
        // Right file names, wrong contents: must never be installed, let alone loaded.
        val fake = zipOf("jni/arm64-v8a/libonnxruntime.so" to ByteArray(100), "jni/arm64-v8a/libsherpa-onnx-jni.so" to ByteArray(100))
        assertThrows(IOException::class.java) { voice.installEngine(fake.inputStream()) }
        assertThrows(IOException::class.java) { voice.installEngine(zipOf("readme.txt" to ByteArray(3)).inputStream()) }
        assertThrows(IOException::class.java) { voice.importFile(fake.inputStream(), File(tmp.root, "scratch")) }
        assertFalse(voice.engineInstalled())
        assertFalse(voice.engineDir.exists())
        assertFalse(File(tmp.root, "tts-engine.tmp").exists())
        assertFalse(File(tmp.root, "scratch").exists())
    }

    @Test fun pickedFileIsRecognisedAsModel() {
        val voice = OfflineVoice(tmp.root)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar()) }
        assertEquals(OfflineVoice.MODEL, voice.importFile(gz.toByteArray().inputStream(), File(tmp.root, "scratch")))
        assertTrue(voice.modelInstalled())
        assertFalse(voice.isInstalled())   // the engine is still missing
        checkInstalled(voice.modelDir)
    }

    /** Runs only when DITING_ENGINE_AAR points at the real sherpa-onnx-2.0.1.aar. */
    @Test fun installsEngineFromRealAarReadingOnlyItsStart() {
        val aar = System.getenv("DITING_ENGINE_AAR")?.let(::File)
        assumeTrue(aar != null && aar.isFile)
        val voice = OfflineVoice(tmp.root)
        var read = 0L
        val counting = object : java.io.FilterInputStream(aar!!.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { if (it > 0) read += it }
        }
        counting.use { voice.installEngine(it) }
        assertTrue(voice.engineInstalled())
        assertEquals(setOf("libonnxruntime.so", "libsherpa-onnx-jni.so"), voice.engineDir.list()!!.toSet())
        assertEquals(22249560L, File(voice.engineDir, "libonnxruntime.so").length())
        assertTrue("read $read of ${aar.length()} bytes", read <= OfflineVoice.ENGINE_DOWNLOAD_BYTES)

        // The same file through the "pick a file" path.
        val second = OfflineVoice(tmp.newFolder("second"))
        assertEquals(OfflineVoice.ENGINE, aar.inputStream().use { second.importFile(it, File(tmp.root, "scratch")) })
        assertTrue(second.engineInstalled())
    }

    /** Runs only with DITING_NETWORK_TEST=1: the real automatic download, end to end. */
    @Test fun downloadsEngineAndModelFromGitHub() {
        assumeTrue(System.getenv("DITING_NETWORK_TEST") == "1")
        val voice = OfflineVoice(tmp.root)
        var engineBytes = 0L
        voice.downloadEngine(OfflineVoice.Transfer()) { bytes, total ->
            engineBytes = bytes
            assertEquals(OfflineVoice.ENGINE_DOWNLOAD_BYTES, total)
        }
        assertTrue(voice.engineInstalled())
        assertTrue("downloaded $engineBytes bytes", engineBytes in 9_000_000L..OfflineVoice.ENGINE_DOWNLOAD_BYTES)

        var modelBytes = 0L
        var modelTotal = 0L
        voice.downloadModel(OfflineVoice.Transfer()) { bytes, total -> modelBytes = bytes; modelTotal = total }
        assertTrue(voice.isInstalled())
        assertEquals(31559701L, modelTotal)
        assertEquals(30482262L, File(voice.modelDir, "model.onnx").length())
        assertTrue(modelBytes > 31_000_000L)

        // A cancelled transfer reports cancellation and installs nothing.
        val other = OfflineVoice(tmp.newFolder("other"))
        val transfer = OfflineVoice.Transfer()
        val error = assertThrows(IOException::class.java) {
            other.downloadEngine(transfer) { bytes, _ -> if (bytes > 500_000) transfer.cancel() }
        }
        assertEquals("已取消", error.message)
        assertFalse(other.engineInstalled())
    }
}
