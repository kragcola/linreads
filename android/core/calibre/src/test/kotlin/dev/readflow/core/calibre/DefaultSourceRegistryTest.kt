package dev.readflow.core.calibre

import dev.readflow.core.database.PersistedBookSource
import dev.readflow.core.database.SourceConfigStore
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.DefaultSourceAdapterRegistry
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.SourceAdapterFactory
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSourceRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun observeSourcesCombinesBuiltinCalibreAndUserSources() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-json-1",
                    kind = SourceKind.JSON_HTTP.name,
                    name = "JSON LAN",
                    baseUrl = "http://192.168.1.10:9000/catalog.json",
                    enabled = true,
                    sortOrder = 0,
                    createdAt = 1L,
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreCatalogFactory = { FakeCatalog(it) },
            genericCatalogFactory = { FakeCatalog(checkNotNull(it.kind), it.baseUrl) },
        )

        val sources = registry.observeSources().first()
        val sourcesById = sources.associateBy(SourceDescriptor::id)
        assertEquals(2, sources.size)
        assertEquals(true, sourcesById.getValue(BUILTIN_CALIBRE_SOURCE_ID).enabled)
        assertEquals(SourceKind.JSON_HTTP, sourcesById.getValue("source-json-1").kind)
    }

    @Test
    fun openCatalogRoutesBySourceId() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-opds",
                    kind = SourceKind.OPDS.name,
                    name = "OPDS",
                    baseUrl = "http://192.168.1.20:8080/opds",
                    enabled = true,
                ),
            ),
        )
        val opened = mutableListOf<String>()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreCatalogFactory = { descriptor ->
                opened += "calibre:${descriptor.baseUrl}"
                FakeCatalog(descriptor)
            },
            genericCatalogFactory = { descriptor ->
                opened += "generic:${descriptor.id}"
                FakeCatalog(checkNotNull(descriptor.kind), descriptor.baseUrl)
            },
        )

        val calibre = registry.openCatalog(BUILTIN_CALIBRE_SOURCE_ID)
        assertTrue(calibre is ReadflowResult.Success)
        val generic = registry.openCatalog("source-opds")
        assertTrue(generic is ReadflowResult.Success)
        assertEquals(
            listOf("calibre:http://192.168.1.5:8080", "generic:source-opds"),
            opened,
        )
    }

    @Test
    fun addUserSourceAcceptsCalibreAndRejectsInvalidHttpPublicHosts() = runTest {
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(),
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
        )

        val calibreAdd = registry.addUserSource(SourceKind.CALIBRE, "Calibre", "http://192.168.1.5:8080")
        assertTrue(calibreAdd is ReadflowResult.Success)

        val publicHttp = registry.addUserSource(
            SourceKind.JSON_HTTP,
            "Public",
            "http://example.com/catalog.json",
        )
        assertTrue(publicHttp is ReadflowResult.Failure)

        val ok = registry.addUserSource(
            SourceKind.JSON_HTTP,
            "LAN JSON",
            "http://192.168.1.30:8080/catalog.json",
        )
        assertTrue(ok is ReadflowResult.Success)
        assertEquals("LAN JSON", (ok as ReadflowResult.Success).value.name)
        assertEquals(SourceKind.JSON_HTTP, ok.value.kind)
    }

    @Test
    fun migratedCalibreCanBeRemovedWithoutBeingRecreated() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-x",
                    kind = SourceKind.OPDS.name,
                    name = "X",
                    baseUrl = "http://10.0.0.2:8080/opds",
                    enabled = true,
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        registry.observeSources().first()
        assertTrue(registry.removeUserSource(BUILTIN_CALIBRE_SOURCE_ID) is ReadflowResult.Success)
        assertEquals(null, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID))
        assertEquals("", settings.calibreBaseUrl.value)
        assertTrue(registry.removeUserSource("source-x") is ReadflowResult.Success)
        assertEquals(null, store.getUserSource("source-x"))
    }

    @Test
    fun blankLegacySettingCleansInterruptedBuiltinDeletion() = runTest {
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, "http://192.168.1.5:8080")),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = ""),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        val sources = registry.observeSources().first()

        assertTrue(sources.isEmpty())
        assertEquals(null, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun firstRunDiscoversAndPersistsBuiltinCalibre() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = null)
        val store = InMemorySourceConfigStore()
        var discoveryCalls = 0
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )

        val sources = registry.observeSources().first()

        assertEquals(1, discoveryCalls)
        assertEquals("http://192.168.2.1:8080", settings.calibreBaseUrl.value)
        assertEquals(BUILTIN_CALIBRE_SOURCE_ID, sources.single().id)
        assertEquals("http://192.168.2.1:8080", sources.single().baseUrl)
    }

    @Test
    fun deletedBuiltinCalibreNeverRunsDiscoveryAgain() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "")
        var discoveryCalls = 0
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )

        assertTrue(registry.observeSources().first().isEmpty())
        assertEquals(0, discoveryCalls)
        assertEquals("", settings.calibreBaseUrl.value)
    }

    @Test
    fun existingCalibreSourceSuppressesAutomaticDiscovery() = runTest {
        var discoveryCalls = 0
        val existing = calibreSource("source-calibre-user", "http://192.168.1.5:8080")
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(existing)),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )

        val sources = registry.observeSources().first()

        assertEquals(0, discoveryCalls)
        assertEquals(listOf("source-calibre-user"), sources.map(SourceDescriptor::id))
    }

    @Test
    fun nullLegacySettingPreservesExistingBuiltinCalibreSource() = runTest {
        var discoveryCalls = 0
        val builtin = calibreSource(BUILTIN_CALIBRE_SOURCE_ID, "http://192.168.1.5:8080")
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(builtin)),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.NotFound
            },
        )

        val sources = registry.observeSources().first()

        assertEquals(0, discoveryCalls)
        assertEquals(listOf(BUILTIN_CALIBRE_SOURCE_ID), sources.map(SourceDescriptor::id))
    }

    @Test
    fun cancelledDiscoveryIsRetriedByTheNextCollector() = runTest {
        var discoveryCalls = 0
        val firstDiscoveryStarted = CompletableDeferred<Unit>()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                if (discoveryCalls == 1) {
                    firstDiscoveryStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    CalibreDiscoveryResult.NotFound
                } else {
                    CalibreDiscoveryResult.Found(
                        baseUrl = "http://192.168.2.1:8080",
                        serviceName = "Books in calibre",
                    )
                }
            },
        )

        registry.observeSources().first()
        firstDiscoveryStarted.await()
        val retry = async {
            registry.observeSources().first { sources -> sources.isNotEmpty() }
        }
        runCurrent()
        val retriedAfterCancellation = discoveryCalls == 2
        retry.cancel()

        assertTrue("cancelled discovery must not consume the one allowed attempt", retriedAfterCancellation)
    }

    @Test
    fun discoveryCancelledWhileWaitingForSourceWriteLockIsRetried() = runTest {
        var discoveryCalls = 0
        val sourceWriteStarted = CompletableDeferred<Unit>()
        val sourceWriteGate = CompletableDeferred<Unit>()
        val store = InMemorySourceConfigStore(
            beforeNextSortOrder = {
                sourceWriteStarted.complete(Unit)
                sourceWriteGate.await()
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )
        val manualAdd = async {
            registry.addUserSource(
                SourceKind.OPDS,
                "OPDS",
                "http://192.168.1.20:8080/opds",
            )
        }
        sourceWriteStarted.await()

        registry.observeSources().first()
        sourceWriteGate.complete(Unit)
        manualAdd.await()
        val retry = async {
            registry.observeSources().first { sources ->
                sources.any { it.id == BUILTIN_CALIBRE_SOURCE_ID }
            }
        }
        runCurrent()
        val retriedAfterLockCancellation = discoveryCalls == 2
        retry.cancel()

        assertTrue(
            "cancellation while waiting for the source lock must leave discovery retryable",
            retriedAfterLockCancellation,
        )
    }

    @Test
    fun manualCalibreAddDuringDiscoverySuppressesBuiltinPersistence() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = null)
        val store = InMemorySourceConfigStore()
        val discoveryStarted = CompletableDeferred<Unit>()
        val discoveryGate = CompletableDeferred<Unit>()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryStarted.complete(Unit)
                discoveryGate.await()
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )
        val observation = launch { registry.observeSources().collect() }
        discoveryStarted.await()

        val manual = registry.addUserSource(
            SourceKind.CALIBRE,
            "My Calibre",
            "http://192.168.1.5:8080",
        )
        discoveryGate.complete(Unit)
        runCurrent()
        observation.cancelAndJoin()

        assertTrue(manual is ReadflowResult.Success)
        assertEquals(null, settings.calibreBaseUrl.value)
        assertEquals(1, store.observeUserSources().first().count { it.adapterId == SourceAdapterIds.CALIBRE })
    }

    @Test
    fun pendingDiscoveryDoesNotDelayExistingSources() = runTest {
        val discoveryGate = CompletableDeferred<Unit>()
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-opds",
                    kind = SourceKind.OPDS.name,
                    name = "OPDS",
                    baseUrl = "http://192.168.1.20:8080/opds",
                    enabled = true,
                    adapterId = SourceAdapterIds.OPDS,
                    configVersion = 1,
                    configJson = httpCatalogSourceConfigJson("http://192.168.1.20:8080/opds"),
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryGate.await()
                CalibreDiscoveryResult.NotFound
            },
        )

        val observation = async { registry.observeSources().first() }
        runCurrent()
        val emittedBeforeDiscoveryFinished = observation.isCompleted
        discoveryGate.complete(Unit)
        runCurrent()

        assertTrue("existing sources must render while Bonjour is pending", emittedBeforeDiscoveryFinished)
        assertEquals(listOf("source-opds"), observation.await().map(SourceDescriptor::id))
    }

    @Test
    fun openingExistingSourceDoesNotWaitForDiscovery() = runTest {
        val discoveryGate = CompletableDeferred<Unit>()
        val source = PersistedBookSource(
            id = "source-opds",
            kind = SourceKind.OPDS.name,
            name = "OPDS",
            baseUrl = "http://192.168.1.20:8080/opds",
            enabled = true,
            adapterId = SourceAdapterIds.OPDS,
            configVersion = 1,
            configJson = httpCatalogSourceConfigJson("http://192.168.1.20:8080/opds"),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(source)),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryGate.await()
                CalibreDiscoveryResult.NotFound
            },
        )

        val opening = async { registry.openCatalog(source.id) }
        runCurrent()
        val openedBeforeDiscoveryFinished = opening.isCompleted
        discoveryGate.complete(Unit)
        runCurrent()

        assertTrue("opening a configured source must not wait for Bonjour", openedBeforeDiscoveryFinished)
        assertTrue(opening.await() is ReadflowResult.Success)
    }

    @Test
    fun twoCalibreSourcesOpenWithTheirOwnDescriptors() = runTest {
        val store = InMemorySourceConfigStore(
            listOf(
                calibreSource("source-calibre-a", "http://192.168.1.5:8080"),
                calibreSource("source-calibre-b", "http://192.168.1.6:8080"),
            ),
        )
        val opened = mutableListOf<SourceDescriptor>()
        val adapter = object : SourceAdapterFactory {
            override val adapterId = SourceAdapterIds.CALIBRE
            override val latestConfigVersion = 1
            override fun capabilities(configVersion: Int, configJson: String) =
                SourceCapabilities(canSearch = true, canDownload = true)
            override fun validate(configVersion: Int, configJson: String) = ReadflowResult.Success(Unit)
            override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> {
                opened += descriptor
                return ReadflowResult.Success(FakeCatalog(descriptor))
            }
        }
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            sourceAdapters = DefaultSourceAdapterRegistry(setOf(adapter)),
        )

        val first = registry.openCatalog("source-calibre-a")
        val second = registry.openCatalog("source-calibre-b")

        assertTrue(first is ReadflowResult.Success)
        assertTrue(second is ReadflowResult.Success)
        assertEquals(listOf("source-calibre-a", "source-calibre-b"), opened.map(SourceDescriptor::id))
        assertEquals(
            listOf("http://192.168.1.5:8080", "http://192.168.1.6:8080"),
            opened.map(SourceDescriptor::baseUrl),
        )
    }

    @Test
    fun compatibilityRegistryPreservesCalibreSourceDescriptor() = runTest {
        val source = calibreSource("source-calibre-user", "http://192.168.1.5:8080")
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(source)),
            booksDir = tempFolder.root,
        )

        val result = registry.openCatalog(source.id)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(source.id, (result as ReadflowResult.Success).value.descriptor.id)
    }

    @Test
    fun unknownAdapterIsDescribedAsDisabledAndFailsClosed() = runTest {
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-unknown",
                    kind = "THIRD_PARTY",
                    name = "Unknown",
                    baseUrl = "https://example.com/catalog",
                    enabled = true,
                    adapterId = "missing-adapter",
                    configJson = "{}",
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            sourceAdapters = DefaultSourceAdapterRegistry(emptySet()),
        )

        assertEquals(false, registry.observeSources().first().single().enabled)
        assertTrue(registry.openCatalog("source-unknown") is ReadflowResult.Failure)
    }

    @Test
    fun legacyCalibreImportIsIdempotent() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        registry.observeSources().first()
        registry.observeSources().first()
        assertEquals(1, store.upsertCalls)

        settings.calibreBaseUrl.value = "http://192.168.1.6:8080"
        registry.observeSources().first()
        registry.observeSources().first()

        val builtin = store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
        assertEquals(2, store.upsertCalls)
        assertEquals("http://192.168.1.6:8080", builtin?.baseUrl)
        assertEquals(calibreSourceConfigJson("http://192.168.1.6:8080"), builtin?.configJson)
    }

    private class FakeCatalog(
        kind: SourceKind,
        baseUrl: String,
    ) : OnlineBookCatalog {
        constructor(descriptor: SourceDescriptor) : this(
            kind = checkNotNull(descriptor.kind),
            baseUrl = descriptor.baseUrl,
        ) {
            sourceDescriptor = descriptor
        }

        private var sourceDescriptor = SourceDescriptor(
            id = "fake",
            kind = kind,
            name = "fake",
            baseUrl = baseUrl,
        )
        override val descriptor: SourceDescriptor
            get() = sourceDescriptor

        override suspend fun search(
            query: String,
            filter: OnlineCatalogFilter,
            offset: Int,
            limit: Int,
        ) = ReadflowResult.Success(emptyList<OnlineCatalogEntry>())

        override suspend fun download(entry: OnlineCatalogEntry) =
            ReadflowResult.Success(
                BookMeta(id = "d", title = "t", author = "a", format = BookFormat.EPUB),
            )

    }

    private class InMemorySourceConfigStore(
        initial: List<PersistedBookSource> = emptyList(),
        private val beforeNextSortOrder: (suspend () -> Unit)? = null,
    ) : SourceConfigStore {
        private val sources = MutableStateFlow(initial)
        var upsertCalls = 0
            private set

        override fun observeUserSources(): Flow<List<PersistedBookSource>> = sources

        override suspend fun getUserSource(id: String): PersistedBookSource? =
            sources.value.firstOrNull { it.id == id }

        override suspend fun upsertUserSource(source: PersistedBookSource) {
            upsertCalls += 1
            sources.value = sources.value.filterNot { it.id == source.id } + source
        }

        override suspend fun deleteUserSource(id: String) {
            sources.value = sources.value.filterNot { it.id == id }
        }

        override suspend fun nextSortOrder(): Int {
            beforeNextSortOrder?.invoke()
            return (sources.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
        }
    }

    private fun calibreSource(id: String, baseUrl: String) = PersistedBookSource(
        id = id,
        kind = "CALIBRE",
        name = id,
        baseUrl = baseUrl,
        enabled = true,
        adapterId = SourceAdapterIds.CALIBRE,
        configVersion = 1,
        configJson = calibreSourceConfigJson(baseUrl),
    )

    private class FakeSettingsRepository(
        calibreUrl: String? = "http://192.168.1.5:8080",
    ) : SettingsRepository {
        override val calibreBaseUrl = MutableStateFlow(calibreUrl)
        override val fontSize = MutableStateFlow(18)
        override val lineSpacing = MutableStateFlow(1.75f)
        override val readingMode = MutableStateFlow(ReaderReadingMode.SCROLL)
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override val deviceId = MutableStateFlow("device")
        override val engineOverrides = MutableStateFlow(emptyMap<BookFormat, String>())
        override val useSourceHanFont = MutableStateFlow(true)
        override val txtEncoding = MutableStateFlow(TxtEncoding.AUTO)
        override val fontChoice = MutableStateFlow<FontChoice>(FontChoice.SourceHan)
        override val readerGuideShown = MutableStateFlow(true)
        override val pageFlipStyle = MutableStateFlow(dev.readflow.core.model.PageFlipStyle.SLIDE)
        override suspend fun setCalibreBaseUrl(url: String) {
            calibreBaseUrl.value = url
        }
        override suspend fun setFontSize(size: Int) = Unit
        override suspend fun setLineSpacing(multiplier: Float) = Unit
        override suspend fun setReadingMode(mode: ReaderReadingMode) = Unit
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setEngineOverride(format: BookFormat, engineId: String?) = Unit
        override suspend fun setUseSourceHanFont(enabled: Boolean) = Unit
        override suspend fun setTxtEncoding(encoding: TxtEncoding) = Unit
        override suspend fun setFontChoice(choice: FontChoice) = Unit
        override suspend fun setReaderGuideShown(shown: Boolean) = Unit
        override suspend fun setPageFlipStyle(style: dev.readflow.core.model.PageFlipStyle) = Unit
    }
}
