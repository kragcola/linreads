package dev.readflow.render.epub

private const val EPUB_FONT_EXCERPT_MAX_CHARS = 120
private val EPUB_SENTENCE_BOUNDARIES = setOf('\n', '\r', '.', '!', '?', '\u3002', '\uff01', '\uff1f')

internal data class EpubCssFontUsage(
    val family: String,
    val displayName: String,
    val fontFamilyChain: List<String> = listOf(family),
    val occurrenceCount: Int,
    val coveredChars: Int,
    val excerpt: String,
    val excerptMatchStart: Int,
    val excerptMatchEnd: Int,
    val excerptSpineIndex: Int = 0,
)

internal class EpubCssFontUsageAccumulator {
    private data class MutableUsage(
        val family: String,
        var displayName: String,
        var fontFamilyChain: List<String>,
        var occurrenceCount: Int = 0,
        var coveredChars: Int = 0,
        var excerptSpineIndex: Int = Int.MAX_VALUE,
        var excerpt: EpubFontExcerpt? = null,
    )

    private val byFamily = linkedMapOf<String, MutableUsage>()

    fun addSpine(spineIndex: Int, items: List<EpubReaderItem>) {
        items.forEach { item ->
            val text: String
            val spans: List<EpubTextStyleSpan>
            when (item) {
                is EpubReaderItem.Text -> {
                    text = item.text
                    spans = item.styleSpans
                }
                is EpubReaderItem.Heading -> {
                    text = item.text
                    spans = item.styleSpans
                }
                else -> return@forEach
            }
            if (text.isBlank()) return@forEach
            spans.asSequence()
                .filter { it.style == EpubTextStyle.FontFamily }
                .forEach { span -> addSpan(spineIndex, text, span) }
        }
    }

    fun build(): List<EpubCssFontUsage> = byFamily.values
        .mapNotNull { usage ->
            val excerpt = usage.excerpt ?: return@mapNotNull null
            EpubCssFontUsage(
                family = usage.family,
                displayName = usage.displayName,
                fontFamilyChain = usage.fontFamilyChain,
                occurrenceCount = usage.occurrenceCount,
                coveredChars = usage.coveredChars,
                excerpt = excerpt.text,
                excerptMatchStart = excerpt.matchStart,
                excerptMatchEnd = excerpt.matchEnd,
                excerptSpineIndex = usage.excerptSpineIndex,
            )
        }
        .sortedWith(
            compareByDescending<EpubCssFontUsage> { it.coveredChars }
                .thenByDescending { it.occurrenceCount }
                .thenBy { it.family },
        )

    private fun addSpan(spineIndex: Int, text: String, span: EpubTextStyleSpan) {
        val rawTokens = splitCssFontFamilyList(span.fontFamily.orEmpty())
        val fontFamilyChain = rawTokens.mapNotNull(::normalizeFontFamilyKey)
        val primaryIndex = fontFamilyChain.indexOfFirst { it !in GENERIC_FONT_FAMILIES }
        if (primaryIndex < 0) return
        val family = fontFamilyChain[primaryIndex]
        val primaryToken = rawTokens.mapNotNull { token ->
            normalizeFontFamilyKey(token)?.let { token to it }
        }.firstOrNull { (_, key) -> key == family }?.first ?: family
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        if (start >= end || text.substring(start, end).isBlank()) return
        val excerpt = epubFontExcerpt(text, start, end) ?: return
        val usage = byFamily.getOrPut(family) {
            MutableUsage(
                family = family,
                displayName = epubFontFamilyDisplayName(primaryToken, family),
                fontFamilyChain = fontFamilyChain,
            )
        }
        usage.occurrenceCount = saturatingAdd(usage.occurrenceCount, 1)
        usage.coveredChars = saturatingAdd(usage.coveredChars, end - start)
        if (spineIndex < usage.excerptSpineIndex || usage.excerpt == null) {
            usage.excerptSpineIndex = spineIndex
            usage.excerpt = excerpt
            usage.displayName = epubFontFamilyDisplayName(primaryToken, family)
            usage.fontFamilyChain = fontFamilyChain
        }
    }
}

internal fun buildEpubCssFontUsages(
    spines: List<Pair<Int, List<EpubReaderItem>>>,
): List<EpubCssFontUsage> = EpubCssFontUsageAccumulator().apply {
    spines.forEach { (spineIndex, items) -> addSpine(spineIndex, items) }
}.build()

private data class EpubFontExcerpt(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
)

private fun epubFontExcerpt(text: String, matchStart: Int, matchEnd: Int): EpubFontExcerpt? {
    val safeMatchStart = text.codePointBoundaryAtOrBefore(matchStart.coerceIn(0, text.length))
    val safeMatchEnd = text.codePointBoundaryAtOrAfter(matchEnd.coerceIn(safeMatchStart, text.length))
    var excerptStart = text.lastIndexBefore(safeMatchStart) { it in EPUB_SENTENCE_BOUNDARIES } + 1
    var excerptEnd = text.indexAtOrAfter(safeMatchEnd) { it in EPUB_SENTENCE_BOUNDARIES }
        .let { if (it >= 0) it + 1 else text.length }

    while (excerptStart < excerptEnd && text[excerptStart].isWhitespace()) excerptStart++
    while (excerptEnd > excerptStart && text[excerptEnd - 1].isWhitespace()) excerptEnd--
    if (excerptStart >= excerptEnd) return null

    if (excerptEnd - excerptStart > EPUB_FONT_EXCERPT_MAX_CHARS) {
        val desiredStart = (safeMatchStart - EPUB_FONT_EXCERPT_MAX_CHARS / 3)
            .coerceAtLeast(excerptStart)
        excerptStart = desiredStart.coerceAtMost(
            (excerptEnd - EPUB_FONT_EXCERPT_MAX_CHARS).coerceAtLeast(excerptStart),
        )
        excerptStart = text.codePointBoundaryAtOrBefore(excerptStart)
        excerptEnd = (excerptStart + EPUB_FONT_EXCERPT_MAX_CHARS).coerceAtMost(excerptEnd)
        excerptEnd = text.codePointBoundaryAtOrBefore(excerptEnd)
    }

    val localStart = (safeMatchStart - excerptStart).coerceIn(0, excerptEnd - excerptStart)
    val localEnd = (safeMatchEnd - excerptStart).coerceIn(localStart, excerptEnd - excerptStart)
    if (localStart >= localEnd) return null
    return EpubFontExcerpt(
        text = text.substring(excerptStart, excerptEnd),
        matchStart = localStart,
        matchEnd = localEnd,
    )
}

private fun String.codePointBoundaryAtOrBefore(index: Int): Int {
    val safe = index.coerceIn(0, length)
    return if (safe in 1 until length && this[safe].isLowSurrogate() && this[safe - 1].isHighSurrogate()) {
        safe - 1
    } else {
        safe
    }
}

private fun String.codePointBoundaryAtOrAfter(index: Int): Int {
    val safe = index.coerceIn(0, length)
    return if (safe in 1 until length && this[safe].isLowSurrogate() && this[safe - 1].isHighSurrogate()) {
        safe + 1
    } else {
        safe
    }
}

private inline fun String.lastIndexBefore(index: Int, predicate: (Char) -> Boolean): Int {
    for (candidate in (index - 1).coerceAtMost(lastIndex) downTo 0) {
        if (predicate(this[candidate])) return candidate
    }
    return -1
}

private inline fun String.indexAtOrAfter(index: Int, predicate: (Char) -> Boolean): Int {
    for (candidate in index.coerceAtLeast(0)..lastIndex) {
        if (predicate(this[candidate])) return candidate
    }
    return -1
}

private fun epubFontFamilyDisplayName(rawToken: String, fallback: String): String {
    val trimmed = rawToken.trim()
    val unquoted = if (trimmed.length >= 2 &&
        ((trimmed.first() == '"' && trimmed.last() == '"') ||
            (trimmed.first() == '\'' && trimmed.last() == '\''))
    ) {
        trimmed.substring(1, trimmed.lastIndex)
    } else {
        trimmed
    }
    return unquoted.trim().replace(Regex("\\s+"), " ").ifBlank { fallback }
}

private fun saturatingAdd(current: Int, delta: Int): Int =
    if (Int.MAX_VALUE - current < delta) Int.MAX_VALUE else current + delta
