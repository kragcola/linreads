package dev.readflow.render.pdf

import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.adjacentTocEntry
import dev.readflow.core.model.fixedPageIndex
import dev.readflow.core.model.pageChapterInfo

/**
 * Destination page for a PDF outline [TocEntry], when the locator carries a finite page index.
 * Non-page strategies and negative indices are treated as missing.
 * Outline destinations remain bare [LocatorStrategy.Page] (progress identity).
 */
internal fun pdfOutlineDestinationPage(entry: TocEntry): Int? {
    val page = (entry.locator.strategy as? LocatorStrategy.Page)?.index ?: return null
    return page.takeIf { it >= 0 }
}

/**
 * Target page for PDF goTo / pageIndexForLocator.
 * Accepts bare [LocatorStrategy.Page] and annotation [LocatorStrategy.PageText] via
 * [fixedPageIndex]; callers must still publish bare Page as progress identity.
 */
internal fun pdfTargetPageIndex(locator: Locator, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    return (fixedPageIndex(locator) ?: 0).coerceIn(0, pageCount - 1)
}

/**
 * CHAPTER chrome from a **real** PDF outline (not the fallback page list).
 *
 * Selection is page-based and safe for non-monotonic destination order: among entries whose
 * finite destination page is `<=` [currentPageIndex], pick the closest (largest page). Ties keep
 * the **last** original outline index. Displayed TOC order is never reordered here.
 *
 * [progressInChapter] is always `0f` (no invented local page percent).
 *
 * When [pageCount] <= 0, returns DOCUMENT chrome even if [outlineEntries] is nonempty
 * (zero-count dominance — no fake CHAPTER over a closed/empty document).
 */
internal fun pdfOutlineChapterInfo(
    outlineEntries: List<TocEntry>,
    currentPageIndex: Int,
    pageCount: Int,
    documentTitleFallback: String = PDF_DOCUMENT_TITLE_FALLBACK,
): ChapterInfo {
    val fallbackTitle = documentTitleFallback.trim().ifEmpty { PDF_DOCUMENT_TITLE_FALLBACK }
    val safeCount = pageCount.coerceAtLeast(0)
    if (safeCount <= 0) {
        return pdfClosedOrEmptyDocumentChapterInfo(fallbackTitle)
    }
    if (outlineEntries.isEmpty()) {
        return pdfClosedOrEmptyDocumentChapterInfo(fallbackTitle)
    }
    val safePage = currentPageIndex.coerceIn(0, safeCount - 1)

    var bestIndex = -1
    var bestPage = Int.MIN_VALUE
    for (i in outlineEntries.indices) {
        val dest = pdfOutlineDestinationPage(outlineEntries[i]) ?: continue
        if (dest <= safePage && dest >= bestPage) {
            bestPage = dest
            bestIndex = i
        }
    }
    if (bestIndex < 0) bestIndex = 0

    val entry = outlineEntries[bestIndex]
    val title = entry.title.trim().ifEmpty { fallbackTitle }
    return ChapterInfo(
        currentIndex = bestIndex,
        totalChapters = outlineEntries.size,
        currentTitle = title,
        progressInChapter = 0f,
        kind = ChapterInfo.Kind.CHAPTER,
    )
}

/**
 * Truthful PDF navigation chrome:
 * - [pageCount] <= 0 → [ChapterInfo.Kind.DOCUMENT] / "正文" (even if outline is nonempty)
 * - real outline present and pages > 0 → [ChapterInfo.Kind.CHAPTER]
 * - no outline and [pageCount] > 0 → [ChapterInfo.Kind.PAGE] titled "第 n 页"
 *
 * Fallback page-list TOC is **not** treated as a chapter outline.
 */
internal fun pdfNavigationChapterInfo(
    realOutlineEntries: List<TocEntry>,
    currentPageIndex: Int,
    pageCount: Int,
    documentTitleFallback: String = PDF_DOCUMENT_TITLE_FALLBACK,
): ChapterInfo {
    val safeCount = pageCount.coerceAtLeast(0)
    // Zero-count dominance: closed/empty document never exposes CHAPTER chrome.
    if (safeCount <= 0) {
        return pdfClosedOrEmptyDocumentChapterInfo(documentTitleFallback)
    }
    if (realOutlineEntries.isNotEmpty()) {
        return pdfOutlineChapterInfo(
            outlineEntries = realOutlineEntries,
            currentPageIndex = currentPageIndex,
            pageCount = pageCount,
            documentTitleFallback = documentTitleFallback,
        )
    }
    val safeIndex = currentPageIndex.coerceIn(0, safeCount - 1)
    return pageChapterInfo(
        pageIndex = safeIndex,
        pageCount = safeCount,
        documentTitleFallback = "第 ${safeIndex + 1} 页",
    )
}

/** Closed / unopened document chrome: DOCUMENT, no fake page count. */
internal fun pdfClosedOrEmptyDocumentChapterInfo(
    documentTitleFallback: String = PDF_DOCUMENT_TITLE_FALLBACK,
): ChapterInfo {
    val title = documentTitleFallback.trim().ifEmpty { PDF_DOCUMENT_TITLE_FALLBACK }
    return ChapterInfo(
        currentIndex = 0,
        totalChapters = 0,
        currentTitle = title,
        progressInChapter = 0f,
        kind = ChapterInfo.Kind.DOCUMENT,
    )
}

/**
 * Adjacent navigation target for PDF:
 * - [pageCount] <= 0 → null (even if outline is nonempty)
 * - real outline → next/prev entry in **original** outline order (boundaries → null)
 * - no outline → previous/next actual page (boundaries → null)
 */
internal fun pdfAdjacentNavigationLocator(
    realOutlineEntries: List<TocEntry>,
    currentPageIndex: Int,
    pageCount: Int,
    delta: Int,
    documentTitleFallback: String = PDF_DOCUMENT_TITLE_FALLBACK,
): Locator? {
    val safeCount = pageCount.coerceAtLeast(0)
    if (safeCount <= 0) return null
    if (realOutlineEntries.isNotEmpty()) {
        val current = pdfOutlineChapterInfo(
            outlineEntries = realOutlineEntries,
            currentPageIndex = currentPageIndex,
            pageCount = pageCount,
            documentTitleFallback = documentTitleFallback,
        )
        // Zero-count dominance already handled above; CHAPTER path only when pages exist.
        if (current.kind != ChapterInfo.Kind.CHAPTER) return null
        return adjacentTocEntry(
            tocEntries = realOutlineEntries,
            currentIndex = current.currentIndex,
            delta = delta,
        )?.locator
    }
    val safeIndex = currentPageIndex.coerceIn(0, safeCount - 1)
    val target = safeIndex + delta
    if (target !in 0 until safeCount) return null
    return Locator(
        strategy = LocatorStrategy.Page(target, safeCount),
        progression = target.toFloat() / safeCount,
        totalProgression = target.toFloat() / safeCount,
    )
}

internal const val PDF_DOCUMENT_TITLE_FALLBACK = "正文"
