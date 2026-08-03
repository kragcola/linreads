package dev.readflow.render.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Opaque static SLIDE page-turn renderer. Per-frame motion is a View translation, so this renderer's
 * display list is recorded once and its two page-shot bitmaps are never reallocated or copied per
 * MOVE. The fully covered live TextView is skipped until the overlay is removed.
 *
 * The View is a 2W x H strip laid out in content coordinates. Forward content order is
 * [front][revealed] with layout left = 0; backward is [revealed][front] with layout left = -W.
 * Top is the current scrollY content coordinate. [PageSlideDrawable] remains the zero-copy bitmap
 * owner; this View only records the same geometry and seam treatment once.
 */
internal class PageSlideOverlayView(
    context: Context,
    frontBitmap: Bitmap,
    revealedBitmap: Bitmap,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val density: Float,
) : View(context) {

    private var frontBitmap: Bitmap? = frontBitmap
    private var revealedBitmap: Bitmap? = revealedBitmap

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapSrc = Rect()
    private val bitmapDst = RectF()
    private val shadowMatrix = android.graphics.Matrix()
    private val seamShadowShader = LinearGradient(
        0f,
        0f,
        1f,
        0f,
        0x40000000,
        0x00000000,
        Shader.TileMode.CLAMP,
    )

    init {
        // The renderer is a noninteractive visual layer; accessibility stays on the live TextView.
        isFocusable = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = viewportW.toFloat()
        val h = viewportH.toFloat()
        if (forward) {
            frontBitmap?.let { drawBitmapWindow(canvas, it, 0f, w, h) }
            revealedBitmap?.let { drawBitmapWindow(canvas, it, w, w, h) }
        } else {
            revealedBitmap?.let { drawBitmapWindow(canvas, it, 0f, w, h) }
            frontBitmap?.let { drawBitmapWindow(canvas, it, w, w, h) }
        }
        drawSeamShadow(canvas, w, h)
    }

    /** Motion shots are viewport-sized and stay on the 1:1 blit path; keep the mapping fallback for legacy owners. */
    private fun drawBitmapWindow(canvas: Canvas, bitmap: Bitmap, left: Float, w: Float, h: Float) {
        if (bitmap.width == viewportW && bitmap.height == viewportH) {
            canvas.drawBitmap(bitmap, left, 0f, paint)
            return
        }
        bitmapSrc.set(0, 0, bitmap.width, bitmap.height)
        bitmapDst.set(left, 0f, left + w, h)
        canvas.drawBitmap(bitmap, bitmapSrc, bitmapDst, paint)
    }

    /**
     * Atomically drops both bitmap references and returns the unique recorded identities without
     * recycling or copying. Called by the flow view before any drawable transfer or recycle so the
     * HWUI display list that recorded these bitmaps can be retired behind a two-frame fence.
     */
    fun takeRecordedBitmaps(): List<Bitmap> {
        val front = frontBitmap
        val revealed = revealedBitmap
        frontBitmap = null
        revealedBitmap = null
        invalidate()
        return when {
            front == null -> listOfNotNull(revealed)
            revealed == null || revealed === front -> listOf(front)
            else -> listOf(front, revealed)
        }
    }

    /** A soft drop shadow on the outgoing page's trailing edge, at the strip-local seam x = W. */
    private fun drawSeamShadow(canvas: Canvas, w: Float, h: Float) {
        val shadowW = min(14f * density, w * 0.06f)
        val edge = w
        val to = if (forward) edge + shadowW else edge - shadowW
        shadowMatrix.setScale(if (forward) shadowW else -shadowW, 1f)
        shadowMatrix.postTranslate(edge, 0f)
        seamShadowShader.setLocalMatrix(shadowMatrix)
        shadePaint.shader = seamShadowShader
        canvas.drawRect(min(edge, to), 0f, max(edge, to), h, shadePaint)
        shadePaint.shader = null
    }
}
