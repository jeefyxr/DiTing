package io.github.jeefyxr.diting

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.jeefyxr.diting.data.BookMeta
import io.github.jeefyxr.diting.data.BookStore
import io.github.jeefyxr.diting.reader.ReaderActivity
import java.io.File
import kotlin.concurrent.thread

/** The bookshelf: import, open and delete books. */
class MainActivity : Activity() {
    private lateinit var store: BookStore
    private lateinit var prefs: Prefs
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var empty: TextView
    private var books: List<BookMeta> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BookStore(File(filesDir, "books"))
        prefs = Prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(pad, pad / 2, pad / 2, pad / 2)
        val title = TextView(this)
        title.text = "书架"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val add = Button(this, null, android.R.attr.borderlessButtonStyle)
        add.text = "导入"
        add.setOnClickListener { pickFiles() }
        bar.addView(add)
        root.addView(bar)

        empty = TextView(this)
        empty.text = "还没有书。点右上角“导入”，选择 .txt 或 .epub 文件。"
        empty.setPadding(pad, pad * 2, pad, pad)
        root.addView(empty)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK, books[i].id))
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            confirmDelete(books[i])
            true
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        books = store.list()
        adapter.clear()
        adapter.addAll(books.map { book ->
            val (chapter, _) = prefs.progress(book.id)
            val percent = if (book.chapters.isEmpty()) 0 else 100 * chapter.coerceIn(0, book.chapters.size) / book.chapters.size
            "${book.title}\n已读 $percent% · 共 ${book.chapters.size} 章"
        })
        empty.visibility = if (books.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun confirmDelete(book: BookMeta) {
        AlertDialog.Builder(this)
            .setMessage("删除《${book.title}》？")
            .setPositiveButton("删除") { _, _ ->
                store.delete(book.id)
                prefs.clearProgress(book.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        thread {
            val errors = ArrayList<String>()
            for (uri in uris) {
                val name = displayName(uri)
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_IMPORT_BYTES) throw java.io.IOException("文件超过 100MB")
                        }
                        out.toByteArray()
                    } ?: throw java.io.IOException("无法读取文件")
                    store.import(name, bytes)
                } catch (e: Exception) {
                    errors.add("$name：${e.message ?: "导入失败"}")
                } catch (e: OutOfMemoryError) {
                    errors.add("$name：文件太大")
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                reload()
                if (errors.isNotEmpty()) {
                    AlertDialog.Builder(this).setTitle("部分文件未能导入").setMessage(errors.joinToString("\n")).setPositiveButton("好", null).show()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
    }

    private companion object {
        const val REQUEST_IMPORT = 1
        const val MAX_IMPORT_BYTES = 100 * 1024 * 1024
    }
}
