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
 * page is blitted at a horizontal offset that tracks [progress]. Every turn also carries the frozen
 * incoming page frame so a frame never mixes a generation with the parked live view. This avoids
 * the Canvas mesh work used by the PAPER renderer.
 *
 * Warm same-chapter SLIDE turns carry [SlidePageFrame.ArtifactFrame] command artifacts recorded by
 * [SlidePageArtifact]; [PageSlideOverlayView] draws the static 2W x H strip and this Drawable stays
 * the detached progress/ownership state (it never draws the artifact strip). Cold deferred MOVE
 * handoffs, boundary, and continuity turns keep the [SlidePageFrame.BitmapFrame] pair.
 *
 * Forward (next): both pages slide LEFT together — outgoing exits left, incoming enters from the right.
 * Backward (prev): mirrored — both slide RIGHT, incoming enters from the left.
 *
 * A soft edge shadow is drawn on the leading seam between the two pages for depth.
 */
internal class PageSlideDrawable(
    frontFrame: SlidePageFrame,
    revealedFrame: SlidePageFrame,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val density: Float,
    private val frameRecycler: (SlidePageFrame) -> Unit = { frame ->
        when (frame) {
            is SlidePageFrame.BitmapFrame -> if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            is SlidePageFrame.ArtifactFrame -> frame.artifact.discard()
        }
    },
) : Drawable() {

    /** Legacy Bitmap-pair constructor kept for cold/boundary/continuity SLIDE compatibility. */
    constructor(
        frontBitmap: Bitmap,
        revealedBitmap: Bitmap,
        viewportW: Int,
        viewportH: Int,
        forward: Boolean,
        density: Float,
        bitmapRecycler: (Bitmap) -> Unit = { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        },
    ) : this(
        SlidePageFrame.BitmapFrame(frontBitmap),
        SlidePageFrame.BitmapFrame(revealedBitmap),
        viewportW,
        viewportH,
        forward,
        density,
        { frame -> if (frame is SlidePageFrame.BitmapFrame) bitmapRecycler(frame.bitmap) },
    )

    private var frontFrame: SlidePageFrame? = frontFrame
    private var revealedFrame: SlidePageFrame? = revealedFrame
    /** Bitmap aliases for legacy/cold pairs; null while the pair is artifact-framed. */
    private var frontBitmap: Bitmap? = (frontFrame as? SlidePageFrame.BitmapFrame)?.bitmap
    private var revealedBitmap: Bitmap? = (revealedFrame as? SlidePageFrame.BitmapFrame)?.bitmap

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
        sourceXForViewportX(
            incomingLeft(viewportW.toFloat()),
            viewportX.toFloat(),
            viewportW.toFloat(),
            viewportW,
        )

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

        // Viewport-sized page shots keep a clipped, translated 1:1 blit. Legacy reduced owners use
        // the scaled source/destination path below:
        // drawBitmap(src,dst) makes the GPU run a filtered scale on every MOVE even when both rects
        // are the same size, which is especially costly for image-heavy EPUB pages.
        if (bitmap.width == viewportW && bitmap.height == viewportH) {
            val save = canvas.save()
            try {
                canvas.clipRect(visibleLeft, 0f, visibleRight, h)
                canvas.translate(left, 0f)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            } finally {
                canvas.restoreToCount(save)
            }
            return
        }

        val srcLeft = sourceXForViewportX(left, visibleLeft, w, bitmap.width) ?: return
        val srcRight = sourceXForViewportX(left, visibleRight, w, bitmap.width)
            ?.coerceIn(srcLeft, bitmap.width)
            ?: bitmap.width
        if (srcRight <= srcLeft) return
        bitmapSrc.set(srcLeft, 0, srcRight, bitmap.height)
        bitmapDst.set(visibleLeft, 0f, visibleRight, h)
        canvas.drawBitmap(bitmap, bitmapSrc, bitmapDst, paint)
    }

    private fun sourceXForViewportX(
        left: Float,
        viewportX: Float,
        w: Float,
        sourceWidth: Int,
    ): Int? {
        val sourceX = viewportX - left
        if (sourceX < 0f || sourceX > w) return null
        return (sourceX * sourceWidth.toFloat() / w).toInt().coerceIn(0, sourceWidth)
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

    /** The owned Bitmap or artifact behind a frame, for alias-aware ownership comparisons. */
    private fun SlidePageFrame?.underlyingResource(): Any? = when (this) {
        is SlidePageFrame.BitmapFrame -> bitmap
        is SlidePageFrame.ArtifactFrame -> artifact
        null -> null
    }

    /** Transfers the revealed Bitmap to the caller without copying it. Null and non-consuming for artifact frames. */
    fun takeRevealedBitmap(): Bitmap? {
        val frame = revealedFrame
        if (frame !is SlidePageFrame.BitmapFrame) return null
        val bitmap = frame.bitmap
        revealedFrame = null
        if (frontFrame.underlyingResource() === frame.underlyingResource()) frontFrame = null
        revealedBitmap = null
        if (frontBitmap === bitmap) frontBitmap = null
        return bitmap?.takeUnless { it.isRecycled }
    }

    /** Transfers the outgoing Bitmap to the caller without copying it. Null and non-consuming for artifact frames. */
    fun takeFrontBitmap(): Bitmap? {
        val frame = frontFrame
        if (frame !is SlidePageFrame.BitmapFrame) return null
        val bitmap = frame.bitmap
        frontFrame = null
        if (revealedFrame.underlyingResource() === frame.underlyingResource()) revealedFrame = null
        frontBitmap = null
        if (revealedBitmap === bitmap) revealedBitmap = null
        return bitmap?.takeUnless { it.isRecycled }
    }

    /** Transfers the revealed frame (Bitmap or artifact) to the caller without copying it. */
    fun takeRevealedFrame(): SlidePageFrame? {
        val frame = revealedFrame
        revealedFrame = null
        if (frontFrame.underlyingResource() === frame.underlyingResource()) frontFrame = null
        if (frame is SlidePageFrame.BitmapFrame) {
            val bitmap = frame.bitmap
            if (revealedBitmap === bitmap) revealedBitmap = null
            if (frontBitmap === bitmap) frontBitmap = null
        }
        return frame
    }

    /** Transfers the outgoing frame (Bitmap or artifact) to the caller without copying it. */
    fun takeFrontFrame(): SlidePageFrame? {
        val frame = frontFrame
        frontFrame = null
        if (revealedFrame.underlyingResource() === frame.underlyingResource()) revealedFrame = null
        if (frame is SlidePageFrame.BitmapFrame) {
            val bitmap = frame.bitmap
            if (frontBitmap === bitmap) frontBitmap = null
            if (revealedBitmap === bitmap) revealedBitmap = null
        }
        return frame
    }

    fun recycle() {
        val front = frontFrame
        val revealed = revealedFrame
        frontFrame = null
        revealedFrame = null
        frontBitmap = null
        revealedBitmap = null
        if (front != null) frameRecycler(front)
        if (revealed != null && (front == null || front.underlyingResource() !== revealed.underlyingResource())) {
            frameRecycler(revealed)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
