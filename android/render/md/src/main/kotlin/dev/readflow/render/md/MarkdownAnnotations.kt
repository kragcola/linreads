package dev.readflow.render.md

import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.READER_SEARCH_HIGHLIGHT_COLOR
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange

internal fun MarkdownDocument.highlightRanges(
    annotations: List<ReaderTextAnnotation>,
    renderedText: CharSequence? = null,
): List<ReaderTextHighlightRange> =
    annotations.mapNotNull { annotation ->
        val start = offsetFor(annotation.start)
        val end = offsetFor(annotation.end)
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (first == last) return@mapNotNull null
        if (renderedText != null) {
            val range = renderedRangeForSourceOffsets(first, last, renderedText) ?: return@mapNotNull null
            return@mapNotNull ReaderTextHighlightRange(range.first, range.last + 1, annotation.color)
        }
        ReaderTextHighlightRange(first, last, annotation.color)
    }

/**
 * Map a search hit's source Section/ByteOffset start + [ReaderSearchHit.matchLength] through
 * source→rendered mapping for both ends. Annotation ranges stay separate; combine only at paint.
 */
internal fun MarkdownDocument.searchHighlightRange(
    hit: ReaderSearchHit,
    renderedText: CharSequence,
): ReaderTextHighlightRange? {
    if (hit.matchLength <= 0 || renderedText.isEmpty()) return null
    val sourceStart = when (val strategy = hit.locator.strategy) {
        is LocatorStrategy.Section -> strategy.charOffset
        is LocatorStrategy.ByteOffset -> strategy.offset.toInt().coerceIn(0, markdown.length)
        is LocatorStrategy.Page,
        is LocatorStrategy.PageText,
        LocatorStrategy.Unknown,
        -> offsetFor(hit.locator)
    }.coerceIn(0, markdown.length)
    val sourceEnd = (sourceStart + hit.matchLength).coerceAtMost(markdown.length)
    if (sourceStart >= sourceEnd) return null
    val range = renderedRangeForSourceOffsets(sourceStart, sourceEnd, renderedText) ?: return null
    return ReaderTextHighlightRange(
        start = range.first,
        end = range.last + 1,
        color = READER_SEARCH_HIGHLIGHT_COLOR,
    )
}
