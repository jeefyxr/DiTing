package io.github.jeefyxr.diting.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * Paged text view. One chapter is laid out at a time and cut into pages on line boundaries;
 * the reading position is a character offset, so it survives any change of font or margins.
 */
class ReaderView(context: Context) : View(context) {

    interface Source {
        val chapterCount: Int
        fun chapterTitle(index: Int): String
        fun chapterText(index: Int): String
    }

    data class PageRef(val chapter: Int, val page: Int)

    private class ChapterLayout(val text: CharSequence, val layout: StaticLayout, val firstLines: IntArray) {
        val pageCount get() = firstLines.size - 1
        fun pageStart(page: Int) = layout.getLineStart(firstLines[page])
        fun pageEnd(page: Int) = layout.getLineEnd(firstLines[page + 1] - 1)
        fun pageOf(offset: Int): Int {
            val line = layout.getLineForOffset(offset.coerceIn(0, text.length))
            var p = 0
            while (p + 1 < pageCount && firstLines[p + 1] <= line) p++
            return p
        }
    }

    var source: Source? = null
    var onCenterTap: (() -> Unit)? = null
    /** Called after the visible page changed. The flag tells whether the user caused it. */
    var onPageChanged: ((byUser: Boolean) -> Unit)? = null
    var onEdge: ((direction: Int) -> Unit)? = null
    var onAutoFinished: (() -> Unit)? = null

    var chapter = 0; private set
    /** Character offset of the top of the visible page within the chapter's display text. */
    var offset = 0; private set

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint()
    private val linePaint = Paint()
    private val path = Path()
    private val cache = HashMap<Int, ChapterLayout>()

    private var spacing = 1.5f
    private var margin = 0f
    private var background = 0xFFFFFFFF.toInt()
    private var vertical = false
    private var safeLeft = 0; private var safeTop = 0; private var safeRight = 0; private var safeBottom = 0
    private val infoHeight = 26 * density

    private var highlightStart = -1
    private var highlightEnd = -1

    // Page-turn slide: pixels the current page is displaced along the turn axis.
    private var slide = 0f
    private var target: PageRef? = null
    private var animator: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var dragDir = 0

    // Auto page turn ("cover" mode): fraction of the next page already revealed from the top.
    var autoRunning = false; private set
    private var autoProgress = 0f
    private var autoSeconds = 30
    private var autoLast = 0L

    init {
        infoPaint.textSize = 12 * resources.displayMetrics.scaledDensity
    }

    fun applyStyle(fontPx: Float, spacingMult: Float, marginPx: Float, backgroundColor: Int, textColor: Int, verticalTurn: Boolean) {
        textPaint.textSize = fontPx
        textPaint.color = textColor
        infoPaint.color = (textColor and 0x00FFFFFF) or 0x99000000.toInt()
        highlightPaint.color = (textColor and 0x00FFFFFF) or 0x30000000
        linePaint.color = (textColor and 0x00FFFFFF) or 0x66000000
        spacing = spacingMult
        margin = marginPx
        background = backgroundColor
        vertical = verticalTurn
        relayout()
    }

    fun open(chapterIndex: Int, charOffset: Int) {
        finishAnimation()
        val count = source?.chapterCount ?: 0
        chapter = chapterIndex.coerceIn(0, maxOf(0, count - 1))
        offset = maxOf(0, charOffset)
        autoProgress = 0f
        trimCache()
        snapOffset()
        invalidate()
        onPageChanged?.invoke(false)
    }

    /** Text of the current chapter as displayed (title line, then body). Offsets refer to this. */
    fun chapterText(): CharSequence = layoutFor(chapter)?.text ?: ""

    fun pageEndOffset(): Int {
        val cl = layoutFor(chapter) ?: return 0
        return cl.pageEnd(cl.pageOf(offset))
    }

    fun pageInfo(): Pair<Int, Int> {
        val cl = layoutFor(chapter) ?: return 0 to 0
        return (cl.pageOf(offset) + 1) to cl.pageCount
    }

    fun setHighlight(start: Int, end: Int) {
        highlightStart = start
        highlightEnd = end
        invalidate()
    }

    /** Turns one page forward (+1) or back (-1). Returns false at either end of the book. */
    fun turn(direction: Int, animate: Boolean = true, byUser: Boolean = true): Boolean {
        finishAnimation()
        val cur = current() ?: return false
        val next = neighbor(cur, direction)
        if (next == null) {
            if (byUser) onEdge?.invoke(direction)
            return false
        }
        if (!animate || width == 0 || autoRunning) {
            autoProgress = 0f
            commit(next, byUser)
        } else {
            target = next
            dragDir = direction
            animateSlide(-direction * axisSize(), true)
        }
        return true
    }

    fun startAuto(secondsPerPage: Int) {
        finishAnimation()
        autoSeconds = secondsPerPage
        if (!autoRunning) {
            autoRunning = true
            autoProgress = 0f
        }
        autoLast = SystemClock.uptimeMillis()
        invalidate()
    }

    fun stopAuto() {
        if (!autoRunning) return
        autoRunning = false
        autoProgress = 0f
        invalidate()
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun contentWidth() = (width - safeLeft - safeRight - 2 * margin).toInt()
    private fun contentTop() = safeTop + infoHeight + margin / 2
    private fun contentHeight() = (height - safeBottom - infoHeight - margin / 2 - contentTop()).toInt()
    private fun axisSize() = (if (vertical) height else width).toFloat()

    private fun relayout() {
        finishAnimation()
        cache.clear()
        snapOffset()
        invalidate()
    }

    /** Moves [offset] to the start of the page that contains it. */
    private fun snapOffset() {
        val cl = layoutFor(chapter) ?: return
        offset = cl.pageStart(cl.pageOf(offset))
    }

    private fun layoutFor(index: Int): ChapterLayout? {
        val src = source ?: return null
        if (index < 0 || index >= src.chapterCount) return null
        val w = contentWidth()
        val h = contentHeight()
        if (w <= 0 || h <= 0) return null
        cache[index]?.let { return it }

        val title = src.chapterTitle(index)
        val text = SpannableString(title + "\n" + src.chapterText(index))
        text.setSpan(RelativeSizeSpan(1.25f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, w)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

        val firsts = ArrayList<Int>()
        val lines = layout.lineCount
        var start = 0
        while (start < lines) {
            firsts.add(start)
            var end = start + 1
            while (end < lines && layout.getLineBottom(end) - layout.getLineTop(start) <= h) end++
            start = end
        }
        firsts.add(lines)
        return ChapterLayout(text, layout, firsts.toIntArray()).also { cache[index] = it }
    }

    private fun trimCache() {
        cache.keys.retainAll { abs(it - chapter) <= 1 }
    }

    private fun current(): PageRef? = layoutFor(chapter)?.let { PageRef(chapter, it.pageOf(offset)) }

    private fun neighbor(ref: PageRef, direction: Int): PageRef? {
        val cl = layoutFor(ref.chapter) ?: return null
        return if (direction > 0) {
            if (ref.page + 1 < cl.pageCount) PageRef(ref.chapter, ref.page + 1)
            else layoutFor(ref.chapter + 1)?.let { PageRef(ref.chapter + 1, 0) }
        } else {
            if (ref.page > 0) PageRef(ref.chapter, ref.page - 1)
            else layoutFor(ref.chapter - 1)?.let { PageRef(ref.chapter - 1, it.pageCount - 1) }
        }
    }

    private fun commit(ref: PageRef, byUser: Boolean) {
        val cl = layoutFor(ref.chapter) ?: return
        if (ref.chapter != chapter) setHighlight(-1, -1)
        chapter = ref.chapter
        offset = cl.pageStart(ref.page)
        trimCache()
        invalidate()
        onPageChanged?.invoke(byUser)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // System bars are hidden while reading; only a display cutout still needs to be avoided.
        var l = 0; var t = 0; var r = 0; var b = 0
        if (Build.VERSION.SDK_INT >= 28) {
            insets.displayCutout?.let {
                l = it.safeInsetLeft; t = it.safeInsetTop; r = it.safeInsetRight; b = it.safeInsetBottom
            }
        }
        if (l != safeLeft || t != safeTop || r != safeRight || b != safeBottom) {
            safeLeft = l; safeTop = t; safeRight = r; safeBottom = b
            relayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    // ---- drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val cur = current() ?: return
        if (autoRunning) {
            drawAuto(canvas, cur)
            return
        }
        val t = target
        if (slide != 0f && t != null) {
            val size = axisSize()
            val other = slide + if (dragDir > 0) size else -size
            if (vertical) {
                drawPage(canvas, t, 0f, other)
                drawPage(canvas, cur, 0f, slide)
            } else {
                drawPage(canvas, t, other, 0f)
                drawPage(canvas, cur, slide, 0f)
            }
        } else {
            drawPage(canvas, cur, if (vertical) 0f else slide, if (vertical) slide else 0f)
        }
    }

    /**
     * The current page stays put while the next page is revealed over it from the top down.
     * No text ever moves: only the boundary line travels.
     */
    private fun drawAuto(canvas: Canvas, cur: PageRef) {
        val next = neighbor(cur, 1)
        val now = SystemClock.uptimeMillis()
        autoProgress += (now - autoLast) / (autoSeconds * 1000f)
        autoLast = now
        if (autoProgress >= 1f) {
            if (next == null) {
                drawPage(canvas, cur, 0f, 0f)
                stopAuto()
                post { onAutoFinished?.invoke() }
                return
            }
            autoProgress = 0f
            commit(next, false)
            drawPage(canvas, next, 0f, 0f)
        } else {
            drawPage(canvas, cur, 0f, 0f)
            if (next != null) {
                val y = autoProgress * height
                canvas.save()
                canvas.clipRect(0f, 0f, width.toFloat(), y)
                drawPage(canvas, next, 0f, 0f)
                canvas.restore()
                canvas.drawRect(0f, y - density, width.toFloat(), y, linePaint)
            }
        }
        postInvalidateDelayed(33)
    }

    private fun drawPage(canvas: Canvas, ref: PageRef, dx: Float, dy: Float) {
        val cl = layoutFor(ref.chapter) ?: return
        val src = source ?: return
        val layout = cl.layout
        val first = cl.firstLines[ref.page]
        val last = cl.firstLines[ref.page + 1] - 1
        val left = safeLeft + margin
        val w = contentWidth().toFloat()

        canvas.save()
        canvas.translate(dx, dy)
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(background)

        val infoLeft = safeLeft + maxOf(margin, 12 * density)
        val infoWidth = width - safeRight - maxOf(margin, 12 * density) - infoLeft
        val page = "${ref.page + 1}/${cl.pageCount}"
        val pageWidth = infoPaint.measureText(page)
        val title = TextUtils.ellipsize(src.chapterTitle(ref.chapter), infoPaint, infoWidth, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, infoLeft, safeTop + infoHeight * 0.7f, infoPaint)
        val percent = "%.1f%%".format(100f * (ref.chapter + (ref.page + 1f) / cl.pageCount) / src.chapterCount)
        val bottomBase = height - safeBottom - infoHeight * 0.4f
        canvas.drawText(percent, infoLeft, bottomBase, infoPaint)
        canvas.drawText(page, infoLeft + infoWidth - pageWidth, bottomBase, infoPaint)

        canvas.translate(left, contentTop())
        canvas.clipRect(0f, 0f, w, (layout.getLineBottom(last) - layout.getLineTop(first)).toFloat())
        canvas.translate(0f, -layout.getLineTop(first).toFloat())
        if (ref.chapter == chapter && highlightEnd > highlightStart && highlightStart >= 0) {
            layout.getSelectionPath(highlightStart, minOf(highlightEnd, cl.text.length), path)
            canvas.drawPath(path, highlightPaint)
        }
        layout.draw(canvas)
        canvas.restore()
    }

    // ---- touch ----------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishAnimation()
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (autoRunning) return true
                val d = if (vertical) event.y - downY else event.x - downX
                if (!dragging && abs(d) > slop) {
                    dragging = true
                    dragDir = if (d < 0) 1 else -1
                    target = current()?.let { neighbor(it, dragDir) }
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    // Only follow the finger in the direction the drag started in.
                    val along = if (dragDir > 0) minOf(d, 0f) else maxOf(d, 0f)
                    slide = if (target == null) along * 0.15f else along
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    onTap(event.x, event.y)
                } else {
                    dragging = false
                    val size = axisSize()
                    if (target != null && abs(slide) > size * 0.15f) {
                        animateSlide(-dragDir * size, true)
                    } else {
                        if (target == null && abs(slide) > slop) onEdge?.invoke(dragDir)
                        animateSlide(0f, false)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                slide = 0f
                target = null
                invalidate()
            }
        }
        return true
    }

    private fun onTap(x: Float, y: Float) {
        if (autoRunning) {
            onCenterTap?.invoke()
            return
        }
        val p = if (vertical) y / height else x / width
        when {
            p < 0.33f -> turn(-1)
            p > 0.67f -> turn(1)
            else -> onCenterTap?.invoke()
        }
    }

    private fun animateSlide(end: Float, commitTarget: Boolean) {
        val a = ValueAnimator.ofFloat(slide, end)
        a.duration = 180
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            slide = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animator = null
                slide = 0f
                val t = target
                target = null
                if (commitTarget && t != null) commit(t, true) else invalidate()
            }
        })
        animator = a
        a.start()
    }

    private fun finishAnimation() {
        animator?.end()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
