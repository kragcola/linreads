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
 * display list is recorded once and its two page frames are never reallocated or copied per MOVE.
 * Warm same-chapter turns carry [SlidePageFrame.ArtifactFrame] command artifacts (no viewport-sized
 * page-shot Bitmap); cold deferred MOVE handoffs, boundary, and continuity turns keep the
 * [SlidePageFrame.BitmapFrame] pair. The fully covered live TextView is skipped until the overlay is
 * removed.
 *
 * The View is a 2W x H strip laid out in content coordinates. Forward content order is
 * [front][revealed] with layout left = 0; backward is [revealed][front] with layout left = -W.
 * Top is the current scrollY content coordinate. [PageSlideDrawable] remains the zero-copy frame
 * owner; this View only records the same geometry and seam treatment once. Warm artifact frames are
 * replayed synchronously through [SlidePageArtifact.drawTo] when the overlay is first drawn — the
 * pair is admitted without any hidden alpha-0 record prerequisite.
 */
internal class PageSlideOverlayView(
    context: Context,
    frontFrame: SlidePageFrame,
    revealedFrame: SlidePageFrame,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val density: Float,
) : View(context) {

    private var frontFrame: SlidePageFrame? = frontFrame
    private var revealedFrame: SlidePageFrame? = revealedFrame

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapSrc = Rect()
    private val bitmapDst = RectF()
    // The seam shadow is a direction-specific LinearGradient in actual strip coordinates, with the
    // dark edge pinned to the seam x = W. Precomputed once so no draw allocates a shader or Matrix.
    private val forwardSeamShadow = LinearGradient(
        viewportW.toFloat(),
        0f,
        viewportW + seamShadowWidthPx().toFloat(),
        0f,
        0x40000000,
        0x00000000,
        Shader.TileMode.CLAMP,
    )
    private val backwardSeamShadow = LinearGradient(
        viewportW - seamShadowWidthPx().toFloat(),
        0f,
        viewportW.toFloat(),
        0f,
        0x00000000,
        0x40000000,
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
        val timingStartNs = EpubRapidIdleWorkProbe.beginTimingNs()
        try {
            val w = viewportW.toFloat()
            val h = viewportH.toFloat()
            if (forward) {
                drawFrame(canvas, frontFrame, 0f, w, h)
                drawFrame(canvas, revealedFrame, w, w, h)
            } else {
                drawFrame(canvas, revealedFrame, 0f, w, h)
                drawFrame(canvas, frontFrame, w, w, h)
            }
            drawSeamShadow(canvas, w, h)
        } finally {
            EpubRapidIdleWorkProbe.endOverlayDrawTiming(timingStartNs)
        }
    }

    /** Draws one strip-local page frame at [left]; artifacts replay at their recorded viewport size. */
    private fun drawFrame(canvas: Canvas, frame: SlidePageFrame?, left: Float, w: Float, h: Float) {
        when (frame) {
            is SlidePageFrame.BitmapFrame -> drawBitmapWindow(canvas, frame.bitmap, left, w, h)
            is SlidePageFrame.ArtifactFrame -> {
                val save = canvas.save()
                try {
                    canvas.translate(left, 0f)
                    frame.artifact.drawTo(canvas)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
            null -> Unit
        }
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
     * HWUI display list that recorded these frames can be retired behind a two-frame fence.
     */
    fun takeRecordedFrames(): List<SlidePageFrame> {
        val front = frontFrame
        val revealed = revealedFrame
        frontFrame = null
        revealedFrame = null
        invalidate()
        return when {
            front == null -> listOfNotNull(revealed)
            revealed == null || revealed === front -> listOf(front)
            else -> listOf(front, revealed)
        }
    }

    /** Legacy identity accessor for Bitmap-framed renderers (cold/boundary/continuity turns). */
    fun takeRecordedBitmaps(): List<Bitmap> =
        takeRecordedFrames().mapNotNull { (it as? SlidePageFrame.BitmapFrame)?.bitmap }

    /** Shadow band width used by the baseline seam treatment; mirrors [drawSeamShadow]. */
    fun seamShadowWidthPx(): Int =
        min(14f * density, viewportW * 0.06f).toInt().coerceAtLeast(1)

    /** A soft drop shadow on the outgoing page's trailing edge, at the strip-local seam x = W. */
    private fun drawSeamShadow(canvas: Canvas, w: Float, h: Float) {
        val shadowW = min(14f * density, w * 0.06f)
        val edge = w
        val to = if (forward) edge + shadowW else edge - shadowW
        shadePaint.shader = if (forward) forwardSeamShadow else backwardSeamShadow
        canvas.drawRect(min(edge, to), 0f, max(edge, to), h, shadePaint)
        shadePaint.shader = null
    }
}
