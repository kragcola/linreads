package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
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
