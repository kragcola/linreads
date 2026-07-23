package dev.readflow.core.calibre

import dev.readflow.core.database.PersistedBookSource
import dev.readflow.core.database.SourceConfigStore
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.DefaultSourceAdapterRegistry
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.SourceAdapterFactory
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceDescriptor
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceConfigImportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validEnvelopeImportsViaRegisteredAdapterValidation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = validEnvelopeJson(
            name = "LAN JSON",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"http://192.168.1.30:8080/catalog.json"}""",
        )

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Success)
        val source = (result as ReadflowResult.Success).value
        assertEquals("LAN JSON", source.name)
        assertEquals(SourceAdapterIds.JSON_HTTP, source.adapterId)
        assertEquals(1, store.upsertCalls)
        assertEquals(1, store.observeUserSources().first().size)
    }

    @Test
    fun rejectsUnknownAdapterWithoutMutation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = validEnvelopeJson(
            name = "Ghost",
            adapterId = "not-a-real-adapter",
            configJson = """{"baseUrl":"http://192.168.1.30:8080/catalog.json"}""",
        )

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("适配器"))
        assertEquals(0, store.upsertCalls)
    }

    @Test
    fun rejectsUnsupportedSchemaVersionWithoutMutation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = """
            {
              "schemaVersion": 99,
              "name": "Future",
              "adapterId": "${SourceAdapterIds.JSON_HTTP}",
              "configVersion": 1,
              "configJson": {"baseUrl":"http://192.168.1.30:8080/catalog.json"}
            }
        """.trimIndent()

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("schema") ||
            result.error.message.contains("版本"))
        assertEquals(0, store.upsertCalls)
    }

    @Test
    fun rejectsMalformedJsonWithoutMutation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)

        val result = registry.importUserSourceConfig("{not-json")

        assertTrue(result is ReadflowResult.Failure)
        assertEquals(ReadflowError.Kind.PARSE, (result as ReadflowResult.Failure).error.kind)
        assertEquals(0, store.upsertCalls)
    }

    @Test
    fun rejectsBlankNameWithoutMutation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = validEnvelopeJson(
            name = "   ",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"http://192.168.1.30:8080/catalog.json"}""",
        )

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("名称"))
        assertEquals(0, store.upsertCalls)
    }

    @Test
    fun rejectsInvalidAdapterConfigWithoutMutation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = validEnvelopeJson(
            name = "Bad URL",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"http://example.com/public.json"}""",
        )

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Failure)
        assertEquals(0, store.upsertCalls)
    }

    @Test
    fun rejectsOversizedInputWithoutMutation() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val oversized = ByteArray(SOURCE_CONFIG_IMPORT_MAX_BYTES + 1) { 'a'.code.toByte() }

        val result = registry.importUserSourceConfig(ByteArrayInputStream(oversized))

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("过大") ||
            result.error.message.contains("太大"))
        assertEquals(0, store.upsertCalls)
    }

    @Test
    fun duplicateSemanticConfigReturnsExistingWithoutSecondInsert() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val configJson = """{"baseUrl":"http://192.168.1.30:8080/catalog.json"}"""
        val json = validEnvelopeJson(
            name = "First",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = configJson,
        )
        val first = registry.importUserSourceConfig(json) as ReadflowResult.Success
        val secondJson = validEnvelopeJson(
            name = "Second Name",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = configJson,
        )

        val second = registry.importUserSourceConfig(secondJson)

        assertTrue(second is ReadflowResult.Success)
        assertEquals(first.value.id, (second as ReadflowResult.Success).value.id)
        assertEquals(1, store.upsertCalls)
        assertEquals(1, store.observeUserSources().first().size)
    }

    @Test
    fun nestedConfigJsonObjectIsAccepted() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = """
            {
              "schemaVersion": 1,
              "name": "Nested",
              "adapterId": "${SourceAdapterIds.JSON_HTTP}",
              "configVersion": 1,
              "configJson": {"baseUrl":"http://192.168.1.40:8080/catalog.json"}
            }
        """.trimIndent()

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(1, store.upsertCalls)
    }

    @Test
    fun urlNormalizationDedupsSemanticallyEquivalentConfigs() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val firstJson = validEnvelopeJson(
            name = "Trailing Slash",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"HTTP://LOCALHOST:80/catalog.json/"}""",
        )
        val secondJson = validEnvelopeJson(
            name = "Whitespace URL",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"  http://localhost/catalog.json  "}""",
        )

        val first = registry.importUserSourceConfig(firstJson) as ReadflowResult.Success
        val second = registry.importUserSourceConfig(secondJson)

        assertTrue(second is ReadflowResult.Success)
        assertEquals(first.value.id, (second as ReadflowResult.Success).value.id)
        assertEquals(1, store.upsertCalls)
        assertEquals(1, store.observeUserSources().first().size)
        assertEquals(
            "http://localhost/catalog.json",
            store.observeUserSources().first().single().baseUrl,
        )
    }

    @Test
    fun htmlCanonicalizationDedupsHostSetsAndCharsetAliases() {
        val rules = HtmlRulesV1Config(
            searchUrlTemplate = "https://books.example/search?q={query}",
            allowedHosts = listOf("CDN.BOOKS.EXAMPLE", "books.example", "books.example"),
            charset = "utf-8",
            search = HtmlSearchRules(".book", ".title", ".author", "a"),
            detail = HtmlDetailRules(".chapter", "a"),
            chapter = HtmlChapterRules(bodySelector = ".content"),
        )
        val equivalent = rules.copy(
            allowedHosts = listOf("books.example", "cdn.books.example"),
            charset = "UTF-8",
        )

        assertEquals(
            canonicalizeImportedConfigJson(SourceAdapterIds.HTML_RULES_V1, htmlRulesV1ConfigJson(rules)),
            canonicalizeImportedConfigJson(SourceAdapterIds.HTML_RULES_V1, htmlRulesV1ConfigJson(equivalent)),
        )
    }

    @Test
    fun extensionConfigCanonicalizationIgnoresObjectKeyOrderAndWhitespace() {
        val first = canonicalizeImportedConfigJson("third-party", """{"b":2,"a":{"y":1,"x":0}}""")
        val second = canonicalizeImportedConfigJson(
            "third-party",
            """ { "a" : { "x" : 0, "y" : 1 }, "b" : 2 } """,
        )

        assertEquals(first, second)
    }

    @Test
    fun invalidUtf8IsRejectedInsteadOfPersistingReplacementCharacters() {
        val prefix = """{"schemaVersion":1,"name":"""".toByteArray()
        val suffix = """","adapterId":"${SourceAdapterIds.JSON_HTTP}","configVersion":1,"configJson":{}}"""
            .toByteArray()
        val invalid = prefix + byteArrayOf(0xC3.toByte(), 0x28) + suffix

        val result = readBoundedSourceConfigBytes(ByteArrayInputStream(invalid))

        assertTrue(result is ReadflowResult.Failure)
        assertEquals(ReadflowError.Kind.PARSE, (result as ReadflowResult.Failure).error.kind)
    }

    @Test
    fun duplicateImportReenablesExistingDisabledSource() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = validEnvelopeJson(
            name = "Reusable",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"http://192.168.1.60/catalog.json"}""",
        )
        registry.importUserSourceConfig(json)
        val existing = store.observeUserSources().first().single()
        store.upsertUserSource(existing.copy(enabled = false))

        val result = registry.importUserSourceConfig(json)

        assertTrue(result is ReadflowResult.Success)
        assertTrue((result as ReadflowResult.Success).value.enabled)
        assertTrue(store.observeUserSources().first().single().enabled)
    }

    @Test
    fun concurrentIdenticalImportsProduceOneInsert() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = registry(store)
        val json = validEnvelopeJson(
            name = "Concurrent",
            adapterId = SourceAdapterIds.JSON_HTTP,
            configJson = """{"baseUrl":"http://192.168.1.55:8080/catalog.json"}""",
        )

        val results = (1..8).map {
            async { registry.importUserSourceConfig(json) }
        }.awaitAll()

        assertTrue(results.all { it is ReadflowResult.Success })
        val ids = results.map { (it as ReadflowResult.Success).value.id }.toSet()
        assertEquals(1, ids.size)
        assertEquals(1, store.upsertCalls)
        assertEquals(1, store.observeUserSources().first().size)
    }

    private fun registry(store: SourceConfigStore): DefaultSourceRegistry =
        DefaultSourceRegistry(
            settings = FakeSettingsRepository(),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

    private fun validEnvelopeJson(
        name: String,
        adapterId: String,
        configJson: String,
        schemaVersion: Int = 1,
        configVersion: Int = 1,
    ): String {
        val escapedConfig = configJson
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """
            {
              "schemaVersion": $schemaVersion,
              "name": "$name",
              "adapterId": "$adapterId",
              "configVersion": $configVersion,
              "configJson": "$escapedConfig"
            }
        """.trimIndent()
    }

    private class InMemorySourceConfigStore(
        initial: List<PersistedBookSource> = emptyList(),
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

        override suspend fun nextSortOrder(): Int =
            (sources.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val calibreBaseUrl = MutableStateFlow<String?>(null)
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
