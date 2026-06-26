package dev.readflow.render.md

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.ReaderTextSelection

internal class MarkdownDocument private constructor(
    val markdown: String,
    val lineCount: Int,
    val tableOfContents: List<TocEntry>,
    private val lineStarts: IntArray,
) {
    private val totalLength = markdown.length.coerceAtLeast(1)

    fun locatorForOffset(offset: Int): Locator {
        val safeOffset = offset.coerceIn(0, markdown.length)
        val lineIndex = lineIndexForOffset(safeOffset)
        val progression = safeOffset.toFloat() / totalLength
        return Locator(
            strategy = LocatorStrategy.Section(0, lineIndex, safeOffset),
            progression = progression,
            totalProgression = progression,
        )
    }

    fun locatorForRenderedOffset(renderedOffset: Int, renderedText: CharSequence): Locator =
        locatorForOffset(renderedToSourceStart(renderedOffset, renderedText))

    fun offsetFor(locator: Locator): Int =
        when (val strategy = locator.strategy) {
            is LocatorStrategy.Section -> {
                if (strategy.charOffset > 0) {
                    strategy.charOffset
                } else {
                    lineStart(strategy.elementIndex)
                }
            }
            is LocatorStrategy.ByteOffset -> strategy.offset.toInt()
            else -> locator.totalProgression?.let { (it.coerceIn(0f, 1f) * markdown.length).toInt() } ?: 0
        }.coerceIn(0, markdown.length)

    fun renderedOffsetFor(locator: Locator, renderedText: CharSequence): Int =
        sourceToRenderedOffset(offsetFor(locator), renderedText)

    fun lineStart(lineIndex: Int): Int =
        lineStarts[lineIndex.coerceIn(0, lineStarts.lastIndex)]

    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Locator> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0 || markdown.isEmpty()) return emptyList()
        val results = mutableListOf<Locator>()
        var fromIndex = 0
        while (results.size < limit) {
            val matchIndex = markdown.indexOf(needle, startIndex = fromIndex, ignoreCase = true)
            if (matchIndex < 0) break
            results += locatorForOffset(matchIndex)
            fromIndex = matchIndex + needle.length
        }
        return results
    }

    fun selectionForOffsets(startOffset: Int, endOffset: Int): ReaderTextSelection? {
        val start = startOffset.coerceIn(0, markdown.length)
        val end = endOffset.coerceIn(0, markdown.length)
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (first == last) return null
        val selectedText = markdown.substring(first, last)
        if (selectedText.isBlank()) return null
        return ReaderTextSelection(
            start = locatorForOffset(first),
            end = locatorForOffset(last),
            selectedText = selectedText,
        )
    }

    fun selectionForRenderedOffsets(
        startOffset: Int,
        endOffset: Int,
        renderedText: CharSequence,
    ): ReaderTextSelection? {
        val rendered = renderedText.toString()
        val start = startOffset.coerceIn(0, rendered.length)
        val end = endOffset.coerceIn(0, rendered.length)
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (first == last) return null
        val selectedText = rendered.substring(first, last)
        if (selectedText.isBlank()) return null
        return ReaderTextSelection(
            start = locatorForOffset(renderedToSourceStart(first, rendered)),
            end = locatorForOffset(renderedToSourceEnd(last, rendered)),
            selectedText = selectedText,
        )
    }

    fun renderedRangeForSourceOffsets(
        startOffset: Int,
        endOffset: Int,
        renderedText: CharSequence,
    ): IntRange? {
        val rendered = renderedText.toString()
        val start = sourceToRenderedOffset(startOffset.coerceIn(0, markdown.length), rendered)
        val end = sourceToRenderedOffset(endOffset.coerceIn(0, markdown.length), rendered)
        if (start >= end) return null
        return start until end
    }

    private fun lineIndexForOffset(offset: Int): Int {
        var low = 0
        var high = lineStarts.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineStarts[mid] <= offset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun renderedToSourceStart(renderedOffset: Int, renderedText: CharSequence): Int {
        val positions = renderedSourcePositions(renderedText.toString())
        if (positions.isEmpty()) return renderedOffset.coerceIn(0, markdown.length)
        val safeOffset = renderedOffset.coerceIn(0, positions.size)
        return if (safeOffset == positions.size) {
            (positions.last() + 1).coerceIn(0, markdown.length)
        } else {
            positions[safeOffset].coerceIn(0, markdown.length)
        }
    }

    private fun renderedToSourceEnd(renderedOffset: Int, renderedText: CharSequence): Int {
        val positions = renderedSourcePositions(renderedText.toString())
        if (positions.isEmpty()) return renderedOffset.coerceIn(0, markdown.length)
        val safeOffset = renderedOffset.coerceIn(0, positions.size)
        return if (safeOffset <= 0) {
            positions.first().coerceIn(0, markdown.length)
        } else {
            (positions[safeOffset - 1] + 1).coerceIn(0, markdown.length)
        }
    }

    private fun sourceToRenderedOffset(sourceOffset: Int, renderedText: CharSequence): Int {
        val safeOffset = sourceOffset.coerceIn(0, markdown.length)
        val positions = renderedSourcePositions(renderedText.toString())
        var renderedOffset = 0
        while (renderedOffset < positions.size && positions[renderedOffset] < safeOffset) {
            renderedOffset++
        }
        return renderedOffset
    }

    private fun renderedSourcePositions(renderedText: String): IntArray {
        if (renderedText.isEmpty() || markdown.isEmpty()) return IntArray(0)
        val positions = IntArray(renderedText.length)
        var sourceIndex = 0
        renderedText.forEachIndexed { renderedIndex, char ->
            val matchedIndex = markdown.indexOf(char, startIndex = sourceIndex)
            val sourcePosition = if (matchedIndex >= 0) matchedIndex else sourceIndex.coerceAtMost(markdown.length)
            positions[renderedIndex] = sourcePosition
            sourceIndex = (sourcePosition + 1).coerceAtMost(markdown.length)
        }
        return positions
    }

    companion object {
        private const val DEFAULT_SEARCH_LIMIT: Int = 100

        fun parse(markdown: String): MarkdownDocument {
            val lineStarts = buildLineStarts(markdown)
            val lineCount = lineStarts.size.coerceAtLeast(1)
            val tableOfContents = buildToc(markdown, lineStarts)
            return MarkdownDocument(
                markdown = markdown,
                lineCount = lineCount,
                tableOfContents = tableOfContents,
                lineStarts = lineStarts,
            )
        }

        private fun buildLineStarts(markdown: String): IntArray {
            val starts = mutableListOf(0)
            markdown.forEachIndexed { index, char ->
                if (char == '\n' && index + 1 <= markdown.length) {
                    starts += index + 1
                }
            }
            return starts.distinct().toIntArray()
        }

        private fun buildToc(markdown: String, lineStarts: IntArray): List<TocEntry> {
            if (markdown.isBlank()) return emptyList()
            val totalLength = markdown.length.coerceAtLeast(1)
            val entries = mutableListOf<TocEntry>()
            lineStarts.forEachIndexed { lineIndex, lineStart ->
                val nextLineStart = lineStarts.getOrNull(lineIndex + 1)
                val lineEndExclusive = if (nextLineStart == null) {
                    markdown.length
                } else {
                    (nextLineStart - 1).coerceAtLeast(lineStart)
                }
                val line = markdown.substring(lineStart, lineEndExclusive).trimEnd('\r')
                val trimmed = line.trimStart()
                val hashes = trimmed.takeWhile { it == '#' }.length
                if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                    val title = trimmed.drop(hashes).trim()
                    if (title.isNotEmpty()) {
                        entries += TocEntry(
                            title = title.take(80),
                            locator = Locator(
                                strategy = LocatorStrategy.Section(0, lineIndex, lineStart),
                                totalProgression = lineStart.toFloat() / totalLength,
                            ),
                            level = hashes - 1,
                        )
                    }
                }
            }
            return entries.ifEmpty {
                listOf(TocEntry("正文", Locator(LocatorStrategy.Section(0, 0, 0), totalProgression = 0f)))
            }
        }
    }
}
