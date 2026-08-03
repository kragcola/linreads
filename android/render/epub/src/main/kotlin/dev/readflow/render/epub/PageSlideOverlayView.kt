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
 * Persistent staged three-page SLIDE strip for warm same-chapter turns.
 *
 * The renderer stays attached to the flow view's overlay while idle (alpha 0). During artifact
 * precache the flow view stages previous/current/next pages here; the next HWUI display-list record
 * pass bakes the page commands directly into this View's own RenderNode (via the flow view's
 * page-draw callback, never through [SlidePageArtifact.drawTo]). A warm turn then promotes the same
 * instance using only render properties (alpha/translationX), so the page content is never
 * re-recorded inside the turn frame. After settle/cancel the renderer returns to alpha 0 and stays
 * attached; it is reconfigured only during a later precache.
 *
 * The strip is 3W wide laid out at left = -W so the visible window [0, W] shows the current page at
 * rest. Previous sits at strip [-W, 0], current at [0, W], next at [W, 2W]; promotion translates the
 * whole strip ±progress*W. The directional seam shadow lives in a separate lightweight sibling view
 * ([SlideSeamShadowView]) so flipping it never invalidates this content display list.
 */
internal class PageSlideOverlayView(
    context: Context,
    private val flowView: EpubFlowView,
    private val viewportW: Int,
    private val viewportH: Int,
    private val density: Float,
) : View(context) {

    /** One staged strip slot: page content top and its paginated window (null for missing pages). */
    private class StripPage(
        val topPx: Int,
        val window: EpubFlowPage?,
    )

    private var previousPage: StripPage? = null
    private var currentPage: StripPage? = null
    private var nextPage: StripPage? = null
    /** Exact artifact identities the staged strip is bound to (control tokens; never drawn). */
    private var stagedPreviousArtifact: SlidePageArtifact? = null
    private var stagedCurrentArtifact: SlidePageArtifact? = null
    private var stagedNextArtifact: SlidePageArtifact? = null
    private var stageGenerationValue = 0L
    private var recordedGeneration = -1L

    /** Frames of the currently promoted turn, kept only for the ownership/retirement contract. */
    private var activeTurnFrames: List<SlidePageFrame> = emptyList()

    /** Cold BitmapFrame pair for cold/boundary/continuity turns (unchanged bitmap path). */
    private var coldFrontBitmap: Bitmap? = null
    private var coldRevealedBitmap: Bitmap? = null
    private var coldForward = true

    /** Whether the strip content has been staged and may be drawn. Parked renderers draw nothing. */
    private var staged = false

    /** Test-visible record-pass counter: incremented only by a real staged onDraw pass. */
    internal var contentRecordPassesForTest: Int = 0
        private set

    /** Current stage generation (bumps only when a new stage is staged). */
    internal val stageGeneration: Long
        get() = stageGenerationValue

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapSrc = Rect()
    private val bitmapDst = RectF()
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    /**
     * Stages the three-page strip bound to exact artifact identities. The strip is marked
     * unrecorded and invalidated; only a real staged [onDraw] pass marks that exact generation
     * recorded, so promotion can never outrun the hidden display-list record traversal.
     */
    fun stageStrip(
        previousTop: Int?,
        previousWindow: EpubFlowPage?,
        previousArtifact: SlidePageArtifact?,
        currentTop: Int,
        currentWindow: EpubFlowPage?,
        currentArtifact: SlidePageArtifact,
        nextTop: Int?,
        nextWindow: EpubFlowPage?,
        nextArtifact: SlidePageArtifact?,
    ) {
        previousPage = previousTop?.let { StripPage(it, previousWindow) }
        currentPage = StripPage(currentTop, currentWindow)
        nextPage = nextTop?.let { StripPage(it, nextWindow) }
        stagedPreviousArtifact = previousArtifact
        stagedCurrentArtifact = currentArtifact
        stagedNextArtifact = nextArtifact
        staged = true
        activeTurnFrames = emptyList()
        stageGenerationValue += 1L
        invalidate()
    }

    /**
     * Exact staged triple match: previous/current/next artifact identities (null-aware) must all
     * match, independent of record readiness. Used to avoid re-staging the same exact generation.
     */
    fun matchesStagedArtifacts(
        previousArtifact: SlidePageArtifact?,
        currentArtifact: SlidePageArtifact,
        nextArtifact: SlidePageArtifact?,
    ): Boolean =
        staged &&
            stagedPreviousArtifact === previousArtifact &&
            stagedCurrentArtifact === currentArtifact &&
            stagedNextArtifact === nextArtifact

    /** True when the staged strip is bound to this exact current artifact identity. */
    fun isStagedCurrentArtifact(artifact: SlidePageArtifact): Boolean =
        staged && stagedCurrentArtifact === artifact

    /**
     * True when the staged strip is bound to the exact current + direction-specific revealed
     * artifact identity (forward -> next, reverse -> previous).
     */
    fun isStagedPairForDirection(
        currentArtifact: SlidePageArtifact,
        revealedArtifact: SlidePageArtifact,
        forward: Boolean,
    ): Boolean =
        isStagedCurrentArtifact(currentArtifact) &&
            if (forward) {
                stagedNextArtifact === revealedArtifact
            } else {
                stagedPreviousArtifact === revealedArtifact
            }

    /** True when the viewport dimensions still match the renderer's construction-time values. */
    fun matchesViewport(viewportWidth: Int, viewportHeight: Int): Boolean =
        this.viewportW == viewportWidth && this.viewportH == viewportHeight

    /** True when the staged content has been recorded for the current stage generation. */
    fun isRecorded(): Boolean = staged && recordedGeneration == stageGenerationValue

    /** Promotes the staged strip for a warm turn. Render properties only; never re-records content. */
    fun promote(frames: List<SlidePageFrame>, forward: Boolean, progress: Float) {
        activeTurnFrames = frames
        applyProgress(forward, progress)
    }

    /** Updates only render properties (alpha/translation) for the promoted strip. */
    fun applyProgress(forward: Boolean, progress: Float) {
        alpha = 1f
        translationX = (if (forward) -progress else progress) * viewportW
    }

    /** Returns to the hidden idle state after settle/cancel. Render properties only. */
    fun park() {
        alpha = 0f
        translationX = 0f
        activeTurnFrames = emptyList()
        coldFrontBitmap = null
        coldRevealedBitmap = null
    }

    /** Installs a cold BitmapFrame pair for a fresh two-page overlay (boundary/cold/continuity). */
    fun beginColdTurn(front: Bitmap, revealed: Bitmap, forward: Boolean, frames: List<SlidePageFrame>) {
        coldFrontBitmap = front
        coldRevealedBitmap = revealed
        coldForward = forward
        activeTurnFrames = frames
        alpha = 1f
    }

    /**
     * Resets the staged page content, artifact identities, and record readiness. Invalidates this
     * View so a stale display list is never replayed, and parks it hidden. Callers must only invoke
     * this while the staged strip is not the actively displayed turn (i.e. after detach/park).
     */
    fun clearStagedContent() {
        staged = false
        previousPage = null
        currentPage = null
        nextPage = null
        stagedPreviousArtifact = null
        stagedCurrentArtifact = null
        stagedNextArtifact = null
        stageGenerationValue += 1L
        recordedGeneration = -1L
        activeTurnFrames = emptyList()
        park()
        invalidate()
    }

    /**
     * Returns the promoted turn's frames without consuming them, and clears the internal reference.
     * The flow view keeps the artifact ownership contract (cache slots / retirement fence) while this
     * renderer only used the frames as the control token for the visual transaction.
     */
    fun takeRecordedFrames(): List<SlidePageFrame> {
        val frames = activeTurnFrames
        activeTurnFrames = emptyList()
        return frames
    }

    /** Legacy identity accessor for Bitmap-framed renderers (cold/boundary/continuity turns). */
    fun takeRecordedBitmaps(): List<android.graphics.Bitmap> =
        takeRecordedFrames().mapNotNull { (it as? SlidePageFrame.BitmapFrame)?.bitmap }

    override fun onDraw(canvas: Canvas) {
        val coldFront = coldFrontBitmap
        val coldRevealed = coldRevealedBitmap
        if (coldFront != null && coldRevealed != null) {
            val w = viewportW.toFloat()
            if (coldForward) {
                drawBitmapWindow(canvas, coldFront, 0f, w)
                drawBitmapWindow(canvas, coldRevealed, w, w)
            } else {
                drawBitmapWindow(canvas, coldRevealed, 0f, w)
                drawBitmapWindow(canvas, coldFront, w, w)
            }
            drawColdSeamShadow(canvas, w)
            return
        }
        if (!staged) return
        val w = viewportW.toFloat()
        // The staged View is laid out at local strip left = -W with width = 3W, so page-local X
        // origins are previous = 0, current = W, next = 2W. Vertical page offsets never enter the
        // horizontal placement; drawPageInto positions content vertically itself.
        previousPage?.let { page ->
            val save = canvas.save()
            canvas.translate(0f, 0f)
            flowView.slideStripDrawXOriginsForTest?.add(0f)
            flowView.drawSlideStripPage(canvas, page.topPx, page.window)
            canvas.restoreToCount(save)
        }
        currentPage?.let { page ->
            val save = canvas.save()
            canvas.translate(w, 0f)
            flowView.slideStripDrawXOriginsForTest?.add(w)
            flowView.drawSlideStripPage(canvas, page.topPx, page.window)
            canvas.restoreToCount(save)
        }
        nextPage?.let { page ->
            val save = canvas.save()
            canvas.translate(2f * w, 0f)
            flowView.slideStripDrawXOriginsForTest?.add(2f * w)
            flowView.drawSlideStripPage(canvas, page.topPx, page.window)
            canvas.restoreToCount(save)
        }
        recordedGeneration = stageGenerationValue
        contentRecordPassesForTest += 1
    }

    /** Baseline seam shadow for fresh cold/boundary/continuity Bitmap SLIDE overlays. */
    private fun drawColdSeamShadow(canvas: Canvas, w: Float) {
        val shadowW = min(14f * density, w * 0.06f)
        val edge = w
        val to = if (coldForward) edge + shadowW else edge - shadowW
        shadowMatrix.setScale(if (coldForward) shadowW else -shadowW, 1f)
        shadowMatrix.postTranslate(edge, 0f)
        seamShadowShader.setLocalMatrix(shadowMatrix)
        shadePaint.shader = seamShadowShader
        canvas.drawRect(min(edge, to), 0f, max(edge, to), viewportH.toFloat(), shadePaint)
        shadePaint.shader = null
    }

    /** Motion shots are viewport-sized and stay on the 1:1 blit path; keep the mapping fallback. */
    private fun drawBitmapWindow(canvas: Canvas, bitmap: Bitmap, left: Float, w: Float) {
        if (bitmap.width == viewportW && bitmap.height == viewportH) {
            canvas.drawBitmap(bitmap, left, 0f, paint)
            return
        }
        bitmapSrc.set(0, 0, bitmap.width, bitmap.height)
        bitmapDst.set(left, 0f, left + w, viewportH.toFloat())
        canvas.drawBitmap(bitmap, bitmapSrc, bitmapDst, paint)
    }

    /** Shadow band width used to size the sibling seam-shadow view. */
    fun seamShadowWidthPx(): Int =
        min(14f * density, viewportW * 0.06f).toInt().coerceAtLeast(1)
}
