package dev.readflow.render.api

import dev.readflow.core.model.Locator

/**
 * One in-book search match: navigation locator, plain single-line context snippet, and the
 * matched span length in the engine's search-text character space (not bytes).
 *
 * [matchLength] is required and has no default so every producer must state the character length
 * explicitly. TXT engines still use [dev.readflow.core.model.LocatorStrategy.ByteOffset] with a
 * separate byte length on the locator; do not confuse that with this field.
 */
data class ReaderSearchHit(
    val locator: Locator,
    val snippet: String,
    /** Matched needle length in search-text characters (engine character space). */
    val matchLength: Int,
    /** Optional match start in the engine's search-text character space (PDF fixed-page text). */
    val matchStart: Int? = null,
)

/** Default max visible characters for [buildSearchSnippet] (ellipsis markers extra). */
const val DEFAULT_SEARCH_SNIPPET_MAX_CHARS: Int = 80

/**
 * Pure reusable snippet builder for in-book search.
 *
 * Takes [source] text and a match at [matchStart]/[matchLength], returns a trimmed single-line
 * context that keeps the match, collapses whitespace, and adds leading/trailing ellipsis when
 * the window is clipped. Safe for CJK / no-space text and out-of-bounds match ranges.
 */
fun buildSearchSnippet(
    source: String,
    matchStart: Int,
    matchLength: Int,
    maxChars: Int = DEFAULT_SEARCH_SNIPPET_MAX_CHARS,
): String {
    if (source.isEmpty() || maxChars <= 0) return ""

    val length = source.length
    val safeStart = matchStart.coerceIn(0, length)
    val safeEnd = (matchStart + matchLength.coerceAtLeast(0)).coerceIn(safeStart, length)

    // Flatten whitespace to spaces while preserving 1:1 indices, then collapse runs.
    val collapsed = StringBuilder(length)
    val origToCollapsed = IntArray(length + 1)
    var lastWasSpace = true // drop leading whitespace
    for (i in 0 until length) {
        origToCollapsed[i] = collapsed.length
        val ch = source[i]
        if (ch.isWhitespace()) {
            if (!lastWasSpace) {
                collapsed.append(' ')
                lastWasSpace = true
            }
        } else {
            collapsed.append(ch)
            lastWasSpace = false
        }
    }
    origToCollapsed[length] = collapsed.length
    if (collapsed.isNotEmpty() && collapsed.last() == ' ') {
        collapsed.setLength(collapsed.length - 1)
        // Point past end still maps to length after trailing trim.
        if (origToCollapsed[length] > collapsed.length) {
            origToCollapsed[length] = collapsed.length
        }
    }
    if (collapsed.isEmpty()) return ""

    val total = collapsed.length
    var cStart = origToCollapsed[safeStart].coerceIn(0, total)
    var cEnd = origToCollapsed[safeEnd].coerceIn(cStart, total)
    // Empty match: treat as a point so context still expands around it.
    if (cStart == cEnd && total > 0) {
        cEnd = (cStart + 1).coerceAtMost(total)
        cStart = (cEnd - 1).coerceAtLeast(0)
    }

    if (total <= maxChars) {
        return collapsed.toString()
    }

    val matchChars = (cEnd - cStart).coerceAtLeast(1).coerceAtMost(maxChars)
    val remaining = maxChars - matchChars
    var before = remaining / 2
    var after = remaining - before
    var winStart = (cStart - before).coerceAtLeast(0)
    var winEnd = (cEnd + after).coerceAtMost(total)

    // Rebalance when a bound is hit so we still use the full budget.
    val used = winEnd - winStart
    if (used < maxChars) {
        val deficit = maxChars - used
        if (winStart == 0) {
            winEnd = (winEnd + deficit).coerceAtMost(total)
        } else if (winEnd == total) {
            winStart = (winStart - deficit).coerceAtLeast(0)
        }
    }

    // Guarantee the match is inside the window.
    if (cStart < winStart) winStart = cStart
    if (cEnd > winEnd) winEnd = cEnd
    if (winEnd - winStart > maxChars) {
        winStart = cStart
        winEnd = (winStart + maxChars).coerceAtMost(total)
        if (winEnd - winStart < maxChars) {
            winStart = (winEnd - maxChars).coerceAtLeast(0)
        }
    }

    // Avoid edge spaces inside the window for cleaner ellipsis boundaries.
    while (winStart < winEnd && collapsed[winStart] == ' ') winStart++
    while (winEnd > winStart && collapsed[winEnd - 1] == ' ') winEnd--

    val body = collapsed.substring(winStart, winEnd)
    if (body.isEmpty()) return ""

    val leading = winStart > 0
    val trailing = winEnd < total
    return buildString(body.length + 2) {
        if (leading) append('…')
        append(body)
        if (trailing) append('…')
    }
}
