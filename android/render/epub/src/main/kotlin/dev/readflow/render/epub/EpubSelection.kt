package dev.readflow.render.epub

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextSelection
import java.text.BreakIterator
import java.util.Locale

internal fun epubTextSelection(
    indexedParas: List<EpubPara>,
    paragraphIndex: Int,
    selectionStart: Int,
    selectionEnd: Int,
    paragraphProvider: (Int) -> EpubPara?,
): ReaderTextSelection? {
    val indexedPara = indexedParas.getOrNull(paragraphIndex) ?: return null
    val paragraph = paragraphProvider(paragraphIndex) ?: indexedPara
    val (trimmedFirst, trimmedLast) = epubNormalizedTextSelectionRange(
        text = paragraph.text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
    ) ?: return null
    val selectedText = paragraph.text.substring(trimmedFirst, trimmedLast)

    val totalChars = epubTotalChars(indexedParas).coerceAtLeast(1).toFloat()
    fun locatorFor(offset: Int): Locator {
        val documentOffset = indexedPara.documentCharStart + offset
        val totalProgression = (documentOffset.toFloat() / totalChars).coerceIn(0f, 1f)
        return Locator(
            strategy = LocatorStrategy.Section(
                spineIndex = indexedPara.spineIndex,
                elementIndex = paragraphIndex,
                charOffset = indexedPara.spineCharStart + offset,
            ),
            progression = totalProgression,
            totalProgression = totalProgression,
        )
    }

    return ReaderTextSelection(
        start = locatorFor(trimmedFirst),
        end = locatorFor(trimmedLast),
        selectedText = selectedText,
    )
}

internal fun epubNormalizedTextSelectionRange(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
): Pair<Int, Int>? {
    val (first, last) = epubSelectionRangeOnCodePointBoundaries(
        text = text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
    )
    val (trimmedFirst, trimmedLast) = text.epubTrimSelectionEdges(first, last) ?: return null
    val selectedText = text.substring(trimmedFirst, trimmedLast)
    return if (selectedText.isBlank()) {
        null
    } else {
        trimmedFirst to trimmedLast
    }
}

internal fun epubComposeInitialSelectionRange(text: String, offset: Int): Pair<Int, Int>? {
    if (text.isEmpty()) return null
    if (offset !in 0 until text.length) return null
    val hitCodePointStart = text.epubCodePointStartAt(offset)
    val hitCodePointEnd = hitCodePointStart + Character.charCount(text.codePointAt(hitCodePointStart))
    val hitRange = text.epubSelectionTextElementRange(hitCodePointStart, hitCodePointEnd)
    val index = hitRange.first.coerceIn(0, text.lastIndex)
    val codePoint = text.codePointAt(index)
    if (!codePoint.isEpubSelectionWordCodePoint()) {
        return if (codePoint.isEpubSelectionSymbolSequenceCodePoint()) {
            text.epubSelectionSymbolTextElementRange(hitRange.first, hitRange.second)
                .takeIf { (start, end) -> text.epubRangeContainsSelectableSymbol(start, end) }
        } else {
            null
        }
    }
    if (!codePoint.isEpubSelectionExpandableWordCodePoint()) return hitRange
    var start = hitRange.first
    while (start > 0) {
        val previousStart = text.offsetByCodePoints(start, -1)
        val previousRange = text.epubSelectionTextElementRange(previousStart, start)
        val previousCodePoint = text.codePointAt(previousRange.first)
        if (!previousCodePoint.isEpubSelectionExpandableWordCodePoint()) break
        start = previousRange.first
    }
    var end = hitRange.second
    while (end < text.length) {
        val nextCodePoint = text.codePointAt(end)
        if (!nextCodePoint.isEpubSelectionExpandableWordCodePoint()) break
        end = text.epubSelectionTextElementRange(
            start = end,
            end = end + Character.charCount(nextCodePoint),
        ).second
    }
    val trimmedRange = text.epubTrimSelectionWordJoiners(start, end) ?: return null
    return if (hitRange.second <= trimmedRange.first || hitRange.first >= trimmedRange.second) {
        null
    } else {
        trimmedRange
    }
}

internal fun epubSelectionRangeOnCodePointBoundaries(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
): Pair<Int, Int> {
    val rawStart = selectionStart.coerceIn(0, text.length)
    val rawEnd = selectionEnd.coerceIn(0, text.length)
    if (rawStart == rawEnd) return rawStart to rawEnd
    val first = minOf(rawStart, rawEnd)
    val last = maxOf(rawStart, rawEnd)
    return text.epubSelectionTextElementRange(
        start = text.epubSelectionCodePointStartAt(first),
        end = text.epubSelectionCodePointEndAt(last),
    )
}

internal data class EpubComposeInitialSelection(
    val range: Pair<Int, Int>,
    val anchor: Int?,
    val text: String,
)

internal fun epubComposeInitialSelectionAt(text: String, offset: Int): EpubComposeInitialSelection {
    val initialRange = epubComposeInitialSelectionRange(text, offset)
    if (initialRange != null) {
        return EpubComposeInitialSelection(initialRange, anchor = initialRange.first, text = text)
    }
    val collapsedOffset = offset.coerceIn(0, text.length)
    return EpubComposeInitialSelection(collapsedOffset to collapsedOffset, anchor = null, text = text)
}

internal fun epubComposeDragSelectionRange(
    initialSelection: EpubComposeInitialSelection?,
    focusOffset: Int,
): Pair<Int, Int>? {
    if (initialSelection?.anchor == null) return null
    val (start, end) = initialSelection.range
    val (focusStart, focusEnd) = initialSelection.text.epubComposeDragFocusRange(focusOffset)
    return when {
        focusEnd <= start -> initialSelection.text.epubTrimSelectionEdges(focusStart, end) ?: (start to end)
        focusStart >= end -> initialSelection.text.epubTrimSelectionEdges(start, focusEnd) ?: (start to end)
        else -> start to end
    }
}

private fun String.epubComposeDragFocusRange(offset: Int): Pair<Int, Int> {
    if (isEmpty()) return 0 to 0
    val focus = offset.coerceIn(0, length)
    if (focus == length) return length to length
    val codePointStart = epubCodePointStartAt(focus)
    val codePointEnd = codePointStart + Character.charCount(codePointAt(codePointStart))
    val (elementStart, elementEnd) = epubSelectionTextElementRange(codePointStart, codePointEnd)
    val codePoint = codePointAt(elementStart)
    return when {
        codePoint.isEpubSelectionSymbolSequenceCodePoint() ->
            epubSelectionSymbolTextElementRange(elementStart, elementEnd)
        codePoint.isEpubSelectionWordJoinerCodePoint() &&
            !isEpubSelectionInternalWordJoinerAt(elementStart, elementEnd) -> focus to focus
        codePoint.isEpubSelectionWordCodePoint() -> elementStart to elementEnd
        else -> focus to focus
    }
}

private fun String.isEpubSelectionInternalWordJoinerAt(start: Int, end: Int): Boolean {
    if (start <= 0 || end >= length) return false
    val previousStart = offsetByCodePoints(start, -1)
    val previousRange = epubSelectionTextElementRange(previousStart, start)
    val nextCodePoint = codePointAt(end)
    return codePointAt(previousRange.first).isEpubSelectionExpandableWordCodePoint() &&
        nextCodePoint.isEpubSelectionExpandableWordCodePoint()
}

private fun String.epubCodePointStartAt(offset: Int): Int {
    val index = offset.coerceIn(0, lastIndex)
    return if (
        index > 0 &&
        Character.isLowSurrogate(this[index]) &&
        Character.isHighSurrogate(this[index - 1])
    ) {
        index - 1
    } else {
        index
    }
}

private fun String.epubSelectionCodePointStartAt(offset: Int): Int {
    val index = offset.coerceIn(0, length)
    return if (
        index > 0 &&
        index < length &&
        Character.isLowSurrogate(this[index]) &&
        Character.isHighSurrogate(this[index - 1])
    ) {
        index - 1
    } else {
        index
    }
}

private fun String.epubSelectionCodePointEndAt(offset: Int): Int {
    val index = offset.coerceIn(0, length)
    return if (
        index > 0 &&
        index < length &&
        Character.isLowSurrogate(this[index]) &&
        Character.isHighSurrogate(this[index - 1])
    ) {
        index + 1
    } else {
        index
    }
}

private fun String.epubSelectionTextElementRange(start: Int, end: Int): Pair<Int, Int> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(this)
    return iterator.epubBoundaryAtOrBefore(start) to iterator.epubBoundaryAtOrAfter(end)
}

private fun BreakIterator.epubBoundaryAtOrBefore(index: Int): Int =
    if (isBoundary(index)) {
        index
    } else {
        preceding(index).takeUnless { it == BreakIterator.DONE } ?: index
    }

private fun BreakIterator.epubBoundaryAtOrAfter(index: Int): Int =
    if (isBoundary(index)) {
        index
    } else {
        following(index).takeUnless { it == BreakIterator.DONE } ?: index
    }

private fun String.epubTrimSelectionWordJoiners(start: Int, end: Int): Pair<Int, Int>? {
    var first = start
    var last = end
    while (first < last) {
        val codePoint = codePointAt(first)
        if (!codePoint.isEpubSelectionWordJoinerCodePoint()) break
        first = epubSelectionTextElementRange(
            start = first,
            end = first + Character.charCount(codePoint),
        ).second
    }
    while (first < last) {
        val previousStart = offsetByCodePoints(last, -1)
        val previousRange = epubSelectionTextElementRange(previousStart, last)
        val previousCodePoint = codePointAt(previousRange.first)
        if (!previousCodePoint.isEpubSelectionWordJoinerCodePoint()) break
        last = previousRange.first
    }
    return if (first < last) first to last else null
}

private fun String.epubSelectionSymbolTextElementRange(start: Int, end: Int): Pair<Int, Int> {
    var first = start
    var last = end
    while (first > 0) {
        val previousStart = offsetByCodePoints(first, -1)
        val previousCodePoint = codePointAt(previousStart)
        if (previousCodePoint.isEpubSelectionEmojiModifierCodePoint()) {
            first = previousStart
            continue
        }
        if (previousCodePoint == EPUB_ZERO_WIDTH_JOINER && previousStart > 0) {
            first = offsetByCodePoints(previousStart, -1)
            continue
        }
        break
    }
    while (last < length) {
        val codePoint = codePointAt(last)
        if (codePoint.isEpubSelectionEmojiModifierCodePoint()) {
            last += Character.charCount(codePoint)
            continue
        }
        if (codePoint == EPUB_ZERO_WIDTH_JOINER) {
            last += Character.charCount(codePoint)
            if (last < length) {
                last += Character.charCount(codePointAt(last))
            }
            continue
        }
        break
    }
    return first to last
}

private fun String.epubRangeContainsSelectableSymbol(start: Int, end: Int): Boolean {
    var index = start
    while (index < end) {
        val codePoint = codePointAt(index)
        if (codePoint.isEpubSelectionSelectableSymbolCodePoint()) return true
        index += Character.charCount(codePoint)
    }
    return false
}

private fun String.epubTrimSelectionEdges(start: Int, end: Int): Pair<Int, Int>? {
    var first = start
    var last = end
    while (first < last) {
        val codePoint = codePointAt(first)
        if (!codePoint.isEpubSelectionEdgeTrimCodePoint()) break
        first = epubSelectionTextElementRange(
            start = first,
            end = first + Character.charCount(codePoint),
        ).second
    }
    while (first < last) {
        val previousStart = offsetByCodePoints(last, -1)
        val previousRange = epubSelectionTextElementRange(previousStart, last)
        val previousCodePoint = codePointAt(previousRange.first)
        if (!previousCodePoint.isEpubSelectionEdgeTrimCodePoint()) break
        last = previousRange.first
    }
    return if (first < last) first to last else null
}

private fun Int.isEpubSelectionEdgeTrimCodePoint(): Boolean =
    Character.isWhitespace(this) ||
        Character.isSpaceChar(this) ||
        isEpubSelectionWordJoinerCodePoint()

private fun Int.isEpubSelectionWordCodePoint(): Boolean =
    Character.isLetterOrDigit(this) ||
        this == '_'.code ||
        isEpubSelectionWordJoinerCodePoint()

private fun Int.isEpubSelectionWordJoinerCodePoint(): Boolean =
    this == '\''.code ||
        this == '\u2019'.code ||
        this == '-'.code ||
        this == '\u2010'.code ||
        this == '\u2011'.code

private fun Int.isEpubSelectionExpandableWordCodePoint(): Boolean =
    isEpubSelectionWordCodePoint() && !isEpubSelectionSingleCharacterWordCodePoint()

private fun Int.isEpubSelectionSymbolSequenceCodePoint(): Boolean =
    isEpubSelectionSelectableSymbolCodePoint() ||
        isEpubSelectionEmojiModifierCodePoint() ||
        this == EPUB_ZERO_WIDTH_JOINER

private fun Int.isEpubSelectionSelectableSymbolCodePoint(): Boolean =
    Character.getType(this) == Character.OTHER_SYMBOL.toInt()

private fun Int.isEpubSelectionEmojiModifierCodePoint(): Boolean =
    this in 0xFE00..0xFE0F ||
        this in 0x1F3FB..0x1F3FF

private fun Int.isEpubSelectionSingleCharacterWordCodePoint(): Boolean =
    when (Character.UnicodeScript.of(this)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }

private const val EPUB_ZERO_WIDTH_JOINER = 0x200D
