package dev.readflow.features.reader

import android.net.Uri
import android.view.View
import androidx.lifecycle.SavedStateHandle
import dev.readflow.core.database.BookDao
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.BookWithProgress
import dev.readflow.core.database.BookmarkDao
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.DownloadedCacheBook
import dev.readflow.core.database.ReadingProgressDao
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.ReadingSessionDao
import dev.readflow.core.database.ReadingSessionEntity
import dev.readflow.core.database.TextAnnotationDao
import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.EngineDescriptor
import dev.readflow.render.api.EngineStateStore
import dev.readflow.render.api.PageTransitionHost
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderEngineRegistry
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SearchHighlightableReaderEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral contracts for B1 bookmark/search ownership:
 * rapid toggles, search generation, capability short-circuit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderBookmarkSearchConcurrencyTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rapid bookmark toggles at one locator create at most one active before DAO emits`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val locator = Locator(LocatorStrategy.Page(index = 3, total = 10), totalProgression = 0.3f)
        val bookmarkDao = DelayedEmitBookmarkDao()
        val engine = SearchableFakeEngine(initialLocator = locator, supportsSearch = true)
        val viewModel = readerViewModel(engine = engine, bookmarkDao = bookmarkDao)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        // Room has not re-emitted yet; five rapid toggles must not mint five actives.
        repeat(5) {
            viewModel.onIntent(ReaderIntent.ToggleBookmark)
        }
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            "serialized toggles must leave at most one active upsert before observe catches up",
            1,
            bookmarkDao.activeUpserts().size,
        )
        assertTrue(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertEquals(1, viewModel.uiState.value.bookmarks.items.size)

        // Release DAO emission — still one active, and remove still works.
        bookmarkDao.publish()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.bookmarks.items.size)
        assertTrue(viewModel.uiState.value.bookmarks.isCurrentBookmarked)

        viewModel.onIntent(ReaderIntent.ToggleBookmark)
        advanceUntilIdle()
        bookmarkDao.publish()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertEquals(0, viewModel.uiState.value.bookmarks.items.size)
    }

    @Test
    fun `locator change before DAO publish cannot roll back optimistic bookmark add`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val addLocator = Locator(LocatorStrategy.Page(index = 3, total = 10), totalProgression = 0.3f)
        val movedLocator = Locator(LocatorStrategy.Page(index = 7, total = 10), totalProgression = 0.7f)
        val bookmarkDao = DelayedEmitBookmarkDao()
        val engine = SearchableFakeEngine(initialLocator = addLocator, supportsSearch = true)
        val viewModel = readerViewModel(engine = engine, bookmarkDao = bookmarkDao)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        viewModel.onIntent(ReaderIntent.ToggleBookmark)
        runCurrent()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertEquals(1, viewModel.uiState.value.bookmarks.items.size)
        val optimisticId = viewModel.uiState.value.bookmarks.items.single().id

        // Locator tick must not replay empty Room over the pending insert.
        engine.currentLocator.value = movedLocator
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.bookmarks.items.size)
        assertEquals(optimisticId, viewModel.uiState.value.bookmarks.items.single().id)
        assertFalse(
            "current bookmark status follows moved locator, not the pending insert site",
            viewModel.uiState.value.bookmarks.isCurrentBookmarked,
        )
        assertNull(viewModel.uiState.value.bookmarks.currentBookmarkId)

        bookmarkDao.publish()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.bookmarks.items.size)
        assertEquals(optimisticId, viewModel.uiState.value.bookmarks.items.single().id)
        assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
    }

    @Test
    fun `locator change before DAO publish cannot resurrect optimistically removed bookmark`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val locator = Locator(LocatorStrategy.Page(index = 3, total = 10), totalProgression = 0.3f)
        val movedLocator = Locator(LocatorStrategy.Page(index = 8, total = 10), totalProgression = 0.8f)
        val bookmarkDao = DelayedEmitBookmarkDao()
        val engine = SearchableFakeEngine(initialLocator = locator, supportsSearch = true)
        val viewModel = readerViewModel(engine = engine, bookmarkDao = bookmarkDao)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        viewModel.onIntent(ReaderIntent.ToggleBookmark)
        advanceUntilIdle()
        bookmarkDao.publish()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertEquals(1, viewModel.uiState.value.bookmarks.items.size)
        val existingId = viewModel.uiState.value.bookmarks.items.single().id

        viewModel.onIntent(ReaderIntent.ToggleBookmark)
        runCurrent()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertEquals(0, viewModel.uiState.value.bookmarks.items.size)

        // Stale Room list still holds the bookmark until publish; locator must not resurrect it.
        engine.currentLocator.value = movedLocator
        runCurrent()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.bookmarks.items.size)
        assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertNull(viewModel.uiState.value.bookmarks.currentBookmarkId)

        bookmarkDao.publish()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.bookmarks.items.size)
        assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
        assertTrue(bookmarkDao.activeUpserts().none { it.id == existingId })
    }

    @Test
    fun `insert then remove keeps UI removed when Room emits active row before delete settles`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val addLocator = Locator(LocatorStrategy.Page(index = 3, total = 10), totalProgression = 0.3f)
            val movedLocator = Locator(LocatorStrategy.Page(index = 6, total = 10), totalProgression = 0.6f)
            val bookmarkDao = DelayedEmitBookmarkDao(gateMarkDeleted = true)
            val engine = SearchableFakeEngine(initialLocator = addLocator, supportsSearch = true)
            val viewModel = readerViewModel(engine = engine, bookmarkDao = bookmarkDao)

            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()

            // Optimistic insert; Room has not published yet.
            viewModel.onIntent(ReaderIntent.ToggleBookmark)
            runCurrent()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
            assertEquals(1, viewModel.uiState.value.bookmarks.items.size)
            val optimisticId = viewModel.uiState.value.bookmarks.items.single().id

            // Toggle remove while markDeleted is suspended (delete not in store yet).
            viewModel.onIntent(ReaderIntent.ToggleBookmark)
            runCurrent()
            assertEquals(1, bookmarkDao.pendingMarkDeletedCount)
            assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
            assertEquals(0, viewModel.uiState.value.bookmarks.items.size)

            // Intermediate Room emission: upsert active row arrives before delete invalidation.
            // Overlay must stay as Remove so the row does not resurrect.
            bookmarkDao.publish()
            runCurrent()
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.bookmarks.items.size)
            assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
            assertNull(viewModel.uiState.value.bookmarks.currentBookmarkId)

            // Locator tick during the same intermediate window must also stay removed.
            engine.currentLocator.value = movedLocator
            runCurrent()
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.bookmarks.items.size)
            assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)

            // Complete delete, then publish absence — UI stays empty (ack removes pending).
            bookmarkDao.releaseMarkDeleted()
            runCurrent()
            advanceUntilIdle()
            bookmarkDao.publish()
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.bookmarks.items.size)
            assertFalse(viewModel.uiState.value.bookmarks.isCurrentBookmarked)
            assertTrue(bookmarkDao.activeUpserts().none { it.id == optimisticId })
        }

    @Test
    fun `older non-cooperative search never overwrites newer same-query request`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val staleLocator = Locator(LocatorStrategy.Page(index = 1, total = 10), totalProgression = 0.1f)
        val freshLocator = Locator(LocatorStrategy.Page(index = 9, total = 10), totalProgression = 0.9f)
        val engine = NonCooperativeSearchEngine(
            resultsByCall = listOf(listOf(staleLocator), listOf(freshLocator)),
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("同一关键词"))
        advanceUntilIdle()

        viewModel.onIntent(ReaderIntent.SubmitSearch)
        runCurrent()
        assertTrue(viewModel.uiState.value.search.isSearching)

        // Resubmit same query while first engine.search is still in NonCancellable work.
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        runCurrent()
        assertEquals(2, engine.searchCallCount)
        assertTrue(viewModel.uiState.value.search.isSearching)

        engine.releaseNext() // complete older request first
        advanceUntilIdle()
        assertEquals(
            "stale same-query success must not publish after a newer generation owns state",
            emptyList<ReaderSearchResult>(),
            viewModel.uiState.value.search.results,
        )
        assertTrue(viewModel.uiState.value.search.isSearching)

        engine.releaseNext() // complete latest
        advanceUntilIdle()
        assertEquals(listOf(freshLocator), viewModel.uiState.value.search.results.map { it.locator })
        assertFalse(viewModel.uiState.value.search.isSearching)
        assertNull(viewModel.uiState.value.search.message)
    }

    @Test
    fun `older search failure cannot overwrite newer request`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val freshLocator = Locator(LocatorStrategy.Page(index = 4, total = 10), totalProgression = 0.4f)
        val engine = NonCooperativeSearchEngine(
            resultsByCall = listOf(
                Result.failure(IllegalStateException("stale boom")),
                Result.success(listOf(freshLocator)),
            ),
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("关键词"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        runCurrent()
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        runCurrent()

        engine.releaseNext()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.search.message)
        assertTrue(viewModel.uiState.value.search.isSearching)

        engine.releaseNext()
        advanceUntilIdle()
        assertEquals(listOf(freshLocator), viewModel.uiState.value.search.results.map { it.locator })
        assertFalse(viewModel.uiState.value.search.message.orEmpty().contains("stale boom"))
    }

    @Test
    fun `clearing or changing query blocks stale results from reappearing`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val staleLocator = Locator(LocatorStrategy.Page(index = 2, total = 10), totalProgression = 0.2f)
        val engine = NonCooperativeSearchEngine(
            resultsByCall = listOf(
                listOf(staleLocator),
                listOf(staleLocator),
            ),
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("旧词"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        runCurrent()

        viewModel.onIntent(ReaderIntent.ClearSearch)
        advanceUntilIdle()
        assertEquals(ReaderSearchState(), viewModel.uiState.value.search)

        engine.releaseNext()
        advanceUntilIdle()
        assertEquals(ReaderSearchState(), viewModel.uiState.value.search)

        // Changed query path: start search for A, switch to B before release.
        viewModel.onIntent(ReaderIntent.SetSearchQuery("词A"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        runCurrent()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("词B"))
        advanceUntilIdle()
        engine.releaseNext()
        advanceUntilIdle()
        assertEquals("词B", viewModel.uiState.value.search.query)
        assertEquals(emptyList<ReaderSearchResult>(), viewModel.uiState.value.search.results)
        assertFalse(viewModel.uiState.value.search.isSearching)
    }

    @Test
    fun `unsupported search is hidden by features and resolves without engine work`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 1), totalProgression = 0f),
            supportsSearch = false,
        )
        assertFalse(readerFeaturesFor(engine).contains(ReaderFeature.SEARCH))

        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())
        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("任意"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(0, engine.searchCallCount)
        assertEquals("当前格式暂不支持搜索", viewModel.uiState.value.search.message)
        assertFalse(viewModel.uiState.value.search.isSearching)
        assertEquals(emptyList<ReaderSearchResult>(), viewModel.uiState.value.search.results)
    }

    @Test
    fun `search success path preserves non-empty ReaderSearchHit snippet unchanged`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val hitLocator = Locator(LocatorStrategy.Page(index = 5, total = 12), totalProgression = 0.42f)
        val expectedSnippet = "…left context needle right context…"
        val expectedMatchLength = 6
        val engineHit = ReaderSearchHit(
            locator = hitLocator,
            snippet = expectedSnippet,
            matchLength = expectedMatchLength,
        )
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 12), totalProgression = 0f),
            supportsSearch = true,
            searchHits = listOf(engineHit),
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("needle"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(1, engine.searchCallCount)
        assertFalse(viewModel.uiState.value.search.isSearching)
        assertNull(viewModel.uiState.value.search.message)
        val results = viewModel.uiState.value.search.results
        assertEquals(1, results.size)
        val result = results.single()
        assertEquals(0, result.index)
        assertEquals(hitLocator, result.locator)
        assertEquals(expectedSnippet, result.snippet)
        assertEquals(expectedMatchLength, result.matchLength)
        // Full hit mapping: locator + non-empty snippet + matchLength must survive success path exactly.
        assertEquals(
            listOf(
                ReaderSearchResult(
                    index = 0,
                    locator = hitLocator,
                    snippet = expectedSnippet,
                    matchLength = expectedMatchLength,
                ),
            ),
            results,
        )
        assertTrue(result.snippet.isNotEmpty())
        assertTrue(result.snippet.contains("needle"))
        // Search completion does not auto-select a hit.
        assertNull(viewModel.uiState.value.search.selectedIndex)
    }

    @Test
    fun `next search result from null selection navigates first hit and keeps SEARCH chrome`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val hits = listOf(
                searchHit(0, 0.1f, "a"),
                searchHit(1, 0.2f, "b"),
                searchHit(2, 0.3f, "c"),
            )
            val engine = SearchableFakeEngine(
                initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
                supportsSearch = true,
                searchHits = hits,
            )
            val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()
            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
            viewModel.onIntent(ReaderIntent.SetSearchQuery("n"))
            viewModel.onIntent(ReaderIntent.SubmitSearch)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.search.selectedIndex)

            viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
            advanceUntilIdle()

            assertEquals(0, viewModel.uiState.value.search.selectedIndex)
            assertEquals(hits[0].locator, engine.currentLocator.value)
            assertEquals(ReaderPanel.SEARCH, viewModel.uiState.value.activePanel)
            assertTrue(viewModel.uiState.value.isUiVisible)
        }

    @Test
    fun `previous and next search results are no-wrap and keep SEARCH chrome`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val hits = listOf(
            searchHit(0, 0.1f, "a"),
            searchHit(1, 0.2f, "b"),
            searchHit(2, 0.3f, "c"),
        )
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            supportsSearch = true,
            searchHits = hits,
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
        viewModel.onIntent(ReaderIntent.SetSearchQuery("n"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()

        // At null: previous is no-op (engine stays at open locator).
        val openLocator = engine.currentLocator.value
        viewModel.onIntent(ReaderIntent.GoToPreviousSearchResult)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.search.selectedIndex)
        assertEquals(openLocator, engine.currentLocator.value)

        viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.search.selectedIndex)

        // At first: previous no-op.
        val firstLocator = engine.currentLocator.value
        viewModel.onIntent(ReaderIntent.GoToPreviousSearchResult)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.search.selectedIndex)
        assertEquals(firstLocator, engine.currentLocator.value)

        viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.search.selectedIndex)
        assertEquals(hits[2].locator, engine.currentLocator.value)

        // At last: next no-op (no wrap).
        viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.search.selectedIndex)
        assertEquals(hits[2].locator, engine.currentLocator.value)

        viewModel.onIntent(ReaderIntent.GoToPreviousSearchResult)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.search.selectedIndex)
        assertEquals(hits[1].locator, engine.currentLocator.value)
        assertEquals(ReaderPanel.SEARCH, viewModel.uiState.value.activePanel)
        assertTrue(viewModel.uiState.value.isUiVisible)
    }

    @Test
    fun `direct search result click keeps SEARCH panel and chrome visible`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val hits = listOf(searchHit(3, 0.33f, "direct"))
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            supportsSearch = true,
            searchHits = hits,
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
        viewModel.onIntent(ReaderIntent.SetSearchQuery("direct"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()

        val result = viewModel.uiState.value.search.results.single()
        viewModel.onIntent(ReaderIntent.GoToSearchResult(result))
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.search.selectedIndex)
        assertEquals(hits[0].locator, engine.currentLocator.value)
        assertEquals(ReaderPanel.SEARCH, viewModel.uiState.value.activePanel)
        assertTrue(viewModel.uiState.value.isUiVisible)
    }

    @Test
    fun `search result navigation applies exact hit highlight and keeps SEARCH chrome`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val hits = listOf(
            searchHit(0, 0.1f, "alpha", matchLength = 5),
            searchHit(1, 0.2f, "beta!!", matchLength = 4),
        )
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            supportsSearch = true,
            searchHits = hits,
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
        viewModel.onIntent(ReaderIntent.SetSearchQuery("needle"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()
        engine.searchHighlightCalls.clear()

        viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
        advanceUntilIdle()
        assertEquals(hits[0], engine.searchHighlightCalls.single())
        assertEquals(0, viewModel.uiState.value.search.selectedIndex)
        assertEquals(ReaderPanel.SEARCH, viewModel.uiState.value.activePanel)
        assertTrue(viewModel.uiState.value.isUiVisible)
        assertEquals(hits[0].matchLength, engine.searchHighlightCalls.single()?.matchLength)
        assertEquals(hits[0].locator, engine.searchHighlightCalls.single()?.locator)

        engine.searchHighlightCalls.clear()
        viewModel.onIntent(ReaderIntent.GoToNextSearchResult)
        advanceUntilIdle()
        assertEquals(listOf(hits[1]), engine.searchHighlightCalls)
        assertEquals(1, viewModel.uiState.value.search.selectedIndex)
        assertEquals(4, engine.currentSearchHighlight?.matchLength)

        engine.searchHighlightCalls.clear()
        val firstResult = viewModel.uiState.value.search.results[0]
        viewModel.onIntent(ReaderIntent.GoToSearchResult(firstResult))
        advanceUntilIdle()
        assertEquals(hits[0], engine.searchHighlightCalls.single())
        assertEquals(ReaderPanel.SEARCH, viewModel.uiState.value.activePanel)
    }

    @Test
    fun `query submit clear and non-search nav clear highlight and ClosePanel preserves`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val hits = listOf(searchHit(2, 0.25f, "keep", matchLength = 4))
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            supportsSearch = true,
            searchHits = hits,
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
        viewModel.onIntent(ReaderIntent.SetSearchQuery("keep"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.GoToSearchResult(viewModel.uiState.value.search.results.single()))
        advanceUntilIdle()
        assertEquals(hits[0], engine.currentSearchHighlight)
        assertEquals(0, viewModel.uiState.value.search.selectedIndex)

        // ClosePanel alone must preserve the active search session + paint.
        engine.searchHighlightCalls.clear()
        viewModel.onIntent(ReaderIntent.ClosePanel)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.activePanel)
        assertEquals(hits[0], engine.currentSearchHighlight)
        assertEquals(0, viewModel.uiState.value.search.selectedIndex)
        assertTrue(engine.searchHighlightCalls.isEmpty())

        // SetSearchQuery clears paint + selectedIndex (session query change).
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
        viewModel.onIntent(ReaderIntent.SetSearchQuery("other"))
        advanceUntilIdle()
        assertNull(engine.currentSearchHighlight)
        assertNull(viewModel.uiState.value.search.selectedIndex)
        assertTrue(engine.searchHighlightCalls.last() == null)

        // Re-select then ClearSearch.
        viewModel.onIntent(ReaderIntent.SetSearchQuery("keep"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.GoToSearchResult(viewModel.uiState.value.search.results.single()))
        advanceUntilIdle()
        assertEquals(hits[0], engine.currentSearchHighlight)

        viewModel.onIntent(ReaderIntent.ClearSearch)
        advanceUntilIdle()
        assertNull(engine.currentSearchHighlight)
        assertNull(viewModel.uiState.value.search.selectedIndex)
        assertTrue(viewModel.uiState.value.search.results.isEmpty())

        // Fresh selection then SubmitSearch restart clears before new results.
        viewModel.onIntent(ReaderIntent.SetSearchQuery("keep"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.GoToSearchResult(viewModel.uiState.value.search.results.single()))
        advanceUntilIdle()
        engine.searchHighlightCalls.clear()
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()
        assertTrue(engine.searchHighlightCalls.contains(null))
        assertNull(engine.currentSearchHighlight)
        assertNull(viewModel.uiState.value.search.selectedIndex)

        // Non-search explicit navigation clears paint + selectedIndex.
        viewModel.onIntent(ReaderIntent.GoToSearchResult(viewModel.uiState.value.search.results.single()))
        advanceUntilIdle()
        assertEquals(hits[0], engine.currentSearchHighlight)
        engine.searchHighlightCalls.clear()
        val elsewhere = Locator(LocatorStrategy.Page(index = 9, total = 10), totalProgression = 0.9f)
        viewModel.onIntent(ReaderIntent.GoTo(elsewhere))
        advanceUntilIdle()
        assertNull(engine.currentSearchHighlight)
        assertNull(viewModel.uiState.value.search.selectedIndex)
        assertTrue(engine.searchHighlightCalls.contains(null))
    }

    @Test
    fun `stale result navigation does not leave mismatched highlight after clear`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val hits = listOf(searchHit(1, 0.15f, "once", matchLength = 4))
        val engine = SearchableFakeEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            supportsSearch = true,
            searchHits = hits,
        )
        val viewModel = readerViewModel(engine = engine, bookmarkDao = DelayedEmitBookmarkDao())

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetSearchQuery("once"))
        viewModel.onIntent(ReaderIntent.SubmitSearch)
        advanceUntilIdle()
        val staleResult = viewModel.uiState.value.search.results.single()
        viewModel.onIntent(ReaderIntent.GoToSearchResult(staleResult))
        advanceUntilIdle()
        assertEquals(hits[0], engine.currentSearchHighlight)

        viewModel.onIntent(ReaderIntent.ClearSearch)
        advanceUntilIdle()
        assertNull(engine.currentSearchHighlight)

        // After clear, no re-apply from residual selectedIndex (already null).
        assertNull(viewModel.uiState.value.search.selectedIndex)
        engine.searchHighlightCalls.clear()
        // Query change while idle must still emit clear (idempotent null).
        viewModel.onIntent(ReaderIntent.SetSearchQuery("x"))
        advanceUntilIdle()
        assertTrue(engine.searchHighlightCalls.last() == null)
        assertNull(engine.currentSearchHighlight)
    }

    @Test
    fun `newer search result navigation supersedes in-flight goTo and keeps selected highlight`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val hits = listOf(
                searchHit(1, 0.1f, "first", matchLength = 5),
                searchHit(5, 0.5f, "second", matchLength = 6),
            )
            val engine = NonCooperativeGoToEngine(searchHits = hits)
            val progressDao = RecordingProgressDao()
            val viewModel = readerViewModel(
                engine = engine,
                bookmarkDao = DelayedEmitBookmarkDao(),
                progressDao = progressDao,
            )

            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()
            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
            viewModel.onIntent(ReaderIntent.SetSearchQuery("hit"))
            viewModel.onIntent(ReaderIntent.SubmitSearch)
            advanceUntilIdle()
            val results = viewModel.uiState.value.search.results
            assertEquals(2, results.size)

            // Start navigation to first hit; gate keeps goTo in flight.
            viewModel.onIntent(ReaderIntent.GoToSearchResult(results[0]))
            runCurrent()
            assertEquals(0, viewModel.uiState.value.search.selectedIndex)
            assertEquals(hits[0], engine.currentSearchHighlight)
            assertEquals(1, engine.pendingGoToCount)

            // Newer next/previous request must supersede: selectedIndex + highlight flip immediately.
            viewModel.onIntent(ReaderIntent.GoToSearchResult(results[1]))
            runCurrent()
            assertEquals(1, viewModel.uiState.value.search.selectedIndex)
            assertEquals(hits[1], engine.currentSearchHighlight)
            assertEquals(2, engine.pendingGoToCount)

            // Release older goTo first (out of order). Must not clobber selectedIndex/highlight
            // or persist the superseded locator.
            engine.releaseNextGoTo()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.search.selectedIndex)
            assertEquals(hits[1], engine.currentSearchHighlight)
            assertTrue(
                "stale goTo completion must not persist progress for the superseded hit",
                progressDao.upserts.none { it.second == 0.1f },
            )

            // Completing the latest navigation may persist the winning locator.
            engine.releaseNextGoTo()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.search.selectedIndex)
            assertEquals(hits[1], engine.currentSearchHighlight)
            assertEquals(hits[1].locator, engine.currentLocator.value)
            assertTrue(
                progressDao.upserts.any { it.first == "book-1" && it.second == 0.5f },
            )
        }

    @Test
    fun `close book invalidates in-flight search navigation so stale goTo cannot persist`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val hits = listOf(searchHit(4, 0.4f, "gate", matchLength = 4))
            val engine = NonCooperativeGoToEngine(searchHits = hits)
            val progressDao = RecordingProgressDao()
            val viewModel = readerViewModel(
                engine = engine,
                bookmarkDao = DelayedEmitBookmarkDao(),
                progressDao = progressDao,
            )

            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()
            viewModel.onIntent(ReaderIntent.SetSearchQuery("gate"))
            viewModel.onIntent(ReaderIntent.SubmitSearch)
            advanceUntilIdle()
            val result = viewModel.uiState.value.search.results.single()
            viewModel.onIntent(ReaderIntent.GoToSearchResult(result))
            runCurrent()
            assertEquals(1, engine.pendingGoToCount)
            assertEquals(hits[0], engine.currentSearchHighlight)

            // Close while goTo is still gated: generation bump must drop completion side-effects.
            viewModel.onIntent(ReaderIntent.CloseBook)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.engine)

            val upsertsBeforeStaleRelease = progressDao.upserts.toList()
            engine.releaseNextGoTo()
            advanceUntilIdle()
            assertEquals(
                "stale search navigation after close must not upsert progress for the gated hit",
                upsertsBeforeStaleRelease,
                progressDao.upserts,
            )
            assertTrue(
                "gated hit progression must never be written after close",
                progressDao.upserts.none { it.second == 0.4f },
            )

            // Re-open same engine instance (registry reuses provider); prior navigation must stay dead.
            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.search.selectedIndex)
            assertTrue(viewModel.uiState.value.search.results.isEmpty())
        }

    private fun searchHit(
        page: Int,
        progression: Float,
        snippet: String,
        matchLength: Int = snippet.length.coerceAtLeast(1),
    ): ReaderSearchHit =
        ReaderSearchHit(
            locator = Locator(LocatorStrategy.Page(index = page, total = 10), totalProgression = progression),
            snippet = snippet,
            matchLength = matchLength,
        )

    private fun readerViewModel(
        engine: ReaderEngine,
        bookmarkDao: BookmarkDao,
        progressDao: ReadingProgressDao = RecordingProgressDao(),
    ): ReaderViewModel =
        ReaderViewModel(
            savedStateHandle = SavedStateHandle(),
            engineRegistry = ReaderEngineRegistry(
                descriptors = setOf(
                    EngineDescriptor(
                        id = engine.id,
                        format = BookFormat.TXT,
                        priority = 0,
                        quickSupports = { true },
                        provider = { engine },
                    ),
                ),
                userOverrides = MutableStateFlow(emptyMap()),
            ),
            hostFactory = object : PageTransitionHostFactory {
                override fun paged(transition: dev.readflow.core.model.TransitionType): PageTransitionHost =
                    object : PageTransitionHost {
                        override fun hostView(): View = error("unused")
                        override fun bind(engine: ReaderEngine) = Unit
                        override fun setTransition(type: dev.readflow.core.model.TransitionType) = Unit
                        override fun setOffscreenPageLimit(limit: Int) = Unit
                        override suspend fun next() = Unit
                        override suspend fun previous() = Unit
                        override fun setOnPageSettled(callback: (pageIndex: Int) -> Unit) = Unit
                        override fun unbind() = Unit
                    }
                override fun continuous(): PageTransitionHost = paged(dev.readflow.core.model.TransitionType.SLIDE)
            },
            bookDao = object : BookDao {
                private val book = BookEntity(
                    id = "book-1",
                    title = "Book",
                    author = "Author",
                    format = BookFormat.TXT.name,
                    downloadStatus = "DOWNLOADED",
                    localUri = "file:///tmp/book.txt",
                )
                override fun observeAll(): Flow<List<BookEntity>> = MutableStateFlow(listOf(book))
                override fun observeShelf(): Flow<List<BookWithProgress>> = MutableStateFlow(emptyList())
                override suspend fun count(): Int = 1
                override suspend fun maxSortOrder(): Int? = 0
                override suspend fun shelfRowsForOrderNormalization(): List<BookEntity> = listOf(book)
                override suspend fun upsert(book: BookEntity) = Unit
                override suspend fun upsertAll(books: List<BookEntity>) = Unit
                override suspend fun getById(id: String): BookEntity? = book.takeIf { it.id == id }
                override suspend fun setLastReadAt(id: String, ts: Long) = Unit
                override suspend fun downloadedRemoteCacheBooks(
                    remotePrefix: String,
                    downloadedStatus: String,
                ): List<DownloadedCacheBook> = emptyList()
                override suspend fun clearDownloadedAsset(id: String, downloadStatus: String) = Unit
                override suspend fun deleteById(id: String) = Unit
                override suspend fun updateTitle(id: String, title: String) = Unit
                override suspend fun updateCollection(
                    id: String,
                    collectionId: String?,
                    name: String?,
                ): Int = 1
                override suspend fun moveToGroup(sourceId: String, targetCollectionId: String): Int = 1
                override suspend fun createGroup(
                    sourceId: String,
                    targetId: String,
                    collectionId: String,
                    name: String,
                ): Int = 2
                override suspend fun renameCollection(collectionId: String, newName: String): Int = 0
                override suspend fun clearCollection(collectionId: String): Int = 0
                override suspend fun updateSortOrder(id: String, order: Int) = Unit
            },
            progressDao = progressDao,
            bookmarkDao = bookmarkDao,
            textAnnotationDao = object : TextAnnotationDao {
                override fun observeForBook(bookId: String): Flow<List<TextAnnotationEntity>> =
                    MutableStateFlow(emptyList())
                override suspend fun getById(id: String): TextAnnotationEntity? = null
                override suspend fun allForBackup(): List<TextAnnotationEntity> = emptyList()
                override suspend fun upsert(annotation: TextAnnotationEntity) = Unit
            },
            syncManager = SyncManager(NoOpSyncBackend()),
            settings = object : SettingsRepository {
                override val calibreBaseUrl = MutableStateFlow<String?>(null)
                override val fontSize = MutableStateFlow(16)
                override val lineSpacing = MutableStateFlow(1.75f)
                override val readingMode = MutableStateFlow(ReaderReadingMode.SCROLL)
                override val themeMode = MutableStateFlow(ThemeMode.LIGHT)
                override val deviceId = MutableStateFlow("device-id")
                override val engineOverrides = MutableStateFlow(emptyMap<BookFormat, String>())
                override val useSourceHanFont = MutableStateFlow(true)
                override val txtEncoding = MutableStateFlow(TxtEncoding.AUTO)
                override val fontChoice = MutableStateFlow<FontChoice>(FontChoice.System)
                override val epubFontReplacements = MutableStateFlow<Map<String, String>>(emptyMap())
                override val readerGuideShown = MutableStateFlow(true)
                override val pageFlipStyle = MutableStateFlow(PageFlipStyle.SLIDE)
                override val readerMenuConfig = MutableStateFlow(ReaderMenuConfig.v1Defaults())
                override suspend fun setCalibreBaseUrl(url: String) = Unit
                override suspend fun setFontSize(size: Int) = Unit
                override suspend fun setLineSpacing(multiplier: Float) = Unit
                override suspend fun setReadingMode(mode: ReaderReadingMode) = Unit
                override suspend fun setThemeMode(mode: ThemeMode) = Unit
                override suspend fun setEngineOverride(format: BookFormat, engineId: String?) = Unit
                override suspend fun setUseSourceHanFont(enabled: Boolean) = Unit
                override suspend fun setTxtEncoding(encoding: TxtEncoding) = Unit
                override suspend fun setFontChoice(choice: FontChoice) = Unit
                override suspend fun setEpubFontReplacements(replacements: Map<String, String>) = Unit
                override suspend fun setReaderGuideShown(shown: Boolean) = Unit
                override suspend fun setPageFlipStyle(style: PageFlipStyle) = Unit
                override suspend fun setReaderMenuConfig(config: ReaderMenuConfig) = Unit
            },
            engineStateStore = object : EngineStateStore {
                override suspend fun load(bookId: String): ByteArray? = null
                override suspend fun save(bookId: String, state: ByteArray) = Unit
                override suspend fun evict(bookId: String) = Unit
            },
            readingSessionDao = object : ReadingSessionDao {
                override suspend fun insert(session: ReadingSessionEntity) = Unit
                override suspend fun totalDurationSince(sinceMillis: Long): Long = 0
                override suspend fun totalDurationForBook(bookId: String): Long = 0
                override suspend fun allForBackup(): List<ReadingSessionEntity> = emptyList()
            },
        )

    /**
     * Simulates Room lag: mutations land in memory immediately, but [observeForBook]
     * does not re-emit until [publish].
     *
     * When [gateMarkDeleted] is true, [markDeleted] suspends until [releaseMarkDeleted],
     * allowing intermediate active-row publishes between upsert and delete.
     */
    private class DelayedEmitBookmarkDao(
        private val gateMarkDeleted: Boolean = false,
    ) : BookmarkDao {
        private val store = linkedMapOf<String, BookmarkEntity>()
        private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())
        private val markDeletedGates = ArrayDeque<CompletableDeferred<Unit>>()

        val pendingMarkDeletedCount: Int
            get() = markDeletedGates.size

        override fun observeForBook(bookId: String): Flow<List<BookmarkEntity>> = flow

        override suspend fun getById(id: String): BookmarkEntity? = store[id]

        override suspend fun allForBackup(): List<BookmarkEntity> = store.values.toList()

        override suspend fun upsert(bookmark: BookmarkEntity) {
            store[bookmark.id] = bookmark
        }

        override suspend fun markDeleted(id: String, updatedAt: Long, deviceId: String) {
            if (gateMarkDeleted) {
                val gate = CompletableDeferred<Unit>()
                markDeletedGates.addLast(gate)
                gate.await()
            }
            store[id]?.let { existing ->
                store[id] = existing.copy(isDeleted = true, updatedAt = updatedAt, deviceId = deviceId)
            }
        }

        fun activeUpserts(): List<BookmarkEntity> = store.values.filterNot { it.isDeleted }

        fun publish() {
            flow.value = store.values.filterNot { it.isDeleted }.sortedBy { it.totalProgression }
        }

        fun releaseMarkDeleted() {
            val gate = markDeletedGates.removeFirstOrNull() ?: return
            gate.complete(Unit)
        }
    }

    private class RecordingProgressDao : ReadingProgressDao {
        /** (bookId, totalProgression) for each [upsert]. */
        val upserts = mutableListOf<Pair<String, Float>>()

        override suspend fun get(bookId: String): ReadingProgressEntity? = null
        override suspend fun allForBackup(): List<ReadingProgressEntity> = emptyList()
        override suspend fun upsert(progress: ReadingProgressEntity) {
            upserts += progress.bookId to progress.totalProgression
        }
    }

    private open class SearchableFakeEngine(
        initialLocator: Locator,
        override val supportsSearch: Boolean,
        private val searchHits: List<ReaderSearchHit> = emptyList(),
    ) : ReaderEngine, SearchHighlightableReaderEngine {
        override val id: String = "fake-txt"
        override val format: BookFormat = BookFormat.TXT
        override val priority: Int = 0
        override val pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
        override val supportedModes = setOf(ReadingMode.SCROLL, ReadingMode.PAGED)
        override val currentLocator = MutableStateFlow(initialLocator)
        override val pageCount = MutableStateFlow(10)
        override val chapterInfo = MutableStateFlow(ChapterInfo(0, 1, "Chapter", 0f))
        override val tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
        var searchCallCount = 0
            protected set
        val searchHighlightCalls = mutableListOf<ReaderSearchHit?>()
        var currentSearchHighlight: ReaderSearchHit? = null
            private set

        override suspend fun supports(uri: Uri): Boolean = true
        override suspend fun openBook(uri: Uri): Locator = currentLocator.value
        override fun createView(): View = error("unused in unit tests")
        override suspend fun close() = Unit
        override suspend fun goTo(locator: Locator) {
            currentLocator.value = locator
        }
        override suspend fun setFontSize(sp: Float) = Unit
        override suspend fun setMode(mode: ReadingMode) = Unit
        override suspend fun search(query: String): List<ReaderSearchHit> {
            searchCallCount += 1
            return searchHits
        }

        override fun setSearchHighlight(hit: ReaderSearchHit?) {
            searchHighlightCalls += hit
            currentSearchHighlight = hit
        }
    }

    /**
     * goTo finishes even after the calling Job is cancelled (NonCancellable gate), modeling a
     * slow engine so ViewModel generation ownership can be exercised out of order.
     */
    private class NonCooperativeGoToEngine(
        searchHits: List<ReaderSearchHit>,
    ) : SearchableFakeEngine(
        initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
        supportsSearch = true,
        searchHits = searchHits,
    ) {
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val pendingGoToCount: Int
            get() = gates.size

        fun releaseNextGoTo() {
            val gate = gates.removeFirstOrNull() ?: return
            gate.complete(Unit)
        }

        override suspend fun goTo(locator: Locator) {
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            // Wait even if the caller Job is cancelled so out-of-order completion can be
            // scheduled; only apply the locator when the calling coroutine is still active.
            // Models a slow engine where cancel supersedes application of the stale target.
            withContext(NonCancellable) {
                gate.await()
            }
            if (!coroutineContext.isActive) return
            currentLocator.value = locator
        }
    }

    /**
     * Engine that finishes search work even after the calling Job is cancelled,
     * modeling a non-cooperative implementation that would clobber state without
     * generation ownership.
     */
    private class NonCooperativeSearchEngine(
        resultsByCall: List<Any>,
    ) : SearchableFakeEngine(
        initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
        supportsSearch = true,
    ) {
        private val pending = ArrayDeque(resultsByCall)
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()

        fun releaseNext() {
            val gate = gates.removeFirstOrNull() ?: return
            gate.complete(Unit)
        }

        override suspend fun search(query: String): List<ReaderSearchHit> {
            searchCallCount += 1
            val outcome = pending.removeFirstOrNull()
                ?: error("unexpected extra search call")
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            // Finish even if the caller Job is cancelled (non-cooperative engine).
            // CompletableDeferred avoids perpetual delay loops that hang advanceUntilIdle.
            withContext(NonCancellable) {
                gate.await()
            }
            @Suppress("UNCHECKED_CAST")
            return when (outcome) {
                is Result<*> -> asSearchHits((outcome as Result<*>).getOrThrow())
                is List<*> -> asSearchHits(outcome)
                else -> error("bad outcome")
            }
        }

        private fun asSearchHits(value: Any?): List<ReaderSearchHit> {
            val items = value as? List<*> ?: error("expected list of hits/locators")
            return items.map { item ->
                when (item) {
                    is ReaderSearchHit -> item
                    is Locator -> ReaderSearchHit(locator = item, snippet = "", matchLength = 0)
                    else -> error("bad search item: $item")
                }
            }
        }
    }
}
