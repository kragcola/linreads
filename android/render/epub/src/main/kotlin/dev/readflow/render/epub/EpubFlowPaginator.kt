package dev.readflow.render.epub

/**
 * Continuous-flow paginator (方案 C). Slices ONE per-chapter layout into viewport-height page windows
 * — the Moon+ Reader / FBReader model: fill until the next line overflows, "非必要不分页". Grounded in
 * the StaticLayout windowing recipe (getLineForVertical + back-off-one-clipped-line) and CSS
 * Fragmentation L3 break rules (widows/orphans = 2, heading keep-with-next, oversized-image guard).
 *
 * Abstracted over [LineGeometry] (mirrors android.text.Layout) so the windowing + fragmentation logic
 * is pure and unit-testable with a fake geometry — no Robolectric layout needed.
 */
internal interface LineGeometry {
    val lineCount: Int
    fun getLineTop(line: Int): Int
    fun getLineBottom(line: Int): Int
    /** Char offset where [line] starts (into the flow text). */
    fun getLineStart(line: Int): Int
    /** Char offset where [line] ends. */
    fun getLineEnd(line: Int): Int
    /** Line index containing vertical pixel [y]. */
    fun getLineForVertical(y: Int): Int
    /** Line index containing char [offset]. */
    fun getLineForOffset(offset: Int): Int
}

/** A page window over the chapter layout: an inclusive line range and its char range. */
internal data class EpubFlowPage(
    val startLine: Int,
    val endLineExclusive: Int,
    val startOffset: Int,
    val endOffset: Int,
    val topPx: Int,
    val bottomPx: Int,
)

internal const val EPUB_FLOW_WIDOWS = 2
internal const val EPUB_FLOW_ORPHANS = 2

/**
 * Paginate [geometry] into [EpubFlowPage] windows of [pageHeightPx].
 *
 * @param isHeadingLine `true` if a line belongs to a heading block (for keep-with-next).
 * @param paragraphLineRange maps a line → the [first, lastExclusive) line range of its paragraph,
 *   used for widows/orphans. Lines not in any multi-line paragraph may return `line..line+1`.
 */
internal fun epubPaginateFlow(
    geometry: LineGeometry,
    pageHeightPx: Int,
    isHeadingLine: (Int) -> Boolean = { false },
    paragraphLineRange: (Int) -> IntRange = { it..it },
): List<EpubFlowPage> {
    val pageH = pageHeightPx.coerceAtLeast(1)
    val lineCount = geometry.lineCount
    if (lineCount <= 0) return emptyList()

    val pages = ArrayList<EpubFlowPage>()
    var startLine = 0
    while (startLine < lineCount) {
        val page = epubPageFromStartLine(
            geometry = geometry,
            startLine = startLine,
            pageHeightPx = pageH,
            isHeadingLine = isHeadingLine,
            paragraphLineRange = paragraphLineRange,
        ) ?: break
        pages += page
        startLine = page.endLineExclusive
    }
    return pages
}

/**
 * Builds one complete-line page beginning at [startLine]. This is also the forward-turn primitive
 * used after an arbitrary FREE_REST viewport: navigation starts after its last fully visible line,
 * then applies exactly the same bottom fragmentation policy as canonical pagination.
 */
internal fun epubPageFromStartLine(
    geometry: LineGeometry,
    startLine: Int,
    pageHeightPx: Int,
    isHeadingLine: (Int) -> Boolean = { false },
    paragraphLineRange: (Int) -> IntRange = { it..it },
): EpubFlowPage? {
    val lineCount = geometry.lineCount
    if (lineCount <= 0 || startLine !in 0 until lineCount) return null
    val pageTop = geometry.getLineTop(startLine)
    val pageBottom = pageTop + pageHeightPx.coerceAtLeast(1)

    // Last line whose top is in the viewport, minus a bottom-clipped ordinary line.
    var lastLine = geometry.getLineForVertical(pageBottom - 1).coerceIn(startLine, lineCount - 1)
    if (lastLine > startLine && geometry.getLineBottom(lastLine) > pageBottom) lastLine--

    // A line taller than the viewport (normally an image/block span) is indivisible.
    if (lastLine < startLine) lastLine = startLine
    if (lastLine < lineCount - 1 && lastLine > startLine) {
        lastLine = applyFragmentationRules(
            geometry = geometry,
            startLine = startLine,
            rawLastLine = lastLine,
            lineCount = lineCount,
            isHeadingLine = isHeadingLine,
            paragraphLineRange = paragraphLineRange,
        )
    }
    return geometry.pageForLineRange(startLine, lastLine + 1)
}

/**
 * Builds the previous complete-line page whose fixed lower boundary is [endLineExclusive]. The end
 * stays fixed so a backward turn from FREE_REST lands immediately before the line intersecting the
 * old viewport top; the start expands upward only while every selected line fits completely.
 */
internal fun epubPageEndingAtLine(
    geometry: LineGeometry,
    endLineExclusive: Int,
    pageHeightPx: Int,
): EpubFlowPage? {
    val lineCount = geometry.lineCount
    if (lineCount <= 0 || endLineExclusive !in 1..lineCount) return null
    val lastLine = endLineExclusive - 1
    val fixedBottom = geometry.getLineBottom(lastLine)
    val pageH = pageHeightPx.coerceAtLeast(1)
    var startLine = lastLine
    while (startLine > 0 && fixedBottom - geometry.getLineTop(startLine - 1) <= pageH) {
        startLine--
    }
    return geometry.pageForLineRange(startLine, endLineExclusive)
}

private fun LineGeometry.pageForLineRange(startLine: Int, endLineExclusive: Int): EpubFlowPage {
    val lastLine = endLineExclusive - 1
    return EpubFlowPage(
        startLine = startLine,
        endLineExclusive = endLineExclusive,
        startOffset = getLineStart(startLine),
        endOffset = getLineEnd(lastLine),
        topPx = getLineTop(startLine),
        bottomPx = getLineBottom(lastLine),
    )
}

/**
 * Adjusts the raw page-bottom line up to honor keep-with-next (heading never alone at page bottom)
 * and widows/orphans. Never pushes the break above [startLine] (a page must keep ≥1 line).
 */
private fun applyFragmentationRules(
    geometry: LineGeometry,
    startLine: Int,
    rawLastLine: Int,
    lineCount: Int,
    isHeadingLine: (Int) -> Boolean,
    paragraphLineRange: (Int) -> IntRange,
): Int {
    var lastLine = rawLastLine

    // keep-with-next: a heading at the very bottom (its paragraph's body would start next page) →
    // push the whole heading to the next page.
    if (isHeadingLine(lastLine) && !isHeadingLine(lastLine + 1)) {
        val headingRange = paragraphLineRange(lastLine)
        val target = (headingRange.first - 1)
        if (target >= startLine) return target
    }

    // orphans: don't strand the FIRST < ORPHANS lines of a paragraph at the page bottom.
    val bottomRange = paragraphLineRange(lastLine)
    val linesOfBottomParaOnPage = lastLine - bottomRange.first + 1
    val bottomParaContinues = bottomRange.last > lastLine + 1
    if (bottomParaContinues && linesOfBottomParaOnPage in 1 until EPUB_FLOW_ORPHANS) {
        val target = bottomRange.first - 1
        if (target >= startLine) return target
    }

    // widows: don't carry only the LAST < WIDOWS lines of a paragraph to the next page.
    val nextRange = paragraphLineRange(lastLine + 1)
    val linesCarried = nextRange.last - (lastLine + 1)
    val paraStartedBefore = nextRange.first <= lastLine
    if (paraStartedBefore && linesCarried in 1 until EPUB_FLOW_WIDOWS) {
        val target = lastLine - (EPUB_FLOW_WIDOWS - linesCarried)
        if (target >= startLine) return target
    }

    return lastLine
}
