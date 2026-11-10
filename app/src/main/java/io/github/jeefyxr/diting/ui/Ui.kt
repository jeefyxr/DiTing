package io.github.jeefyxr.diting.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Every interface colour is derived from one background/text pair, so the chrome always matches the page. */
class Palette(val background: Int, val text: Int) {
    val night = luminance(background) < 0.3f
    /** Material sheets and bars: one tonal step away from the page. */
    val surface = if (night) blend(background, Color.WHITE, 0.07f) else blend(background, Color.WHITE, 0.6f)
    val muted = withAlpha(text, 0.6f)
    val faint = withAlpha(text, 0.36f)
    val outline = withAlpha(text, 0.16f)
    val fill = withAlpha(text, 0.07f)
    val accent = if (night) 0xFFD08573.toInt() else 0xFFB4553D.toInt()
    val onAccent = if (night) 0xFF1E1410.toInt() else Color.WHITE

    companion object {
        fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    }
}

/** Stroked glyphs drawn in a 24x24 box, so no image assets are needed. */
class IconDrawable(private val kind: Int, color: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f
        this.color = color
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = Ui.MEDIUM
    }
    private val path = Path().apply {
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { moveTo(x0, y0); lineTo(x1, y1) }
        when (kind) {
            BACK -> { line(19f, 12f, 5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
            LIST -> for (y in listOf(7f, 12f, 17f)) { line(4.5f, y, 4.6f, y); line(9f, y, 19.5f, y) }
            PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            CLOSE -> { line(6.5f, 6.5f, 17.5f, 17.5f); line(17.5f, 6.5f, 6.5f, 17.5f) }
            PLAY -> { moveTo(8.5f, 5.5f); lineTo(18.5f, 12f); lineTo(8.5f, 18.5f); close() }
            PAUSE -> { line(8.5f, 6f, 8.5f, 18f); line(15.5f, 6f, 15.5f, 18f) }
            SUN -> {
                addCircle(12f, 12f, 3.6f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
                    line(12f + 6.6f * c, 12f + 6.6f * s, 12f + 8.6f * c, 12f + 8.6f * s)
                }
            }
            MOON -> {
                val cut = Path().apply { addCircle(16.2f, 8.2f, 6.6f, Path.Direction.CW) }
                addCircle(11.5f, 12.5f, 7.6f, Path.Direction.CW)
                op(cut, Path.Op.DIFFERENCE)
            }
            VOICE -> {
                moveTo(3.5f, 9.6f); lineTo(7f, 9.6f); lineTo(11.5f, 6f); lineTo(11.5f, 18f); lineTo(7f, 14.4f); lineTo(3.5f, 14.4f); close()
                addArc(9.4f, 8.4f, 16.6f, 15.6f, -45f, 90f)
                addArc(6.2f, 5.2f, 19.8f, 18.8f, -45f, 90f)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height(), sizePx).toFloat()
        canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
        canvas.scale(size / 24f, size / 24f)
        if (kind == TEXT) {
            textPaint.textSize = 15f
            canvas.drawText("Aa", 12f, 17.2f, textPaint)
        } else {
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val BACK = 0
        const val LIST = 1
        const val PLUS = 2
        const val CLOSE = 3
        const val PLAY = 4
        const val PAUSE = 5
        const val SUN = 6
        const val MOON = 7
        const val VOICE = 8
        const val TEXT = 9
    }
}

/**
 * The "soft UI" building block: a shape in the same colour as what it sits on, lifted by a light
 * shadow from the top-left and a dark one to the bottom-right, or pressed in with the two reversed.
 * The shadows are drawn inside the bounds, [pad] pixels in from each edge.
 */
class SoftDrawable(
    private val color: Int,
    private val radius: Float,
    private val light: Int,
    private val dark: Int,
    private val depth: Float,
    private val sunken: Boolean,
    private val pad: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        rect.inset(pad, pad)
        val r = minOf(radius, rect.height() / 2, rect.width() / 2)
        paint.style = Paint.Style.FILL
        paint.maskFilter = null
        if (!sunken) {
            paint.color = color
            paint.setShadowLayer(depth * 1.7f, depth, depth, dark)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.setShadowLayer(depth * 1.7f, -depth, -depth, light)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.clearShadowLayer()
            canvas.drawRoundRect(rect, r, r, paint)
            return
        }
        paint.clearShadowLayer()
        paint.color = Palette.blend(color, dark, 0.12f)
        canvas.drawRoundRect(rect, r, r, paint)
        clip.rewind()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = depth * 2f
        paint.maskFilter = BlurMaskFilter(depth * 1.4f, BlurMaskFilter.Blur.NORMAL)
        paint.color = dark
        canvas.save(); canvas.translate(depth * 0.7f, depth * 0.7f); canvas.drawRoundRect(rect, r, r, paint); canvas.restore()
        paint.color = light
        canvas.save(); canvas.translate(-depth * 0.7f, -depth * 0.7f); canvas.drawRoundRect(rect, r, r, paint); canvas.restore()
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Builders for the controls the app uses. Two skins share one layout:
 * Material (flat tonal surfaces, outlined and filled shapes) and Soft (raised and pressed shapes).
 */
class Ui(val context: Context, val p: Palette, val soft: Boolean) {
    private val density = context.resources.displayMetrics.density
    /** Callbacks that re-read state into the controls; run them with [refresh]. */
    val refreshers = ArrayList<() -> Unit>()

    /** Colour of sheets, bars and cards. Soft shapes must share the colour of what they sit on. */
    val surface = if (soft) p.background else p.surface
    private val softLight = Palette.blend(p.background, Color.WHITE, if (p.night) 0.13f else 0.95f)
    private val softDark = Palette.blend(p.background, Color.BLACK, if (p.night) 0.6f else 0.17f)
    /** Room inside a soft control's bounds for its shadows; zero for Material. */
    val pad = if (soft) dp(5) else 0
    private val chipRadius = if (soft) 100f else 10f

    fun refresh() = refreshers.forEach { it() }

    fun dp(v: Int) = (v * density + 0.5f).toInt()
    fun dpf(v: Float) = v * density

    fun shape(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        if (strokeDp > 0f) setStroke(maxOf(1, dpf(strokeDp).toInt()), strokeColor)
    }

    fun raised(radiusDp: Float, padPx: Int = pad): Drawable =
        SoftDrawable(surface, dpf(radiusDp), softLight, softDark, dpf(2.6f), false, padPx.toFloat())

    fun sunken(radiusDp: Float, padPx: Int = pad): Drawable =
        SoftDrawable(surface, dpf(radiusDp), softLight, softDark, dpf(2.2f), true, padPx.toFloat())

    /** Shadow layers and blurs need a software layer before Android 9. */
    fun <T : View> softLayer(view: T): T = view.apply {
        if (soft && Build.VERSION.SDK_INT < 28) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** [content] with a touch ripple clipped to a rounded shape. */
    fun pressable(content: Drawable?, radiusDp: Float = 100f): Drawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(radiusDp) }
        return RippleDrawable(ColorStateList.valueOf(Palette.withAlpha(p.text, 0.12f)), content, mask)
    }

    /** A card or panel background. */
    fun card(radiusDp: Float): Drawable = if (soft) raised(radiusDp, dp(7)) else shape(p.surface, radiusDp)

    /** Background of a bottom sheet: only the top corners are rounded. */
    fun sheet(): Drawable = GradientDrawable().apply {
        setColor(surface)
        val r = dpf(28f)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun text(value: CharSequence, sizeSp: Float, color: Int = p.text, medium: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        includeFontPadding = false
        if (medium) typeface = MEDIUM
    }

    fun singleLine(view: TextView) = view.apply {
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    }

    fun hairline() = View(context).apply { setBackgroundColor(if (soft) Palette.withAlpha(p.text, 0.07f) else p.outline) }

    fun iconDrawable(kind: Int, color: Int = p.text, sizeDp: Int = 24) = IconDrawable(kind, color, dp(sizeDp))

    /** A plain 48dp icon button. */
    fun icon(kind: Int, color: Int = p.text, onClick: () -> Unit) = ImageView(context).apply {
        setImageDrawable(iconDrawable(kind, color))
        scaleType = ImageView.ScaleType.CENTER
        background = pressable(null)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    /** Icon over a caption, for the reader's bottom bar. [active] tints it with the accent. */
    fun tab(kind: () -> Int, label: () -> String, active: () -> Boolean, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val image = softLayer(ImageView(context))
        image.scaleType = ImageView.ScaleType.CENTER
        val caption = text("", 11.5f, p.muted)
        val box = if (soft) dp(52) else dp(40)
        col.addView(image, LinearLayout.LayoutParams(box, box))
        col.addView(caption, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(if (soft) 2 else 0) })
        col.setPadding(0, dp(6), 0, dp(6))
        col.setOnClickListener { onClick() }
        if (!soft) col.background = pressable(null, 16f)
        refreshers.add {
            val on = active()
            val color = if (on) p.accent else p.text
            image.setImageDrawable(iconDrawable(kind(), color, 22))
            if (soft) image.background = if (on) sunken(100f) else raised(100f)
            caption.text = label()
            caption.setTextColor(if (on) p.accent else p.muted)
        }
        return col
    }

    private fun chip(label: String, minWidthDp: Int = 0): TextView {
        val c = softLayer(text(label, 14f))
        c.gravity = Gravity.CENTER
        c.setPadding(dp(15) + pad, 0, dp(15) + pad, 0)
        c.minWidth = dp(minWidthDp) + 2 * pad
        c.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(36) + 2 * pad).apply { marginEnd = dp(8) - pad }
        return c
    }

    private fun styleChip(c: TextView, on: Boolean) {
        c.setTextColor(if (on) p.accent else p.text)
        c.typeface = if (on) MEDIUM else Typeface.DEFAULT
        c.background = when {
            soft -> if (on) sunken(chipRadius) else raised(chipRadius)
            on -> pressable(shape(Palette.withAlpha(p.accent, 0.16f), chipRadius, p.accent, 1.2f), chipRadius)
            else -> pressable(shape(Color.TRANSPARENT, chipRadius, p.outline, 1f), chipRadius)
        }
    }

    private fun scroller(row: LinearLayout) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(row)
    }

    /** Mutually exclusive choices. Scrolls sideways rather than squeezing on narrow screens. */
    fun chips(options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(context)
        options.forEachIndexed { i, name ->
            val c = chip(name)
            c.setOnClickListener { onSelect(i); refresh() }
            refreshers.add { styleChip(c, selected() == i) }
            row.addView(c)
        }
        return scroller(row)
    }

    /** `A−  19  A+` */
    fun fontStepper(value: () -> String, change: (Int) -> Unit): View {
        val row = LinearLayout(context)
        row.gravity = Gravity.CENTER_VERTICAL
        val number = text("", 17f, medium = true)
        number.gravity = Gravity.CENTER
        refreshers.add { number.text = value() }
        fun side(label: String, delta: Int) = chip(label, 64).also { c ->
            styleChip(c, false)
            (c.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
            c.setOnClickListener { change(delta); number.text = value() }
        }
        row.addView(side("A−", -1))
        row.addView(number, LinearLayout.LayoutParams(dp(60), WRAP_CONTENT))
        row.addView(side("A+", 1))
        return row
    }

    /** A quiet button, e.g. "导入模型". */
    fun button(label: String, onClick: () -> Unit): TextView = chip(label).also {
        styleChip(it, false)
        it.setTextColor(p.accent)
        it.setOnClickListener { onClick() }
    }

    /** A filled call-to-action. */
    fun primaryButton(label: String, onClick: () -> Unit): TextView {
        val b = text(label, 15f, p.onAccent, medium = true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(28), 0, dp(28), 0)
        b.background = pressable(shape(p.accent, if (soft) 100f else 14f), if (soft) 100f else 14f)
        if (soft) b.elevation = dpf(6f)
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(48))
        return b
    }

    /**
     * A slider over 0..[max]. [onChange] gets every value; `done` is true when the finger lifts.
     */
    fun slider(max: Int, value: () -> Int, onChange: (value: Int, done: Boolean) -> Unit): SeekBar {
        val seek = softLayer(SeekBar(context))
        seek.max = max
        val trackHeight = if (soft) dp(10) else dp(4)
        val track = if (soft) sunken(100f, 0) else shape(p.outline, 100f)
        val fill = ClipDrawable(shape(p.accent, 100f), Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(track, fill))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        for (i in 0..1) {
            layers.setLayerHeight(i, if (soft && i == 1) dp(4) else trackHeight)
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL)
        }
        if (soft) layers.setLayerInset(1, dp(3), 0, dp(3), 0)
        seek.progressDrawable = layers
        val thumbSize = if (soft) dp(30) else dp(20)
        seek.thumb = if (soft) {
            LayerDrawable(arrayOf(raised(100f, dp(4)), shape(p.accent, 100f))).apply {
                setLayerSize(0, thumbSize, thumbSize)
                setLayerSize(1, dp(8), dp(8))
                setLayerGravity(1, Gravity.CENTER)
            }
        } else {
            shape(p.accent, 100f).apply { setSize(thumbSize, thumbSize) }
        }
        seek.thumbOffset = thumbSize / 2
        seek.splitTrack = false
        seek.background = null
        seek.setPadding(thumbSize / 2, 0, thumbSize / 2, 0)
        seek.minimumHeight = dp(40)
        refreshers.add { seek.progress = value() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress, false)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress, true)
        })
        return seek
    }

    fun toggle(checked: () -> Boolean, onChange: (Boolean) -> Unit): View {
        val t = softLayer(ToggleView(this))
        t.checked = checked()
        t.onChange = onChange
        refreshers.add { t.checked = checked() }
        t.layoutParams = LinearLayout.LayoutParams(dp(52) + 2 * pad, dp(32) + 2 * pad)
        return t
    }

    /** A round colour sample with its name underneath. */
    fun swatch(name: String, background: Int, textColor: Int, selected: () -> Boolean, onClick: () -> Unit): View {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val dot = softLayer(SwatchView(this, background, textColor))
        val caption = text(name, 11.5f, p.muted)
        col.addView(dot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
        col.addView(caption, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.setOnClickListener { onClick() }
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        refreshers.add {
            dot.chosen = selected()
            caption.setTextColor(if (selected()) p.accent else p.muted)
        }
        return col
    }

    /** Lays [views] out in equal columns across the available width. */
    fun spread(views: List<View>) = LinearLayout(context).apply { views.forEach { addView(it) } }

    /** A soft shadow strip that separates a bar from the page beneath it. */
    fun edgeShadow(downward: Boolean) = View(context).apply {
        val strength = if (p.night) 0x59000000 else 0x1F000000
        background = GradientDrawable(
            if (downward) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(strength, 0),
        )
    }

    internal fun softShadow(paint: Paint, lightSide: Boolean) {
        val d = dpf(2.4f)
        if (lightSide) paint.setShadowLayer(d * 1.7f, -d, -d, softLight) else paint.setShadowLayer(d * 1.7f, d, d, softDark)
    }

    companion object {
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val SERIF_BOLD: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
}

/** On/off switch drawn to match the current skin. */
class ToggleView(private val ui: Ui) : View(ui.context) {
    var onChange: ((Boolean) -> Unit)? = null
    var checked = false
        set(value) {
            if (field == value) return
            field = value
            if (isLaidOut) animateTo(if (value) 1f else 0f) else { position = if (value) 1f else 0f; invalidate() }
        }
    private var position = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val track by lazy { ui.sunken(100f) }

    init {
        setOnClickListener {
            checked = !checked
            onChange?.invoke(checked)
        }
    }

    private fun animateTo(end: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(position, end).apply {
            duration = 160
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val p = ui.p
        val pad = ui.pad.toFloat()
        rect.set(pad, pad, width - pad, height - pad)
        val h = rect.height()
        val travel = rect.width() - h
        val cx = rect.left + h / 2 + travel * position
        val cy = rect.centerY()
        if (ui.soft) {
            track.setBounds(0, 0, width, height)
            track.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = Palette.withAlpha(p.accent, 0.55f * position)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            drawKnob(canvas, cx, cy, h / 2 - ui.dpf(3f))
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Palette.blend(Palette.blend(p.background, p.text, 0.12f), p.accent, position)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            if (position < 1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ui.dpf(1.6f)
                paint.color = Palette.withAlpha(p.text, 0.38f * (1 - position))
                val half = paint.strokeWidth / 2
                rect.inset(half, half)
                canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Palette.blend(Palette.withAlpha(p.text, 1f).let { Palette.blend(p.background, it, 0.55f) }, p.onAccent, position)
            canvas.drawCircle(cx, cy, ui.dpf(8f) + ui.dpf(4f) * position, paint)
        }
    }

    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = ui.surface
        ui.softShadow(paint, false)
        canvas.drawCircle(cx, cy, r, paint)
        ui.softShadow(paint, true)
        canvas.drawCircle(cx, cy, r, paint)
        paint.clearShadowLayer()
        canvas.drawCircle(cx, cy, r, paint)
    }
}

/** One reading theme shown as a disc with a sample character. */
class SwatchView(private val ui: Ui, private val fillColor: Int, private val textColor: Int) : View(ui.context) {
    var chosen = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Shrinks on narrow screens so that six of them always fit in a row.
        val r = minOf(ui.dpf(19f), width / 2f - ui.dpf(6.5f))
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        if (ui.soft) {
            ui.softShadow(paint, false); canvas.drawCircle(cx, cy, r, paint)
            ui.softShadow(paint, true); canvas.drawCircle(cx, cy, r, paint)
            paint.clearShadowLayer()
        }
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        if (!ui.soft) {
            paint.strokeWidth = ui.dpf(1f)
            paint.color = ui.p.outline
            canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
        }
        if (chosen) {
            paint.strokeWidth = ui.dpf(2f)
            paint.color = ui.p.accent
            canvas.drawCircle(cx, cy, r + ui.dpf(4f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = ui.dpf(15f)
        canvas.drawText("文", cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
