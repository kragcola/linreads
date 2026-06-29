package dev.readflow.render.epub

import android.annotation.SuppressLint
import android.content.Context
import android.text.Layout
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
 * page turns. PAGED: edge tap or horizontal/non-middle drag flips a page (instant scrollTo); a
 * vertical drag STARTING in the centre 1/3×1/3 zone becomes a free native scroll that stays where
 * released (no snap). SCROLL: free scroll throughout.
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
    }

    /** Installs the chapter Spannable and paginates over the TextView's measured layout. */
    fun setChapter(flow: EpubChapterFlow, spannable: CharSequence, pageHeightPx: Int) {
        this.flow = flow
        this.pageHeightPx = pageHeightPx.coerceAtLeast(1)
        currentPage = 0
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
        val geometry = EpubLayoutLineGeometry(layout)
        val headingLines = headingLineSet(layout, f)
        paged = if (mode == Mode.PAGED) {
            epubPaginateFlow(
                geometry = geometry,
                pageHeightPx = pageHeightPx,
                isHeadingLine = { it in headingLines },
                paragraphLineRange = { paragraphLineRange(layout, f, it) },
            )
        } else {
            emptyList()
        }
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            currentPage = currentPage.coerceIn(0, paged.lastIndex)
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
            goToPage(target)
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
        scrollTo(0, paged[currentPage].topPx)
        reportTopOffset()
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

    private fun inMiddleZone(x: Float, y: Float): Boolean =
        x > width / 3f && x < width * 2f / 3f && y > height / 3f && y < height * 2f / 3f

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
                    freeScrolling = mode == Mode.SCROLL || (verticalDominant && inMiddleZone(downX, downY))
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
                    freeScrolling = mode == Mode.SCROLL || (verticalDominant && inMiddleZone(downX, downY))
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
}

internal enum class EpubFlowTapZone { PREV, NEXT, MENU }
