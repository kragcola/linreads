package dev.readflow.render.txt

import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.READER_SEARCH_HIGHLIGHT_COLOR
import dev.readflow.render.api.ReaderSearchHit
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

/**
 * Map a search hit's [ByteOffset] start + [ReaderSearchHit.matchLength] characters onto paragraph-local
 * character offsets. Does **not** use [LocatorStrategy.ByteOffset.length] (bytes) as the end.
 */
internal fun TxtDocument.searchHighlightRangeForParagraph(
    paragraphIndex: Int,
    hit: ReaderSearchHit,
): ReaderTextHighlightRange? {
    val start = hit.locator.strategy as? LocatorStrategy.ByteOffset ?: return null
    if (hit.matchLength <= 0) return null
    val paragraphRange = rangeAt(paragraphIndex) ?: return null
    val paragraph = readParagraph(paragraphIndex)
    val charset = charsetDetection.charset
    val paragraphByteLength = paragraph.toByteArray(charset).size.toLong()
    if (start.offset < paragraphRange.startByte ||
        start.offset >= paragraphRange.startByte + paragraphByteLength
    ) {
        return null
    }
    val localStart = paragraph.characterIndexForByteOffset(
        start.offset - paragraphRange.startByte,
        charset,
    )
    val localEnd = (localStart + hit.matchLength).coerceAtMost(paragraph.length)
    if (localStart >= localEnd) return null
    return ReaderTextHighlightRange(
        start = localStart,
        end = localEnd,
        color = READER_SEARCH_HIGHLIGHT_COLOR,
    )
}

internal fun String.characterIndexForByteOffset(offset: Long, charset: Charset): Int {
    val target = offset.coerceAtLeast(0L).coerceAtMost(toByteArray(charset).size.toLong()).toInt()
    for (index in 0..length) {
        if (substring(0, index).toByteArray(charset).size >= target) return index
    }
    return length
}
