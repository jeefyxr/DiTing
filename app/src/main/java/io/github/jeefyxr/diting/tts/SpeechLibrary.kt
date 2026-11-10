package io.github.jeefyxr.diting.tts

import java.io.File

/**
 * Chapters downloaded for offline listening. Unlike [AudioCache] nothing here is ever evicted:
 * clips stay until the user deletes the chapter or the book. Layout: `<root>/<book>/<chapter>/<key>`,
 * plus a `done` file once every sentence of the chapter is present; it names the voice used, because
 * clips made with another voice do not match what would be requested now.
 */
class SpeechLibrary(private val root: File) {
    private fun dir(book: String, chapter: Int) = File(File(root, book), chapter.toString())

    fun get(book: String, chapter: Int, key: String): AudioCache.Clip? {
        val file = File(dir(book, chapter), key)
        return if (file.isFile) AudioCache.read(file) else null
    }

    fun has(book: String, chapter: Int, key: String) = File(dir(book, chapter), key).isFile

    fun put(book: String, chapter: Int, key: String, clip: AudioCache.Clip) {
        val d = dir(book, chapter)
        d.mkdirs()
        if (!AudioCache.write(File(d, key), clip)) throw java.io.IOException("存储空间不足，无法保存下载的语音")
    }

    fun markComplete(book: String, chapter: Int, voice: String) = File(dir(book, chapter), DONE).writeText(voice)

    /** Whether the whole chapter was downloaded with [voice]. */
    fun isComplete(book: String, chapter: Int, voice: String): Boolean {
        val marker = File(dir(book, chapter), DONE)
        return marker.isFile && marker.readText() == voice
    }

    fun delete(book: String, chapter: Int) {
        dir(book, chapter).deleteRecursively()
    }

    fun deleteBook(book: String) {
        File(root, book).deleteRecursively()
    }

    /** Chapters of [book] that have any downloaded audio, with the bytes each one uses. */
    fun chapters(book: String): Map<Int, Long> =
        (File(root, book).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val index = d.name.toIntOrNull() ?: return@mapNotNull null
            index to (d.listFiles()?.sumOf { it.length() } ?: 0L)
        }.toMap()

    private companion object {
        const val DONE = "done"
    }
}
