package io.github.jeefyxr.diting.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewOutlineProvider
import java.io.File

/**
 * A book cover at a fixed 3:4.2 shape. Shows the book's own image when it has one; otherwise
 * typesets the title on a cloth-coloured board, so plain text files still look like books.
 */
class CoverView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        typeface = Ui.SERIF_BOLD
    }
    private val src = Rect()
    private val dst = RectF()
    private var bitmap: Bitmap? = null
    private var title = ""
    private var board = CLOTHS[0]

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 5 * density)
        }
        clipToOutline = true
        elevation = 5 * density
    }

    fun show(bookTitle: String, image: File?) {
        title = bookTitle
        board = CLOTHS[Math.floorMod(bookTitle.hashCode(), CLOTHS.size)]
        bitmap = image?.let(::decode)
        invalidate()
    }

    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 1.4f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val b = bitmap
        if (b != null) {
            // Centre-crop.
            val scale = maxOf(w / b.width, h / b.height)
            val cw = (w / scale).toInt()
            val ch = (h / scale).toInt()
            src.set((b.width - cw) / 2, (b.height - ch) / 2, (b.width + cw) / 2, (b.height + ch) / 2)
            dst.set(0f, 0f, w, h)
            paint.shader = null
            canvas.drawBitmap(b, src, dst, paint)
        } else {
            paint.shader = LinearGradient(0f, 0f, w, h, board, Palette.blend(board, 0xFF000000.toInt(), 0.28f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            // A thin inset frame, like a stamped border.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, density * 0.75f)
            paint.color = 0x40FFFFFF
            val inset = w * 0.09f
            canvas.drawRect(inset, inset, w - inset, h - inset, paint)
            paint.style = Paint.Style.FILL

            titlePaint.textSize = w * if (title.length <= 4) 0.19f else 0.15f
            val textWidth = (w - inset * 3.2f).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.25f)
                .setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate((w - textWidth) / 2, h * 0.22f)
            layout.draw(canvas)
            canvas.restore()
        }
        // The spine: a soft dark band with a highlight beside it.
        paint.shader = LinearGradient(
            0f, 0f, w * 0.11f, 0f,
            intArrayOf(0x47000000, 0x14000000, 0x1FFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w * 0.11f, h, paint)
        paint.shader = null
    }

    private companion object {
        val CLOTHS = intArrayOf(
            0xFF8A5A44.toInt(), 0xFF4F6B5C.toInt(), 0xFF4A6382.toInt(),
            0xFF8A4B57.toInt(), 0xFF655882.toInt(), 0xFF7A7550.toInt(), 0xFF3F6E78.toInt(),
        )
    }
}
