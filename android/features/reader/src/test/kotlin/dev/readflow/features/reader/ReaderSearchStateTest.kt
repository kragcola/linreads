package dev.readflow.features.reader

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderSearchStateTest {

    @Test
    fun `blank query clears stale results and selection`() {
        val oldState = ReaderSearchState(
            query = "旧词",
            results = readerSearchResultsFor(
                listOf(Locator(LocatorStrategy.Page(index = 2, total = 10), totalProgression = 0.2f)),
            ),
            selectedIndex = 0,
            isSearching = true,
            message = "旧结果",
        )

        val newState = oldState.withQuery("   ")

        assertEquals("", newState.query)
        assertEquals(emptyList<ReaderSearchResult>(), newState.results)
        assertNull(newState.selectedIndex)
        assertFalse(newState.isSearching)
        assertNull(newState.message)
    }

    @Test
    fun `search results keep locators and expose numbered progress labels`() {
        val locators = listOf(
            Locator(LocatorStrategy.Page(index = 2, total = 10), totalProgression = 0.2f),
            Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 12, charOffset = 50), totalProgression = 0.625f),
        )

        val results = readerSearchResultsFor(locators)

        assertEquals(listOf(0, 1), results.map { it.index })
        assertEquals(locators, results.map { it.locator })
        assertEquals(listOf("结果 1 · 20%", "结果 2 · 63%"), results.map { it.readerLabel() })
    }

    @Test
    fun `clear resets query results and status`() {
        val state = ReaderSearchState(
            query = "keyword",
            results = readerSearchResultsFor(
                listOf(Locator(LocatorStrategy.Page(index = 1, total = 3), totalProgression = 0.33f)),
            ),
            selectedIndex = 0,
            isSearching = true,
            message = "搜索中",
        )

        assertEquals(ReaderSearchState(), state.cleared())
    }
}
