package io.github.jeefyxr.diting.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import io.github.jeefyxr.diting.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Paged text view. One chapter is laid out at a time and cut into pages on line boundaries;
 * the reading position is a character offset, so it survives any change of font or margins.
 *
 * Android's own line breaker decides where lines end (it knows the CJK punctuation rules), but the
 * lines are drawn here, stretched to the full width, because Android cannot justify Chinese text.
 */
class ReaderView(context: Context) : View(context) {

    interface Source {
        val chapterCount: Int
        fun chapterTitle(index: Int): String
        fun chapterText(index: Int): String
    }

    class Style(
        val fontPx: Float,
        val lineSpacing: Float,
        /** Extra space after each paragraph, in multiples of the font size. */
        val paragraphSpacing: Float,
        val marginPx: Float,
        val background: Int,
        val text: Int,
        val accent: Int,
        val turnMode: Int,
        val serif: Boolean,
        val indent: Boolean,
    )

    data class PageRef(val chapter: Int, val page: Int)

    private class ChapterLayout(val text: CharSequence, val titleLength: Int, val layout: StaticLayout, val firstLines: IntArray) {
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

    /** Adds space below the last line of every paragraph in its range. */
    private class ParagraphGap(private val extra: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (end > 0 && end <= text.length && text[end - 1] == '\n') {
                fm.descent += extra
                fm.bottom += extra
            }
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
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint()
    private val rect = RectF()
    private val cache = HashMap<Int, ChapterLayout>()
    private val edgeShadow = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x38000000, 0))
    private val sweepShadow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24000000, 0))
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var style = Style(48f, 1.5f, 0.5f, 0f, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFB4553D.toInt(), Prefs.TURN_COVER, false, true)
    private val vertical get() = style.turnMode == Prefs.TURN_VERTICAL
    private var safeLeft = 0; private var safeTop = 0; private var safeRight = 0; private var safeBottom = 0
    private val infoHeight = 30 * density

    private var highlightStart = -1
    private var highlightEnd = -1

    // Page turn: pixels the moving page is displaced along the turn axis.
    private var slide = 0f
    private var target: PageRef? = null
    private var animator: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var dragDir = 0

    // Auto page turn: fraction of the next page already revealed from the top.
    var autoRunning = false; private set
    private var autoProgress = 0f
    private var autoSeconds = 30
    private var autoLast = 0L

    private val minuteTick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    init {
        infoPaint.textSize = 12 * resources.displayMetrics.scaledDensity
    }

    fun applyStyle(s: Style) {
        style = s
        textPaint.textSize = s.fontPx
        textPaint.color = s.text
        textPaint.typeface = if (s.serif) Typeface.SERIF else Typeface.DEFAULT
        infoPaint.color = (s.text and 0x00FFFFFF) or 0x73000000
        highlightPaint.color = (s.accent and 0x00FFFFFF) or 0x38000000
        linePaint.color = (s.accent and 0x00FFFFFF) or 0x99000000.toInt()
        relayout()
    }

    fun open(chapterIndex: Int, charOffset: Int) {
        finishAnimation()
        val count = source?.chapterCount ?: 0
        chapter = chapterIndex.coerceIn(0, maxOf(0, count - 1))
        offset = maxOf(0, charOffset)
        autoProgress = 0f
        setHighlight(-1, -1)
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

    /** Position in the whole book, 0..1, counting every chapter as equally long. */
    fun bookFraction(): Float {
        val count = source?.chapterCount ?: return 0f
        val (page, pages) = pageInfo()
        if (count == 0 || pages == 0) return 0f
        return (chapter + (page - 1f) / pages) / count
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
        if (!animate || width == 0 || autoRunning || style.turnMode == Prefs.TURN_NONE) {
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

    private fun contentLeft() = safeLeft + style.marginPx
    private fun contentWidth() = (width - safeLeft - safeRight - 2 * style.marginPx).toInt()
    private fun contentTop() = safeTop + infoHeight + 6 * density
    private fun contentHeight() = (height - safeBottom - infoHeight - 6 * density - contentTop()).toInt()
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
        var body = src.chapterText(index)
        if (!style.indent) body = body.replace("　　", "")
        val text = SpannableString(title + "\n" + body)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        text.setSpan(RelativeSizeSpan(1.3f), 0, title.length, flags)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, flags)
        text.setSpan(ParagraphGap((style.fontPx * 0.9f).toInt()), 0, title.length + 1, flags)
        if (style.paragraphSpacing > 0f && body.isNotEmpty()) {
            text.setSpan(ParagraphGap((style.fontPx * style.paragraphSpacing).toInt()), title.length + 1, text.length, flags)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, w)
            .setLineSpacing(0f, style.lineSpacing)
            .setIncludePad(false)
            .build()

        val firsts = ArrayList<Int>()
        val lines = layout.lineCount
        var start = 0
        while (start < lines) {
            firsts.add(start)
            var end = start + 1
            // The gap below a page's last line may hang off the page; its text must not.
            while (end < lines && layout.getLineBaseline(end) + textPaint.descent() - layout.getLineTop(start) <= h) end++
            start = end
        }
        firsts.add(lines)
        return ChapterLayout(text, title.length, layout, firsts.toIntArray()).also { cache[index] = it }
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(minuteTick, 60_000 - System.currentTimeMillis() % 60_000 + 50)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(minuteTick)
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(style.background)
        val cur = current() ?: return
        if (autoRunning) {
            drawAuto(canvas, cur)
            return
        }
        val t = target
        val size = axisSize()
        when {
            slide == 0f || style.turnMode == Prefs.TURN_NONE -> drawPage(canvas, cur, 0f)
            t == null -> drawPage(canvas, cur, slide)   // rubber band at either end of the book
            style.turnMode == Prefs.TURN_COVER -> {
                // The upper sheet moves; the one below stays still.
                if (dragDir > 0) {
                    drawPage(canvas, t, 0f)
                    drawPage(canvas, cur, slide)
                    drawEdgeShadow(canvas, slide + size)
                } else {
                    drawPage(canvas, cur, 0f)
                    drawPage(canvas, t, slide - size)
                    drawEdgeShadow(canvas, slide)
                }
            }
            else -> {
                drawPage(canvas, t, slide + if (dragDir > 0) size else -size)
                drawPage(canvas, cur, slide)
            }
        }
    }

    private fun drawEdgeShadow(canvas: Canvas, edge: Float) {
        val w = (14 * density).toInt()
        edgeShadow.setBounds(edge.toInt(), 0, edge.toInt() + w, height)
        edgeShadow.draw(canvas)
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
                drawPage(canvas, cur, 0f)
                stopAuto()
                post { onAutoFinished?.invoke() }
                return
            }
            autoProgress = 0f
            commit(next, false)
            drawPage(canvas, next, 0f)
        } else {
            drawPage(canvas, cur, 0f)
            if (next != null) {
                val y = autoProgress * height
                canvas.save()
                canvas.clipRect(0f, 0f, width.toFloat(), y)
                drawPage(canvas, next, 0f)
                canvas.restore()
                canvas.drawRect(0f, y - density, width.toFloat(), y, linePaint)
                sweepShadow.setBounds(0, y.toInt(), width, y.toInt() + (8 * density).toInt())
                sweepShadow.draw(canvas)
            }
        }
        postInvalidateDelayed(33)
    }

    /** Draws one page displaced by [shift] pixels along the turn axis. */
    private fun drawPage(canvas: Canvas, ref: PageRef, shift: Float) {
        val cl = layoutFor(ref.chapter) ?: return
        val src = source ?: return
        val layout = cl.layout
        val first = cl.firstLines[ref.page]
        val last = cl.firstLines[ref.page + 1] - 1

        canvas.save()
        if (vertical) canvas.translate(0f, shift) else canvas.translate(shift, 0f)
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(style.background)

        // Running header and footer.
        val infoLeft = safeLeft + maxOf(style.marginPx, 14 * density)
        val infoRight = width - safeRight - maxOf(style.marginPx, 14 * density)
        val title = TextUtils.ellipsize(src.chapterTitle(ref.chapter), infoPaint, infoRight - infoLeft, TextUtils.TruncateAt.END)
        infoPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, 0, title.length, infoLeft, safeTop + infoHeight * 0.72f, infoPaint)
        val bottomBase = height - safeBottom - infoHeight * 0.42f
        canvas.drawText(clock.format(Date()), infoLeft, bottomBase, infoPaint)
        val percent = "%.1f%%".format(100f * (ref.chapter + (ref.page + 1f) / cl.pageCount) / src.chapterCount)
        infoPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${ref.page + 1}/${cl.pageCount}    $percent", infoRight, bottomBase, infoPaint)

        canvas.translate(contentLeft(), contentTop() - layout.getLineTop(first))
        val w = contentWidth().toFloat()
        for (line in first..last) {
            val start = layout.getLineStart(line)
            val end = layout.getLineVisibleEnd(line)
            if (end <= start) continue
            val baseline = layout.getLineBaseline(line).toFloat()
            if (start < cl.titleLength) {
                // The title keeps its spans, so let the layout draw just these lines.
                canvas.save()
                canvas.clipRect(0f, layout.getLineTop(line) - 8 * density, w, layout.getLineBottom(line).toFloat())
                layout.draw(canvas)
                canvas.restore()
                continue
            }
            justify(cl.text, start, end, layout.getLineEnd(line), w)
            if (ref.chapter == chapter && highlightEnd > start && highlightStart < end) {
                val a = maxOf(highlightStart, start)
                val b = minOf(highlightEnd, end)
                val x0 = textPaint.measureText(cl.text, start, a)
                val x1 = x0 + textPaint.measureText(cl.text, a, b)
                rect.set(x0 - 2 * density, baseline + textPaint.ascent() - density, x1 + 2 * density, baseline + textPaint.descent() + density)
                canvas.drawRoundRect(rect, 4 * density, 4 * density, highlightPaint)
            }
            canvas.drawText(cl.text, start, end, 0f, baseline, textPaint)
        }
        textPaint.letterSpacing = 0f
        if (Build.VERSION.SDK_INT >= 29) textPaint.wordSpacing = 0f
        canvas.restore()
    }

    /**
     * Sets up [textPaint] so that drawing text[start, end) fills [width] exactly. Chinese lines are
     * spread between characters, Latin lines between words; a paragraph's last line is left alone.
     */
    private fun justify(text: CharSequence, start: Int, end: Int, lineEnd: Int, width: Float) {
        textPaint.letterSpacing = 0f
        if (Build.VERSION.SDK_INT >= 29) textPaint.wordSpacing = 0f
        val paragraphEnd = lineEnd >= text.length || text[lineEnd - 1] == '\n'
        val count = end - start
        if (paragraphEnd || count < 2) return
        val extra = width - textPaint.measureText(text, start, end)
        if (extra <= 0.5f) return
        var cjk = 0
        var spaces = 0
        for (i in start until end) {
            val c = text[i]
            if (c == ' ') spaces++ else if (c.code >= 0x2E80) cjk++
        }
        if (cjk * 2 >= count) {
            val perGap = extra / (count - 1)
            if (perGap <= textPaint.textSize * 0.3f) textPaint.letterSpacing = perGap / textPaint.textSize
        } else if (spaces > 0 && Build.VERSION.SDK_INT >= 29) {
            val perSpace = extra / spaces
            if (perSpace <= textPaint.textSize) textPaint.wordSpacing = perSpace
        }
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
        a.duration = if (style.turnMode == Prefs.TURN_NONE) 0 else 220
        a.interpolator = DecelerateInterpolator(1.6f)
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
}
