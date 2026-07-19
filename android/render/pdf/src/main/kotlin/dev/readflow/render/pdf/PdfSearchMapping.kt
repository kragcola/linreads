package dev.readflow.render.pdf

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.buildSearchSnippet

/**
 * Internal search hit: navigation [ReaderSearchHit] plus page-point rectangles for transient paint.
 * Rectangles are never persisted as annotations.
 */
internal data class PdfSearchMatch(
    val hit: ReaderSearchHit,
    val pageIndex: Int,
    /** Page-point bounds (1/72"), origin top-left of the PDF page. */
    val pagePointBounds: List<PdfRect>,
    /** Page size in PDF points (captured while the page was open on the render dispatcher). */
    val pageWidthPt: Float,
    val pageHeightPt: Float,
)

/**
 * Build a single-line snippet from concatenated page text around [matchStart]/[matchLength].
 * Falls back to a trimmed needle when the page text stream is empty.
 */
internal fun pdfSearchSnippet(
    pageText: String,
    matchStart: Int,
    matchLength: Int,
    query: String,
): String {
    if (pageText.isNotEmpty() && matchLength > 0) {
        return buildSearchSnippet(pageText, matchStart, matchLength)
    }
    val needle = query.trim().ifEmpty { return "" }
    return if (needle.length <= 80) needle else needle.take(79) + "…"
}

/**
 * Map framework matches on one page into engine search results.
 *
 * [pageText] is the concatenated [PdfRenderer.Page] text stream used for snippet context and
 * [ReaderSearchHit.matchLength] stays the needle character length (search-text space).
 */
internal fun mapPdfFrameworkMatches(
    pageIndex: Int,
    pageCount: Int,
    query: String,
    pageText: String,
    matches: List<PdfFrameworkTextMatch>,
    pageWidthPt: Float,
    pageHeightPt: Float,
): List<PdfSearchMatch> {
    if (pageCount <= 0 || pageIndex !in 0 until pageCount) return emptyList()
    val matchLength = query.length.coerceAtLeast(0)
    if (matchLength == 0) return emptyList()

    return matches.map { match ->
        val snippet = pdfSearchSnippet(
            pageText = pageText,
            matchStart = match.textStartIndex,
            matchLength = matchLength,
            query = query,
        )
        PdfSearchMatch(
            hit = ReaderSearchHit(
                locator = Locator(
                    strategy = LocatorStrategy.Page(pageIndex, pageCount),
                    progression = pageIndex.toFloat() / pageCount,
                    totalProgression = pageIndex.toFloat() / pageCount,
                ),
                snippet = snippet,
                matchLength = matchLength,
                matchStart = match.textStartIndex,
            ),
            pageIndex = pageIndex,
            pagePointBounds = match.bounds,
            pageWidthPt = pageWidthPt,
            pageHeightPt = pageHeightPt,
        )
    }
}

/**
 * Scale page-point rectangles into bitmap pixel space (top-left origin, same as [PdfRenderer] render).
 * Empty / non-positive dimensions yield an empty list.
 */
internal fun normalizePdfPageRectsToBitmap(
    pagePointBounds: List<PdfRect>,
    pageWidthPt: Float,
    pageHeightPt: Float,
    bitmapWidthPx: Int,
    bitmapHeightPx: Int,
): List<PdfRect> {
    if (pagePointBounds.isEmpty()) return emptyList()
    if (pageWidthPt <= 0f || pageHeightPt <= 0f) return emptyList()
    if (bitmapWidthPx <= 0 || bitmapHeightPx <= 0) return emptyList()

    val scaleX = bitmapWidthPx.toFloat() / pageWidthPt
    val scaleY = bitmapHeightPx.toFloat() / pageHeightPt
    return pagePointBounds.mapNotNull { src ->
        val left = (src.left * scaleX).coerceIn(0f, bitmapWidthPx.toFloat())
        val top = (src.top * scaleY).coerceIn(0f, bitmapHeightPx.toFloat())
        val right = (src.right * scaleX).coerceIn(0f, bitmapWidthPx.toFloat())
        val bottom = (src.bottom * scaleY).coerceIn(0f, bitmapHeightPx.toFloat())
        if (right <= left || bottom <= top) null else PdfRect(left, top, right, bottom)
    }
}

/**
 * Map bitmap-local highlight rects into the [ImageView] drawable destination rect used by
 * FIT_CENTER / matrix zoom. Returns empty when drawable or view content size is unknown.
 */
internal fun mapBitmapRectsToView(
    bitmapRects: List<PdfRect>,
    drawableWidth: Int,
    drawableHeight: Int,
    contentLeft: Float,
    contentTop: Float,
    contentWidth: Float,
    contentHeight: Float,
    zoomScale: Float = 1f,
): List<PdfRect> {
    if (bitmapRects.isEmpty()) return emptyList()
    if (drawableWidth <= 0 || drawableHeight <= 0) return emptyList()
    if (contentWidth <= 0f || contentHeight <= 0f) return emptyList()

    val scale = minOf(
        contentWidth / drawableWidth.toFloat(),
        contentHeight / drawableHeight.toFloat(),
    )
    val drawnW = drawableWidth * scale
    val drawnH = drawableHeight * scale
    val offsetX = contentLeft + (contentWidth - drawnW) / 2f
    val offsetY = contentTop + (contentHeight - drawnH) / 2f
    val safeZoom = zoomScale.coerceAtLeast(1f)
    val centerX = contentLeft + contentWidth / 2f
    val centerY = contentTop + contentHeight / 2f

    return bitmapRects.map { src ->
        val left = offsetX + src.left * scale
        val top = offsetY + src.top * scale
        val right = offsetX + src.right * scale
        val bottom = offsetY + src.bottom * scale
        if (safeZoom <= 1f) {
            PdfRect(left, top, right, bottom)
        } else {
            PdfRect(
                centerX + (left - centerX) * safeZoom,
                centerY + (top - centerY) * safeZoom,
                centerX + (right - centerX) * safeZoom,
                centerY + (bottom - centerY) * safeZoom,
            )
        }
    }
}
