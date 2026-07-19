package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.READER_SEARCH_HIGHLIGHT_COLOR
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange

internal fun epubHighlightRanges(
    indexedParas: List<EpubPara>,
    paragraphIndex: Int,
    annotations: List<ReaderTextAnnotation>,
): List<ReaderTextHighlightRange> {
    val paragraph = indexedParas.getOrNull(paragraphIndex) ?: return emptyList()
    return annotations.mapNotNull { annotation ->
        val start = annotation.start.strategy as? LocatorStrategy.Section ?: return@mapNotNull null
        val end = annotation.end.strategy as? LocatorStrategy.Section
        if (start.spineIndex != paragraph.spineIndex) return@mapNotNull null
        val annotationStart = start.charOffset
        val annotationEnd = (end?.takeIf { it.spineIndex == paragraph.spineIndex }?.charOffset
            ?: start.charOffset).coerceAtLeast(annotationStart)
        val overlapStart = maxOf(annotationStart, paragraph.spineCharStart)
        val overlapEnd = minOf(annotationEnd, paragraph.spineCharEnd)
        if (overlapStart >= overlapEnd) return@mapNotNull null
        ReaderTextHighlightRange(
            start = overlapStart - paragraph.spineCharStart,
            end = overlapEnd - paragraph.spineCharStart,
            color = annotation.color,
        )
    }
}

/**
 * Map a search hit's Section spine char start + [ReaderSearchHit.matchLength] to a paragraph-local
 * character range. Reuse with existing paragraph→flow/page mapping at paint time.
 */
internal fun epubSearchHighlightRange(
    indexedParas: List<EpubPara>,
    paragraphIndex: Int,
    hit: ReaderSearchHit,
): ReaderTextHighlightRange? {
    if (hit.matchLength <= 0) return null
    val start = hit.locator.strategy as? LocatorStrategy.Section ?: return null
    val paragraph = indexedParas.getOrNull(paragraphIndex) ?: return null
    if (start.spineIndex != paragraph.spineIndex) return null
    val matchStart = start.charOffset
    val matchEnd = matchStart + hit.matchLength
    val overlapStart = maxOf(matchStart, paragraph.spineCharStart)
    val overlapEnd = minOf(matchEnd, paragraph.spineCharEnd)
    if (overlapStart >= overlapEnd) return null
    return ReaderTextHighlightRange(
        start = overlapStart - paragraph.spineCharStart,
        end = overlapEnd - paragraph.spineCharStart,
        color = READER_SEARCH_HIGHLIGHT_COLOR,
    )
}
