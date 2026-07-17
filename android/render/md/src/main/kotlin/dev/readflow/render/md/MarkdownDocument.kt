package dev.readflow.render.md

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.ReaderTextSelection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class MarkdownDocument private constructor(
    val markdown: String,
    val lineCount: Int,
    val tableOfContents: List<TocEntry>,
    private val lineStarts: IntArray,
) {
    private val totalLength = markdown.length.coerceAtLeast(1)

    /**
     * Cached rendered→source pin positions for the last rendered CharSequence.
     * Page settle / annotation / page bind reuse this instead of rescanning the document.
     * [mappingCacheIdentity] is the fast path; [mappingCacheKey] is content-equivalence fallback
     * for highlighted Spannable instances that share text but not identity.
     */
    private var mappingCacheIdentity: CharSequence? = null
    private var mappingCacheKey: String? = null
    private var mappingCache: IntArray? = null

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

    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Locator> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0 || markdown.isEmpty()) return emptyList()
        val results = mutableListOf<Locator>()
        var fromIndex = 0
        while (results.size < limit) {
            currentCoroutineContext().ensureActive()
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
        val start = startOffset.coerceIn(0, renderedText.length)
        val end = endOffset.coerceIn(0, renderedText.length)
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (first == last) return null
        val selectedText = renderedText.subSequence(first, last).toString()
        if (selectedText.isBlank()) return null
        return ReaderTextSelection(
            start = locatorForOffset(renderedToSourceStart(first, renderedText)),
            end = locatorForOffset(renderedToSourceEnd(last, renderedText)),
            selectedText = selectedText,
        )
    }

    fun renderedRangeForSourceOffsets(
        startOffset: Int,
        endOffset: Int,
        renderedText: CharSequence,
    ): IntRange? {
        val start = sourceToRenderedOffset(startOffset.coerceIn(0, markdown.length), renderedText)
        val end = sourceToRenderedOffset(endOffset.coerceIn(0, markdown.length), renderedText)
        if (start >= end) return null
        return start until end
    }

    /** Invalidate cached mapping (call when the open document source changes). */
    fun clearMappingCache() {
        mappingCacheIdentity = null
        mappingCacheKey = null
        mappingCache = null
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
        val positions = renderedSourcePositions(renderedText)
        if (positions.isEmpty()) return renderedOffset.coerceIn(0, markdown.length)
        val safeOffset = renderedOffset.coerceIn(0, positions.size)
        return if (safeOffset == positions.size) {
            (positions.last() + 1).coerceIn(0, markdown.length)
        } else {
            positions[safeOffset].coerceIn(0, markdown.length)
        }
    }

    private fun renderedToSourceEnd(renderedOffset: Int, renderedText: CharSequence): Int {
        val positions = renderedSourcePositions(renderedText)
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
        val positions = renderedSourcePositions(renderedText)
        if (positions.isEmpty()) return 0
        // Monotonic pins: lower-bound first index with position >= safeOffset preserves
        // first-exact semantics for duplicate pins and first-ge for gaps.
        return firstRenderedIndexForSource(positions, safeOffset).let { idx ->
            if (idx < positions.size) idx else renderedText.length
        }
    }

    /**
     * Maps each rendered character index to a source markdown index.
     *
     * Strategy (monotonic, literal-safe):
     * 1. Walk source and rendered left-to-right; never move source backwards.
     * 2. Prefer exact character matches (so literal `*`, `_`, `` ` ``, code content, etc. survive).
     * 3. When rendered has Markwon-inserted whitespace with no source counterpart, pin without
     *    jumping to a distant source newline.
     * 4. Only then skip recognized source-only markdown *syntax delimiters* (not content chars).
     */
    private fun renderedSourcePositions(renderedText: CharSequence): IntArray {
        if (renderedText.isEmpty() || markdown.isEmpty()) return IntArray(0)
        mappingCache?.let { cached ->
            // Identity hit: no toString / content scan (page settle reuses the same TextView text).
            if (mappingCacheIdentity === renderedText) return cached
            // Content-equivalence fallback for highlighted Spannable vs plain body text.
            val key = renderedText.toString()
            if (mappingCacheKey == key) {
                mappingCacheIdentity = renderedText
                return cached
            }
            val positions = buildRenderedSourcePositions(key)
            mappingCacheIdentity = renderedText
            mappingCacheKey = key
            mappingCache = positions
            return positions
        }
        val key = renderedText.toString()
        val positions = buildRenderedSourcePositions(key)
        mappingCacheIdentity = renderedText
        mappingCacheKey = key
        mappingCache = positions
        return positions
    }

    private fun buildRenderedSourcePositions(renderedText: String): IntArray {
        val positions = IntArray(renderedText.length)
        var sourceIndex = 0
        for (renderedIndex in renderedText.indices) {
            val char = renderedText[renderedIndex]
            // 1) Exact match at current source cursor (includes literal * _ ~ ` [ ] etc.).
            if (sourceIndex < markdown.length && markdown[sourceIndex] == char) {
                positions[renderedIndex] = sourceIndex
                sourceIndex++
                continue
            }
            // 2) Skip only true source-only syntax delimiters, then try exact match again.
            val afterSkip = skipSourceOnlySyntax(sourceIndex)
            if (afterSkip != sourceIndex && afterSkip < markdown.length && markdown[afterSkip] == char) {
                positions[renderedIndex] = afterSkip
                sourceIndex = afterSkip + 1
                continue
            }
            // 3) Extra rendered whitespace (heading blank lines, table placeholders).
            // TablePlugin emits ReplacementSpan NBSP/whitespace per table row — each placeholder
            // must consume the corresponding source table line so multi-row / long-row tables
            // advance monotonically instead of pinning forever at the table start.
            if (char.isWhitespace()) {
                if (sourceIndex < markdown.length && isMarkdownTableLineAt(sourceIndex)) {
                    positions[renderedIndex] = sourceIndex.coerceIn(0, markdown.length)
                    sourceIndex = endOfLineExclusive(sourceIndex)
                    continue
                }
                positions[renderedIndex] = sourceIndex.coerceIn(0, markdown.length)
                continue
            }
            // 4) Look ahead for this plain char after skipping syntax, without greedy distant jumps.
            val matchedIndex = findNextLiteralMatch(sourceIndex, char)
            if (matchedIndex >= 0) {
                positions[renderedIndex] = matchedIndex
                sourceIndex = matchedIndex + 1
            } else {
                positions[renderedIndex] = sourceIndex.coerceIn(0, markdown.length)
            }
        }
        return positions
    }

    /**
     * Skip markdown *syntax delimiters* that Markwon strips from the rendered CharSequence.
     * Does **not** treat arbitrary `*`, `_`, `~`, backticks, brackets, or code content as syntax:
     * only recognized delimiter patterns are advanced past.
     */
    private fun skipSourceOnlySyntax(from: Int): Int {
        var i = from
        var guard = 0
        while (i < markdown.length && guard < markdown.length) {
            guard++
            when (val c = markdown[i]) {
                '\\' -> {
                    // Escaped marker: backslash is source-only; the next char is literal content.
                    if (i + 1 < markdown.length) {
                        return i + 1 // stop so next exact-match can consume the literal char
                    }
                    return i
                }
                '#' -> {
                    val atLineStart = i == 0 || markdown[i - 1] == '\n'
                    if (!atLineStart) return i
                    val hashes = countRun(i, '#')
                    if (hashes !in 1..6) return i
                    var j = i + hashes
                    if (j >= markdown.length || markdown[j] != ' ') return i
                    while (j < markdown.length && markdown[j] == ' ') j++
                    i = j
                }
                '`' -> {
                    val ticks = countRun(i, '`')
                    if (ticks >= 3) {
                        // Fenced code: skip opening fence line only (content is rendered).
                        val lineEnd = markdown.indexOf('\n', startIndex = i)
                        i = if (lineEnd >= 0) lineEnd + 1 else markdown.length
                    } else if (ticks == 1 || ticks == 2) {
                        // Inline code delimiter — skip opening/closing ticks; content matched later.
                        i += ticks
                    } else {
                        return i
                    }
                }
                '*', '_' -> {
                    // Emphasis/strong delimiters only when they form a marker run (not "a * b").
                    val run = countRun(i, c)
                    if (run !in 1..2) return i
                    val beforeOk = i == 0 || markdown[i - 1].isWhitespace() || markdown[i - 1] in "([{\"'"
                    val after = i + run
                    val afterOk = after < markdown.length &&
                        !markdown[after].isWhitespace() &&
                        markdown[after] !in ".,;:!?)\"'"
                    // Closing markers: after whitespace-or-punct, before whitespace/end/punct.
                    val closingBefore = i > 0 && !markdown[i - 1].isWhitespace()
                    val closingAfter = after >= markdown.length ||
                        markdown[after].isWhitespace() ||
                        markdown[after] in ".,;:!?)\"']"
                    if ((beforeOk && afterOk) || (closingBefore && closingAfter)) {
                        i += run
                    } else {
                        return i // literal asterisk / underscore (e.g. a * b, snake_case)
                    }
                }
                '~' -> {
                    val run = countRun(i, '~')
                    if (run == 2) {
                        i += 2 // strikethrough delimiter
                    } else {
                        return i
                    }
                }
                '!' -> {
                    // Image: Markwon keeps alt text in plain CharSequence (drops only ! and URL).
                    // Skip bang; '[' / ']' handlers consume brackets and destination.
                    if (i + 1 < markdown.length && markdown[i + 1] == '[') {
                        i++
                    } else {
                        return i
                    }
                }
                '[' -> {
                    // Opening link/image bracket is source-only; link/alt text is rendered.
                    i++
                }
                ']' -> {
                    i++
                    if (i < markdown.length && markdown[i] == '(') {
                        val end = findBalancedParenEnd(i)
                        if (end >= 0) {
                            i = end + 1
                        } else {
                            return i
                        }
                    }
                }
                '|' -> {
                    // TablePlugin renders tables as ReplacementSpan placeholders (nbsp), not cell
                    // text. Skip the entire table line so later body text can re-sync.
                    if (isMarkdownTableLineAt(i)) {
                        val lineEnd = markdown.indexOf('\n', startIndex = i)
                        i = if (lineEnd >= 0) lineEnd + 1 else markdown.length
                        continue
                    }
                    return i
                }
                else -> return i
            }
        }
        return i
    }

    private fun findNextLiteralMatch(from: Int, char: Char): Int {
        var i = from
        // Recognized structural syntax (long URLs, fenced openers, full table lines) may advance
        // any legitimate length. Unrecognized plain-text resync stays bounded so we never jump
        // to a distant duplicate character.
        var plainBudget = PLAIN_RESYNC_BUDGET
        while (i < markdown.length) {
            val skipped = skipSourceOnlySyntax(i)
            if (skipped > i) {
                i = skipped
                continue
            }
            if (i >= markdown.length) return -1
            if (markdown[i] == char) return i
            // Source has a plain char that is not in rendered yet — only allow a short skip
            // for invisible source-only whitespace runs.
            if (markdown[i].isWhitespace()) {
                i++
                plainBudget--
                if (plainBudget < 0) return -1
                continue
            }
            // Mismatch: allow tiny window for soft re-sync (table padding quirks).
            val windowEnd = (i + 4).coerceAtMost(markdown.length)
            val nearby = markdown.indexOf(char, startIndex = i + 1)
            if (nearby in (i + 1) until windowEnd) {
                return nearby
            }
            return -1
        }
        return -1
    }

    /** Exclusive end of the source line containing [from] (past newline if present). */
    private fun endOfLineExclusive(from: Int): Int {
        val lineEnd = markdown.indexOf('\n', startIndex = from)
        return if (lineEnd >= 0) lineEnd + 1 else markdown.length
    }

    private fun countRun(from: Int, mark: Char): Int {
        var i = from
        while (i < markdown.length && markdown[i] == mark) i++
        return i - from
    }

    /** True when [from] sits on a GFM table row or separator line. */
    private fun isMarkdownTableLineAt(from: Int): Boolean {
        var lineStart = from
        while (lineStart > 0 && markdown[lineStart - 1] != '\n') lineStart--
        val lineEnd = markdown.indexOf('\n', startIndex = lineStart).let {
            if (it < 0) markdown.length else it
        }
        val line = markdown.substring(lineStart, lineEnd).trim()
        if (line.isEmpty() || !line.contains('|')) return false
        // Separator: | --- | :---: |
        val stripped = line.trim('|').trim()
        if (stripped.isNotEmpty() && stripped.all { it == '-' || it == ':' || it == '|' || it.isWhitespace() }) {
            return true
        }
        // Data/header row: starts with | or has multiple pipes.
        return line.startsWith('|') || line.count { it == '|' } >= 2
    }

    private fun findBalancedParenEnd(openParenIndex: Int): Int {
        // openParenIndex points at '('
        var depth = 0
        var i = openParenIndex
        while (i < markdown.length) {
            when (markdown[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
                '\\' -> if (i + 1 < markdown.length) i++ // skip escaped char
            }
            i++
        }
        return -1
    }

    companion object {
        private const val DEFAULT_SEARCH_LIMIT: Int = 100
        /** Bound only unrecognized plain mismatches; structural skips are uncapped. */
        private const val PLAIN_RESYNC_BUDGET: Int = 64

        /**
         * Lower-bound index of the first rendered pin whose source position is >= [sourceOffset]
         * in a monotonic [positions] array. Returns [positions.size] when every pin is strictly
         * less than [sourceOffset] (caller maps that to rendered length).
         *
         * Because pins are non-decreasing, this is equivalent to first-exact when an exact pin
         * exists and first-ge otherwise — including stable first-of-duplicate-pin selection.
         */
        internal fun firstRenderedIndexForSource(positions: IntArray, sourceOffset: Int): Int {
            var low = 0
            var high = positions.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (positions[mid] < sourceOffset) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            return low
        }

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
            // Empty when no real ATX headings — never invent a fake "正文" TOC entry.
            return entries
        }
    }
}
