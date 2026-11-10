package io.github.jeefyxr.diting

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.jeefyxr.diting.data.BookStore
import io.github.jeefyxr.diting.reader.ReaderActivity
import io.github.jeefyxr.diting.reader.ReaderView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders every screen in a light and a dark theme to build/gallery so the look can be reviewed without a device.
 * Set DITING_SAMPLE_EPUB to use a real book.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class GalleryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out = File("build/gallery").apply { mkdirs() }

    private fun idle(ms: Long = 500) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    private fun shot(a: Activity, name: String) {
        idle()
        val root = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : View> find(v: View, type: Class<T>, match: (T) -> Boolean = { true }): T? {
        if (type.isInstance(v) && match(type.cast(v)!!)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type, match)?.let { return it }
        return null
    }

    private fun clickText(a: Activity, text: String) {
        val label = find(a.window.decorView, TextView::class.java) { it.text.toString() == text && it.isShown }
        assertTrue("no visible \"$text\"", label != null)
        var v: View = label!!
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        idle()
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0))
        idle()
    }

    @Test fun renderBothSkins() {
        val store = BookStore(File(app.filesDir, "books"))
        val para = "夜深了，他合上书，听见窗外下起了小雨。街上的灯一盏一盏地灭了，只有巷口那家面馆还亮着。" +
            "她说：“明天再来吧！”他点点头，没有说话……\n过了很久，雨才停。"
        fun novel(chapters: Int) = (1..chapters).joinToString("\n") { c -> "第${c}章 夜雨\n" + (1..30).joinToString("\n") { para } }
        store.import("边城.txt", novel(21).toByteArray(), now = 1000)
        store.import("一个人的村庄.txt", novel(8).toByteArray(), now = 2000)
        store.import("呼兰河传.txt", novel(12).toByteArray(), now = 3000)
        val sample = System.getenv("DITING_SAMPLE_EPUB")?.let(::File)?.takeIf { it.isFile }
        val book = if (sample != null) store.import(sample.name, sample.readBytes(), now = 4000)
        else store.import("夜雨寄北.txt", novel(40).toByteArray(), now = 4000)
        val prefs = Prefs(app)
        prefs.saveProgress(book.id, minOf(17, book.chapters.size - 1), 0, 0.02f)

        run {
            for ((tone, theme) in listOf("light" to 0, "dark" to Prefs.NIGHT_THEME)) {
                prefs.theme = theme
                val name = tone

                val shelf = Robolectric.buildActivity(MainActivity::class.java).setup().get()
                shot(shelf, "$name-1-shelf")

                val intent = Intent(app, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK, book.id)
                val reader = Robolectric.buildActivity(ReaderActivity::class.java, intent).setup().get()
                val page = find(reader.window.decorView, ReaderView::class.java)!!
                idle()
                page.turn(1, animate = false)
                shot(reader, "$name-2-page")
                tap(page, page.width / 2f, page.height / 2f)
                shot(reader, "$name-3-menu")
                clickText(reader, "设置")
                shot(reader, "$name-4-settings")
                reader.onBackPressed()
                idle()
                tap(page, page.width / 2f, page.height / 2f)
                clickText(reader, "目录")
                shot(reader, "$name-5-contents")
                reader.onBackPressed()
                idle()
                tap(page, page.width / 2f, page.height / 2f)
                clickText(reader, "听")
                shot(reader, "$name-6-listen")
                reader.finish()
            }
        }
    }
}
