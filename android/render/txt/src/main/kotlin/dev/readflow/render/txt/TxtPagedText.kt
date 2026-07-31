package dev.readflow.render.txt

import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.LineHeightSpan
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.withTextHighlightSpans

internal data class TxtPagedTextRange(
    val pageStart: Int,
    val pageEnd: Int,
    val segment: TxtPageSegment,
)

internal data class TxtPagedSelection(
    val startParagraphIndex: Int,
    val startCharacterOffset: Int,
    val endParagraphIndex: Int,
    val endCharacterOffset: Int,
    val selectedText: String,
)

internal data class TxtPagedText(
    val text: CharSequence,
    val ranges: List<TxtPagedTextRange>,
) {
    val segments: List<TxtPageSegment>
        get() = ranges.map(TxtPagedTextRange::segment)

    val anchorParagraphIndex: Int?
        get() = ranges.firstOrNull()?.segment?.paragraphIndex

    fun mapSelection(selectionStart: Int, selectionEnd: Int): TxtPagedSelection? {
        val firstOffset = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val lastOffset = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        if (firstOffset == lastOffset) return null
        val selectedRanges = ranges.filter { range ->
            lastOffset > range.pageStart && firstOffset < range.pageEnd
        }
        val firstRange = selectedRanges.firstOrNull() ?: return null
        val lastRange = selectedRanges.last()
        val visibleStart = firstOffset.coerceIn(firstRange.pageStart, firstRange.pageEnd)
        val visibleEnd = lastOffset.coerceIn(lastRange.pageStart, lastRange.pageEnd)
        if (visibleStart == visibleEnd && firstRange === lastRange) return null
        val selectedText = text.subSequence(visibleStart, visibleEnd).toString().trim('\n')
        if (selectedText.isBlank()) return null
        return TxtPagedSelection(
            startParagraphIndex = firstRange.segment.paragraphIndex,
            startCharacterOffset = firstRange.segment.startOffset + (visibleStart - firstRange.pageStart),
            endParagraphIndex = lastRange.segment.paragraphIndex,
            endCharacterOffset = lastRange.segment.startOffset + (visibleEnd - lastRange.pageStart),
            selectedText = selectedText,
        )
    }
}

internal fun buildTxtPagedText(
    segments: List<TxtPageSegment>,
    paragraphProvider: (Int) -> String,
    highlightRangesProvider: (Int) -> List<ReaderTextHighlightRange>,
    searchHighlightRangesProvider: (Int) -> List<ReaderTextHighlightRange>,
    paragraphGapPx: Int,
): TxtPagedText {
    val text = SpannableStringBuilder()
    val ranges = mutableListOf<TxtPagedTextRange>()
    segments.forEachIndexed { index, segment ->
        val paragraph = paragraphProvider(segment.paragraphIndex)
        val start = segment.startOffset.coerceIn(0, paragraph.length)
        val end = segment.endOffset.coerceIn(start, paragraph.length)
        val pageStart = text.length
        text.append(
            paragraph.substring(start, end).withTextHighlightSpans(
                ranges = mapRangesToPagedSegment(highlightRangesProvider(segment.paragraphIndex), segment),
                searchRanges = mapRangesToPagedSegment(
                    searchHighlightRangesProvider(segment.paragraphIndex),
                    segment,
                ),
            ),
        )
        ranges += TxtPagedTextRange(
            pageStart = pageStart,
            pageEnd = text.length,
            segment = segment,
        )
        if (index < segments.lastIndex) {
            val gapStart = text.length
            text.append('\n')
            text.setSpan(
                FixedLineHeightSpan(paragraphGapPx.coerceAtLeast(1)),
                gapStart,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return TxtPagedText(text = text, ranges = ranges)
}

private fun mapRangesToPagedSegment(
    ranges: List<ReaderTextHighlightRange>,
    segment: TxtPageSegment,
): List<ReaderTextHighlightRange> = ranges.mapNotNull { range ->
    val start = maxOf(range.start, segment.startOffset)
    val end = minOf(range.end, segment.endOffset)
    if (start >= end) return@mapNotNull null
    range.copy(start = start - segment.startOffset, end = end - segment.startOffset)
}

private class FixedLineHeightSpan(
    private val heightPx: Int,
) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val currentHeight = fm.descent - fm.ascent
        if (currentHeight <= 0 || currentHeight == heightPx) return
        val center = (fm.ascent + fm.descent) / 2
        fm.ascent = center - heightPx / 2
        fm.descent = fm.ascent + heightPx
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}
