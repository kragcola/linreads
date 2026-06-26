package dev.readflow.render.md

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
