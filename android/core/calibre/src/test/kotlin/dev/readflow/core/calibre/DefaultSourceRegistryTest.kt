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
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
            calibreCatalogFactory = { FakeCatalog(SourceKind.CALIBRE, it) },
            genericCatalogFactory = { FakeCatalog(it.kind, it.baseUrl) },
        )

        val sources = registry.observeSources().first()
        assertEquals(2, sources.size)
        assertEquals(BUILTIN_CALIBRE_SOURCE_ID, sources[0].id)
        assertEquals(true, sources[0].enabled)
        assertEquals("source-json-1", sources[1].id)
        assertEquals(SourceKind.JSON_HTTP, sources[1].kind)
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
            calibreCatalogFactory = { url ->
                opened += "calibre:$url"
                FakeCatalog(SourceKind.CALIBRE, url)
            },
            genericCatalogFactory = { descriptor ->
                opened += "generic:${descriptor.id}"
                FakeCatalog(descriptor.kind, descriptor.baseUrl)
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
    fun addUserSourceRejectsCalibreAndInvalidHttpPublicHosts() = runTest {
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(),
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
        )

        val calibreAdd = registry.addUserSource(SourceKind.CALIBRE, "Calibre", "http://192.168.1.5:8080")
        assertTrue(calibreAdd is ReadflowResult.Failure)

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
    fun removeUserSourceBlocksBuiltinCalibre() = runTest {
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
            settings = FakeSettingsRepository(),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        assertTrue(registry.removeUserSource(BUILTIN_CALIBRE_SOURCE_ID) is ReadflowResult.Failure)
        assertTrue(registry.removeUserSource("source-x") is ReadflowResult.Success)
        assertEquals(null, store.getUserSource("source-x"))
    }

    private class FakeCatalog(
        kind: SourceKind,
        baseUrl: String,
    ) : OnlineBookCatalog {
        override val descriptor = SourceDescriptor(
            id = "fake",
            kind = kind,
            name = "fake",
            baseUrl = baseUrl,
        )

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

        override suspend fun previewUrl(entry: OnlineCatalogEntry) =
            ReadflowResult.Failure(dev.readflow.core.model.ReadflowError.notFound("preview", "x"))
    }

    private class InMemorySourceConfigStore(
        initial: List<PersistedBookSource> = emptyList(),
    ) : SourceConfigStore {
        private val sources = MutableStateFlow(initial)

        override fun observeUserSources(): Flow<List<PersistedBookSource>> = sources

        override suspend fun getUserSource(id: String): PersistedBookSource? =
            sources.value.firstOrNull { it.id == id }

        override suspend fun upsertUserSource(source: PersistedBookSource) {
            sources.value = sources.value.filterNot { it.id == source.id } + source
        }

        override suspend fun deleteUserSource(id: String) {
            sources.value = sources.value.filterNot { it.id == id }
        }

        override suspend fun nextSortOrder(): Int =
            (sources.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

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
