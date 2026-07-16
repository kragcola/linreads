package dev.readflow.render.md

/**
 * Pure page-windowing over rendered Markdown line geometry.
 *
 * Mirrors the EPUB continuous-flow recipe: fill until the next line would be
 * clipped, then end the page on a complete line boundary. An oversized first
 * line/block (taller than the viewport) is emitted as its own page so pagination
 * never loops.
 *
 * Abstracted over [MarkdownLineGeometry] so unit tests can inject fake geometry
 * without Robolectric, while production uses Android [android.text.StaticLayout]
 * via [StaticLayoutMarkdownGeometry].
 */
internal interface MarkdownLineGeometry {
    val lineCount: Int
    fun getLineTop(line: Int): Int
    fun getLineBottom(line: Int): Int
    fun getLineStart(line: Int): Int
    fun getLineEnd(line: Int): Int
    fun getLineForVertical(y: Int): Int
}

/** Inclusive line range and exclusive char range for one Markdown page slot. */
internal data class MarkdownPageWindow(
    val startLine: Int,
    val endLineExclusive: Int,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Paginate [geometry] into complete-line [MarkdownPageWindow]s of [pageHeightPx].
 * Empty geometry yields a single empty page window at offset 0.
 */
internal fun markdownPaginate(
    geometry: MarkdownLineGeometry,
    pageHeightPx: Int,
): List<MarkdownPageWindow> {
    val pageH = pageHeightPx.coerceAtLeast(1)
    val lineCount = geometry.lineCount
    if (lineCount <= 0) {
        return listOf(MarkdownPageWindow(0, 0, 0, 0))
    }

    val pages = ArrayList<MarkdownPageWindow>()
    var startLine = 0
    while (startLine < lineCount) {
        val page = markdownPageFromStartLine(geometry, startLine, pageH) ?: break
        pages += page
        // Guaranteed progress: endLineExclusive > startLine always (at least one line).
        startLine = page.endLineExclusive
    }
    return pages.ifEmpty { listOf(geometry.pageForLineRange(0, 1)) }
}

/**
 * Builds one complete-line page beginning at [startLine].
 * A page ends only at a full line boundary; never mid-line.
 */
internal fun markdownPageFromStartLine(
    geometry: MarkdownLineGeometry,
    startLine: Int,
    pageHeightPx: Int,
): MarkdownPageWindow? {
    val lineCount = geometry.lineCount
    if (lineCount <= 0 || startLine !in 0 until lineCount) return null
    val pageTop = geometry.getLineTop(startLine)
    val pageBottom = pageTop + pageHeightPx.coerceAtLeast(1)

    // Last line whose top is still inside the viewport, minus a bottom-clipped line.
    var lastLine = geometry.getLineForVertical(pageBottom - 1).coerceIn(startLine, lineCount - 1)
    if (lastLine > startLine && geometry.getLineBottom(lastLine) > pageBottom) {
        lastLine--
    }
    // Oversized first line/block: keep it as a single page rather than looping.
    if (lastLine < startLine) lastLine = startLine

    return geometry.pageForLineRange(startLine, lastLine + 1)
}

private fun MarkdownLineGeometry.pageForLineRange(
    startLine: Int,
    endLineExclusive: Int,
): MarkdownPageWindow {
    val lastLine = (endLineExclusive - 1).coerceAtLeast(startLine)
    return MarkdownPageWindow(
        startLine = startLine,
        endLineExclusive = endLineExclusive.coerceAtLeast(startLine + 1),
        startOffset = getLineStart(startLine),
        endOffset = getLineEnd(lastLine),
    )
}

/**
 * Finds the page index containing [renderedOffset] (absolute char offset into the
 * full rendered Spanned). Empty/invalid windows map everything to page 0.
 */
internal fun pageIndexForRenderedOffset(
    windows: List<MarkdownPageWindow>,
    renderedOffset: Int,
): Int {
    if (windows.isEmpty()) return 0
    val safeOffset = renderedOffset.coerceAtLeast(0)
    var page = 0
    for (i in windows.indices) {
        val window = windows[i]
        if (window.startOffset <= safeOffset) {
            page = i
        } else {
            break
        }
        // Prefer the window that actually contains the offset when ranges abut.
        if (safeOffset < window.endOffset || i == windows.lastIndex) {
            // Keep walking so the last startOffset <= safeOffset wins for offsets
            // exactly on a boundary (boundary belongs to the later page only if
            // equal to that page's start). For startOffset == endOffset empty windows,
            // stay on the last matching start.
            if (safeOffset < window.endOffset) return i
        }
    }
    return page.coerceIn(0, windows.lastIndex)
}
