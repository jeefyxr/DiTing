package io.github.jeefyxr.diting

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.jeefyxr.diting.data.BookMeta
import io.github.jeefyxr.diting.data.BookStore
import io.github.jeefyxr.diting.reader.ReaderActivity
import io.github.jeefyxr.diting.ui.CoverView
import io.github.jeefyxr.diting.ui.IconDrawable
import io.github.jeefyxr.diting.ui.Palette
import io.github.jeefyxr.diting.ui.Ui
import java.io.File
import kotlin.concurrent.thread

/** The bookshelf: import, open and delete books. It wears the same theme as the reading page. */
class MainActivity : Activity() {
    private lateinit var store: BookStore
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private var insetTop = 0
    private var insetBottom = 0
    private var shownKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BookStore(File(filesDir, "books"))
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int) = ui.dp(v)

    private fun render() {
        val theme = Prefs.THEMES[prefs.theme]
        ui = Ui(this, Palette(theme.background, theme.text))
        val p = ui.p
        window.decorView.setBackgroundColor(p.background)
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.night) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (p.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = p.background
            @Suppress("DEPRECATION")
            window.navigationBarColor = p.background
        }

        val books = store.list()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), insetTop + dp(28), dp(24), insetBottom + dp(32))

        // Header.
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val heading = ui.text("书架", 34f)
        heading.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        titles.addView(heading)
        titles.addView(
            ui.text(if (books.isEmpty()) "谛读" else "共 ${books.size} 本", 13.5f, p.muted),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val add = ui.softLayer(ui.icon(IconDrawable.PLUS) { pickFiles() })
        add.background = ui.raised(100f)
        header.addView(add, LinearLayout.LayoutParams(dp(48) + 2 * ui.pad, dp(48) + 2 * ui.pad).apply { marginEnd = -ui.pad })
        col.addView(header)

        if (books.isEmpty()) {
            col.addView(emptyState(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(96) })
        } else {
            books.maxByOrNull { prefs.lastRead(it.id) }?.takeIf { prefs.lastRead(it.id) > 0 }?.let { recent ->
                col.addView(continueCard(recent), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(28) - ui.pad
                    marginStart = -dp(7)
                    marginEnd = -dp(7)
                })
            }
            val columns = 3
            val gap = dp(18)
            for (start in books.indices step columns) {
                val row = LinearLayout(this)
                for (i in 0 until columns) {
                    val cell = books.getOrNull(start + i)?.let(::bookCell) ?: View(this)
                    row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = gap })
                }
                col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
            }
            col.addView(
                ui.text("长按一本书可以删除", 12f, p.faint).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(40) },
            )
        }

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.addView(col)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            if (top != insetTop || bottom != insetBottom) {
                insetTop = top
                insetBottom = bottom
                scroll.post { render() }
            }
            insets
        }
        setContentView(scroll)
    }

    private fun percent(book: BookMeta) = (prefs.fraction(book.id) * 100).toInt().coerceIn(0, 100)

    private fun open(book: BookMeta) =
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK, book.id))

    private fun continueCard(book: BookMeta): View {
        val p = ui.p
        val inner = dp(7)
        val card = ui.softLayer(LinearLayout(this))
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = ui.card(22f)
        card.setPadding(dp(18) + inner, dp(18) + inner, dp(20) + inner, dp(18) + inner)

        val cover = CoverView(this)
        cover.show(book.title, store.coverFile(book.id))
        card.addView(cover, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        val tag = ui.text("继续阅读", 12f, p.accent, medium = true)
        tag.letterSpacing = 0.08f
        info.addView(tag)
        info.addView(
            ui.singleLine(ui.text(book.title, 18f, medium = true)),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        val chapter = book.chapters.getOrNull(prefs.progress(book.id).first)?.title.orEmpty()
        info.addView(
            ui.singleLine(ui.text(chapter, 13f, p.muted)),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) },
        )
        // Progress bar with the figure at its end, on one baseline.
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        val track = FrameLayout(this)
        track.background = ui.shape(p.outline, 100f)
        val fill = View(this)
        fill.background = ui.shape(p.accent, 100f)
        val pct = percent(book)
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        track.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = maxOf(v.height, (r - l) * pct / 100)
            if (fill.layoutParams.width != w) { fill.layoutParams.width = w; fill.post { fill.requestLayout() } }
        }
        bar.addView(track, LinearLayout.LayoutParams(0, dp(4), 1f))
        bar.addView(ui.text("$pct%", 12.5f, p.muted, medium = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        info.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        card.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(18) })

        card.foreground = ui.pressable(null, 22f)
        card.setOnClickListener { open(book) }
        card.setOnLongClickListener { confirmDelete(book); true }
        return card
    }

    private fun bookCell(book: BookMeta): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        val cover = CoverView(this)
        cover.show(book.title, store.coverFile(book.id))
        cell.addView(cover, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val title = ui.text(book.title, 14f, medium = true)
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setLineSpacing(0f, 1.2f)
        cell.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        val pct = percent(book)
        cell.addView(
            ui.text(if (prefs.lastRead(book.id) == 0L) "未读" else "读到 $pct%", 12f, ui.p.muted),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) },
        )
        cell.setOnClickListener { open(book) }
        cell.setOnLongClickListener { confirmDelete(book); true }
        return cell
    }

    private fun emptyState(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val cover = CoverView(this)
        cover.show("谛读", null)
        col.addView(cover, LinearLayout.LayoutParams(dp(104), WRAP_CONTENT))
        col.addView(ui.text("书架还是空的", 18f, medium = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(32) })
        col.addView(
            ui.text("导入 .txt 或 .epub 文件，开始阅读", 14f, ui.p.muted),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) },
        )
        col.addView(
            ui.primaryButton("导入书籍") { pickFiles() },
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)).apply { topMargin = dp(32) },
        )
        return col
    }

    private fun dialogTheme() =
        if (ui.p.night) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun confirmDelete(book: BookMeta) {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("删除《${book.title}》？")
            .setMessage("书籍和阅读进度都会从本应用中移除，原文件不受影响。")
            .setPositiveButton("删除") { _, _ ->
                store.delete(book.id)
                prefs.clearProgress(book.id)
                render()
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
                render()
                if (errors.isNotEmpty()) {
                    AlertDialog.Builder(this, dialogTheme()).setTitle("部分文件未能导入")
                        .setMessage(errors.joinToString("\n")).setPositiveButton("好", null).show()
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
