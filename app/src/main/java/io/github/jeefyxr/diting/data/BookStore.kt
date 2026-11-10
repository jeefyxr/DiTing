package io.github.jeefyxr.diting.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class ChapterInfo(val title: String, val offset: Long, val length: Int)

data class BookMeta(val id: String, val title: String, val addedAt: Long, val chapters: List<ChapterInfo>) {
    val totalBytes: Long get() = chapters.lastOrNull()?.let { it.offset + it.length } ?: 0L
}

/**
 * Imported books live under [root] as `<id>/text.txt` (all chapters, UTF-8) plus `<id>/meta.json`
 * (titles and byte ranges), so opening a chapter is a single seek no matter how large the book is.
 */
class BookStore(private val root: File) {

    fun list(): List<BookMeta> =
        (root.listFiles() ?: emptyArray()).mapNotNull { load(it.name) }.sortedByDescending { it.addedAt }

    fun load(id: String): BookMeta? {
        val f = File(File(root, id), META)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("chapters")
            val chapters = List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                ChapterInfo(c.getString("t"), c.getLong("o"), c.getInt("l"))
            }
            BookMeta(id, o.getString("title"), o.getLong("added"), chapters)
        } catch (e: Exception) {
            null
        }
    }

    fun import(fileName: String, bytes: ByteArray, now: Long = System.currentTimeMillis()): BookMeta {
        val book = BookParser.parse(fileName, bytes)
        val id = java.lang.Long.toString(now, 36) + "-" + Integer.toString((Math.random() * 1_000_000).toInt(), 36)
        val dir = File(root, id)
        if (!dir.mkdirs()) throw java.io.IOException("无法创建书籍目录")
        try {
            val infos = ArrayList<ChapterInfo>()
            var offset = 0L
            File(dir, TEXT).outputStream().buffered().use { out ->
                for (ch in book.chapters) {
                    val data = ch.text.toByteArray(Charsets.UTF_8)
                    out.write(data)
                    infos.add(ChapterInfo(ch.title, offset, data.size))
                    offset += data.size
                }
            }
            val arr = JSONArray()
            for (c in infos) arr.put(JSONObject().put("t", c.title).put("o", c.offset).put("l", c.length))
            val meta = JSONObject().put("title", book.title).put("added", now).put("chapters", arr)
            File(dir, META).writeText(meta.toString())
            return BookMeta(id, book.title, now, infos)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun readChapter(meta: BookMeta, index: Int): String {
        val c = meta.chapters[index]
        val buf = ByteArray(c.length)
        RandomAccessFile(File(File(root, meta.id), TEXT), "r").use {
            it.seek(c.offset)
            it.readFully(buf)
        }
        return String(buf, Charsets.UTF_8)
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private companion object {
        const val TEXT = "text.txt"
        const val META = "meta.json"
    }
}
