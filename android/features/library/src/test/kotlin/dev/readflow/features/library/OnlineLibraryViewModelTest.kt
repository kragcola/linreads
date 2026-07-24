package dev.readflow.features.library

import android.net.Uri
import dev.readflow.core.database.CoroutineBookAssetOperationCoordinator
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.DownloadedAsset
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.LocalBookImporter
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineBookPreview
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceRegistry
import dev.readflow.extensions.api.stableRemoteBookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineLibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialSourceSelectionPrefersGenericWebSourceOverMigratedCalibre() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource(BUILTIN_CALIBRE_SOURCE_ID, SourceAdapterIds.CALIBRE),
                enabledSource("source-web", SourceAdapterIds.HTML_RULES_V1, "网页小说站"),
            ),
            catalogs = emptyMap(),
        )

        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        assertEquals("source-web", viewModel.onlineLibraryState.value.selectedSourceId)
        assertEquals(SourceAdapterIds.HTML_RULES_V1, viewModel.onlineLibraryState.value.addSourceAdapterId)
    }

    @Test
    fun latestOnlineSearchWinsWhenEarlierSearchIsStillRunning() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val catalog = FakeOnlineCatalog(
            searchHandler = { query, _ ->
                if (query == "old") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                ReadflowResult.Success(listOf(entry(query, "Result $query")))
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource(BUILTIN_CALIBRE_SOURCE_ID, SourceAdapterIds.CALIBRE)),
            catalogs = mapOf(BUILTIN_CALIBRE_SOURCE_ID to catalog),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.updateOnlineQuery("old")
        viewModel.searchOnlineLibrary()
        runCurrent()
        firstStarted.await()

        viewModel.updateOnlineQuery("new")
        viewModel.searchOnlineLibrary()
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals("new", viewModel.onlineLibraryState.value.query)
        assertEquals(listOf("new"), viewModel.onlineLibraryState.value.results.map { it.meta.id })
    }

    @Test
    fun sourceSwitchClearsResultsAndUsesSelectedSourceOnSearch() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val calibre = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(entry("c1", "Calibre Book"))) },
        )
        val json = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(entry("j1", "JSON Book"))) },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource(BUILTIN_CALIBRE_SOURCE_ID, SourceAdapterIds.CALIBRE),
                enabledSource("source-json", SourceAdapterIds.JSON_HTTP, "JSON"),
            ),
            catalogs = mapOf(
                BUILTIN_CALIBRE_SOURCE_ID to calibre,
                "source-json" to json,
            ),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.updateOnlineQuery("q")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        assertEquals(listOf("c1"), viewModel.onlineLibraryState.value.results.map { it.meta.id })

        viewModel.updateOnlineFilter(OnlineCatalogFilter(author = "Old author"))
        viewModel.selectOnlineSource("source-json")
        assertTrue(viewModel.onlineLibraryState.value.results.isEmpty())
        assertEquals("source-json", viewModel.onlineLibraryState.value.selectedSourceId)
        assertTrue(viewModel.onlineLibraryState.value.filter.isEmpty)

        viewModel.updateOnlineQuery("q2")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        assertEquals(listOf("j1"), viewModel.onlineLibraryState.value.results.map { it.meta.id })
        assertEquals(listOf("source-json"), registry.openedSourceIds.filter { it == "source-json" }.takeLast(1))
    }

    @Test
    fun batchSelectionAndSeriesAuthorOperationsSelectMatchingEntries() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val results = listOf(
            entry("1", "Book One", author = "Ann, Bob", authors = listOf("Ann", "Bob"), series = "Saga"),
            entry("2", "Book Two", author = "Ann", series = "Other"),
            entry("3", "Book Three", author = "Bob", series = "Saga"),
        )
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(results) },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()

        viewModel.toggleOnlineSelection(results[0])
        assertEquals(setOf(results[0].selectionKey()), viewModel.onlineLibraryState.value.selectedEntryKeys)

        viewModel.toggleOnlineSelection(results[0])
        assertTrue(viewModel.onlineLibraryState.value.selectedEntryKeys.isEmpty())

        viewModel.selectOnlineByAuthor("Ann")
        assertEquals(
            setOf(results[0].selectionKey(), results[1].selectionKey()),
            viewModel.onlineLibraryState.value.selectedEntryKeys,
        )

        viewModel.selectOnlineBySeries("Saga")
        assertEquals(
            setOf(results[0].selectionKey(), results[2].selectionKey()),
            viewModel.onlineLibraryState.value.selectedEntryKeys,
        )

        viewModel.clearOnlineSelection()
        assertTrue(viewModel.onlineLibraryState.value.selectedEntryKeys.isEmpty())
    }

    @Test
    fun currentResultsCanBeSelectedAndDeselectedAsOneSet() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val results = listOf(entry("1", "One"), entry("2", "Two"), entry("3", "Three"))
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf(
                "source-json" to FakeOnlineCatalog(
                    searchHandler = { _, _ -> ReadflowResult.Success(results) },
                ),
            ),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()

        viewModel.toggleAllCurrentOnlineResults()
        assertEquals(results.mapTo(linkedSetOf(), OnlineCatalogEntry::selectionKey), viewModel.onlineLibraryState.value.selectedEntryKeys)

        viewModel.toggleAllCurrentOnlineResults()
        assertTrue(viewModel.onlineLibraryState.value.selectedEntryKeys.isEmpty())
    }

    @Test
    fun metadataFacetsDeduplicatePerBookCountAndSortDeterministically() {
        val multiAuthorEntry = entry(
            "1",
            "One",
            author = "Ann, Bob",
            authors = listOf(" Ann ", "Bob", "ann"),
            series = "Saga",
            tags = listOf("Classic", "classic"),
            formats = listOf("EPUB", "PDF"),
        )
        val facets = buildOnlineMetadataFacets(
            listOf(
                multiAuthorEntry,
                entry("2", "Two", author = "Ann", series = "Saga", tags = listOf("Classic"), formats = listOf("EPUB")),
                entry("3", "Three", author = "Bob", series = "Other", tags = listOf("New"), formats = listOf("MOBI")),
            ),
        )

        assertEquals(listOf("Ann", "Bob"), multiAuthorEntry.individualAuthors())
        assertEquals(listOf(MetadataFacet("Ann", 2), MetadataFacet("Bob", 2)), facets.authors)
        assertEquals(listOf(MetadataFacet("Saga", 2), MetadataFacet("Other", 1)), facets.series)
        assertEquals(
            listOf(MetadataFacet("EPUB", 2), MetadataFacet("MOBI", 1), MetadataFacet("PDF", 1)),
            facets.formats,
        )
        assertEquals(listOf(MetadataFacet("Classic", 2), MetadataFacet("New", 1)), facets.tags)
    }

    @Test
    fun sourceWideAuthorSelectionEnumeratesPagesWhenAdapterSupportsIt() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val requestedOffsets = mutableListOf<Int>()
        val first = entry("1", "Book One", author = "Ann")
        val second = entry("2", "Book Two", author = "Ann")
        val third = entry("3", "Book Three", author = "Ann")
        val catalog = FakeOnlineCatalog(
            searchPageHandler = { _, filter, offset, _ ->
                requestedOffsets += offset
                assertEquals("Ann", filter.author)
                ReadflowResult.Success(
                    when (offset) {
                        0 -> listOf(first, second)
                        100 -> listOf(third)
                        else -> emptyList()
                    },
                )
            },
        )
        val source = enabledSource("source-json", SourceAdapterIds.JSON_HTTP).copy(
            capabilities = SourceCapabilities(
                canSearch = true,
                canFilterByAuthor = true,
                canDownload = true,
                canBatchAcrossSource = true,
            ),
        )
        val registry = FakeSourceRegistry(
            sources = listOf(source),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")

        viewModel.selectOnlineByAuthor("Ann")
        advanceUntilIdle()

        assertEquals(listOf(0, 100, 200), requestedOffsets)
        assertEquals(listOf("1", "2", "3"), viewModel.onlineLibraryState.value.results.map { it.meta.id })
        assertEquals(
            setOf(first.selectionKey(), second.selectionKey(), third.selectionKey()),
            viewModel.onlineLibraryState.value.selectedEntryKeys,
        )
        assertFalse(viewModel.onlineLibraryState.value.isSelectingBatch)
    }

    @Test
    fun sourceWideSelectionContinuesPastAnUnmatchedPage() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val matching = entry("2", "Matching", author = "Ann")
        val requestedOffsets = mutableListOf<Int>()
        val catalog = FakeOnlineCatalog(
            searchPageHandler = { _, _, offset, _ ->
                requestedOffsets += offset
                ReadflowResult.Success(
                    when (offset) {
                        0 -> listOf(entry("1", "Unmatched", author = "Bob"))
                        100 -> listOf(matching)
                        else -> emptyList()
                    },
                )
            },
        )
        val source = enabledSource("source-json", SourceAdapterIds.JSON_HTTP).copy(
            capabilities = SourceCapabilities(
                canSearch = true,
                canFilterByAuthor = true,
                canDownload = true,
                canBatchAcrossSource = true,
            ),
        )
        val viewModel = viewModel(
            registry = FakeSourceRegistry(
                sources = listOf(source),
                catalogs = mapOf(source.id to catalog),
            ),
        )
        advanceUntilIdle()

        viewModel.selectOnlineByAuthor("Ann")
        advanceUntilIdle()

        assertEquals(listOf(0, 100, 200), requestedOffsets)
        assertEquals(setOf(matching.selectionKey()), viewModel.onlineLibraryState.value.selectedEntryKeys)
    }

    @Test
    fun sourceEditorClosesOnlyAfterRegistryAcceptsTheSource() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val added = enabledSource("source-added", SourceAdapterIds.JSON_HTTP, name = "JSON")
        val registry = FakeSourceRegistry(
            sources = emptyList(),
            catalogs = emptyMap(),
            addHandler = { _, _, _, _, _ -> ReadflowResult.Success(added) },
        )
        val viewModel = viewModel(registry = registry)
        var successCallbacks = 0
        viewModel.updateAddSourceForm(
            name = "JSON",
            url = "https://books.example/catalog.json",
            adapterId = SourceAdapterIds.JSON_HTTP,
        )

        viewModel.addOnlineSource { successCallbacks += 1 }
        advanceUntilIdle()

        assertEquals(1, successCallbacks)
        assertFalse(viewModel.onlineLibraryState.value.isAddingSource)
        assertEquals("source-added", viewModel.onlineLibraryState.value.selectedSourceId)
    }

    @Test
    fun blankSourceNameUsesTheSelectedTypeDefault() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        var persistedName = ""
        val added = enabledSource("source-added", SourceAdapterIds.CALIBRE, name = "Calibre")
        val registry = FakeSourceRegistry(
            sources = emptyList(),
            catalogs = emptyMap(),
            addHandler = { _, name, _, _, _ ->
                persistedName = name
                ReadflowResult.Success(added)
            },
        )
        val viewModel = viewModel(registry = registry)
        viewModel.updateAddSourceForm(
            name = "",
            url = "http://192.168.1.5:8080",
            adapterId = SourceAdapterIds.CALIBRE,
        )

        viewModel.addOnlineSource()
        advanceUntilIdle()

        assertEquals("Calibre", persistedName)
    }

    @Test
    fun sourceEditorStaysOpenWhenRegistryRejectsTheSource() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = FakeSourceRegistry(
            sources = emptyList(),
            catalogs = emptyMap(),
            addHandler = { _, _, _, _, _ -> ReadflowResult.Failure(ReadflowError.parse("规则无效")) },
        )
        val viewModel = viewModel(registry = registry)
        var successCallbacks = 0
        viewModel.updateAddSourceForm(
            name = "Broken",
            url = "https://books.example/catalog.json",
            adapterId = SourceAdapterIds.JSON_HTTP,
        )

        viewModel.addOnlineSource { successCallbacks += 1 }
        advanceUntilIdle()

        assertEquals(0, successCallbacks)
        assertFalse(viewModel.onlineLibraryState.value.isAddingSource)
        assertEquals("规则无效", viewModel.onlineLibraryState.value.error)
    }

    @Test
    fun calibreSourceEditorLoadsAndPersistsCredentialsForAddAndEdit() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val source = enabledSource("source-calibre", SourceAdapterIds.CALIBRE, name = "Calibre")
        var addedCredentials: SourceCredentials? = null
        var updatedCredentials: SourceCredentials? = null
        val registry = FakeSourceRegistry(
            sources = listOf(source),
            catalogs = emptyMap(),
            credentials = mutableMapOf(source.id to SourceCredentials("existing", "old-secret")),
            addHandler = { _, _, _, _, supplied ->
                addedCredentials = supplied
                ReadflowResult.Success(source.copy(id = "source-added"))
            },
            updateHandler = { _, _, _, _, supplied ->
                updatedCredentials = supplied
                ReadflowResult.Success(source)
            },
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.prepareSourceEditor()
        viewModel.updateSourceCredentials("new-user", "new-secret")
        viewModel.updateAddSourceForm(
            url = "http://192.168.1.5:8080",
            adapterId = SourceAdapterIds.CALIBRE,
        )
        viewModel.saveOnlineSource()
        advanceUntilIdle()
        assertEquals(SourceCredentials("new-user", "new-secret"), addedCredentials)

        viewModel.prepareSourceEditor(source.id)
        advanceUntilIdle()
        assertEquals("existing", viewModel.onlineLibraryState.value.sourceUsername)
        assertEquals("old-secret", viewModel.onlineLibraryState.value.sourcePassword)

        viewModel.updateSourceCredentials("updated-user", "updated-secret")
        viewModel.saveOnlineSource()
        advanceUntilIdle()
        assertEquals(SourceCredentials("updated-user", "updated-secret"), updatedCredentials)

        viewModel.clearSourceEditor()
        assertEquals("", viewModel.onlineLibraryState.value.sourceUsername)
        assertEquals("", viewModel.onlineLibraryState.value.sourcePassword)
        assertEquals(null, viewModel.onlineLibraryState.value.editingSourceId)
    }

    @Test
    fun credentialReadFailureBlocksSaveUntilUserExplicitlyResetsCredentials() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val source = enabledSource("source-calibre", SourceAdapterIds.CALIBRE, name = "Calibre")
        val registry = FakeSourceRegistry(
            sources = listOf(source),
            catalogs = emptyMap(),
            credentialReadHandler = { throw IllegalStateException("keystore unavailable") },
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.prepareSourceEditor(source.id)
        advanceUntilIdle()
        assertTrue(viewModel.onlineLibraryState.value.sourceCredentialsLoadFailed)

        viewModel.saveOnlineSource()
        advanceUntilIdle()
        assertEquals(0, registry.updateCalls)

        viewModel.resetSourceCredentials()
        advanceUntilIdle()
        assertFalse(viewModel.onlineLibraryState.value.sourceCredentialsLoadFailed)
        assertTrue(registry.credentialsCleared)
    }

    /** Registry/parser tests cover validation; these cover post-read selection and error state. */
    @Test
    fun importSourceConfigSuccessSelectsImportedSource() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val imported = enabledSource("source-imported", SourceAdapterIds.JSON_HTTP, name = "Imported LAN")
        val registry = FakeSourceRegistry(
            sources = emptyList(),
            catalogs = emptyMap(),
            importHandler = { ReadflowResult.Success(imported) },
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.importSourceConfigText("""{"schemaVersion":1,"name":"Imported LAN"}""")
        advanceUntilIdle()

        assertEquals("source-imported", viewModel.onlineLibraryState.value.selectedSourceId)
        assertTrue(viewModel.onlineLibraryState.value.message?.contains("Imported LAN") == true)
        assertNull(viewModel.onlineLibraryState.value.error)
        assertFalse(viewModel.onlineLibraryState.value.isAddingSource)
        assertEquals(1, registry.importCalls)
    }

    @Test
    fun importSourceConfigFailureSurfacesRegistryError() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = FakeSourceRegistry(
            sources = emptyList(),
            catalogs = emptyMap(),
            importHandler = {
                ReadflowResult.Failure(ReadflowError.parse("配置 JSON 格式无效"))
            },
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.importSourceConfigText("not-json")
        advanceUntilIdle()

        assertEquals("配置 JSON 格式无效", viewModel.onlineLibraryState.value.error)
        assertNull(viewModel.onlineLibraryState.value.message)
        assertFalse(viewModel.onlineLibraryState.value.isAddingSource)
        assertEquals(1, registry.importCalls)
    }

    @Test
    fun htmlSourceDraftBuildsStructuredRulesAndDerivesPrimaryHost() {
        val draft = HtmlSourceDraft(
            searchUrlTemplate = "https://books.example/search?q={query}&page={page}",
            additionalAllowedHosts = "cdn.books.example, chapters.example",
            itemSelector = ".book",
            titleSelector = ".title",
            authorSelector = ".author",
            detailLinkSelector = ".detail",
            seriesSelector = ".series",
            chapterItemSelector = ".chapter",
            chapterLinkSelector = "a",
            chapterTitleSelector = "h1",
            bodySelector = ".content",
            nextPageSelector = ".next",
        )

        val config = draft.toConfig()

        assertEquals(
            listOf("books.example", "cdn.books.example", "chapters.example"),
            config.allowedHosts,
        )
        assertEquals(".book", config.search.itemSelector)
        assertEquals(".series", config.search.seriesSelector)
        assertEquals(".content", config.chapter.bodySelector)
        assertEquals(".next", config.chapter.nextPageSelector)
    }

    @Test
    fun htmlSourceDraftProvidesUsableGeneralRuleDefaults() {
        val config = HtmlSourceDraft(
            searchUrlTemplate = "https://books.example/search?q={query}&page={page}",
        ).toConfig()

        assertEquals("UTF-8", config.charset)
        assertEquals(".bookbox, .book-item, li", config.search.itemSelector)
        assertEquals("h3 a, .bookname a, .title", config.search.titleSelector)
        assertEquals(".author, .writer", config.search.authorSelector)
        assertEquals("h3 a, .bookname a, a", config.search.detailLinkSelector)
        assertEquals(".listmain dd, .chapter-list li, dd", config.detail.chapterItemSelector)
        assertEquals("a", config.detail.chapterLinkSelector)
        assertEquals("#chaptercontent, #content, .content", config.chapter.bodySelector)
    }

    @Test
    fun seriesAuthorBatchDownloadUpsertsSelectedEntriesOnly() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val results = listOf(
            entry("1", "Saga 1", author = "Ann", series = "Saga"),
            entry("2", "Saga 2", author = "Ann", series = "Saga"),
            entry("3", "Other", author = "Bob", series = "Other"),
        )
        val downloadedIds = mutableListOf<String>()
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(results) },
            downloadHandler = { entry ->
                downloadedIds += entry.meta.id
                ReadflowResult.Success(
                    entry.meta.copy(
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        localUri = "file:///books/${entry.meta.id}.epub",
                    ),
                )
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(store = store, registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()

        viewModel.selectOnlineBySeries("Saga")
        viewModel.downloadSelectedOnlineBooks()
        advanceUntilIdle()

        assertEquals(listOf("1", "2"), downloadedIds)
        assertEquals(listOf("1", "2"), store.upsertedBooks.map { it.id })
        assertTrue(store.upsertedBooks.none { it.id == "3" })
    }

    @Test
    fun downloadFailureDoesNotUpsertShelfAndClearsDownloadingKey() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val resultEntry = entry("fail-1", "Broken")
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(resultEntry)) },
            downloadHandler = {
                ReadflowResult.Failure(ReadflowError.io("network down"))
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(store = store, registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        viewModel.downloadOnlineEntry(resultEntry)
        advanceUntilIdle()

        assertTrue(store.upsertedBooks.isEmpty())
        assertTrue(viewModel.onlineLibraryState.value.downloadingKeys.isEmpty())
        assertTrue(viewModel.onlineLibraryState.value.error?.contains("network down") == true)
    }

    @Test
    fun previewSurfacesApplicationOwnedTextAndRejectsFailure() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val safeEntry = entry("p1", "Preview Safe")
        val unsafeEntry = entry("p2", "Preview Unsafe")
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(safeEntry, unsafeEntry)) },
            previewHandler = { entry ->
                if (entry.meta.id == "p1") {
                    ReadflowResult.Success(
                        OnlineBookPreview("Preview Safe", "Author", "第一章", "正文"),
                    )
                } else {
                    ReadflowResult.Failure(ReadflowError.network(null, "预览地址不安全"))
                }
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")

        viewModel.previewOnlineEntry(safeEntry)
        advanceUntilIdle()
        assertEquals("正文", viewModel.onlineLibraryState.value.preview?.body)

        viewModel.previewOnlineEntry(unsafeEntry)
        advanceUntilIdle()
        assertEquals(null, viewModel.onlineLibraryState.value.preview)
        assertTrue(viewModel.onlineLibraryState.value.error?.contains("不安全") == true)
    }

    @Test
    fun stalePreviewCannotOverwritePreviewFromNewSource() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldEntry = entry("old", "Old")
        val newEntry = entry("new", "New")
        val oldCatalog = FakeOnlineCatalog(
            previewHandler = {
                oldStarted.complete(Unit)
                releaseOld.await()
                ReadflowResult.Success(OnlineBookPreview("Old", "A", null, "old"))
            },
        )
        val newCatalog = FakeOnlineCatalog(
            previewHandler = {
                ReadflowResult.Success(OnlineBookPreview("New", "A", null, "new"))
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource("source-old", SourceAdapterIds.JSON_HTTP),
                enabledSource("source-new", SourceAdapterIds.JSON_HTTP),
            ),
            catalogs = mapOf("source-old" to oldCatalog, "source-new" to newCatalog),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource("source-old")
        viewModel.previewOnlineEntry(oldEntry)
        runCurrent()
        oldStarted.await()
        viewModel.selectOnlineSource("source-new")
        viewModel.previewOnlineEntry(newEntry)
        runCurrent()
        releaseOld.complete(Unit)
        advanceUntilIdle()

        assertEquals("source-new", viewModel.onlineLibraryState.value.selectedSourceId)
        assertEquals(
            "new",
            viewModel.onlineLibraryState.value.preview?.body,
        )
    }

    @Test
    fun staleDownloadCompletionCannotOverwriteNewSourceState() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val oldEntry = entry("old", "Old")
        val oldCatalog = FakeOnlineCatalog(
            downloadHandler = { entry ->
                downloadStarted.complete(Unit)
                releaseDownload.await()
                ReadflowResult.Success(entry.meta.copy(downloadStatus = DownloadStatus.DOWNLOADED))
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource("source-old", SourceAdapterIds.JSON_HTTP),
                enabledSource("source-new", SourceAdapterIds.JSON_HTTP),
            ),
            catalogs = mapOf(
                "source-old" to oldCatalog,
                "source-new" to FakeOnlineCatalog(),
            ),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource("source-old")
        viewModel.downloadOnlineEntry(oldEntry)
        runCurrent()
        downloadStarted.await()
        viewModel.selectOnlineSource("source-new")
        releaseDownload.complete(Unit)
        advanceUntilIdle()

        assertEquals("source-new", viewModel.onlineLibraryState.value.selectedSourceId)
        assertNull(viewModel.onlineLibraryState.value.message)
        assertNull(viewModel.onlineLibraryState.value.error)
        assertTrue(viewModel.onlineLibraryState.value.downloadingKeys.isEmpty())
    }

    @Test
    fun downloadExceptionClearsProgressAndSurfacesFailure() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val resultEntry = entry("broken", "Broken")
        val catalog = FakeOnlineCatalog(
            downloadHandler = { throw IllegalStateException("database write failed") },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource("source-json")
        viewModel.downloadOnlineEntry(resultEntry)
        advanceUntilIdle()

        assertTrue(viewModel.onlineLibraryState.value.downloadingKeys.isEmpty())
        assertTrue(
            viewModel.onlineLibraryState.value.error?.contains("database write failed") == true,
        )
    }

    @Test
    fun removingSelectedSourceClearsItsResultsAndSelection() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val resultEntry = entry("old", "Old Source Book")
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource(BUILTIN_CALIBRE_SOURCE_ID, SourceAdapterIds.CALIBRE),
                enabledSource("source-json", SourceAdapterIds.JSON_HTTP),
            ),
            catalogs = mapOf(
                "source-json" to FakeOnlineCatalog(
                    searchHandler = { _, _ -> ReadflowResult.Success(listOf(resultEntry)) },
                ),
            ),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        assertEquals(listOf("old"), viewModel.onlineLibraryState.value.results.map { it.meta.id })

        viewModel.removeOnlineSource("source-json")
        advanceUntilIdle()

        assertEquals(BUILTIN_CALIBRE_SOURCE_ID, viewModel.onlineLibraryState.value.selectedSourceId)
        assertTrue(viewModel.onlineLibraryState.value.results.isEmpty())
        assertTrue(viewModel.onlineLibraryState.value.selectedEntryKeys.isEmpty())
    }

    @Test
    fun nonCalibreCoordinatorBookIdMatchesDownloadedAndUpsertedId() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val sourceId = "source-json"
        val rawRemoteId = "raw-remote-42"
        val expectedId = stableRemoteBookId(sourceId, rawRemoteId)
        val coordinatedIds = mutableListOf<String>()
        val entry = entry(rawRemoteId, "Remote Book")
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(entry)) },
            downloadHandler = { e ->
                val bookId = if (e.meta.id.startsWith("remote-")) {
                    e.meta.id
                } else {
                    stableRemoteBookId(sourceId, e.meta.id)
                }
                ReadflowResult.Success(
                    e.meta.copy(
                        id = bookId,
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        localUri = "file:///books/$bookId.epub",
                    ),
                )
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource(sourceId, SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf(sourceId to catalog),
        )
        val viewModel = viewModel(
            store = store,
            registry = registry,
            assetOperations = RecordingAssetCoordinator(coordinatedIds),
        )
        advanceUntilIdle()
        viewModel.selectOnlineSource(sourceId)
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        viewModel.downloadOnlineEntry(entry)
        advanceUntilIdle()

        assertEquals(listOf(expectedId), coordinatedIds)
        assertEquals(listOf(expectedId), store.upsertedBooks.map { it.id })
        assertEquals(expectedId, store.upsertedBooks.single().id)
    }

    @Test
    fun remoteIdsAlreadyStableAreNotRehashedForCoordination() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val sourceId = "source-json"
        val stableId = stableRemoteBookId(sourceId, "raw-1")
        val coordinatedIds = mutableListOf<String>()
        val entry = entry(stableId, "Already Stable")
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(entry)) },
            downloadHandler = { e ->
                ReadflowResult.Success(
                    e.meta.copy(
                        id = e.meta.id,
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        localUri = "file:///books/${e.meta.id}.epub",
                    ),
                )
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource(sourceId, SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf(sourceId to catalog),
        )
        val viewModel = viewModel(
            store = store,
            registry = registry,
            assetOperations = RecordingAssetCoordinator(coordinatedIds),
        )
        advanceUntilIdle()
        viewModel.selectOnlineSource(sourceId)
        viewModel.downloadOnlineEntry(entry)
        advanceUntilIdle()

        assertEquals(listOf(stableId), coordinatedIds)
        assertEquals(listOf(stableId), store.upsertedBooks.map { it.id })
    }

    @Test
    fun sourceSwitchCancelsSearchClearsSpinnerAndIgnoresStaleFailure() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calibre = FakeOnlineCatalog(
            searchHandler = { _, _ ->
                firstStarted.complete(Unit)
                releaseFirst.await()
                ReadflowResult.Failure(ReadflowError.network(null, "stale failure from calibre"))
            },
        )
        val json = FakeOnlineCatalog(
            searchHandler = { _, _ ->
                ReadflowResult.Success(listOf(entry("j1", "JSON Book")))
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource(BUILTIN_CALIBRE_SOURCE_ID, SourceAdapterIds.CALIBRE),
                enabledSource("source-json", SourceAdapterIds.JSON_HTTP, "JSON"),
            ),
            catalogs = mapOf(
                BUILTIN_CALIBRE_SOURCE_ID to calibre,
                "source-json" to json,
            ),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.updateOnlineQuery("slow")
        viewModel.searchOnlineLibrary()
        runCurrent()
        firstStarted.await()
        assertTrue(viewModel.onlineLibraryState.value.isSearching)

        viewModel.selectOnlineSource("source-json")
        runCurrent()
        assertFalse(viewModel.onlineLibraryState.value.isSearching)
        assertTrue(viewModel.onlineLibraryState.value.results.isEmpty())
        assertNull(viewModel.onlineLibraryState.value.error)
        assertEquals("source-json", viewModel.onlineLibraryState.value.selectedSourceId)

        // Stale failure must not mutate the newly selected source.
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.onlineLibraryState.value.isSearching)
        assertNull(viewModel.onlineLibraryState.value.error)
        assertTrue(viewModel.onlineLibraryState.value.results.isEmpty())

        viewModel.updateOnlineQuery("q2")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        assertEquals(listOf("j1"), viewModel.onlineLibraryState.value.results.map { it.meta.id })
        assertFalse(viewModel.onlineLibraryState.value.isSearching)
    }

    @Test
    fun sourceSwitchIgnoresStaleSuccessFromPreviousSource() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calibre = FakeOnlineCatalog(
            searchHandler = { _, _ ->
                firstStarted.complete(Unit)
                releaseFirst.await()
                ReadflowResult.Success(listOf(entry("stale", "Stale Calibre")))
            },
        )
        val json = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(listOf(entry("fresh", "Fresh JSON"))) },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(
                enabledSource(BUILTIN_CALIBRE_SOURCE_ID, SourceAdapterIds.CALIBRE),
                enabledSource("source-json", SourceAdapterIds.JSON_HTTP),
            ),
            catalogs = mapOf(
                BUILTIN_CALIBRE_SOURCE_ID to calibre,
                "source-json" to json,
            ),
        )
        val viewModel = viewModel(registry = registry)
        advanceUntilIdle()

        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.updateOnlineQuery("q")
        viewModel.searchOnlineLibrary()
        runCurrent()
        firstStarted.await()

        viewModel.selectOnlineSource("source-json")
        viewModel.updateOnlineQuery("q2")
        viewModel.searchOnlineLibrary()
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("fresh"), viewModel.onlineLibraryState.value.results.map { it.meta.id })
        assertFalse(viewModel.onlineLibraryState.value.results.any { it.meta.id == "stale" })
        assertFalse(viewModel.onlineLibraryState.value.isSearching)
    }

    @Test
    fun batchDownloadRespectsConcurrencyCapAndDownloadsEachOnce() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val results = (1..6).map { entry("$it", "Book $it", author = "Ann", series = "Saga") }
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val downloadCounts = mutableMapOf<String, Int>()
        val lock = Mutex()
        val catalog = FakeOnlineCatalog(
            searchHandler = { _, _ -> ReadflowResult.Success(results) },
            downloadHandler = { entry ->
                val current = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, current) }
                try {
                    delay(50)
                    lock.withLock {
                        downloadCounts[entry.meta.id] = (downloadCounts[entry.meta.id] ?: 0) + 1
                    }
                    ReadflowResult.Success(
                        entry.meta.copy(
                            id = stableRemoteBookId("source-json", entry.meta.id),
                            downloadStatus = DownloadStatus.DOWNLOADED,
                            localUri = "file:///books/${entry.meta.id}.epub",
                        ),
                    )
                } finally {
                    inFlight.decrementAndGet()
                }
            },
        )
        val registry = FakeSourceRegistry(
            sources = listOf(enabledSource("source-json", SourceAdapterIds.JSON_HTTP)),
            catalogs = mapOf("source-json" to catalog),
        )
        val viewModel = viewModel(store = store, registry = registry)
        advanceUntilIdle()
        viewModel.selectOnlineSource("source-json")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        viewModel.selectOnlineBySeries("Saga")
        assertEquals(6, viewModel.onlineLibraryState.value.selectedEntryKeys.size)

        viewModel.downloadSelectedOnlineBooks()
        advanceUntilIdle()

        assertTrue(
            "peak concurrent downloads was ${peak.get()}, expected <= 3",
            peak.get() <= LibraryViewModel.ONLINE_BATCH_DOWNLOAD_CONCURRENCY,
        )
        assertEquals(6, downloadCounts.size)
        assertTrue(downloadCounts.values.all { it == 1 })
        assertEquals(6, store.upsertedBooks.size)
        assertTrue(viewModel.onlineLibraryState.value.message?.contains("已下载 6") == true)
    }

    @Test
    fun stableIdCollisionResistanceMatchesSharedApi() {
        val prefix = "y".repeat(60)
        val a = stableRemoteBookId("src", prefix + "one")
        val b = stableRemoteBookId("src", prefix + "two")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("remote-"))
        assertTrue(b.startsWith("remote-"))
    }

    private fun viewModel(
        store: FakeLibraryStore = FakeLibraryStore(),
        registry: SourceRegistry,
        assetOperations: BookAssetOperationCoordinator = CoroutineBookAssetOperationCoordinator(),
    ) = LibraryViewModel(
        repository = store,
        localSource = FakeLocalBookImporter(),
        assetOperations = assetOperations,
        sourceRegistry = registry,
    )

    /** Records produce() bookIds so tests can assert coordinator/download id alignment. */
    private class RecordingAssetCoordinator(
        private val producedBookIds: MutableList<String>,
        private val delegate: BookAssetOperationCoordinator = CoroutineBookAssetOperationCoordinator(),
    ) : BookAssetOperationCoordinator {
        override suspend fun <T> produce(bookId: String?, operation: suspend () -> T): T {
            if (bookId != null) producedBookIds += bookId
            return delegate.produce(bookId, operation)
        }

        override suspend fun <T> delete(bookId: String, operation: suspend () -> T): T =
            delegate.delete(bookId, operation)
    }

    private fun enabledSource(
        id: String,
        adapterId: String,
        name: String = adapterId,
        baseUrl: String = "http://192.168.1.5:8080",
    ) = SourceDescriptor(
        id = id,
        adapterId = adapterId,
        name = name,
        configVersion = 1,
        configJson = "{\"baseUrl\":\"$baseUrl\"}",
        baseUrl = baseUrl,
        enabled = true,
        isBuiltin = id == BUILTIN_CALIBRE_SOURCE_ID,
    ).copy(
        capabilities = SourceCapabilities(
            canSearch = true,
            canPreviewText = true,
            canDownload = true,
        ),
    )

    private fun entry(
        id: String,
        title: String,
        author: String = "Author",
        authors: List<String> = emptyList(),
        series: String? = null,
        tags: List<String> = emptyList(),
        formats: List<String> = emptyList(),
    ) = OnlineCatalogEntry(
        meta = BookMeta(
            id = id,
            title = title,
            author = author,
            format = BookFormat.EPUB,
        ),
        authors = authors,
        series = series,
        tags = tags,
        availableFormats = formats,
        downloadUrl = "http://192.168.1.5:8080/get/$id.epub",
        previewUrl = "http://192.168.1.5:8080/cover/$id.jpg",
    )

    private class FakeSourceRegistry(
        sources: List<SourceDescriptor>,
        private val catalogs: Map<String, OnlineBookCatalog>,
        private val addHandler: suspend (
            String,
            String,
            Int,
            String,
            SourceCredentials?,
        ) -> ReadflowResult<SourceDescriptor> = { _, _, _, _, _ ->
            ReadflowResult.Failure(ReadflowError.unsupported("not used"))
        },
        private val updateHandler: suspend (
            String,
            String,
            Int,
            String,
            SourceCredentials?,
        ) -> ReadflowResult<SourceDescriptor> = { _, _, _, _, _ ->
            ReadflowResult.Failure(ReadflowError.unsupported("not used"))
        },
        private val credentials: MutableMap<String, SourceCredentials> = mutableMapOf(),
        private val credentialReadHandler: (suspend (String) -> SourceCredentials?)? = null,
        private val importHandler: suspend (String) -> ReadflowResult<SourceDescriptor> = {
            ReadflowResult.Failure(ReadflowError.unsupported("not used"))
        },
    ) : SourceRegistry {
        private val sourceFlow = MutableStateFlow(sources)
        val openedSourceIds = mutableListOf<String>()
        var importCalls = 0
            private set
        var updateCalls = 0
            private set
        var credentialsCleared = false
            private set

        override fun observeSources(): Flow<List<SourceDescriptor>> = sourceFlow

        override suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog> {
            openedSourceIds += sourceId
            val catalog = catalogs[sourceId]
                ?: return ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
            return ReadflowResult.Success(catalog)
        }

        override suspend fun addUserSource(
            adapterId: String,
            name: String,
            configVersion: Int,
            configJson: String,
            credentials: SourceCredentials?,
        ): ReadflowResult<SourceDescriptor> = addHandler(adapterId, name, configVersion, configJson, credentials)

        override suspend fun updateUserSource(
            sourceId: String,
            name: String,
            configVersion: Int,
            configJson: String,
            credentials: SourceCredentials?,
        ): ReadflowResult<SourceDescriptor> {
            updateCalls += 1
            return updateHandler(sourceId, name, configVersion, configJson, credentials)
        }

        override suspend fun sourceCredentials(sourceId: String): SourceCredentials? =
            credentialReadHandler?.invoke(sourceId) ?: credentials[sourceId]

        override suspend fun clearSourceCredentials(sourceId: String): ReadflowResult<Unit> {
            credentials.remove(sourceId)
            credentialsCleared = true
            return ReadflowResult.Success(Unit)
        }

        override suspend fun removeUserSource(sourceId: String): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit).also {
                sourceFlow.value = sourceFlow.value.filterNot { source -> source.id == sourceId }
            }

        override suspend fun importUserSourceConfig(rawJson: String): ReadflowResult<SourceDescriptor> {
            importCalls += 1
            return importHandler(rawJson)
        }
    }

    private class FakeOnlineCatalog(
        private val searchHandler: suspend (String, OnlineCatalogFilter) -> ReadflowResult<List<OnlineCatalogEntry>> =
            { _, _ -> ReadflowResult.Success(emptyList()) },
        private val searchPageHandler: (suspend (
            String,
            OnlineCatalogFilter,
            Int,
            Int,
        ) -> ReadflowResult<List<OnlineCatalogEntry>>)? = null,
        private val downloadHandler: suspend (OnlineCatalogEntry) -> ReadflowResult<BookMeta> = {
            ReadflowResult.Success(it.meta.copy(downloadStatus = DownloadStatus.DOWNLOADED))
        },
        private val previewHandler: suspend (OnlineCatalogEntry) -> ReadflowResult<OnlineBookPreview> = {
            ReadflowResult.Success(OnlineBookPreview(it.meta.title, it.meta.author, null, "preview"))
        },
    ) : OnlineBookCatalog {
        override val descriptor = SourceDescriptor(
            id = "fake",
            adapterId = SourceAdapterIds.JSON_HTTP,
            name = "fake",
            configVersion = 1,
            configJson = "{\"baseUrl\":\"http://192.168.1.5:8080\"}",
            baseUrl = "http://192.168.1.5:8080",
        )

        override suspend fun search(
            query: String,
            filter: OnlineCatalogFilter,
            offset: Int,
            limit: Int,
        ) = searchPageHandler?.invoke(query, filter, offset, limit) ?: searchHandler(query, filter)

        override suspend fun download(entry: OnlineCatalogEntry) = downloadHandler(entry)

        override suspend fun preview(entry: OnlineCatalogEntry) = previewHandler(entry)
    }

    private class FakeLibraryStore : LibraryStore {
        private val shelf = MutableStateFlow<List<LibraryItem>>(emptyList())
        val upsertedBooks = mutableListOf<BookMeta>()

        override fun observeShelf(): Flow<List<LibraryItem>> = shelf
        override suspend fun count(): Int = upsertedBooks.size
        override suspend fun upsertBook(book: BookMeta) {
            upsertedBooks += book
        }
        override suspend fun upsertAll(books: List<BookMeta>) {
            books.forEach { upsertBook(it) }
        }
        override suspend fun deleteBook(id: String) = Unit
        override suspend fun deleteBookCompletely(id: String) = Unit
        override suspend fun removeDownloadedAsset(id: String): Boolean = false
        override suspend fun renameBook(id: String, title: String) = Unit
        override suspend fun setCollection(id: String, collectionId: String?, name: String?) = Unit
        override suspend fun moveToGroup(sourceId: String, targetCollectionId: String) = Unit
        override suspend fun createGroup(sourceId: String, targetId: String, name: String) = Unit
        override suspend fun renameBundle(collectionId: String, newName: String) = Unit
        override suspend fun ungroupBundle(collectionId: String) = Unit
        override suspend fun updateShelfOrder(ids: List<String>) = Unit
    }

    private class FakeLocalBookImporter : LocalBookImporter {
        override suspend fun import(uri: Uri, mimeType: String?) =
            ReadflowResult.Failure(ReadflowError.unsupported("unused"))
    }
}
