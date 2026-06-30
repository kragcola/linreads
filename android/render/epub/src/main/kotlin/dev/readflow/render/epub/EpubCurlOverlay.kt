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
) : FrameLayout(context) {

    /** Back-face dim (Moon+ 反字: same page content shown ~55% on the curled underside). */
    private val backFaceBlend = Color.argb(0xFF, 0x8C, 0x8C, 0x8C)

    private var frontBitmap: Bitmap? = null
    private var revealedBitmap: Bitmap? = null
    private var forward = true
    /** Per-turn settle callback (committed = whether the turn advanced to the revealed page). */
    private var onTurnSettled: ((committed: Boolean) -> Unit)? = null
    /** True between start() and the settle callback — gates touch forwarding + double-starts. */
    var active = false
        private set

    /**
     * Two-page book for one turn: index 0 = the page being turned (front), index 1 = the page revealed
     * beneath. Forward turn curls 0→1; the host maps "revealed" to the next page. Backward turn passes
     * the previous page as the front and the current page as revealed, then starts the curl from the
     * left edge (handled by CurlView's drag classification).
     */
    private val provider = object : CurlView.PageProvider {
        override fun getPageCount(): Int = 2
        override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) {
            when (index) {
                0 -> {
                    val front = frontBitmap ?: return
                    val cfg = front.config ?: Bitmap.Config.RGB_565
                    page.setTexture(front.copy(cfg, false), CurlPage.SIDE_FRONT)
                    // Back face = same page content, dimmed → Moon+ 纸背透字.
                    page.setTexture(front.copy(cfg, false), CurlPage.SIDE_BACK)
                    page.setColor(backFaceBlend, CurlPage.SIDE_BACK)
                }
                else -> {
                    val revealed = revealedBitmap ?: return
                    val cfg = revealed.config ?: Bitmap.Config.RGB_565
                    page.setTexture(revealed.copy(cfg, false), CurlPage.SIDE_BOTH)
                }
            }
        }
    }

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
            // idx is CurlView's resulting page index: 0 = stayed on front, 1 = advanced to revealed.
            val committed = idx >= 1
            active = false
            val cb = onTurnSettled
            onTurnSettled = null
            cb?.invoke(committed)
        }
        visibility = GONE
    }

    /**
     * Begins a SIMULATION turn. [front] is the page being turned, [revealed] the page beneath. [forward]
     * picks the curl direction (true = next page). [settled] fires once the curl animation finishes.
     * The overlay becomes visible; the caller forwards the in-flight drag via [forwardTouch], or calls
     * [animateTurn] for a discrete (tap) turn.
     */
    fun start(front: Bitmap, revealed: Bitmap, forward: Boolean, settled: (committed: Boolean) -> Unit) {
        recycleBitmaps()
        frontBitmap = front
        revealedBitmap = revealed
        this.forward = forward
        onTurnSettled = settled
        active = true
        visibility = VISIBLE
        curlView.setCurrentIndex(0)
        bringToFront()
    }

    /**
     * Drives a discrete (non-finger) turn by synthesizing a DOWN at the free edge and an UP past the
     * half-way point, so harism's own settle animation curls the page fully over. Used for edge taps /
     * volume-key / arrow turns where there's no drag to track.
     */
    fun animateTurn() {
        if (!active) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val midY = h / 2f
        // Forward: grab the right edge, drag to the left edge → curl reveals next page. Backward mirror.
        val startX = if (forward) w - 1f else 1f
        val endX = if (forward) 1f else w - 1f
        val now = android.os.SystemClock.uptimeMillis()
        dispatchSynthetic(MotionEvent.ACTION_DOWN, startX, midY, now, now)
        dispatchSynthetic(MotionEvent.ACTION_MOVE, (startX + endX) / 2f, midY, now, now + 8)
        dispatchSynthetic(MotionEvent.ACTION_UP, endX, midY, now, now + 16)
    }

    private fun dispatchSynthetic(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        curlView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Re-dispatches a host touch event into the GL curl view (finger-tracking). */
    @SuppressLint("ClickableViewAccessibility")
    fun forwardTouch(ev: MotionEvent) {
        if (!active) return
        curlView.dispatchTouchEvent(ev)
    }

    /** Hides + frees the overlay after a turn settles (or to abort). */
    fun dismiss() {
        active = false
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
}
