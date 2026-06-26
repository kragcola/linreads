package dev.readflow.render.txt

import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange
import java.nio.charset.Charset

internal fun TxtDocument.highlightRangesForParagraph(
    paragraphIndex: Int,
    annotations: List<ReaderTextAnnotation>,
): List<ReaderTextHighlightRange> {
    val paragraphRange = rangeAt(paragraphIndex) ?: return emptyList()
    val paragraph = readParagraph(paragraphIndex)
    return annotations.mapNotNull { annotation ->
        val start = annotation.start.strategy as? LocatorStrategy.ByteOffset ?: return@mapNotNull null
        val end = annotation.end.strategy as? LocatorStrategy.ByteOffset
        val annotationStart = start.offset
        val annotationEnd = (end?.offset ?: (start.offset + start.length)).coerceAtLeast(annotationStart)
        val overlapStart = maxOf(annotationStart, paragraphRange.startByte)
        val overlapEnd = minOf(annotationEnd, paragraphRange.startByte + paragraph.toByteArray(charsetDetection.charset).size)
        if (overlapStart >= overlapEnd) return@mapNotNull null
        ReaderTextHighlightRange(
            start = paragraph.characterIndexForByteOffset(overlapStart - paragraphRange.startByte, charsetDetection.charset),
            end = paragraph.characterIndexForByteOffset(overlapEnd - paragraphRange.startByte, charsetDetection.charset),
            color = annotation.color,
        )
    }
}

private fun String.characterIndexForByteOffset(offset: Long, charset: Charset): Int {
    val target = offset.coerceAtLeast(0L).coerceAtMost(toByteArray(charset).size.toLong()).toInt()
    for (index in 0..length) {
        if (substring(0, index).toByteArray(charset).size >= target) return index
    }
    return length
}
