package dev.readflow.render.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import fi.harism.curl.CurlPage
import fi.harism.curl.CurlView

internal interface EpubCurlTurnOverlay {
    val active: Boolean

    fun start(
        front: Bitmap,
        revealed: Bitmap,
        forward: Boolean,
        settled: (committed: Boolean) -> Unit,
        firstFrameReady: () -> Unit = {},
    )

    fun animateTurn(durationMs: Long)

    fun dismiss()
}

/**
 * Transient OpenGL page-curl overlay (仿真翻页, harism android-pagecurl Apache-2.0 engine). Shown only
 * during a SIMULATION page turn over the reading area, then removed. It holds exactly TWO pages — the
 * page being turned (front) and the page revealed beneath — fed as bitmaps the host snapshots from the
 * live [EpubFlowView]. The turned page's BACK face reuses the same texture with a dim blend, giving the
 * Moon+ "纸背透出本页文字" (see-through reverse text) effect; harism's mesh handles the geometric flip so
 * the back appears mirrored automatically.
 *
 * Discrete tap/key animation, lighting, and shadow come from [CurlView]. Finger drags stay in the host
 * View hierarchy so their frames participate in the same Window composition as the reader content.
 */
internal class EpubCurlOverlay(
    context: Context,
    private val textureCopier: (Bitmap, Bitmap.Config) -> Bitmap? = { source, config ->
        source.copy(config, false)
    },
) : FrameLayout(context), EpubCurlTurnOverlay {

    /**
     * Back-face tint (Moon+ 反字: the curled underside shows this page's own content, mirrored by the
     * mesh geometry, tinted down so it reads as "ink seen through paper"). Stock harism dimmed to ~55%
     * (0x8C) which looked muddy-dark on a light theme (审计: 颜色过黑); 0xCC ≈ 80% keeps the reverse text
     * legible-but-faint like real paper.
     */
    private val backFaceBlend = Color.argb(0xFF, 0xCC, 0xCC, 0xCC)

    private val bitmapOwnershipLock = Any()
    private var frontBitmap: Bitmap? = null
    private var revealedBitmap: Bitmap? = null
    private var forward = true
    /** Per-turn settle callback (committed = whether the turn advanced to the revealed page). */
    private var onTurnSettled: ((committed: Boolean) -> Unit)? = null
    /** True between start() and the settle callback; also gates double-starts. */
    override var active = false
        private set
    private var pendingDiscreteTurnDurationMs: Long? = null
    private var pendingDiscreteTurnRunnable: Runnable? = null
    private var turnFrameReady = false
    private var turnVisibilityGeneration = 0L
    @Volatile
    private var texturePreparationFailure: Throwable? = null

    /** Safety dismiss: if harism never fires setCurlAnimationObserver, force-clean after 5s. */
    private val safetyDismissRunnable = Runnable {
        if (active) {
            turnVisibilityGeneration++
            turnFrameReady = false
            curlView.abortTurn()
            active = false
            alpha = 0f
            clearPendingDiscreteTurn()
            onTurnSettled?.invoke(false)
            onTurnSettled = null
            recycleBitmaps()
        }
    }

    /**
     * Two-page book for one turn. The turning page (book index 0 for a forward turn, book index 1 for a
     * backward one) is two-sided: front = its own content, back = the same content tinted for the Moon+
     * 反字 see-through effect (harism's mesh mirrors it). The other slot is the page revealed beneath,
     * drawn flat (SIDE_BOTH). [bmp0]/[bmp1] resolve which snapshot sits at which harism index per
     * direction, so a forward curl advances 0→1 and a backward curl retreats 1→0.
     */
    private val provider = object : CurlView.PageProvider {
        override fun getPageCount(): Int = 2
        override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) =
            synchronized(bitmapOwnershipLock) {
                if (texturePreparationFailure != null) return
                // The page that physically curls (and thus needs a back face) is index 0 forward, index 1 back.
                val curlingIndex = if (forward) 0 else 1
                val bmp = if (index == 0) bmp0() else bmp1()
                val src = bmp ?: return
                val cfg = src.config ?: Bitmap.Config.RGB_565
                if (index == curlingIndex) {
                    val front = copyTexture(src, cfg) ?: return
                    val back = copyTexture(src, cfg) ?: run {
                        if (!front.isRecycled) front.recycle()
                        return
                    }
                    page.setTexture(front, CurlPage.SIDE_FRONT)
                    page.setTexture(back, CurlPage.SIDE_BACK)
                    page.setColor(backFaceBlend, CurlPage.SIDE_BACK)
                } else {
                    val both = copyTexture(src, cfg) ?: return
                    page.setTexture(both, CurlPage.SIDE_BOTH)
                }
            }
    }

    private fun copyTexture(source: Bitmap, config: Bitmap.Config): Bitmap? =
        try {
            textureCopier(source, config) ?: run {
                recordTexturePreparationFailure(IllegalStateException("Page texture copy returned null"))
                null
            }
        } catch (failure: Throwable) {
            recordTexturePreparationFailure(failure)
            null
        }

    private fun recordTexturePreparationFailure(failure: Throwable) {
        if (texturePreparationFailure != null) return
        texturePreparationFailure = failure
        val failureGeneration = turnVisibilityGeneration
        post textureFailureCleanup@{
            if (failureGeneration != turnVisibilityGeneration) return@textureFailureCleanup
            settleTexturePreparationFailure()
        }
    }

    /** Snapshot at harism book index 0 (forward: current/turning page; backward: previous/revealed). */
    private fun bmp0(): Bitmap? = if (forward) frontBitmap else revealedBitmap

    /** Snapshot at harism book index 1 (forward: next/revealed; backward: current/stationary page). */
    private fun bmp1(): Bitmap? = if (forward) revealedBitmap else frontBitmap

    private val curlView = CurlView(context).apply {
        setViewMode(CurlView.SHOW_ONE_PAGE)
        // The page sits flush against the left edge in one-page mode; don't render the left (prev) slot.
        setRenderLeftPage(false)
        setAllowLastPageCurl(true)
        setEnableTouchPressure(false)
        setMargins(0f, 0f, 0f, 0f)
        // EGL/z-order/translucency are configured INSIDE CurlView.init() before setRenderer (required by
        // GLSurfaceView); only the clear color is safe to set post-construction.
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPageProvider(provider)
    }

    init {
        addView(curlView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        curlView.setCurlAnimationObserver { idx ->
            removeCallbacks(safetyDismissRunnable)
            // Direction-aware commit: a forward turn starts at index 0 and commits once it reaches 1; a
            // backward turn starts at index 1 and commits once it reaches 0. A spring-back leaves the
            // index unchanged, so it reports not-committed.
            val committed = if (forward) idx >= 1 else idx <= 0
            active = false
            clearPendingDiscreteTurn()
            val cb = onTurnSettled
            onTurnSettled = null
            cb?.invoke(committed)
        }
        // Keep the translucent SurfaceView attached after the first creation. Toggling GONE destroys its
        // surface, so the next turn can commit an opaque black initialization buffer before GL redraws.
        visibility = VISIBLE
        alpha = 0f
    }

    /** The flow view owns gesture classification; this visual surface only receives explicitly forwarded events. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    fun start(
        front: Bitmap,
        revealed: Bitmap,
        forward: Boolean,
        settled: (committed: Boolean) -> Unit,
    ) = start(front, revealed, forward, settled, firstFrameReady = {})

    /**
     * Begins a SIMULATION turn. [front] is the current (turning) page, [revealed] the adjacent page
     * beneath. [forward] picks the curl direction (true = next page). [settled] fires once the curl
     * animation finishes. The overlay becomes visible only after the queued texture frame is ready; the
     * caller then drives a discrete tap/key turn via [animateTurn].
     */
    override fun start(
        front: Bitmap,
        revealed: Bitmap,
        forward: Boolean,
        settled: (committed: Boolean) -> Unit,
        firstFrameReady: () -> Unit,
    ) {
        turnVisibilityGeneration++
        turnFrameReady = false
        clearPendingDiscreteTurn()
        curlView.abortTurn()
        synchronized(bitmapOwnershipLock) {
            recycleBitmapsLocked()
            texturePreparationFailure = null
            frontBitmap = front
            revealedBitmap = revealed
            this.forward = forward
        }
        onTurnSettled = settled
        active = true
        visibility = VISIBLE
        alpha = 0f
        // Safety timeout: if the curl never settles (GL error, synthetic DOWN not triggering harism state),
        // force-dismiss after 5s so turnInFlight doesn't stay stuck forever (审计: active 卡死 → 所有翻页失效).
        armSafetyDismiss()
        // Forward curls the page at index 0 over to reveal 1; backward starts parked on 1 and curls the
        // index-0 page back in from the left. setCurrentIndex re-runs the provider for the new anchor.
        curlView.setCurrentIndex(if (forward) 0 else 1)
        texturePreparationFailure?.let { failure ->
            dismiss()
            throw failure
        }
        val visibilityGeneration = turnVisibilityGeneration
        curlView.setNextFrameRenderedCallback frameReady@{
            if (!active || visibilityGeneration != turnVisibilityGeneration) return@frameReady
            if (settleTexturePreparationFailure()) return@frameReady
            turnFrameReady = true
            alpha = 1f
            firstFrameReady()
            schedulePendingDiscreteTurn()
        }
        curlView.requestRender()
        bringToFront()
    }

    /**
     * Drives a discrete (tap / key) turn: harism animates the pointer the whole way across at [durationMs]
     * and commits the index — a complete, visible curl (not the one-frame flash the old synthetic-event
     * burst produced, whose settle had ~0px left to travel; 审计: 翻页一闪而过).
     */
    override fun animateTurn(durationMs: Long) {
        if (!active) return
        pendingDiscreteTurnDurationMs = durationMs
        schedulePendingDiscreteTurn()
    }

    private fun schedulePendingDiscreteTurn() {
        if (!turnFrameReady || pendingDiscreteTurnRunnable != null || pendingDiscreteTurnDurationMs == null) return
        val visibilityGeneration = turnVisibilityGeneration
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (pendingDiscreteTurnRunnable !== runnable) return@Runnable
            pendingDiscreteTurnRunnable = null
            if (visibilityGeneration != turnVisibilityGeneration) return@Runnable
            runPendingDiscreteTurn(visibilityGeneration)
        }
        pendingDiscreteTurnRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun runPendingDiscreteTurn(visibilityGeneration: Long) {
        if (visibilityGeneration != turnVisibilityGeneration) return
        val durationMs = pendingDiscreteTurnDurationMs ?: return
        if (!active || !turnFrameReady) {
            clearPendingDiscreteTurn()
            return
        }
        if (width == 0 || height == 0 || curlView.width == 0 || curlView.height == 0) {
            requestLayout()
            schedulePendingDiscreteTurn()
            return
        }
        val animationStarted = curlView.animatePageTurn(forward, durationMs)
        if (settleTexturePreparationFailure()) return
        if (animationStarted) {
            clearPendingDiscreteTurn()
        } else {
            schedulePendingDiscreteTurn()
        }
    }

    private fun settleTexturePreparationFailure(): Boolean {
        if (texturePreparationFailure == null) return false
        val callback = onTurnSettled
        dismiss()
        callback?.invoke(false)
        return true
    }

    /** Hides + frees the overlay after a turn settles (or to abort). */
    override fun dismiss() {
        removeCallbacks(safetyDismissRunnable)
        turnVisibilityGeneration++
        turnFrameReady = false
        curlView.abortTurn()
        active = false
        clearPendingDiscreteTurn()
        onTurnSettled = null
        alpha = 0f
        recycleBitmaps()
        texturePreparationFailure = null
    }

    override fun onDetachedFromWindow() {
        dismiss()
        super.onDetachedFromWindow()
    }

    fun onPauseGl() = curlView.onPause()
    fun onResumeGl() = curlView.onResume()

    private fun recycleBitmaps() {
        synchronized(bitmapOwnershipLock) {
            recycleBitmapsLocked()
        }
    }

    private fun recycleBitmapsLocked() {
        frontBitmap?.let { if (!it.isRecycled) it.recycle() }
        revealedBitmap?.let { if (!it.isRecycled) it.recycle() }
        frontBitmap = null
        revealedBitmap = null
    }

    private fun clearPendingDiscreteTurn() {
        pendingDiscreteTurnRunnable?.let(::removeCallbacks)
        pendingDiscreteTurnRunnable = null
        pendingDiscreteTurnDurationMs = null
    }

    private fun armSafetyDismiss() {
        removeCallbacks(safetyDismissRunnable)
        if (active) postDelayed(safetyDismissRunnable, SAFETY_DISMISS_MS)
    }

    private companion object {
        /** Timeout before auto-dismissing a curl that never settles (GL error / synthetic DOWN missed). */
        const val SAFETY_DISMISS_MS = 5_000L
    }
}
