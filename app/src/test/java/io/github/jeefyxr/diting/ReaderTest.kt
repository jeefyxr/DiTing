package io.github.jeefyxr.diting

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.jeefyxr.diting.data.BookStore
import io.github.jeefyxr.diting.reader.ReaderActivity
import io.github.jeefyxr.diting.reader.ReaderView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration

/** Drives the real reader screen with real text layout (Robolectric native graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var bookId: String
    private val shots = File("build/reader-screens").apply { mkdirs() }

    @Before fun importBook() {
        val para = "夜深了，他合上书，听见窗外下起了小雨。街上的灯一盏一盏地灭了，只有巷口那家面馆还亮着。" +
            "她说：“明天再来吧！”他点点头，没有说话……"
        val text = (1..6).joinToString("\n") { c -> "第${c}章 夜雨（$c）\n" + (1..40).joinToString("\n") { "$it．$para" } }
        bookId = BookStore(File(app.filesDir, "books")).import("测试小说.txt", text.toByteArray()).id
    }

    private fun launch(): ActivityController<ReaderActivity> {
        val intent = Intent(app, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK, bookId)
        val c = Robolectric.buildActivity(ReaderActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        return c
    }

    private fun readerView(a: ReaderActivity): ReaderView {
        fun find(v: View): ReaderView? = when (v) {
            is ReaderView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)!!
    }

    private fun shot(a: ReaderActivity, name: String): Bitmap {
        val root = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0))
        idle(400)
    }

    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0))
        for (i in 1..5) v.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 10, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 5, y0 + (y1 - y0) * i / 5, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x1, y1, 0))
        idle(400)
    }

    @Test fun pagingProgressAndRestyle() {
        val c = launch()
        val a = c.get()
        val v = readerView(a)
        assertTrue(v.width > 0 && v.height > 0)
        assertEquals(0, v.chapter)
        assertEquals(0, v.offset)
        val (page, pages) = v.pageInfo()
        assertEquals(1, page)
        assertTrue("expected several pages, got $pages", pages > 2)
        shot(a, "1-first-page")

        // Pages tile the chapter exactly: each page starts where the previous one ended.
        var expectedStart = v.pageEndOffset()
        var turns = 0
        while (v.chapter == 0) {
            val end = v.pageEndOffset()
            assertTrue(v.turn(1, animate = false))
            turns++
            if (v.chapter == 0) { assertEquals(expectedStart, v.offset); expectedStart = v.pageEndOffset(); assertTrue(expectedStart > end) }
            else assertEquals(v.chapterText().length.let { 0 }, v.offset)
        }
        assertEquals(pages, turns)
        assertEquals(1, v.chapter)

        // Tap zones: right third = next, left third = previous (animated).
        tap(v, v.width * 0.9f, v.height / 2f)
        assertEquals(2, v.pageInfo().first)
        tap(v, v.width * 0.1f, v.height / 2f)
        tap(v, v.width * 0.1f, v.height / 2f)
        assertEquals(0, v.chapter)
        assertEquals(pages, v.pageInfo().first)

        // Swipe left = next page; a short swipe springs back.
        drag(v, v.width * 0.8f, 800f, v.width * 0.3f, 820f)
        assertEquals(1, v.chapter)
        drag(v, v.width * 0.8f, 800f, v.width * 0.75f, 800f)
        assertEquals(1 to 1, v.chapter to v.pageInfo().first)
        tap(v, v.width * 0.9f, v.height / 2f)
        tap(v, v.width * 0.9f, v.height / 2f)
        assertEquals(3, v.pageInfo().first)

        // Changing the font keeps the same text at the top of the page.
        val anchor = v.offset
        val prefs = Prefs(a)
        val before = v.chapterText().substring(anchor, anchor + 12)
        tap(v, v.width / 2f, v.height / 2f)          // open the menu
        shot(a, "2-menu")
        prefs.fontSp = 30
        a.recreateStyleForTest()
        assertTrue(v.pageInfo().second > pages)
        val pageText = v.chapterText().substring(v.offset, v.pageEndOffset())
        assertTrue("page after restyle must still contain the old top line", before in pageText)
        shot(a, "3-large-font")

        // Leaving and reopening restores chapter and position.
        val chapter = v.chapter
        val offset = v.offset
        c.pause().stop().destroy()
        val v2 = readerView(launch().get())
        assertEquals(chapter, v2.chapter)
        assertEquals(offset, v2.offset)
    }

    @Test fun verticalModeAndVolumeKeys() {
        Prefs(app).vertical = true
        val a = launch().get()
        val v = readerView(a)
        // Horizontal swipes do nothing in vertical mode; swiping up turns the page.
        drag(v, v.width * 0.8f, 800f, v.width * 0.2f, 810f)
        assertEquals(1, v.pageInfo().first)
        drag(v, 500f, v.height * 0.8f, 510f, v.height * 0.3f)
        assertEquals(2, v.pageInfo().first)
        tap(v, v.width / 2f, v.height * 0.1f)
        assertEquals(1, v.pageInfo().first)

        assertTrue(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        idle(400)
        assertEquals(2, v.pageInfo().first)
        assertTrue(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        idle(400)
        assertEquals(1, v.pageInfo().first)

        Prefs(app).volumeKeys = false
        assertFalse(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertEquals(1, v.pageInfo().first)
    }

    @Test fun autoTurnCoversFromTopWithStaticText() {
        val a = launch().get()
        val v = readerView(a)
        val page1 = shot(a, "4-auto-before")
        v.turn(1, animate = false)
        val page2 = shot(a, "4-auto-next")
        v.turn(-1, animate = false)

        v.startAuto(20)
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis())   // no-op guard for clock mode
        ShadowLooper.idleMainLooper(Duration.ofSeconds(8).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        val mid = shot(a, "5-auto-40-percent")
        assertEquals(1, v.pageInfo().first)

        // Above the sweep line every pixel is the next page; below it every pixel is the old page:
        // nothing has moved, the new page is simply uncovered from the top.
        val line = (mid.height * 0.40f).toInt()
        fun rowsEqual(x: Bitmap, y: Bitmap, from: Int, to: Int): Boolean {
            val w = x.width
            val p = IntArray(w); val q = IntArray(w)
            for (row in from until to) {
                x.getPixels(p, 0, w, 0, row, w, 1); y.getPixels(q, 0, w, 0, row, w, 1)
                if (!p.contentEquals(q)) return false
            }
            return true
        }
        val infoBand = 120   // header shows the same chapter title on both pages; skip to body text
        assertTrue("top part should show the next page", rowsEqual(mid, page2, infoBand, line - 30))
        assertTrue("bottom part should still show the current page", rowsEqual(mid, page1, line + 30, mid.height - 150))
        assertFalse("pages must differ for this check to mean anything", rowsEqual(page1, page2, infoBand, line - 30))

        ShadowLooper.idleMainLooper(Duration.ofSeconds(13).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        shot(a, "6-auto-after")
        assertEquals(2, v.pageInfo().first)
        assertTrue(v.autoRunning)

        // A tap stops auto mode and opens the menu instead of turning the page.
        tap(v, v.width * 0.9f, v.height / 2f)
        assertFalse(v.autoRunning)
        assertEquals(2, v.pageInfo().first)
        assertNotEquals(0, v.offset)
    }
}
