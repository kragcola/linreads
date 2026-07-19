package dev.readflow.features.reader

import dev.readflow.core.model.Locator
import dev.readflow.render.api.ReaderSearchHit
import kotlin.math.roundToInt

data class ReaderSearchResult(
    val index: Int,
    val locator: Locator,
    /** Matched needle length in search-text characters; required from engine hits. */
    val matchLength: Int,
    val snippet: String = "",
    /** Optional engine text-space start used to disambiguate multiple fixed-page hits. */
    val matchStart: Int? = null,
)

data class ReaderSearchState(
    val query: String = "",
    val results: List<ReaderSearchResult> = emptyList(),
    val selectedIndex: Int? = null,
    val isSearching: Boolean = false,
    val message: String? = null,
)

internal fun ReaderSearchState.withQuery(query: String): ReaderSearchState =
    query.trim().let { normalized ->
        if (normalized.isEmpty()) {
            ReaderSearchState()
        } else {
            copy(
                query = normalized,
                results = emptyList(),
                selectedIndex = null,
                isSearching = false,
                message = null,
            )
        }
    }

internal fun ReaderSearchState.cleared(): ReaderSearchState = ReaderSearchState()

internal fun readerSearchResultsFor(hits: List<ReaderSearchHit>): List<ReaderSearchResult> =
    hits.mapIndexed { index, hit ->
        ReaderSearchResult(
            index = index,
            locator = hit.locator,
            matchLength = hit.matchLength,
            snippet = hit.snippet,
            matchStart = hit.matchStart,
        )
    }

/**
 * No-wrap previous-result target index, or null when previous is unavailable.
 *
 * Semantics: empty results → null; selectedIndex null or invalid/stale → null (previous disabled);
 * selected at 0 → null; otherwise selectedIndex - 1.
 */
internal fun ReaderSearchState.previousSearchResultIndex(): Int? {
    val size = results.size
    if (size == 0) return null
    val selected = selectedIndex ?: return null
    if (selected !in 0 until size) return null
    if (selected == 0) return null
    return selected - 1
}

/**
 * No-wrap next-result target index, or null when next is unavailable.
 *
 * Semantics: empty results → null; selectedIndex null or invalid/stale → 0 when size > 0;
 * selected at last → null; otherwise selectedIndex + 1. Never wraps.
 */
internal fun ReaderSearchState.nextSearchResultIndex(): Int? {
    val size = results.size
    if (size == 0) return null
    val selected = selectedIndex
    if (selected == null || selected !in 0 until size) return 0
    if (selected >= size - 1) return null
    return selected + 1
}

internal fun ReaderSearchState.canNavigateToPreviousSearchResult(): Boolean =
    previousSearchResultIndex() != null

internal fun ReaderSearchState.canNavigateToNextSearchResult(): Boolean =
    nextSearchResultIndex() != null

internal fun ReaderSearchResult.readerLabel(): String {
    val percent = locator.totalProgression?.coerceIn(0f, 1f)?.let { (it * 100f).roundToInt() }
    return if (percent == null) {
        "结果 ${index + 1}"
    } else {
        "结果 ${index + 1} · $percent%"
    }
}
