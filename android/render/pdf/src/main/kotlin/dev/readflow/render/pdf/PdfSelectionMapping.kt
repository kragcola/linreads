package dev.readflow.render.pdf

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.fixedPageIndex
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection

/**
 * Colored page-point or bitmap-space rectangles for selection / annotation paint.
 * Distinct from transient search highlights (different overlay + color).
 */
internal data class PdfColoredRect(
    val rect: PdfRect,
    val color: Int,
)

/**
 * Map a same-page framework [PdfFrameworkPageSelection] into [ReaderTextSelection] with
 * [LocatorStrategy.PageText] start/end on that page.
 *
 * Fail-closed rules:
 * - blank/empty selected text → null
 * - unresolvable char offsets → null
 * - framework page index mismatch with the opened page → null (cross-page not fabricated)
 * - [pageCount] invalid / page out of range → null
 *
 * Offset resolution order (deterministic):
 * 1. Prefer framework boundary indices when both are non-negative
 * 2. Else locate [PdfFrameworkPageSelection.selectedText] inside [pageText]
 * 3. Else null
 */
internal fun mapPageSelectionToReaderTextSelection(
    pageIndex: Int,
    pageCount: Int,
    pageText: String,
    selection: PdfFrameworkPageSelection,
): ReaderTextSelection? {
    if (pageCount <= 0 || pageIndex !in 0 until pageCount) return null
    // selectContent is per-page; a different page index means we cannot form a same-page locator.
    if (selection.page != pageIndex) return null

    val selectedText = selection.selectedText
    if (selectedText.isBlank()) return null

    val range = resolveCharRange(pageText = pageText, selection = selection, selectedText = selectedText)
        ?: return null
    val (startOffset, endOffset) = range
    if (startOffset >= endOffset) return null

    val progression = pageIndex.toFloat() / pageCount
    val start = Locator(
        strategy = LocatorStrategy.PageText(pageIndex, pageCount, startOffset),
        progression = progression,
        totalProgression = progression,
    )
    val end = Locator(
        strategy = LocatorStrategy.PageText(pageIndex, pageCount, endOffset),
        progression = progression,
        totalProgression = progression,
    )
    return ReaderTextSelection(start = start, end = end, selectedText = selectedText)
}

/**
 * Resolve inclusive-exclusive char offsets for a framework selection on [pageText].
 *
 * Deterministic order:
 * 1. Prefer framework boundary indices when both are non-negative, start < end,
 *    and both ends lie within [0, pageText.length] (hi exclusive).
 *    When [selectedText] is non-empty it must equal `pageText.substring(lo, hi)`;
 *    otherwise indices are rejected and we fall through.
 * 2. Else locate [selectedText] inside [pageText] when both are non-empty.
 * 3. Else null — never fabricate ranges past [pageText] or from unmatched indices.
 */
internal fun resolveCharRange(
    pageText: String,
    selection: PdfFrameworkPageSelection,
    selectedText: String = selection.selectedText,
): Pair<Int, Int>? {
    val startIdx = selection.start.index
    val stopIdx = selection.stop.index
    val length = pageText.length

    if (startIdx >= 0 && stopIdx >= 0) {
        val lo = minOf(startIdx, stopIdx)
        val hi = maxOf(startIdx, stopIdx)
        val indicesInRange = hi > lo && lo < length && hi <= length
        if (indicesInRange) {
            if (selectedText.isEmpty() || pageText.substring(lo, hi) == selectedText) {
                return lo to hi
            }
            // Index window valid but text mismatch — do not trust indices; fall through.
        }
        // Negative path already excluded; collapse (hi <= lo) or out-of-range → fall through.
    }

    if (selectedText.isEmpty() || pageText.isEmpty()) return null

    val found = pageText.indexOf(selectedText)
    if (found < 0) return null
    return found to (found + selectedText.length)
}

/**
 * Pure selection-gesture sequencing: only the latest event under the current open-book
 * generation may publish live selection. Older async completions must be dropped.
 *
 * [openBookGeneration] is the engine search/open generation (bumped on close/open).
 * [eventId] is monotonic per gesture event within a session (long-press / move / finish).
 */
internal object PdfSelectionGestureLifecycle {
    fun nextEventId(current: Int): Int = current + 1

    fun isEventFresh(
        openBookGeneration: Int,
        eventOpenBookGeneration: Int,
        currentEventId: Int,
        resultEventId: Int,
    ): Boolean =
        openBookGeneration == eventOpenBookGeneration &&
            currentEventId == resultEventId &&
            resultEventId > 0 &&
            openBookGeneration >= 0

    /**
     * Whether a host should intercept touches for selection (not for idle scroll / pinch).
     * Scale-in-progress always yields to parent / scale detector.
     */
    fun hostInterceptsForSelection(selecting: Boolean, scaleInProgress: Boolean): Boolean =
        selecting && !scaleInProgress
}

/**
 * Page-point bounds from a framework selection (for live selection paint).
 * Empty when the selection has no geometry.
 */
internal fun selectionPagePointBounds(selection: PdfFrameworkPageSelection): List<PdfRect> =
    selection.bounds.filterNot { it.isEmpty() }

/**
 * Filter persisted annotations that map to [pageIndex] via same-page [LocatorStrategy.PageText]
 * start and end. Foreign strategies and cross-page ranges are ignored (not reinterpreted).
 */
internal fun pageTextAnnotationsForPage(
    annotations: List<ReaderTextAnnotation>,
    pageIndex: Int,
): List<ReaderTextAnnotation> =
    annotations.filter { annotation ->
        val start = annotation.start.strategy as? LocatorStrategy.PageText ?: return@filter false
        val end = annotation.end.strategy as? LocatorStrategy.PageText ?: return@filter false
        start.index == pageIndex && end.index == pageIndex &&
            fixedPageIndex(annotation.start) == pageIndex &&
            fixedPageIndex(annotation.end) == pageIndex
    }

/**
 * Char range (start inclusive, end exclusive) for a same-page PageText annotation.
 * Returns null when anchors are foreign, cross-page, or inverted without repairable order.
 */
internal fun pageTextAnnotationCharRange(annotation: ReaderTextAnnotation): Pair<Int, Int>? {
    val start = annotation.start.strategy as? LocatorStrategy.PageText ?: return null
    val end = annotation.end.strategy as? LocatorStrategy.PageText ?: return null
    if (start.index != end.index) return null
    val lo = minOf(start.charOffset, end.charOffset)
    val hi = maxOf(start.charOffset, end.charOffset)
    if (hi <= lo) return null
    return lo to hi
}

/**
 * Map same-page PageText annotations that already have page-point bounds into colored rects.
 * [boundsByAnnotationId] is filled from framework selectContent-by-index resolution.
 */
internal fun mapAnnotationBoundsForPage(
    annotations: List<ReaderTextAnnotation>,
    pageIndex: Int,
    boundsByAnnotationId: Map<String, List<PdfRect>>,
): List<PdfColoredRect> {
    val out = ArrayList<PdfColoredRect>()
    for (annotation in pageTextAnnotationsForPage(annotations, pageIndex)) {
        val bounds = boundsByAnnotationId[annotation.id].orEmpty().filterNot { it.isEmpty() }
        if (bounds.isEmpty()) continue
        for (rect in bounds) {
            out += PdfColoredRect(rect = rect, color = annotation.color)
        }
    }
    return out
}

/**
 * Whether two annotation lists are the same paint input (id/range/color).
 * Used so [setTextAnnotations] stays cheap when ViewModel re-emits an equivalent list.
 */
internal fun annotationPaintKey(annotations: List<ReaderTextAnnotation>): String =
    annotations.joinToString(separator = "|") { a ->
        val start = a.start.strategy
        val end = a.end.strategy
        "${a.id}:${a.color}:$start:$end"
    }

/**
 * Pure lifecycle for async annotation geometry resolution.
 *
 * [annotationListGeneration] is bumped whenever the paint-key input actually changes
 * (or annotations are cleared on open/close). In-flight resolutions that captured an
 * older list generation must not [put] geometry — same-id range updates would otherwise
 * keep painting stale page-point bounds.
 *
 * [openBookGeneration] is the engine search/open generation; both must still match.
 */
internal object PdfAnnotationGeometryLifecycle {
    fun nextGeneration(current: Int): Int = current + 1

    fun isResolutionFresh(
        openBookGeneration: Int,
        resolutionOpenBookGeneration: Int,
        annotationListGeneration: Int,
        resolutionAnnotationListGeneration: Int,
    ): Boolean =
        openBookGeneration == resolutionOpenBookGeneration &&
            annotationListGeneration == resolutionAnnotationListGeneration &&
            openBookGeneration >= 0 &&
            annotationListGeneration >= 0 &&
            resolutionAnnotationListGeneration > 0
}

/**
 * TalkBack content description for a PDF page that may carry a live selection.
 */
internal fun pdfPageContentDescription(
    pageIndex: Int,
    pageCount: Int,
    selectedText: String?,
): String {
    val base = "第 ${pageIndex + 1} 页，共 $pageCount 页"
    val snippet = selectedText?.trim().orEmpty()
    if (snippet.isEmpty()) return base
    val short = if (snippet.length <= 40) snippet else snippet.take(39) + "…"
    return "$base，已选中：$short"
}
