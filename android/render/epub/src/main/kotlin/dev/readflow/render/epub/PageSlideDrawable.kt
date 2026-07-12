package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware-accelerated slide page-turn (滑动翻页, 静读天下「滑动」手感). A snapshot of the OUTGOING
 * page is blitted at a horizontal offset that tracks [progress]; the INCOMING page (the real view
 * content) is slid in alongside it by the host via [android.view.View.setTranslationX]. Both layers
 * are plain GPU transforms (a single [Canvas.drawBitmap] translate here + a render-node translation
 * there), so the turn composites on the GPU and holds 60fps at any resolution — unlike the prior
 * Canvas mesh curl, which warped a full-screen software bitmap on the UI thread every frame.
 *
 * Forward (next): both pages slide LEFT together — outgoing exits left, incoming enters from the right.
 * Backward (prev): mirrored — both slide RIGHT, incoming enters from the left.
 *
 * A soft edge shadow is drawn on the leading seam between the two pages for depth.
 */
internal class PageSlideDrawable(
    frontBitmap: Bitmap,
    revealedBitmap: Bitmap,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val density: Float,
) : Drawable() {

    private var frontBitmap: Bitmap? = frontBitmap
    private var revealedBitmap: Bitmap? = revealedBitmap

    /** 0 = outgoing page fully covers the viewport, 1 = outgoing fully slid off (turn complete). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

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

    override fun draw(canvas: Canvas) {
        // The host is a ScrollView, so its ViewOverlay draws in CONTENT coordinates (canvas already
        // translated by scrollY). [setBounds] is the live viewport in that space; translate to its
        // top-left, then everything is local 0..W / 0..H.
        val w = viewportW.toFloat()
        // Forward: outgoing exits to the left (dx 0 → -W). Backward: exits right (dx 0 → +W).
        val dx = outgoingLeft(w)
        val incomingDx = incomingLeft(w)
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        revealedBitmap?.let { drawBitmapWindow(canvas, it, incomingDx, w, viewportH.toFloat()) }
        frontBitmap?.let { drawBitmapWindow(canvas, it, dx, w, viewportH.toFloat()) }
        drawSeamShadow(canvas, dx, w, viewportH.toFloat())
        canvas.restoreToCount(save)
    }

    internal fun incomingSourceXForViewportX(viewportX: Int): Int? =
        sourceXForViewportX(incomingLeft(viewportW.toFloat()), viewportX.toFloat(), viewportW.toFloat())

    private fun outgoingLeft(w: Float): Float =
        if (forward) -progress * w else progress * w

    private fun incomingLeft(w: Float): Float {
        val dx = outgoingLeft(w)
        return if (forward) w + dx else dx - w
    }

    private fun drawBitmapWindow(canvas: Canvas, bitmap: Bitmap, left: Float, w: Float, h: Float) {
        val visibleLeft = max(0f, left)
        val visibleRight = min(w, left + w)
        if (visibleRight <= visibleLeft) return
        val srcLeft = sourceXForViewportX(left, visibleLeft, w) ?: return
        val srcRight = sourceXForViewportX(left, visibleRight, w)
            ?.coerceIn(srcLeft, viewportW)
            ?: viewportW
        if (srcRight <= srcLeft) return
        bitmapSrc.set(srcLeft, 0, srcRight, viewportH)
        bitmapDst.set(visibleLeft, 0f, visibleRight, h)
        canvas.drawBitmap(bitmap, bitmapSrc, bitmapDst, paint)
    }

    private fun sourceXForViewportX(left: Float, viewportX: Float, w: Float): Int? {
        val sourceX = viewportX - left
        if (sourceX < 0f || sourceX > w) return null
        return sourceX.toInt().coerceIn(0, viewportW)
    }

    /** A soft drop shadow on the outgoing page's trailing edge — the seam where the incoming page meets it. */
    private fun drawSeamShadow(canvas: Canvas, outgoingLeft: Float, w: Float, h: Float) {
        if (progress <= 0f) return
        val shadowW = min(14f * density, w * 0.06f)
        // The incoming page abuts the outgoing edge that faces the slide direction: forward → right edge.
        val edge = outgoingLeft + if (forward) w else 0f
        val to = if (forward) edge + shadowW else edge - shadowW
        shadowMatrix.setScale(if (forward) shadowW else -shadowW, 1f)
        shadowMatrix.postTranslate(edge, 0f)
        seamShadowShader.setLocalMatrix(shadowMatrix)
        shadePaint.shader = seamShadowShader
        canvas.drawRect(min(edge, to), 0f, max(edge, to), h, shadePaint)
        shadePaint.shader = null
    }

    /** Transfers the revealed page to the caller without copying it. */
    fun takeRevealedBitmap(): Bitmap? {
        val bitmap = revealedBitmap
        revealedBitmap = null
        if (frontBitmap === bitmap) frontBitmap = null
        return bitmap?.takeUnless { it.isRecycled }
    }

    fun recycle() {
        val front = frontBitmap
        val revealed = revealedBitmap
        frontBitmap = null
        revealedBitmap = null
        if (front != null && !front.isRecycled) front.recycle()
        if (revealed != null && revealed !== front && !revealed.isRecycled) revealed.recycle()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
