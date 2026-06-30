package dev.readflow.render.epub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.readflow.render.api.SelectionAwareTextView
import kotlin.math.abs

/**
 * Continuous-flow reading surface (方案 C, Moon+ Reader model). One chapter → one whole-chapter
 * Spannable in a single [SelectionAwareTextView] → one StaticLayout (the TextView's OWN layout, the
 * single source of truth for pagination — 审计 C1/H3), hosted in a non-smooth [ScrollView].
 *
 * Touch is owned end-to-end (审计 H4/H5), mirroring Moon+ Reader / FBReader: a [GestureDetector]
 * classifies tap / long-press / scroll; selection is gated behind long-press so it never fights
 * page turns. PAGED: edge tap or horizontal/non-middle drag flips a page (animated slide); a
 * vertical drag STARTING in the centre column (middle 1/3 of width, full height) becomes a free
 * native scroll that stays where released (no snap). SCROLL: free scroll throughout.
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

    private val container = FrameLayout(context).apply { addView(textView) }

    var mode: Mode = Mode.PAGED
        set(value) {
            field = value
            repaginate()
        }

    private var paged: List<EpubFlowPage> = emptyList()
    private var pageHeightPx: Int = 0
    private var currentPage: Int = 0
    private var flow: EpubChapterFlow? = null

    /** Layout height we last paginated against; a change means the content reflowed (async image load). */
    private var paginatedLayoutHeight: Int = -1

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val flipDominanceThresholdPx = 20 * density
    private val flipCrossAxisLimitPx = 40 * density

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var inSelectionMode = false
    private var classified = false
    private var freeScrolling = false
    private var flipped = false
    private var stealing = false

    /** Page-flip slide animation: a snapshot of the outgoing page slides off as the new page shows. */
    var pageTurnAnimated = true
    private var flipAnimator: ValueAnimator? = null
    private var flipDrawable: BitmapDrawable? = null
    private val flipDurationMs = 220L

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
            // Only a clean tap (no drag, no long-press) turns a page / toggles chrome.
            if (!classified && !inSelectionMode) handleTap(e.x)
            return false
        }
    })

    init {
        isSmoothScrollingEnabled = false
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        addView(container)
        textView.onSelectionRangeChanged = { s, e -> onSelectionRange(s, e) }
        // A chapter's images load async with no placeholder height (审计: zero-height until decoded), so
        // each one that arrives reflows the StaticLayout and shifts every following line down. Our page
        // windows + resting scrollY are then stale → the parked page lands mid-line (half-line/half-image
        // at the edges). Re-paginate + re-anchor by content offset whenever the layout height changes, so
        // the parked page snaps back to a line top (mirrors [onSizeChanged] for the view-height case).
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val layout = textView.layout ?: return@addOnLayoutChangeListener
            if (flow == null || layout.height <= 0) return@addOnLayoutChangeListener
            // Only react to a genuine content reflow (height delta from a decoded image), not our own
            // re-layout after repaginate (which records the new height) or a no-op pass.
            if (layout.height == paginatedLayoutHeight) return@addOnLayoutChangeListener
            val anchorOffset = if (paged.isNotEmpty()) topLayoutOffset() else -1
            post {
                repaginate()
                if (anchorOffset >= 0) goToOffset(anchorOffset)
            }
        }
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
        val bottom = if (clipBottom != null) scrollY + clipBottom else scrollY + height
        canvas.clipRect(0, topClip, width, bottom)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    /**
     * Content-y at which to START drawing this page (>= [scrollY]). Page turns snap [scrollY] to an exact
     * line top, so the line ABOVE the page (its box bottom == [scrollY]) sits entirely off-screen — yet
     * because [includeFontPadding] is false the line box is tightened to the font's ascent/descent, and a
     * larger-font (span-sized) line paints a few px of glyph ink BELOW its tight box bottom. That overflow
     * lands just inside the viewport top and shows as a faint half-line (审计: 半截的文字). Unlike a
     * per-line canvas draw (Moon+ never paints the off-page line), our [super.dispatchDraw] paints the whole
     * layout, so we clip it off: drop a thin strip = a fraction of the previous line's height (scales with
     * font size). Measured bleed is ≤4px while the page's own first line ink begins ≥0.6× a line-height
     * below the top, so the strip removes the overflow and never clips real text. Blank leading on a clean
     * page, so nothing visible is lost there.
     */
    private fun pageClipTopInViewport(): Int {
        if (mode != Mode.PAGED || !pageClipActive || paged.isEmpty()) return scrollY
        val layout = textView.layout ?: return scrollY
        val startLine = layout.getLineForVertical(scrollY)
        if (startLine <= 0) return scrollY
        val prevLineHeight = layout.getLineBottom(startLine - 1) - layout.getLineTop(startLine - 1)
        if (prevLineHeight <= 0) return scrollY
        // Bound by the viewport height (never the page's bottomPx: [currentPage] can be transiently stale
        // mid-transition, giving an absolute bottomPx below scrollY → an empty coerce range → draw crash).
        val guard = Math.round(prevLineHeight * TOP_BLEED_GUARD_FRACTION).coerceIn(0, height)
        return scrollY + guard
    }

    /**
     * Pixel height (within the viewport) at which the current page ends, or null when no clip should
     * apply (SCROLL mode, free-scrolling, no pages, or the page already reaches the viewport bottom).
     */
    private fun pageClipBottomInViewport(): Int? {
        if (mode != Mode.PAGED || !pageClipActive || paged.isEmpty()) return null
        val page = paged.getOrNull(currentPage) ?: return null
        val clip = page.bottomPx - scrollY
        // No clip needed once the page fills (or exceeds) the viewport — e.g. a full-page image.
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
        val anchorOffset = if (paged.isNotEmpty()) topLayoutOffset() else -1
        textView.post {
            repaginate()
            if (anchorOffset >= 0) goToOffset(anchorOffset)
        }
    }

    /** Installs the chapter Spannable and paginates over the TextView's measured layout. */
    fun setChapter(flow: EpubChapterFlow, spannable: CharSequence, pageHeightPx: Int) {
        flipAnimator?.cancel()
        clearFlipOverlay()
        this.flow = flow
        this.pageHeightPx = pageHeightPx.coerceAtLeast(1)
        currentPage = 0
        paginatedLayoutHeight = -1
        textView.text = spannable
        // Layout is available after the next measure/layout pass; paginate then.
        textView.post { repaginate() }
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

    private fun repaginate() {
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
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
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
        if (mode == Mode.PAGED) {
            if (paged.isEmpty()) return false
            // Re-anchor to the page currently visible: after a middle-zone free-scroll the user may
            // be parked between page tops, so resume paged turns from where they actually are
            // (统一两种模式 — PAGED and free-scroll share one scroll position).
            // The final page is a short remainder whose topPx exceeds maxScroll, so ScrollView clamps
            // goToPage(lastIndex) to maxScroll; deriving the anchor purely from the clamped scrollY can
            // never resolve to lastIndex, which would trap paging one page before the chapter end and
            // never report the boundary (no cross-spine advance). Snap the anchor to lastIndex once we
            // are parked at the scroll extreme.
            val maxScroll = (container.height - height).coerceAtLeast(0)
            val anchor = if (scrollY >= maxScroll) {
                paged.lastIndex
            } else {
                paged.indexOfLast { it.topPx <= scrollY }.coerceAtLeast(0)
            }
            currentPage = anchor
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

    fun goToPage(index: Int) {
        if (mode != Mode.PAGED || paged.isEmpty()) return
        currentPage = index.coerceIn(0, paged.lastIndex)
        pageClipActive = true
        scrollTo(0, paged[currentPage].topPx)
        reportTopOffset()
    }

    /**
     * Page turn with a Moon+/FBReader-style horizontal slide: snapshot the current page, jump the
     * real content to the target, then slide the snapshot of the OUTGOING page off-screen while the
     * incoming page is revealed beneath it (平移滞留 — a translation, not a visible scroll). Falls back
     * to an instant [goToPage] when animation is off or a snapshot can't be taken.
     */
    private fun goToPageAnimated(index: Int, forward: Boolean) {
        val target = index.coerceIn(0, paged.lastIndex)
        if (!pageTurnAnimated || mode != Mode.PAGED || width == 0 || height == 0) {
            goToPage(target)
            return
        }
        val outgoing = snapshotViewport()
        if (outgoing == null) {
            goToPage(target)
            return
        }
        // Reveal the incoming page underneath, then slide the outgoing snapshot away over the top.
        goToPage(target)
        startFlip(outgoing, forward)
    }

    private fun snapshotViewport(): Bitmap? = try {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        // ScrollView draws its children at -scrollY; replicate so the snapshot is exactly what's
        // on screen now (the current page), independent of the upcoming scroll.
        canvas.translate(0f, -scrollY.toFloat())
        background?.let { bg ->
            bg.setBounds(0, scrollY, width, scrollY + height)
            bg.draw(canvas)
        }
        // Clip the content to the outgoing page's bottom so the sliding snapshot shows blank
        // background below the last line — never the next page's bleed (matches on-screen render).
        val clipBottom = pageClipBottomInViewport()
        if (clipBottom != null) {
            val save = canvas.save()
            canvas.clipRect(0, scrollY, width, scrollY + clipBottom)
            container.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            container.draw(canvas)
        }
        bmp
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun startFlip(outgoing: Bitmap, forward: Boolean) {
        flipAnimator?.cancel()
        clearFlipOverlay()
        val drawable = BitmapDrawable(resources, outgoing)
        drawable.setBounds(0, 0, width, height)
        overlay.add(drawable)
        flipDrawable = drawable
        val endX = if (forward) -width else width
        flipAnimator = ValueAnimator.ofFloat(0f, endX.toFloat()).apply {
            duration = flipDurationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val dx = (a.animatedValue as Float).toInt()
                drawable.setBounds(dx, 0, dx + width, height)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = clearFlipOverlay()
                override fun onAnimationCancel(animation: Animator) = clearFlipOverlay()
            })
            start()
        }
    }

    private fun clearFlipOverlay() {
        flipDrawable?.let {
            overlay.remove(it)
            it.bitmap?.recycle()
        }
        flipDrawable = null
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

    /** Scrolls to show [layoutOffset] (its page in PAGED, or that offset at top in SCROLL). */
    fun goToOffset(layoutOffset: Int) {
        val layout = textView.layout ?: return
        val y = layout.getLineTop(layout.getLineForOffset(layoutOffset.coerceAtLeast(0)))
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            currentPage = paged.indexOfLast { it.topPx <= y }.coerceAtLeast(0)
            pageClipActive = true
            scrollTo(0, paged[currentPage].topPx)
        } else {
            scrollTo(0, y)
        }
        reportTopOffset()
    }

    /** Char offset of the line at the top of the viewport — the locator-stable resume anchor. */
    fun topLayoutOffset(): Int {
        val layout = textView.layout ?: return 0
        return layout.getLineStart(layout.getLineForVertical(scrollY))
    }

    private fun reportTopOffset() {
        onTopOffsetChanged(topLayoutOffset())
    }

    // ---- Touch FSM (owned end-to-end; selection gated behind long-press) ----------------------

    /**
     * Moon+ Reader's temporary-scroll trigger: the centre vertical COLUMN (middle 1/3 of the width),
     * spanning the FULL height — not a small centre box. A vertical drag anywhere down this column
     * becomes a free scroll; the left/right columns stay reserved for page flips. Widening the old
     * 1/3×1/3 box to a full-height column makes the gesture far easier to land (审计: trigger zone).
     */
    private fun inMiddleColumn(x: Float): Boolean =
        x > width / 3f && x < width * 2f / 3f

    /**
     * The [GestureDetector] sees the whole stream here (intercept runs before any child consumes),
     * so tap + long-press are classified centrally. We only *steal* the stream — return true — once
     * a drag is classified as a page-flip or a middle-zone free-scroll; until then the child TextView
     * keeps the events it needs for native long-press selection (the bug a prior always-intercept
     * version introduced). SCROLL mode steals every drag.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                classified = false
                freeScrolling = false
                flipped = false
                inSelectionMode = false
                stealing = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (inSelectionMode) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!classified && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    classified = true
                    val verticalDominant = abs(dy) >= abs(dx)
                    freeScrolling = mode == Mode.SCROLL || (verticalDominant && inMiddleColumn(downX))
                    // A centre-column free scroll is continuous (Moon+ temporary-scroll) — drop the
                    // page clip so the reader can peek across the boundary; the next flip restores it.
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
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reaches here only when no child claimed DOWN (e.g. tap on blank margin).
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                classified = false
                freeScrolling = false
                flipped = false
                inSelectionMode = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (inSelectionMode) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!classified && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    classified = true
                    val verticalDominant = abs(dy) >= abs(dx)
                    freeScrolling = mode == Mode.SCROLL || (verticalDominant && inMiddleColumn(downX))
                    if (freeScrolling && mode == Mode.PAGED) {
                        pageClipActive = false
                        invalidate()
                    }
                    lastY = ev.y
                }
                if (classified && freeScrolling) {
                    // Drive the scroll ourselves: stays exactly where released (no snap, no fling).
                    val deltaY = (lastY - ev.y).toInt()
                    val maxScroll = (container.height - height).coerceAtLeast(0)
                    val target = (scrollY + deltaY).coerceIn(0, maxScroll)
                    if (target != scrollY) scrollTo(0, target)
                    lastY = ev.y
                } else if (classified && !flipped) {
                    maybeFlip(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (freeScrolling) reportTopOffset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun maybeFlip(dx: Float, dy: Float) {
        val horizontalDominant = abs(dx) >= abs(dy)
        val passes = if (horizontalDominant) {
            abs(dx) > flipDominanceThresholdPx && abs(dy) < flipCrossAxisLimitPx
        } else {
            abs(dy) > flipDominanceThresholdPx && abs(dx) < flipCrossAxisLimitPx
        }
        if (!passes) return
        flipped = true
        val forward = if (horizontalDominant) dx < 0 else dy < 0
        onTapZone(if (forward) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
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
        /**
         * Top strip dropped on a mid-paragraph page (as a fraction of the start line's height) to hide the
         * previous line's descender bleed (审计: 半截的文字). Must exceed the typical descender overflow
         * (~6–10% of line height) yet stay well under the first line's own ink offset (~0.85× line height,
         * inside the 1.75× leading), so the page's first line is never clipped.
         */
        const val TOP_BLEED_GUARD_FRACTION = 0.15f
    }
}

internal enum class EpubFlowTapZone { PREV, NEXT, MENU }
