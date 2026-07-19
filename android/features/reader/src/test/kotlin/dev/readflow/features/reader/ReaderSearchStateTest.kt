package dev.readflow.features.reader

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderSearchHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSearchStateTest {

    @Test
    fun `blank query clears stale results and selection`() {
        val oldState = ReaderSearchState(
            query = "旧词",
            results = readerSearchResultsFor(
                listOf(
                    ReaderSearchHit(
                        locator = Locator(LocatorStrategy.Page(index = 2, total = 10), totalProgression = 0.2f),
                        snippet = "旧上下文",
                        matchLength = 2,
                    ),
                ),
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
    fun `search results keep locators snippets matchLength and expose numbered progress labels`() {
        val hits = listOf(
            ReaderSearchHit(
                locator = Locator(LocatorStrategy.Page(index = 2, total = 10), totalProgression = 0.2f),
                snippet = "前文 needle 后文",
                matchLength = 6,
                matchStart = 10,
            ),
            ReaderSearchHit(
                locator = Locator(
                    LocatorStrategy.Section(spineIndex = 0, elementIndex = 12, charOffset = 50),
                    totalProgression = 0.625f,
                ),
                snippet = "另一处 匹配 上下文",
                matchLength = 2,
            ),
        )

        val results = readerSearchResultsFor(hits)

        assertEquals(listOf(0, 1), results.map { it.index })
        assertEquals(hits.map { it.locator }, results.map { it.locator })
        assertEquals(hits.map { it.snippet }, results.map { it.snippet })
        assertEquals(hits.map { it.matchLength }, results.map { it.matchLength })
        assertEquals(hits.map { it.matchStart }, results.map { it.matchStart })
        assertEquals(listOf("结果 1 · 20%", "结果 2 · 63%"), results.map { it.readerLabel() })
    }

    @Test
    fun `clear resets query results and status`() {
        val state = ReaderSearchState(
            query = "keyword",
            results = readerSearchResultsFor(
                listOf(
                    ReaderSearchHit(
                        locator = Locator(LocatorStrategy.Page(index = 1, total = 3), totalProgression = 0.33f),
                        snippet = "context",
                        matchLength = 7,
                    ),
                ),
            ),
            selectedIndex = 0,
            isSearching = true,
            message = "搜索中",
        )

        assertEquals(ReaderSearchState(), state.cleared())
    }

    @Test
    fun `empty results disable previous and next with no target`() {
        val state = ReaderSearchState(query = "x", results = emptyList(), selectedIndex = null)

        assertFalse(state.canNavigateToPreviousSearchResult())
        assertFalse(state.canNavigateToNextSearchResult())
        assertNull(state.previousSearchResultIndex())
        assertNull(state.nextSearchResultIndex())
    }

    @Test
    fun `null selection disables previous and next targets first result without wrap`() {
        val state = threeHitState(selectedIndex = null)

        assertFalse(state.canNavigateToPreviousSearchResult())
        assertTrue(state.canNavigateToNextSearchResult())
        assertNull(state.previousSearchResultIndex())
        assertEquals(0, state.nextSearchResultIndex())
    }

    @Test
    fun `selected at first disables previous and next advances by one`() {
        val state = threeHitState(selectedIndex = 0)

        assertFalse(state.canNavigateToPreviousSearchResult())
        assertTrue(state.canNavigateToNextSearchResult())
        assertNull(state.previousSearchResultIndex())
        assertEquals(1, state.nextSearchResultIndex())
    }

    @Test
    fun `selected at last disables next and previous steps back without wrap`() {
        val state = threeHitState(selectedIndex = 2)

        assertTrue(state.canNavigateToPreviousSearchResult())
        assertFalse(state.canNavigateToNextSearchResult())
        assertEquals(1, state.previousSearchResultIndex())
        assertNull(state.nextSearchResultIndex())
    }

    @Test
    fun `selected in middle enables both directions without wrap`() {
        val state = threeHitState(selectedIndex = 1)

        assertTrue(state.canNavigateToPreviousSearchResult())
        assertTrue(state.canNavigateToNextSearchResult())
        assertEquals(0, state.previousSearchResultIndex())
        assertEquals(2, state.nextSearchResultIndex())
    }

    @Test
    fun `stale selectedIndex is safe and recovers next to first without crash`() {
        val negative = threeHitState(selectedIndex = -1)
        val beyond = threeHitState(selectedIndex = 99)

        assertFalse(negative.canNavigateToPreviousSearchResult())
        assertTrue(negative.canNavigateToNextSearchResult())
        assertNull(negative.previousSearchResultIndex())
        assertEquals(0, negative.nextSearchResultIndex())

        assertFalse(beyond.canNavigateToPreviousSearchResult())
        assertTrue(beyond.canNavigateToNextSearchResult())
        assertNull(beyond.previousSearchResultIndex())
        assertEquals(0, beyond.nextSearchResultIndex())
    }

    private fun threeHitState(selectedIndex: Int?): ReaderSearchState =
        ReaderSearchState(
            query = "n",
            results = readerSearchResultsFor(
                listOf(
                    hit(0, 0.1f),
                    hit(1, 0.2f),
                    hit(2, 0.3f),
                ),
            ),
            selectedIndex = selectedIndex,
        )

    private fun hit(page: Int, progression: Float): ReaderSearchHit =
        ReaderSearchHit(
            locator = Locator(LocatorStrategy.Page(index = page, total = 10), totalProgression = progression),
            snippet = "hit $page",
            matchLength = 1,
        )
}
