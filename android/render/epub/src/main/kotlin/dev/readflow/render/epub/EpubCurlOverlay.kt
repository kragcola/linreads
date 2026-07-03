package dev.readflow.render.epub

import android.annotation.SuppressLint
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

    fun start(front: Bitmap, revealed: Bitmap, forward: Boolean, settled: (committed: Boolean) -> Unit)

    fun animateTurn(durationMs: Long)

    fun forwardTouch(ev: MotionEvent)

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
 * Finger-tracking + release settle + lighting/shadow all come from [CurlView]. The host forwards the
 * touch stream here while a SIMULATION drag is active; [onTurnSettled] fires once the curl animation
 * finishes, with `committed` = whether the turn actually advanced to the revealed page.
 */
internal class EpubCurlOverlay(
    context: Context,
) : FrameLayout(context), EpubCurlTurnOverlay {

    /**
     * Back-face tint (Moon+ 反字: the curled underside shows this page's own content, mirrored by the
     * mesh geometry, tinted down so it reads as "ink seen through paper"). Stock harism dimmed to ~55%
     * (0x8C) which looked muddy-dark on a light theme (审计: 颜色过黑); 0xCC ≈ 80% keeps the reverse text
     * legible-but-faint like real paper.
     */
    private val backFaceBlend = Color.argb(0xFF, 0xCC, 0xCC, 0xCC)

    private var frontBitmap: Bitmap? = null
    private var revealedBitmap: Bitmap? = null
    private var forward = true
    /** Per-turn settle callback (committed = whether the turn advanced to the revealed page). */
    private var onTurnSettled: ((committed: Boolean) -> Unit)? = null
    /** True between start() and the settle callback — gates touch forwarding + double-starts. */
    override var active = false
        private set
    private var pendingDiscreteTurnDurationMs: Long? = null
    private var pendingDiscreteTurnPosted = false

    /** Safety dismiss: if harism never fires setCurlAnimationObserver, force-clean after 5s. */
    private val safetyDismissRunnable = Runnable {
        if (active) {
            active = false
            visibility = GONE
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
        override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) {
            // The page that physically curls (and thus needs a back face) is index 0 forward, index 1 back.
            val curlingIndex = if (forward) 0 else 1
            val bmp = if (index == 0) bmp0() else bmp1()
            val src = bmp ?: return
            val cfg = src.config ?: Bitmap.Config.RGB_565
            if (index == curlingIndex) {
                page.setTexture(src.copy(cfg, false), CurlPage.SIDE_FRONT)
                page.setTexture(src.copy(cfg, false), CurlPage.SIDE_BACK)
                page.setColor(backFaceBlend, CurlPage.SIDE_BACK)
            } else {
                page.setTexture(src.copy(cfg, false), CurlPage.SIDE_BOTH)
            }
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
        visibility = GONE
    }

    /**
     * Begins a SIMULATION turn. [front] is the current (turning) page, [revealed] the adjacent page
     * beneath. [forward] picks the curl direction (true = next page). [settled] fires once the curl
     * animation finishes. The overlay becomes visible; the caller then either drives a discrete tap turn
     * via [animateTurn], or hands off a live finger drag via [forwardTouch] (real touch events replayed).
     */
    override fun start(front: Bitmap, revealed: Bitmap, forward: Boolean, settled: (committed: Boolean) -> Unit) {
        recycleBitmaps()
        frontBitmap = front
        revealedBitmap = revealed
        this.forward = forward
        onTurnSettled = settled
        active = true
        clearPendingDiscreteTurn()
        visibility = VISIBLE
        // Safety timeout: if the curl never settles (GL error, synthetic DOWN not triggering harism state),
        // force-dismiss after 5s so turnInFlight doesn't stay stuck forever (审计: active 卡死 → 所有翻页失效).
        removeCallbacks(safetyDismissRunnable)
        postDelayed(safetyDismissRunnable, SAFETY_DISMISS_MS)
        // Forward curls the page at index 0 over to reveal 1; backward starts parked on 1 and curls the
        // index-0 page back in from the left. setCurrentIndex re-runs the provider for the new anchor.
        curlView.setCurrentIndex(if (forward) 0 else 1)
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
        if (pendingDiscreteTurnPosted || pendingDiscreteTurnDurationMs == null) return
        pendingDiscreteTurnPosted = true
        postOnAnimation {
            pendingDiscreteTurnPosted = false
            runPendingDiscreteTurn()
        }
    }

    private fun runPendingDiscreteTurn() {
        val durationMs = pendingDiscreteTurnDurationMs ?: return
        if (!active) {
            clearPendingDiscreteTurn()
            return
        }
        if (width == 0 || height == 0 || curlView.width == 0 || curlView.height == 0) {
            requestLayout()
            schedulePendingDiscreteTurn()
            return
        }
        if (curlView.animatePageTurn(forward, durationMs)) {
            clearPendingDiscreteTurn()
        } else {
            schedulePendingDiscreteTurn()
        }
    }

    /** Re-dispatches a host touch event into the GL curl view (finger-tracking). */
    @SuppressLint("ClickableViewAccessibility")
    override fun forwardTouch(ev: MotionEvent) {
        if (!active) return
        curlView.dispatchTouchEvent(ev)
    }

    /** Hides + frees the overlay after a turn settles (or to abort). */
    override fun dismiss() {
        removeCallbacks(safetyDismissRunnable)
        active = false
        clearPendingDiscreteTurn()
        onTurnSettled = null
        visibility = GONE
        recycleBitmaps()
    }

    fun onPauseGl() = curlView.onPause()
    fun onResumeGl() = curlView.onResume()

    private fun recycleBitmaps() {
        frontBitmap?.let { if (!it.isRecycled) it.recycle() }
        revealedBitmap?.let { if (!it.isRecycled) it.recycle() }
        frontBitmap = null
        revealedBitmap = null
    }

    private fun clearPendingDiscreteTurn() {
        pendingDiscreteTurnDurationMs = null
        pendingDiscreteTurnPosted = false
    }

    private companion object {
        /** Timeout before auto-dismissing a curl that never settles (GL error / synthetic DOWN missed). */
        const val SAFETY_DISMISS_MS = 5_000L
    }
}
