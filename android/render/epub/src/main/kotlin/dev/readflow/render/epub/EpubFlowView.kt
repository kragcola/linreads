package dev.readflow.render.epub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.Layout
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.R as RenderApiR
import kotlin.math.abs

/**
 * Continuous-flow reading surface (方案 C, Moon+ Reader model). One chapter → one whole-chapter
 * Spannable in a single [SelectionAwareTextView] → one StaticLayout (the TextView's OWN layout, the
 * single source of truth for pagination — 审计 C1/H3), hosted in a non-smooth [ScrollView].
 *
 * Touch is owned end-to-end (审计 H4/H5), mirroring Moon+ Reader / FBReader: a [GestureDetector]
 * classifies tap / long-press / scroll; selection is gated behind long-press so it never fights
 * page turns. PAGED: edge tap or non-center drag can flip a page (animated curl); the center
 * 1/3 x 1/3 box is a no-curl dead zone, and only its inner 1/5 x 1/5 box can become temporary
 * scroll on vertical drag. SCROLL: free scroll throughout.
 *
 * The resume anchor is always the char offset of the top-visible line (font-size stable), never a
 * page index.
 */
@SuppressLint("ViewConstructor")
internal class EpubFlowView(
    context: Context,
    private val onTapZone: (EpubFlowTapZone) -> Unit,
    private val onTopOffsetChanged: (layoutOffset: Int) -> Unit,
    private val onSelectionRange: (start: Int, end: Int) -> Unit,
) : ScrollView(context) {

    enum class Mode { PAGED, SCROLL }

    val textView = SelectionAwareTextView(context).apply {
        setTextIsSelectable(true)
        includeFontPadding = false
    }

    private val container = FrameLayout(context).apply {
        addView(
            textView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private var modeValue: Mode = Mode.PAGED

    var mode: Mode
        get() = modeValue
        set(value) {
            applyMode(value, reposition = true)
        }

    fun setModeAnchored(value: Mode, layoutOffset: Int) {
        applyMode(value, reposition = false)
        goToOffset(layoutOffset, pagedAnchor = PagedAnchor.NEAREST, forceReport = true)
    }

    private fun applyMode(value: Mode, reposition: Boolean) {
        if (modeValue != value) recycleCachedTextures()
        modeValue = value
        repaginate(reposition = reposition)
    }

    private var paged: List<EpubFlowPage> = emptyList()
    private var pageHeightPx: Int = 0
    private var currentPage: Int = 0
    private var flow: EpubChapterFlow? = null
    /** Paper background painted in viewport coordinates; avoids ScrollView's scroll-translated background. */
    private var viewportBackground: Drawable? = null

    /** Resume target for the in-flight [setChapter], applied once layout is ready (before the reveal). */
    private var pendingRestoreOffset: Int? = null
    private var pendingLandOnLast = false
    /** True between [setChapter] and the first positioned frame: content is alpha-hidden until then. */
    private var awaitingReveal = false
    private var lastReportedTopOffset: Int? = null
    private val initialRevealRunnable = Runnable { revealContent() }
    /** First page turn requested before the initial layout exists; replayed once pagination is ready. */
    private var pendingInitialPageTurnDelta: Int? = null
    private val pageShotConfig = Bitmap.Config.ARGB_8888

    /** Layout height we last paginated against; a change means the content reflowed (async image load). */
    private var paginatedLayoutHeight: Int = -1

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val flipDominanceThresholdPx = 20 * density
    private val flipCrossAxisLimitPx = 40 * density

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    /** Real DOWN event saved for replay to GL curl overlay (Moon+ model: no synthetic events). */
    private var savedDownEvent: MotionEvent? = null
    private var inSelectionMode = false
    private var classified = false
    private var freeScrolling = false
    private var centerDeadGesture = false
    private var flipped = false
    private var stealing = false
    private var pendingCleanTapX: Float? = null

    /**
     * Page-turn animation style (PAGED only). SLIDE = hardware overlay slide (default, GPU-composited);
     * SIMULATION = OpenGL realistic page-curl (仿真书页翻动, harism engine via [curlOverlay] — finger
     * tracking + see-through reverse text); NONE = instant cut. Switched live from settings; the next
     * turn uses the new style.
     */
    var flipStyle: dev.readflow.core.model.PageFlipStyle = dev.readflow.core.model.PageFlipStyle.SLIDE
    /**
     * GL curl overlay used when [flipStyle] == SIMULATION. Created lazily by [curlOverlayFactory] on the
     * first SIMULATION turn (so SLIDE/NONE readers never allocate a GL context), then cached here. A null
     * factory or a factory that fails → SIMULATION degrades to the slide path (never crashes the reader).
     */
    var curlOverlay: EpubCurlTurnOverlay? = null
    var curlOverlayFactory: (() -> EpubCurlTurnOverlay?)? = null
    private val pageTurnAnimated: Boolean get() = flipStyle != dev.readflow.core.model.PageFlipStyle.NONE
    private val useGlCurl: Boolean
        get() = flipStyle == dev.readflow.core.model.PageFlipStyle.SIMULATION &&
            (curlOverlay != null || curlOverlayFactory != null)

    /** Lazily creates (once) + returns the GL overlay, or null if unavailable / creation failed. */
    private fun obtainCurlOverlay(): EpubCurlTurnOverlay? {
        curlOverlay?.let { return it }
        val created = runCatching { curlOverlayFactory?.invoke() }.getOrNull()
        curlOverlay = created
        return created
    }
    /**
     * True while ANY page-turn animation is in flight — the GPU slide/curl [ValueAnimator], a
     * finger-tracked GL curl ([glInteractive]), or a discrete GL curl still settling in the overlay
     * ([EpubCurlOverlay.active]). A new turn requested while this holds must be dropped: the slide guard
     * alone missed the GL cases, so a tap mid-curl fell through to the slide path and double-committed
     * (审计: 图文页乱翻 — 一次翻好几页 / 几次都在一页). Reflows also defer on this so the paged array can't
     * be rebuilt out from under a pending GL settle.
     */
    private val turnInFlight: Boolean
        get() = flipAnimator?.isRunning == true || glInteractive || curlOverlay?.active == true

    private var flipAnimator: ValueAnimator? = null
    private var slideDrawable: PageSlideDrawable? = null
    private var curlDrawable: PageCurlDrawable? = null
    private val flipDurationMs = 280L
    /** Discrete GL curl sweep — a touch longer than the slide so the realistic curl reads fully. */
    private val glCurlDurationMs = 420L

    /**
     * True while a live finger drag is being handed to the GL curl overlay (SIMULATION 跟手). The host
     * keeps ownership of the touch stream (it consumed DOWN before classifying) and re-dispatches each
     * MOVE/UP into the overlay via [EpubCurlOverlay.forwardTouch]; the overlay's harism engine tracks the
     * finger and runs its own release settle. While set, the host must not also drive its slide/scroll.
     */
    private var glInteractive = false
    private var glDiscreteTurnGeneration = 0

    // ---- Pre-cache page textures (Moon+ Reader model) ------------------------------------------
    // After every page settle, pre-render the current + next page bitmaps so the next curl doesn't
    // snapshot live (avoids OOM mid-turn and image-text page rendering glitches).
    private var cachedFrontBitmap: Bitmap? = null
    private var cachedRevealedBitmap: Bitmap? = null
    private var cachedFromPage: Int = -1
    private var cachedTargetPage: Int = -1
    private var cachedFromTopPx: Int = -1
    private var cachedTargetTopPx: Int = -1

    private fun preCachePageTextures() {
        if (mode != Mode.PAGED || paged.isEmpty() || width == 0) return
        if (turnInFlight) return
        if (initialRevealActive()) return
        val idx = currentPage
        val nextIdx = (idx + 1).coerceAtMost(paged.lastIndex)
        val parkedTop = canonicalScrollTopForPage(idx) ?: return
        val fromTop = textureTopPxForPage(idx) ?: return
        val targetTop = textureTopPxForPage(nextIdx) ?: return
        if (scrollY != parkedTop) {
            recycleCachedTextures()
            return
        }
        val cacheBitmapsAlive =
            cachedFrontBitmap?.isRecycled == false &&
                cachedRevealedBitmap?.isRecycled == false
        if (
            cacheBitmapsAlive &&
            idx == cachedFromPage &&
            nextIdx == cachedTargetPage &&
            fromTop == cachedFromTopPx &&
            targetTop == cachedTargetTopPx
        ) return
        recycleCachedTextures()
        post {
            if (
                turnInFlight ||
                scrollY != parkedTop ||
                currentPage != idx ||
                textureTopPxForPage(idx) != fromTop ||
                textureTopPxForPage(nextIdx) != targetTop
            ) return@post
            val front = snapshotPageAt(fromTop)
            val revealed = if (front != null) snapshotPageAt(targetTop) else null
            if (front == null || revealed == null) {
                front?.recycle()
                revealed?.recycle()
                recycleCachedTextures()
                return@post
            }
            cachedFrontBitmap = front
            cachedRevealedBitmap = revealed
            cachedFromPage = idx
            cachedTargetPage = nextIdx
            cachedFromTopPx = fromTop
            cachedTargetTopPx = targetTop
        }
    }

    private fun recycleCachedTextures() {
        cachedFrontBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedRevealedBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedFrontBitmap = null
        cachedRevealedBitmap = null
        cachedFromPage = -1
        cachedTargetPage = -1
        cachedFromTopPx = -1
        cachedTargetTopPx = -1
    }

    private fun recycleCachedTexturesIfStaleForTurn(
        fromPage: Int,
        fromTop: Int,
        targetPage: Int,
        targetTop: Int,
    ) {
        if (cachedFrontBitmap == null && cachedRevealedBitmap == null) return
        val cacheBitmapsAlive =
            cachedFrontBitmap?.isRecycled == false &&
                cachedRevealedBitmap?.isRecycled == false
        val cacheMatchesCurrentAnchors =
            fromPage == cachedFromPage &&
                targetPage == cachedTargetPage &&
                fromTop == cachedFromTopPx &&
                targetTop == cachedTargetTopPx
        if (!cacheBitmapsAlive || !cacheMatchesCurrentAnchors) recycleCachedTextures()
    }

    // ---- Finger-tracking (跟手) interactive slide ----------------------------------------------
    // A horizontal drag drives the slide progress directly off finger displacement (静读天下「滑动」),
    // instead of crossing a threshold to fire a fixed animation while the finger is still down. On
    // release we settle by position+velocity: past half the width OR a fling over the threshold/s
    // commits the turn, else it springs back. Both layers move via GPU transforms (overlay snapshot
    // + container translationX), so it stays smooth at tablet resolution.
    private var interactiveCurl = false
    /** The page we slide FROM (restore target on cancel); the incoming page is already parked beneath. */
    private var curlFromPage = 0
    private var curlForward = true
    /** Finger x where the interactive slide began (drag displacement is measured from here). */
    private var curlAnchorX = 0f
    private var velocityTracker: VelocityTracker? = null
    private val flipFlingThresholdPxPerSec = 700 * density

    /**
     * When true (PAGED, parked on a page), drawing is clipped to the current page's bottom so the
     * gap below its last complete line shows the theme background — never the next page's content
     * (Moon+ Reader 的「丢弃半行、底部留白」). Disabled during a centre-column free scroll (Moon+'s
     * temporary-scroll is continuous), then re-enabled on the next page turn.
     */
    private var pageClipActive = true

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Long-press anywhere = native text selection. We stop owning the stream so the child
            // TextView gets the real held DOWN→MOVE→UP it needs for selection + drag handles.
            inSelectionMode = true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Only a clean tap (no drag, no long-press) turns a page / toggles chrome. Defer the
            // action until dispatch finishes so child ClickableSpan taps can report that they consumed
            // the same UP first.
            if (!classified && !inSelectionMode) pendingCleanTapX = e.x
            return false
        }
    })

    init {
        isSmoothScrollingEnabled = false
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addView(container)
        textView.onSelectionRangeChanged = { s, e -> onSelectionRange(s, e) }
        // A chapter's images load async with no placeholder height (审计: zero-height until decoded), so
        // each one that arrives reflows the StaticLayout and shifts every following line down. Our page
        // windows + resting scrollY are then stale → the parked page lands mid-line (half-line/half-image
        // at the edges). Re-paginate + re-anchor by content offset whenever the layout height changes, so
        // the parked page snaps back to a line top (mirrors [onSizeChanged] for the view-height case).
        //
        // Images decode in a burst (the scheduler attaches all visible ones at once), so a per-event
        // repaginate fires 3–4 times in a few frames — each a paginate + scrollTo, the visible flicker.
        // Coalesce the burst: every layout change reschedules ONE debounced pass that runs after the
        // decodes settle, doing a single paginate + single re-anchor.
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val layout = textView.layout ?: return@addOnLayoutChangeListener
            if (flow == null || layout.height <= 0) return@addOnLayoutChangeListener
            // Only react to a genuine content reflow (height delta from a decoded image), not our own
            // re-layout after repaginate (which records the new height) or a no-op pass.
            if (layout.height == paginatedLayoutHeight) return@addOnLayoutChangeListener
            if (awaitingReveal) removeCallbacks(initialRevealRunnable)
            removeCallbacks(reflowRunnable)
            postDelayed(reflowRunnable, REFLOW_DEBOUNCE_MS)
        }
    }

    override fun setBackground(background: Drawable?) {
        viewportBackground = background
        super.setBackground(null)
        invalidate()
    }

    override fun getBackground(): Drawable? = viewportBackground

    override fun draw(canvas: Canvas) {
        drawViewportBackground(canvas)
        super.draw(canvas)
    }

    /**
     * Single coalesced reaction to an async-image reflow: re-paginate against the grown layout and
     * re-anchor the parked page to the same content line. Deferred while ANY page turn is in flight —
     * a slide/curl animation, a finger curl, or a GL curl still settling: a mid-turn repaginate would
     * rebuild the page array under a pending GL settle (→ lands on the wrong page) or tear the animation.
     * It re-arms itself for after the turn ends.
     */
    private val reflowRunnable: Runnable = Runnable {
        val layout = textView.layout ?: return@Runnable
        if (flow == null || layout.height <= 0) return@Runnable
        if (layout.height == paginatedLayoutHeight) return@Runnable
        if (turnInFlight) {
            postDelayed(reflowRunnable, REFLOW_DEBOUNCE_MS)
            return@Runnable
        }
        val anchorOffset = if (awaitingReveal) {
            pendingRestoreOffset ?: if (paged.isNotEmpty()) topLayoutOffset() else -1
        } else {
            if (paged.isNotEmpty()) topLayoutOffset() else -1
        }
        recycleCachedTextures()
        repaginate(reposition = false)
        if (anchorOffset >= 0) goToOffset(anchorOffset, forceReport = true)
        if (awaitingReveal) scheduleInitialReveal()
    }

    /**
     * The selectable child [TextView] keeps a selection cursor and, on every pre-draw, asks us (its
     * scroll parent) to bring that point on-screen — which would smooth-scroll [scrollY] off the page
     * top, bleeding the next page's lines in at the bottom and showing a visible scroll (审计 H4/H5:
     * touch + scrolling are owned end-to-end). We position the page ourselves, so refuse all auto-scroll.
     */
    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: android.graphics.Rect,
        immediate: Boolean,
    ): Boolean = false

    /**
     * Clip content rendering to the current page's bottom boundary. The ScrollView background (the
     * theme page colour, painted in [View.draw] before this runs) fills the strip below, so the
     * remainder of a short page is blank — the next page's lines/images never bleed in. The flip
     * overlay (a [Drawable] on the view overlay) draws after dispatchDraw, so it is unaffected.
     */
    override fun dispatchDraw(canvas: Canvas) {
        val clipBottom = pageClipBottomInViewport()
        val topClip = pageClipTopInViewport()
        if (clipBottom == null && topClip <= scrollY) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        // Canvas is in the ScrollView's own coords here (already translated by scrollY for children),
        // so the viewport spans [scrollY, scrollY + height]. Clip the top past the previous line's ink
        // overflow (drop the half-line bleed) and the bottom to this page's last complete line.
        //
        // The paginator works in pure StaticLayout coords (line 0 at y=0), but the child TextView paints
        // its layout shifted DOWN by its own [TextView.paddingTop] — a line at layout-y L lands at
        // canvas-y L + padTop. [pageClipBottomInViewport] returns the bottom in layout space, so without
        // the offset the clip falls padTop px too high and slices ~padTop off the last line's painted
        // glyphs (审计: 底部半截文字). Add padTop so the clip meets the line's PAINTED bottom; cap at the
        // viewport bottom (a near-full page then relies on the viewport edge, off by ≤padTop, invisible).
        val bottom = if (clipBottom != null) {
            minOf(scrollY + clipBottom + textView.paddingTop, scrollY + height)
        } else {
            scrollY + height
        }
        canvas.clipRect(0, topClip, width, bottom)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    /**
     * Content-y at which to START drawing this page (>= [scrollY]). The paginator works in pure
     * StaticLayout coords (line 0 at y=0) and page tops are line tops in that space, but the child
     * TextView paints its layout shifted DOWN by its own [TextView.paddingTop]: a line at layout-y L
     * lands at canvas-y L + padTop. When parked on a page, [scrollY] equals the page's first line top
     * in LAYOUT space, so that line actually PAINTS at scrollY + padTop — and the strip [scrollY,
     * scrollY+padTop] is occupied entirely by the PREVIOUS line's box (its layout bottom == this page's
     * line top, painted +padTop down), i.e. the half-line bleed (审计: 半截的文字; the earlier fraction
     * guard dropped only ~12px, far short of padTop). Clip the top at scrollY + padTop so the previous
     * line is fully removed and this page's first line sits flush below the clip with a padTop top margin
     * — the exact mirror of [pageClipBottomInViewport]'s +padTop, one consistent content-space boundary.
     */
    private fun pageClipTopInViewport(): Int {
        if (mode != Mode.PAGED || !pageClipActive || paged.isEmpty()) return scrollY
        val layout = textView.layout ?: return scrollY
        // Clip the top only when [scrollY] sits EXACTLY on a line top (page turns and the free-scroll
        // release both snap there). A clamped final page — or any mid-line rest — leaves scrollY between
        // two line tops; clipping +padTop there would shave real text, so skip it. Dynamic (line-top
        // based), not currentPage-based, so it also holds after a middle-column free scroll snaps to a
        // new line (审计: 滚动转分页). getLineForVertical(scrollY) is the line CONTAINING scrollY; we are
        // "on its top" iff its top == scrollY.
        val line = layout.getLineForVertical(scrollY)
        if (layout.getLineTop(line) != scrollY) return scrollY
        return (scrollY + textView.paddingTop).coerceAtMost(scrollY + height)
    }

    /**
     * Pixel height (within the viewport) at which the current page ends, or null when no clip should
     * apply (SCROLL mode, free-scrolling, no pages, or the page already reaches the viewport bottom).
     *
     * Computed DYNAMICALLY from the live [scrollY] (Moon+ Reader's `getLastDisplayLine`): the bottom is
     * the bottom of the last line that FULLY fits in the viewport — never a page-array boundary. So even
     * after a middle-column free scroll parks scrollY mid-grid, the clip still lands on a clean line
     * boundary and the next line's top never bleeds in (审计: 滚动转分页的半截文字). When parked on a
     * page top this resolves to exactly that page's last line (pages were paginated by the same rule).
     */
    private fun pageClipBottomInViewport(): Int? {
        if (mode != Mode.PAGED || !pageClipActive || paged.isEmpty()) return null
        val layout = textView.layout ?: return null
        val pageBottom = scrollY + height
        var line = layout.getLineForVertical(pageBottom - 1)
        // Step back off a line whose bottom spills past the viewport — that partial line belongs to the
        // next page. Leaves a blank gap at the bottom rather than a half-line (Moon+ accepts the gap).
        if (line > 0 && layout.getLineBottom(line) > pageBottom) line--
        val clip = layout.getLineBottom(line) - scrollY
        // No clip needed once the content fills (or exceeds) the viewport — e.g. a full-page image.
        if (clip >= height) return null
        return clip.coerceAtLeast(0)
    }

    /**
     * The real viewport height is only known after measurement — and it differs from the engine's
     * screen-derived estimate (the reader avoids the system bars). [setChapter] posts a [repaginate],
     * but on a cold open that can run before this view is sized (height == 0 → 2464 fallback → pages
     * too tall → clipped last line). Re-paginate whenever the true height arrives or changes (also
     * covers rotation), re-anchoring to the same content line so the resume position stays put.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h == oldh || h <= 0 || flow == null) return
        // Cold open: settleInitialPosition ran at height 0 (fallback) and deferred the reveal. Re-anchor
        // to the EXACT pending resume target (more accurate than the fallback-paginated top line), then
        // reveal — so the content fades in already at the resume page, with no visible scroll.
        if (awaitingReveal) {
            textView.post {
                repaginate(reposition = false)
                if (pendingLandOnLast) goToLastPage() else goToOffset(pendingRestoreOffset ?: 0, forceReport = true)
                if (consumePendingInitialPageTurn()) return@post
                scheduleInitialReveal()
                preCachePageTextures()
            }
            return
        }
        val anchorOffset = if (paged.isNotEmpty()) topLayoutOffset() else -1
        textView.post {
            // Re-anchor by offset does the only scroll; skip repaginate's own scrollTo (no double jump).
            repaginate(reposition = anchorOffset < 0)
            if (anchorOffset >= 0) goToOffset(anchorOffset, forceReport = true)
            preCachePageTextures()
        }
    }

    /**
     * The usable height (px) for a full-page image so its laid-out line fits within ONE page. Derived
     * from the MEASURED viewport (not the engine's screen estimate, which is ~100px taller because it
     * ignores the system bars the reader avoids — a screen-sized cover overflowed onto a blank 2nd
     * page). Drops the TextView's own vertical padding (the image line is inset by it on the first/last
     * page of a chapter) so the image never spills past the viewport. 0 before the first measure pass.
     */
    fun usablePageImageHeightPx(): Int {
        if (height <= 0) return 0
        return (height - textView.paddingTop - textView.paddingBottom).coerceAtLeast(1)
    }

    /**
     * Installs the chapter Spannable and paginates over the TextView's measured layout, then positions
     * to the resume target ([restoreOffset], or the last page when [landOnLast]) in the SAME post — so
     * the first painted frame is already at the resume point, never the chapter top jumping to it
     * (静读天下: positioned before paint). Content is held hidden until that position settles at a real
     * measured height, so even the one transient pre-post frame can't show a scroll.
     */
    fun setChapter(
        flow: EpubChapterFlow,
        spannable: CharSequence,
        pageHeightPx: Int,
        restoreOffset: Int? = null,
        landOnLast: Boolean = false,
    ) {
        flipAnimator?.cancel()
        clearFlipOverlay()
        cancelPendingGlDiscreteSettle()
        removeCallbacks(reflowRunnable)
        savedDownEvent?.recycle()
        savedDownEvent = null
        recycleCachedTextures()
        this.flow = flow
        this.pageHeightPx = pageHeightPx.coerceAtLeast(1)
        currentPage = 0
        paginatedLayoutHeight = -1
        pendingRestoreOffset = restoreOffset
        pendingLandOnLast = landOnLast
        pendingInitialPageTurnDelta = null
        awaitingReveal = true
        lastReportedTopOffset = null
        removeCallbacks(initialRevealRunnable)
        container.animate().cancel()
        container.alpha = 0f
        textView.text = spannable
        // Layout is available after the next measure/layout pass; paginate + position then.
        textView.post { settleInitialPosition() }
    }

    /**
     * Paginates against the current layout and snaps to the pending resume target without reporting an
     * intermediate position. Reveals the content once positioned with a real (measured) height; on a
     * cold open the first pass runs at height 0 (fallback page height), so the reveal is deferred to
     * [onSizeChanged] when the true viewport arrives.
     */
    private fun settleInitialPosition() {
        if (textView.layout == null || flow == null) return
        repaginate(reposition = false)
        if (pendingLandOnLast) goToLastPage() else goToOffset(pendingRestoreOffset ?: 0, forceReport = true)
        if (consumePendingInitialPageTurn()) return
        if (height > 0) scheduleInitialReveal()
        preCachePageTextures()
    }

    private fun consumePendingInitialPageTurn(): Boolean {
        val delta = pendingInitialPageTurnDelta ?: return false
        if (height <= 0) return true
        pendingInitialPageTurnDelta = null
        if (mode != Mode.PAGED || paged.isEmpty()) return false
        if (goToAdjacentPage(delta)) return true
        onTapZone(if (delta > 0) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
        return true
    }

    private fun scheduleInitialReveal() {
        if (!awaitingReveal || height <= 0) return
        removeCallbacks(initialRevealRunnable)
        postDelayed(initialRevealRunnable, INITIAL_REVEAL_SETTLE_MS)
    }

    private fun revealContent() {
        if (!awaitingReveal) return
        removeCallbacks(initialRevealRunnable)
        awaitingReveal = false
        container.animate()
            .alpha(1f)
            .setDuration(REVEAL_FADE_MS)
            .withEndAction {
                if (!consumePendingInitialPageTurn()) {
                    preCachePageTextures()
                }
            }
            .start()
    }

    private fun initialRevealActive(): Boolean =
        awaitingReveal || container.alpha < 1f

    private fun finishInitialRevealForTurn() {
        if (!initialRevealActive()) return
        removeCallbacks(initialRevealRunnable)
        container.animate().cancel()
        awaitingReveal = false
        container.alpha = 1f
    }

    /**
     * Re-applies annotation highlights in place without rebuilding the chapter. [BackgroundColorSpan]
     * is used exclusively for highlights in the flow Spannable, so we can strip the old ones and paint
     * the current [ranges] — span changes don't alter text length, so the StaticLayout just redraws
     * (no repagination, no scroll disruption). Offsets are absolute layout offsets within the chapter.
     */
    fun refreshHighlights(ranges: List<dev.readflow.render.api.ReaderTextHighlightRange>) {
        val text = textView.text as? android.text.Spannable ?: return
        text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java)
            .forEach { text.removeSpan(it) }
        ranges.forEach { range ->
            val s = range.start.coerceIn(0, text.length)
            val e = range.end.coerceIn(s, text.length)
            if (e > s) {
                text.setSpan(
                    android.text.style.BackgroundColorSpan(range.color),
                    s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        textView.invalidate()
    }

    /**
     * Recomputes the page windows over the TextView's current StaticLayout. When [reposition] is true
     * (cold open, mode switch) it also snaps [scrollY] to the current page's top; callers that re-anchor
     * by content offset right after (reflow / size change) pass false, so the page only scrolls ONCE
     * (a [scrollTo] here followed by [goToOffset] is the visible flicker on every image decode).
     */
    private fun repaginate(reposition: Boolean = true) {
        val layout = textView.layout ?: return
        val f = flow ?: return
        paginatedLayoutHeight = layout.height
        // Paginate against the view's REAL measured viewport, not the engine's screen-derived estimate
        // — the reader avoids the system bars, so the visible height is shorter than the screen. Using
        // the true height keeps each page's last line fully visible and lets the page clip engage
        // (审计 C1/H3: the view's own geometry is the single source of truth). [pageHeightPx] is only a
        // pre-measurement fallback (height == 0 before the first layout pass).
        val effectivePageH = if (height > 0) height else pageHeightPx
        val geometry = EpubLayoutLineGeometry(layout)
        val headingLines = headingLineSet(layout, f)
        paged = if (mode == Mode.PAGED) {
            epubPaginateFlow(
                geometry = geometry,
                pageHeightPx = effectivePageH,
                isHeadingLine = { it in headingLines },
                paragraphLineRange = { paragraphLineRange(layout, f, it) },
            )
        } else {
            emptyList()
        }
        if (reposition && mode == Mode.PAGED && paged.isNotEmpty()) {
            currentPage = currentPage.coerceIn(0, paged.lastIndex)
            pageClipActive = true
            scrollTo(0, paged[currentPage].topPx)
        }
    }

    private fun headingLineSet(layout: Layout, f: EpubChapterFlow): Set<Int> {
        val lines = HashSet<Int>()
        f.segments.forEach { seg ->
            val block = seg.block
            if (block is EpubDisplayBlock.Text && block.headingLevel != null && seg.layoutEnd > seg.layoutStart) {
                val first = layout.getLineForOffset(seg.layoutStart)
                val last = layout.getLineForOffset((seg.layoutEnd - 1).coerceAtLeast(seg.layoutStart))
                for (l in first..last) lines += l
            }
        }
        return lines
    }

    private fun paragraphLineRange(layout: Layout, f: EpubChapterFlow, line: Int): IntRange {
        val offset = layout.getLineStart(line)
        val seg = f.segments.firstOrNull { offset in it.layoutStart until it.layoutEnd }
            ?: return line..(line + 1)
        val first = layout.getLineForOffset(seg.layoutStart)
        val last = layout.getLineForOffset((seg.layoutEnd - 1).coerceAtLeast(seg.layoutStart))
        return first..(last + 1)
    }

    fun pageCount(): Int = if (mode == Mode.PAGED) paged.size.coerceAtLeast(1) else 1

    fun currentPageIndex(): Int = currentPage

    /**
     * Moves one page in [delta] direction. Returns true if it moved within this chapter; returns
     * false at a chapter boundary (caller loads the adjacent spine — Phase 4 cross-chapter advance).
     */
    fun goToAdjacentPage(delta: Int): Boolean {
        if (delta == 0) return false
        if (mode == Mode.PAGED) {
            if (awaitingReveal && flow != null) {
                pendingInitialPageTurnDelta = delta.coerceIn(-1, 1)
                if (height > 0) scheduleInitialReveal()
                return true
            }
            if (paged.isEmpty()) {
                if (awaitingReveal && flow != null) {
                    pendingInitialPageTurnDelta = delta.coerceIn(-1, 1)
                    return true
                }
                return false
            }
            val anchor = snapToNearestCanonicalPageAnchor(report = false)
            val target = anchor + delta
            if (target < 0 || target > paged.lastIndex) return false
            goToPageAnimated(target, forward = delta > 0)
            return true
        }
        // SCROLL: page by viewport; boundary only at the true scroll extremes.
        val maxScroll = (container.height - height).coerceAtLeast(0)
        if (delta > 0 && scrollY >= maxScroll) return false
        if (delta < 0 && scrollY <= 0) return false
        scrollTo(0, (scrollY + delta * pageHeightPx).coerceIn(0, maxScroll))
        reportTopOffset()
        return true
    }

    fun goToPage(index: Int) = scrollToPage(index, report = true)

    private fun canonicalScrollTopForPage(index: Int): Int? {
        if (mode != Mode.PAGED || paged.isEmpty() || index !in paged.indices) return null
        val maxScroll = (container.height - height).coerceAtLeast(0)
        return paged[index].topPx.coerceIn(0, maxScroll)
    }

    private fun textureTopPxForPage(index: Int): Int? =
        canonicalScrollTopForPage(index)

    private fun nearestCanonicalPageIndexForScrollY(y: Int): Int {
        if (paged.isEmpty()) return 0
        val maxScroll = (container.height - height).coerceAtLeast(0)
        val clamped = y.coerceIn(0, maxScroll)
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        paged.forEachIndexed { index, page ->
            val top = page.topPx.coerceIn(0, maxScroll)
            val distance = abs(top - clamped)
            if (distance < bestDistance || (distance == bestDistance && top <= clamped)) {
                bestIndex = index
                bestDistance = distance
            }
        }
        return bestIndex
    }

    private fun snapToNearestCanonicalPageAnchor(report: Boolean): Int {
        if (mode != Mode.PAGED || paged.isEmpty()) return currentPage
        val targetPage = nearestCanonicalPageIndexForScrollY(scrollY)
        val targetTop = canonicalScrollTopForPage(targetPage) ?: return currentPage
        if (currentPage != targetPage || scrollY != targetTop) recycleCachedTextures()
        currentPage = targetPage
        pageClipActive = true
        if (scrollY != targetTop) scrollTo(0, targetTop)
        if (report) reportTopOffset()
        invalidate()
        return currentPage
    }

    /**
     * Snaps to page [index]'s top. [report] gates the locator callback: the interactive curl parks the
     * incoming page (and snaps the outgoing one clean for the snapshot) BEFORE the turn is committed, so
     * those intermediate moves must stay silent — only the committed page reports its offset.
     */
    private fun scrollToPage(index: Int, report: Boolean) {
        if (mode != Mode.PAGED || paged.isEmpty()) return
        currentPage = index.coerceIn(0, paged.lastIndex)
        pageClipActive = true
        scrollTo(0, canonicalScrollTopForPage(currentPage) ?: paged[currentPage].topPx)
        if (report) reportTopOffset()
    }

    /**
     * Page turn with a hardware slide (滑动翻页): snapshot the current page, jump the real content to
     * the target, then slide the snapshot of the OUTGOING page off-screen while the incoming page (the
     * real content) slides in beside it (see [PageSlideDrawable]). Falls
     * back to an instant [goToPage] when animation is off or a snapshot can't be taken.
     */
    private fun goToPageAnimated(index: Int, forward: Boolean) {
        val target = index.coerceIn(0, paged.lastIndex)
        finishInitialRevealForTurn()
        if (!pageTurnAnimated || mode != Mode.PAGED || width == 0 || height == 0) {
            goToPage(target)
            preCachePageTextures()
            return
        }
        // Debounce: prevent a second turn from starting while an animation is still in flight
        // (rapid double-tap). The turn is silently dropped to avoid double-committing — the user
        // can tap again once the current animation settles. Combined with EpubCurlOverlay's
        // 5s safety timeout, this also prevents turns when a GL curl is stuck active.
        if (turnInFlight) return
        // Snap the outgoing page to its own top with the clip re-armed BEFORE snapshotting: after a
        // middle-column free scroll scrollY sits mid-page with pageClipActive off, so an un-snapped
        // snapshot would carry the next/prev page's half-line at top & bottom and slide it away (审计:
        // 滚动转分页的上下半截文字). currentPage was just re-anchored by goToAdjacentPage.
        scrollToPage(currentPage, report = false)
        // SIMULATION → OpenGL realistic curl (harism). Snapshot front (current) + revealed (target),
        // hand them to the GL overlay, and commit the page only when the curl settles committed.
        if (useGlCurl && startGlCurlTurn(currentPage, target, forward)) return
        // GL curl unavailable or failed — dismiss any stuck GL overlay so the slide path is clean.
        if (curlOverlay?.active == true) {
            curlOverlay?.dismiss()
            glInteractive = false
            cancelPendingGlDiscreteSettle()
        }
        val outgoing = snapshotViewport() ?: run {
            goToPage(target)
            return
        }
        // Park the incoming page beneath, then slide both: snapshot off + incoming in.
        goToPage(target)
        startFlip(outgoing, forward)
    }

    /**
     * Drives a discrete (tap/key) turn through the GL curl overlay: snapshots the current page (front)
     * and the [target] page (revealed beneath), starts the overlay, and runs harism's settle animation.
     * On settle, commits the page in the real view and dismisses the overlay. Returns false (caller
     * falls back to slide) if a snapshot can't be taken or the overlay is unavailable.
     */
    private fun startGlCurlTurn(fromPage: Int, target: Int, forward: Boolean): Boolean {
        val overlay = obtainCurlOverlay() ?: return false
        if (overlay.active) return false
        return runCatching {
            // Use pre-cached textures when available (avoids live snapshot on image-text pages).
            val fromTop = textureTopPxForPage(fromPage) ?: return@runCatching false
            val targetTop = textureTopPxForPage(target) ?: return@runCatching false
            recycleCachedTexturesIfStaleForTurn(fromPage, fromTop, target, targetTop)
            val front = if (
                fromPage == cachedFromPage &&
                fromTop == cachedFromTopPx &&
                cachedFrontBitmap != null
            ) {
                cachedFrontBitmap?.copy(pageShotConfig, false)
            } else {
                snapshotPageAt(fromTop)
            } ?: return@runCatching false
            val revealed = if (
                target == cachedTargetPage &&
                targetTop == cachedTargetTopPx &&
                cachedRevealedBitmap != null
            ) {
                cachedRevealedBitmap?.copy(pageShotConfig, false)
            } else {
                snapshotPageAt(targetTop)
            } ?: run { front.recycle(); return@runCatching false }
            overlay.start(front, revealed, forward) { committed ->
                cancelPendingGlDiscreteSettle()
                if (committed) {
                    goToPage(target)
                } else {
                    scrollToPage(fromPage, report = false)
                }
                overlay.dismiss()
                preCachePageTextures()
            }
            scheduleGlDiscreteSettleFallback(overlay, target)
            overlay.animateTurn(glCurlDurationMs)
            true
        }.getOrDefault(false)
    }

    private fun scheduleGlDiscreteSettleFallback(overlay: EpubCurlTurnOverlay, target: Int) {
        val generation = ++glDiscreteTurnGeneration
        postDelayed(
            {
                if (generation != glDiscreteTurnGeneration || !overlay.active) return@postDelayed
                cancelPendingGlDiscreteSettle()
                goToPage(target)
                overlay.dismiss()
                preCachePageTextures()
            },
            glCurlDurationMs + GL_DISCRETE_SETTLE_GRACE_MS,
        )
    }

    private fun cancelPendingGlDiscreteSettle() {
        glDiscreteTurnGeneration++
    }

    /**
     * Starts a FINGER-TRACKED GL curl (SIMULATION 跟手, Moon+ Reader model). Uses pre-cached page
     * textures when available (avoids live snapshot under the finger), then replays the REAL saved
     * DOWN event to harism (no synthetic edge-DOWN — the CurlView sees the actual finger position).
     * Subsequent MOVE/UP events are forwarded via [EpubCurlOverlay.forwardTouch].
     */
    private fun beginGlInteractiveCurl(forward: Boolean, atY: Float): Boolean {
        if (mode != Mode.PAGED || paged.isEmpty() || width == 0 || height == 0) return false
        val overlay = obtainCurlOverlay() ?: return false
        if (overlay.active) return false
        val from = snapToNearestCanonicalPageAnchor(report = false)
        val target = from + if (forward) 1 else -1
        if (target < 0 || target > paged.lastIndex) return false
        finishInitialRevealForTurn()
        return runCatching {
            // Use pre-cached textures when available (Moon+ model: no live snapshot under finger).
            val fromTop = textureTopPxForPage(from) ?: return@runCatching false
            val targetTop = textureTopPxForPage(target) ?: return@runCatching false
            recycleCachedTexturesIfStaleForTurn(from, fromTop, target, targetTop)
            val front = if (
                from == cachedFromPage &&
                fromTop == cachedFromTopPx &&
                cachedFrontBitmap != null
            ) {
                cachedFrontBitmap?.copy(pageShotConfig, false)
            } else {
                snapshotPageAt(fromTop)
            } ?: return@runCatching false
            val revealed = if (
                target == cachedTargetPage &&
                targetTop == cachedTargetTopPx &&
                cachedRevealedBitmap != null
            ) {
                cachedRevealedBitmap?.copy(pageShotConfig, false)
            } else {
                snapshotPageAt(targetTop)
            } ?: run { front.recycle(); return@runCatching false }
            overlay.start(front, revealed, forward) { committed ->
                if (committed) goToPage(target) else scrollToPage(from, report = false)
                overlay.dismiss()
                preCachePageTextures()
            }
            // Replay the REAL saved DOWN event to harism (Moon+ model: no synthetic events).
            // The CurlView sees the actual finger position → curl starts where the finger is,
            // not at the extreme edge → no premature observer fire.
            val realDown = savedDownEvent
            if (realDown != null) {
                overlay.forwardTouch(realDown)
            }
            glInteractive = true
            true
        }.getOrDefault(false)
    }

    private fun snapshotViewport(): Bitmap? = try {
        val bmp = Bitmap.createBitmap(width, height, pageShotConfig)
        val canvas = Canvas(bmp)
        drawSnapshotBackground(canvas)
        // ScrollView draws its children at -scrollY; replicate so the snapshot is exactly what's
        // on screen now (the current page), independent of the upcoming scroll.
        canvas.translate(0f, -scrollY.toFloat())
        // Clip the content to the outgoing page's bottom so the sliding snapshot shows blank
        // background below the last line — never the next page's bleed (matches on-screen render).
        val clipBottom = pageClipBottomInViewport()
        val top = snapshotClipTopFor(scrollY)
        if (clipBottom != null || top > scrollY) {
            val save = canvas.save()
            val bottom = if (clipBottom != null) {
                snapshotClipBottomFor(scrollY, clipBottom)
            } else {
                scrollY + height
            }
            canvas.clipRect(0, top, width, bottom)
            container.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            container.draw(canvas)
        }
        bmp
    } catch (_: Throwable) {
        // Throwable, not Exception: Bitmap.createBitmap on a large tablet page can throw
        // OutOfMemoryError (an Error, not an Exception) — catching only Exception would let it crash
        // instead of falling back to a plain goToPage. Also covers draw-time runtime exceptions.
        null
    }

    /**
     * Renders the PAGED page whose content top is [topPx] into a fresh bitmap (theme bg + that page's
     * lines, clipped to the page's last fully-fitting line). Used to build the GL curl's front/back
     * textures for an arbitrary page without moving the live scroll position. Null on OOM or if the
     * view isn't measured / not paged.
     */
    fun snapshotPageAt(topPx: Int): Bitmap? {
        if (width == 0 || height == 0) return null
        return try {
            val bmp = Bitmap.createBitmap(width, height, pageShotConfig)
            val canvas = Canvas(bmp)
            drawSnapshotBackground(canvas)
            canvas.translate(0f, -topPx.toFloat())
            val clipTop = snapshotClipTopFor(topPx)
            val clipBottom = snapshotClipBottomFor(topPx)
            val save = canvas.save()
            canvas.clipRect(0, clipTop, width, clipBottom)
            container.draw(canvas)
            canvas.restoreToCount(save)
            bmp
        } catch (_: Throwable) {
            // Throwable, not Exception: Bitmap.createBitmap can OOM (an Error) on a large tablet page;
            // catching only Exception would crash instead of returning null → caller falls back cleanly.
            null
        }
    }

    private fun drawSnapshotBackground(canvas: Canvas) {
        drawViewportBackground(canvas)
    }

    private fun drawViewportBackground(canvas: Canvas) {
        viewportBackground?.let { bg ->
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
        }
    }

    private fun snapshotClipTopFor(topPx: Int): Int {
        val layout = textView.layout ?: return topPx
        val line = layout.getLineForVertical(topPx)
        return if (layout.getLineTop(line) == topPx) {
            (topPx + textView.paddingTop).coerceAtMost(topPx + height)
        } else {
            topPx
        }
    }

    private fun snapshotClipBottomFor(topPx: Int): Int {
        val layout = textView.layout ?: return topPx + height
        val pageBottom = topPx + height
        var line = layout.getLineForVertical(pageBottom - 1)
        if (line > 0 && layout.getLineBottom(line) > pageBottom) line--
        val clipBottom = (layout.getLineBottom(line) - topPx).coerceIn(0, height)
        return snapshotClipBottomFor(topPx, clipBottom)
    }

    private fun snapshotClipBottomFor(topPx: Int, clipBottom: Int): Int =
        minOf(topPx + clipBottom + textView.paddingTop, topPx + height)

    /** Content-top px of paged index [index], or null if out of range / not paged. */
    fun pageTopPxAt(index: Int): Int? =
        if (mode == Mode.PAGED && index in paged.indices) paged[index].topPx else null

    /** The paged index currently parked at the top of the viewport (Moon+ anchor). */
    fun currentPagedIndex(): Int {
        if (mode != Mode.PAGED || paged.isEmpty()) return 0
        val maxScroll = (container.height - height).coerceAtLeast(0)
        return if (scrollY >= maxScroll) paged.lastIndex
        else paged.indexOfLast { it.topPx <= scrollY }.coerceAtLeast(0)
    }


    /**
     * Drives the active page-turn animation to [progress] (0 = outgoing covers viewport, 1 = complete).
     * SLIDE: the outgoing snapshot moves inside [PageSlideDrawable] AND the incoming page (the real
     * [container]) is slid in from the off-screen side via GPU [View.setTranslationX], so the two move
     * as one 2-page strip. SIMULATION: only the outgoing snapshot warps (in [PageCurlDrawable]) — the
     * incoming page is the real content sitting flat beneath, so the container must NOT translate.
     */
    private fun applyFlipProgress(progress: Float, forward: Boolean) {
        slideDrawable?.let {
            it.progress = progress
            container.translationX = if (forward) (1f - progress) * width else -(1f - progress) * width
        }
        curlDrawable?.progress = progress
    }

    private fun startFlip(outgoing: Bitmap, forward: Boolean) {
        flipAnimator?.cancel()
        clearFlipOverlay()
        // The overlay draws in content coords (canvas translated by scrollY). After [goToPage] the
        // content is parked on the incoming page, so scrollY is the new viewport top — place the overlay
        // there so it covers exactly what's on screen (each drawable translates to its bounds internally).
        val bounds = intArrayOf(0, scrollY, width, scrollY + height)
        if (flipStyle == dev.readflow.core.model.PageFlipStyle.SIMULATION) {
            // Lightweight GPU 3D-hinge fold (see PageCurlDrawable) — no software mesh warp.
            val drawable = PageCurlDrawable(outgoing, width, height, forward, density)
            drawable.setBounds(bounds[0], bounds[1], bounds[2], bounds[3])
            overlay.add(drawable)
            curlDrawable = drawable
        } else {
            val drawable = PageSlideDrawable(outgoing, width, height, forward, density)
            drawable.setBounds(bounds[0], bounds[1], bounds[2], bounds[3])
            overlay.add(drawable)
            slideDrawable = drawable
        }
        applyFlipProgress(0f, forward)
        flipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = flipDurationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a -> applyFlipProgress(a.animatedValue as Float, forward) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearFlipOverlay()
                    preCachePageTextures()
                }
                override fun onAnimationCancel(animation: Animator) = clearFlipOverlay()
            })
            start()
        }
    }

    private fun clearFlipOverlay() {
        slideDrawable?.let {
            overlay.remove(it)
            it.recycle()
        }
        slideDrawable = null
        curlDrawable?.let {
            overlay.remove(it)
            it.recycle()
        }
        curlDrawable = null
        // Re-centre the parked incoming page (SLIDE slid it in via translationX during the turn).
        container.translationX = 0f
    }

    // ---- Finger-tracking interactive slide -----------------------------------------------------

    /**
     * Begins a finger-driven slide toward the adjacent page [curlFromPage] + (forward ? +1 : −1), if
     * that page exists in THIS chapter. Parks the real content on the incoming page beneath, snapshots
     * the OUTGOING page (snapped clean to its own top — drops any residual half-line from a prior free
     * scroll), and lays the outgoing flat (progress 0) over the viewport with the incoming parked just
     * off-screen. Returns false at a chapter boundary (no in-chapter incoming page to preview) so the
     * caller defers to a discrete page turn.
     */
    private fun beginInteractiveCurl(forward: Boolean, anchorX: Float): Boolean {
        if (!pageTurnAnimated || mode != Mode.PAGED || paged.isEmpty() || width == 0 || height == 0) return false
        if (flipAnimator?.isRunning == true) return false
        val from = snapToNearestCanonicalPageAnchor(report = false)
        val target = from + if (forward) 1 else -1
        if (target < 0 || target > paged.lastIndex) return false
        finishInitialRevealForTurn()
        // Snap the outgoing page to its own top so the snapshot is the clean page (a mid-page free
        // scroll leaves scrollY between two page tops → the snapshot would carry top/bottom half-lines).
        scrollToPage(from, report = false)
        val outgoing = snapshotViewport() ?: return false
        // Park content on the incoming page beneath the overlay; stays silent until the turn commits.
        scrollToPage(target, report = false)
        flipAnimator?.cancel()
        clearFlipOverlay()
        if (flipStyle == dev.readflow.core.model.PageFlipStyle.SIMULATION) {
            val drawable = PageCurlDrawable(outgoing, width, height, forward, density)
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            curlDrawable = drawable
        } else {
            val drawable = PageSlideDrawable(outgoing, width, height, forward, density)
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            slideDrawable = drawable
        }
        interactiveCurl = true
        curlFromPage = from
        curlForward = forward
        curlAnchorX = anchorX
        applyFlipProgress(0f, forward)
        return true
    }

    /** Drives turn progress from finger displacement since [curlAnchorX] (full sweep ≈ one page width). */
    private fun updateInteractiveCurl(x: Float) {
        if (slideDrawable == null && curlDrawable == null) return
        val travel = if (curlForward) curlAnchorX - x else x - curlAnchorX
        applyFlipProgress((travel / width.toFloat()).coerceIn(0f, 1f), curlForward)
    }

    /**
     * Settles the interactive turn on release: commit (animate to fully turned, keep the incoming page)
     * when the drag passed half the width OR flung hard enough in the turn direction; otherwise spring
     * back (animate to flat, restore the outgoing page). Only a committed turn reports the new offset.
     */
    private fun endInteractiveCurl(velocityX: Float) {
        val progressNow = slideDrawable?.progress ?: curlDrawable?.progress
        if (progressNow == null) { interactiveCurl = false; return }
        interactiveCurl = false
        val flung = if (curlForward) velocityX < -flipFlingThresholdPxPerSec
            else velocityX > flipFlingThresholdPxPerSec
        val commit = progressNow >= 0.5f || flung
        val start = progressNow
        val end = if (commit) 1f else 0f
        if (!commit) {
            // Cancelled: the real content goes back to the outgoing page beneath as the overlay retreats.
            scrollToPage(curlFromPage, report = false)
            val left = 0; val top = scrollY
            slideDrawable?.setBounds(left, top, width, top + height)
            curlDrawable?.setBounds(left, top, width, top + height)
        }
        val forward = curlForward
        flipAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = (flipDurationMs * kotlin.math.abs(end - start)).toLong().coerceIn(80L, flipDurationMs)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a -> applyFlipProgress(a.animatedValue as Float, forward) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearFlipOverlay()
                    if (commit) reportTopOffset()
                    preCachePageTextures()
                }
                override fun onAnimationCancel(animation: Animator) = clearFlipOverlay()
            })
            start()
        }
    }

    /** Jumps to the final page (PAGED) or the bottom of the chapter (SCROLL) — back-into-prev-spine. */
    fun goToLastPage() {
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            goToPage(paged.lastIndex)
        } else {
            scrollTo(0, (container.height - height).coerceAtLeast(0))
            reportTopOffset()
        }
    }

    /**
     * Turns a temporary middle-column scroll back into a real PAGED resting state. In PAGED mode the
     * release lands on the nearest paginator-produced page anchor, not on an arbitrary scroll line, so
     * complex text/image pages keep the same fragmentation rules as tap/keyboard page turns. SCROLL
     * mode still falls back to a nearest line top.
     */
    private fun settleTemporaryScrollAnchor() {
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            snapToNearestCanonicalPageAnchor(report = false)
            return
        }
        val layout = textView.layout ?: return
        val maxScroll = (container.height - height).coerceAtLeast(0)
        val line = layout.getLineForVertical(scrollY)
        val topHere = layout.getLineTop(line)
        val topNext = if (line + 1 <= layout.lineCount - 1) layout.getLineTop(line + 1) else topHere
        // Nearest of the two surrounding line tops (minimal movement).
        val snapped = if (scrollY - topHere <= topNext - scrollY) topHere else topNext
        val target = snapped.coerceIn(0, maxScroll)
        pageClipActive = true
        if (target != scrollY) scrollTo(0, target)
        if (paged.isNotEmpty()) {
            currentPage = nearestCanonicalPageIndexForScrollY(target)
            recycleCachedTextures()
        }
        invalidate()
    }

    private enum class PagedAnchor { FLOOR, NEAREST }

    /** Scrolls to show [layoutOffset] (its page in PAGED, or that offset at top in SCROLL). */
    fun goToOffset(layoutOffset: Int) {
        goToOffset(layoutOffset, pagedAnchor = PagedAnchor.FLOOR)
    }

    private fun goToOffset(
        layoutOffset: Int,
        pagedAnchor: PagedAnchor = PagedAnchor.FLOOR,
        forceReport: Boolean = false,
    ) {
        val layout = textView.layout ?: return
        val y = layout.getLineTop(layout.getLineForOffset(layoutOffset.coerceAtLeast(0)))
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            currentPage = when (pagedAnchor) {
                PagedAnchor.FLOOR -> paged.indexOfLast { it.topPx <= y }.coerceAtLeast(0)
                PagedAnchor.NEAREST -> nearestCanonicalPageIndexForScrollY(y)
            }
            pageClipActive = true
            scrollTo(0, canonicalScrollTopForPage(currentPage) ?: paged[currentPage].topPx)
        } else {
            scrollTo(0, y)
        }
        reportTopOffset(force = forceReport)
    }

    /** Char offset of the line at the top of the viewport — the locator-stable resume anchor. */
    fun topLayoutOffset(): Int {
        val layout = textView.layout ?: return 0
        return layout.getLineStart(layout.getLineForVertical(scrollY))
    }

    private fun reportTopOffset(force: Boolean = false) {
        val offset = topLayoutOffset()
        if (!force && lastReportedTopOffset == offset) return
        lastReportedTopOffset = offset
        onTopOffsetChanged(offset)
    }

    // ---- Touch FSM (owned end-to-end; selection gated behind long-press) ----------------------

    /**
     * MoonReader-inspired PAGED routing: center 1/3 x 1/3 is a no-curl dead zone, and only the
     * inner 1/5 x 1/5 box can become temporary scroll. Everything outside that center square stays
     * available for the existing page-turn gesture path.
     */
    private fun pagedTouchZoneAtDown(): EpubPagedTouchZone =
        EpubPagedTouchZones.classify(width, height, downX, downY)

    /**
     * The [GestureDetector] sees the whole stream here (intercept runs before any child consumes),
     * so tap + long-press are classified centrally. We only *steal* the stream — return true — once
     * a drag is classified as a page-flip or a middle-zone free-scroll; until then the child TextView
     * keeps the events it needs for native long-press selection (the bug a prior always-intercept
     * version introduced). SCROLL mode steals every drag.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            pendingCleanTapX = null
            textView.setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, false)
        }
        val handled = super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val tapX = pendingCleanTapX
                pendingCleanTapX = null
                if (tapX != null && !classified && !inSelectionMode && !textInteractiveTapWasConsumed()) {
                    handleTap(tapX)
                }
            }
            MotionEvent.ACTION_CANCEL -> pendingCleanTapX = null
        }
        return handled
    }

    private fun textInteractiveTapWasConsumed(): Boolean =
        textView.getTag(RenderApiR.id.selection_aware_interactive_tap_consumed) == true

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        trackVelocity(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                classified = false
                freeScrolling = false
                centerDeadGesture = false
                flipped = false
                inSelectionMode = false
                stealing = false
                glInteractive = false
                savedDownEvent?.recycle()
                savedDownEvent = MotionEvent.obtain(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (inSelectionMode) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!classified && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    classified = true
                    val verticalDominant = abs(dy) >= abs(dx)
                    val pagedZone = if (mode == Mode.PAGED) pagedTouchZoneAtDown() else null
                    freeScrolling = mode == Mode.SCROLL ||
                        (verticalDominant && pagedZone == EpubPagedTouchZone.TemporaryScroll)
                    centerDeadGesture = mode == Mode.PAGED &&
                        !freeScrolling &&
                        pagedZone != EpubPagedTouchZone.PageTurn
                    // Inner-center temporary scroll is continuous; drop the page clip so the reader can
                    // peek across the boundary. The next flip restores it through the canonical anchor gate.
                    if (freeScrolling && mode == Mode.PAGED) {
                        pageClipActive = false
                        invalidate()
                    }
                    lastY = ev.y
                    stealing = true // own the rest of this gesture
                    return true
                }
            }
        }
        return stealing
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        trackVelocity(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reaches here only when no child claimed DOWN (e.g. tap on blank margin).
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                classified = false
                freeScrolling = false
                centerDeadGesture = false
                flipped = false
                inSelectionMode = false
                glInteractive = false
                savedDownEvent?.recycle()
                savedDownEvent = MotionEvent.obtain(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (inSelectionMode) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!classified && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    classified = true
                    val verticalDominant = abs(dy) >= abs(dx)
                    val pagedZone = if (mode == Mode.PAGED) pagedTouchZoneAtDown() else null
                    freeScrolling = mode == Mode.SCROLL ||
                        (verticalDominant && pagedZone == EpubPagedTouchZone.TemporaryScroll)
                    centerDeadGesture = mode == Mode.PAGED &&
                        !freeScrolling &&
                        pagedZone != EpubPagedTouchZone.PageTurn
                    if (freeScrolling && mode == Mode.PAGED) {
                        pageClipActive = false
                        invalidate()
                    }
                    lastY = ev.y
                }
                if (classified && freeScrolling) {
                    // Drive the temporary scroll ourselves; release snaps to a clean line top below.
                    val deltaY = (lastY - ev.y).toInt()
                    val maxScroll = (container.height - height).coerceAtLeast(0)
                    val target = (scrollY + deltaY).coerceIn(0, maxScroll)
                    if (target != scrollY) scrollTo(0, target)
                    lastY = ev.y
                } else if (classified && centerDeadGesture) {
                    // Consume the center ring without turning a page or moving content.
                } else if (classified && !freeScrolling) {
                    driveFlip(dx, dy, ev)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (glInteractive) {
                    // Hand the release to harism; it decides commit vs spring-back and fires the settle
                    // callback (which commits/restores the page + dismisses the overlay).
                    curlOverlay?.forwardTouch(ev)
                    glInteractive = false
                } else if (interactiveCurl) {
                    val vx = computeVelocityX()
                    endInteractiveCurl(vx)
                } else if (freeScrolling) {
                    // Temporary scroll may leave scrollY mid-grid with the page clip off, exposing
                    // boundary content. On release, return to a real paged anchor and re-arm the clip.
                    if (mode == Mode.PAGED) settleTemporaryScrollAnchor()
                    reportTopOffset()
                }
                recycleTracker()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (glInteractive) {
                    curlOverlay?.forwardTouch(ev)
                    glInteractive = false
                } else if (interactiveCurl) {
                    endInteractiveCurl(0f)
                } else if (freeScrolling && mode == Mode.PAGED) {
                    settleTemporaryScrollAnchor()
                    reportTopOffset()
                }
                classified = false
                freeScrolling = false
                centerDeadGesture = false
                inSelectionMode = false
                stealing = false
                recycleTracker()
                return true
            }
        }
        return true
    }

    /**
     * Routes a classified non-scroll drag. A horizontal drag becomes a finger-tracking curl (跟手)
     * toward the adjacent page — the GL realistic curl ([beginGlInteractiveCurl], forwarding the live
     * stream to harism) under SIMULATION, or the GPU slide ([beginInteractiveCurl]) otherwise. Once
     * started, later moves just feed progress. A vertical drag in a side column, or a horizontal drag at
     * a chapter boundary (no in-chapter page to preview), falls back to a discrete turn via [onTapZone].
     */
    private fun driveFlip(dx: Float, dy: Float, ev: MotionEvent) {
        if (glInteractive) {
            curlOverlay?.forwardTouch(ev)
            return
        }
        if (interactiveCurl) {
            updateInteractiveCurl(ev.x)
            return
        }
        if (flipped) return
        val horizontalDominant = abs(dx) >= abs(dy)
        if (horizontalDominant) {
            if (abs(dx) <= flipDominanceThresholdPx || abs(dy) >= flipCrossAxisLimitPx) return
            val forward = dx < 0
            if (useGlCurl) {
                if (beginGlInteractiveCurl(forward, ev.y)) {
                    // Immediately track to the current finger position (harism has only the grab-edge DOWN).
                    curlOverlay?.forwardTouch(ev)
                } else {
                    // GL unavailable / at a chapter boundary → discrete turn.
                    flipped = true
                    onTapZone(if (forward) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
                }
                return
            }
            if (!beginInteractiveCurl(forward, ev.x)) {
                flipped = true
                onTapZone(if (forward) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
            }
        } else {
            if (abs(dy) <= flipDominanceThresholdPx || abs(dx) >= flipCrossAxisLimitPx) return
            flipped = true
            onTapZone(if (dy < 0) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
        }
    }

    private fun trackVelocity(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) recycleTracker()
        val vt = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        vt.addMovement(ev)
    }

    private fun computeVelocityX(): Float {
        val vt = velocityTracker ?: return 0f
        vt.computeCurrentVelocity(1000) // px per second
        return vt.xVelocity
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun handleTap(x: Float) {
        val zone = when {
            x < width / 3f -> EpubFlowTapZone.PREV
            x > width * 2f / 3f -> EpubFlowTapZone.NEXT
            else -> EpubFlowTapZone.MENU
        }
        onTapZone(zone)
    }

    private companion object {
        /** Coalesce window for async-image reflows: collapses a decode burst into ONE paginate+anchor. */
        const val REFLOW_DEBOUNCE_MS = 80L

        /** Fade-in for the chapter's first positioned frame — long enough to hide a one-frame settle. */
        const val REVEAL_FADE_MS = 120L
        const val INITIAL_REVEAL_SETTLE_MS = REFLOW_DEBOUNCE_MS
        const val GL_DISCRETE_SETTLE_GRACE_MS = 480L
    }
}

internal enum class EpubFlowTapZone { PREV, NEXT, MENU }
