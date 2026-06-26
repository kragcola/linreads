package dev.readflow.features.reader

import dev.readflow.core.model.Locator
import kotlin.math.roundToInt

data class ReaderSearchResult(
    val index: Int,
    val locator: Locator,
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

internal fun readerSearchResultsFor(locators: List<Locator>): List<ReaderSearchResult> =
    locators.mapIndexed { index, locator ->
        ReaderSearchResult(index = index, locator = locator)
    }

internal fun ReaderSearchResult.readerLabel(): String {
    val percent = locator.totalProgression?.coerceIn(0f, 1f)?.let { (it * 100f).roundToInt() }
    return if (percent == null) {
        "结果 ${index + 1}"
    } else {
        "结果 ${index + 1} · $percent%"
    }
}
