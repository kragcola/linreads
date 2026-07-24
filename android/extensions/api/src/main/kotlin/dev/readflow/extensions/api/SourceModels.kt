package dev.readflow.extensions.api

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.flow.Flow

/** Stable adapter ids persisted in source configuration. Third-party adapters may add new ids. */
object SourceAdapterIds {
    const val CALIBRE = "calibre"
    const val OPDS = "opds"
    const val JSON_HTTP = "json-http"
    const val HTML_RULES_V1 = "html-rules-v1"
}

/**
 * Compatibility names for pre-v7 call sites and migration only. Runtime dispatch uses adapter ids.
 */
@Deprecated("Use SourceAdapterIds and versioned configJson")
enum class SourceKind(val adapterId: String) {
    CALIBRE(SourceAdapterIds.CALIBRE),
    OPDS(SourceAdapterIds.OPDS),
    JSON_HTTP(SourceAdapterIds.JSON_HTTP),
    HTML_RULES_V1(SourceAdapterIds.HTML_RULES_V1),
    ;

    companion object {
        fun fromAdapterId(adapterId: String): SourceKind? = entries.firstOrNull { it.adapterId == adapterId }
    }
}

data class SourceCapabilities(
    val canSearch: Boolean = true,
    val canFilterByAuthor: Boolean = false,
    val canFilterBySeries: Boolean = false,
    val canFilterByFormat: Boolean = false,
    val canFilterByTag: Boolean = false,
    val canPreviewText: Boolean = false,
    val canDownload: Boolean = false,
    /** True only when author/series selection can enumerate beyond the loaded result page. */
    val canBatchAcrossSource: Boolean = false,
)

/** Authentication material kept outside source config JSON and UI-safe descriptors. */
data class SourceCredentials(
    val username: String,
    val password: String,
) {
    val isEmpty: Boolean
        get() = username.isBlank()
}

/** Persistable and UI-safe source descriptor. Secrets are referenced outside [configJson]. */
data class SourceDescriptor(
    val id: String,
    val adapterId: String,
    val name: String,
    val configVersion: Int,
    val configJson: String,
    val baseUrl: String = "",
    val enabled: Boolean = true,
    val isBuiltin: Boolean = false,
    val capabilities: SourceCapabilities = SourceCapabilities(),
) {
    @Deprecated("Use adapterId")
    val kind: SourceKind?
        get() = SourceKind.fromAdapterId(adapterId)

    @Deprecated("Use the primary adapterId/configJson constructor")
    constructor(
        id: String,
        kind: SourceKind,
        name: String,
        baseUrl: String,
        enabled: Boolean = true,
        isBuiltin: Boolean = false,
    ) : this(
        id = id,
        adapterId = kind.adapterId,
        name = name,
        configVersion = 1,
        configJson = legacyBaseUrlConfigJson(baseUrl),
        baseUrl = baseUrl,
        enabled = enabled,
        isBuiltin = isBuiltin,
    )
}

data class RemoteBookKey(
    val sourceId: String,
    val remoteId: String,
)

data class OnlineCatalogEntry(
    val meta: BookMeta,
    val remoteKey: RemoteBookKey? = null,
    /** Individual author names when the source exposes structured authorship. */
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val tags: List<String> = emptyList(),
    val availableFormats: List<String> = emptyList(),
    val downloadUrl: String? = null,
    /** Adapter-private opaque reference, normally a detail URL for declarative HTML sources. */
    val detailReference: String? = null,
    @Deprecated("Preview is adapter-owned sanitized content, not a URL")
    val previewUrl: String? = null,
)

data class OnlineCatalogFilter(
    val author: String = "",
    val series: String = "",
    val format: String = "",
    val tag: String = "",
) {
    val isEmpty: Boolean
        get() = author.isBlank() && series.isBlank() && format.isBlank() && tag.isBlank()
}

/** Application-owned, plain-text preview. No script, HTML, iframe, or external navigation. */
data class OnlineBookPreview(
    val title: String,
    val author: String,
    val chapterTitle: String?,
    val body: String,
)

interface OnlineBookCatalog : AutoCloseable {
    val descriptor: SourceDescriptor
    val capabilities: SourceCapabilities
        get() = descriptor.capabilities

    suspend fun search(
        query: String,
        filter: OnlineCatalogFilter = OnlineCatalogFilter(),
        offset: Int = 0,
        limit: Int = 100,
    ): ReadflowResult<List<OnlineCatalogEntry>>

    suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta>

    suspend fun preview(entry: OnlineCatalogEntry): ReadflowResult<OnlineBookPreview> =
        ReadflowResult.Failure(ReadflowError.unsupported("该书源不支持应用内正文预览"))

    override fun close() = Unit
}

/** One compile-time adapter implementation. Dispatch is by open [adapterId], never by enum switch. */
interface SourceAdapterFactory {
    val adapterId: String
    val latestConfigVersion: Int
    fun capabilities(configVersion: Int, configJson: String): SourceCapabilities
    fun validate(configVersion: Int, configJson: String): ReadflowResult<Unit>
    fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog>
}

interface SourceAdapterRegistry {
    fun factory(adapterId: String): SourceAdapterFactory?
    fun describe(descriptor: SourceDescriptor): SourceDescriptor
    fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog>
}

class DefaultSourceAdapterRegistry(
    factories: Set<SourceAdapterFactory>,
) : SourceAdapterRegistry {
    private val factoriesById = factories.associateBy(SourceAdapterFactory::adapterId).also { byId ->
        require(byId.size == factories.size) { "Duplicate source adapter id" }
        require(byId.keys.none(String::isBlank)) { "Source adapter id must not be blank" }
    }

    override fun factory(adapterId: String): SourceAdapterFactory? = factoriesById[adapterId]

    override fun describe(descriptor: SourceDescriptor): SourceDescriptor {
        val factory = factory(descriptor.adapterId)
            ?: return descriptor.copy(enabled = false, capabilities = SourceCapabilities(canSearch = false))
        return when (factory.validate(descriptor.configVersion, descriptor.configJson)) {
            is ReadflowResult.Success -> descriptor.copy(
                capabilities = factory.capabilities(descriptor.configVersion, descriptor.configJson),
            )
            is ReadflowResult.Failure -> descriptor.copy(
                enabled = false,
                capabilities = SourceCapabilities(canSearch = false),
            )
        }
    }

    override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> {
        val factory = factory(descriptor.adapterId)
            ?: return ReadflowResult.Failure(
                ReadflowError.unsupported("未安装书源适配器：${descriptor.adapterId}"),
            )
        return when (val validation = factory.validate(descriptor.configVersion, descriptor.configJson)) {
            is ReadflowResult.Failure -> validation
            is ReadflowResult.Success -> factory.open(
                descriptor.copy(
                    capabilities = factory.capabilities(descriptor.configVersion, descriptor.configJson),
                ),
            )
        }
    }
}

interface SourceRegistry {
    fun observeSources(): Flow<List<SourceDescriptor>>
    suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog>

    suspend fun addUserSource(
        adapterId: String,
        name: String,
        configVersion: Int,
        configJson: String,
        credentials: SourceCredentials? = null,
    ): ReadflowResult<SourceDescriptor> =
        ReadflowResult.Failure(ReadflowError.unsupported("书源注册器不支持添加配置"))

    @Deprecated("Use adapterId/configJson")
    suspend fun addUserSource(
        kind: SourceKind,
        name: String,
        baseUrl: String,
    ): ReadflowResult<SourceDescriptor> = addUserSource(
        adapterId = kind.adapterId,
        name = name,
        configVersion = 1,
        configJson = legacyBaseUrlConfigJson(baseUrl),
        credentials = null,
    )

    suspend fun updateUserSource(
        sourceId: String,
        name: String,
        configVersion: Int,
        configJson: String,
        credentials: SourceCredentials? = null,
    ): ReadflowResult<SourceDescriptor> =
        ReadflowResult.Failure(ReadflowError.unsupported("书源注册器不支持编辑配置"))

    suspend fun sourceCredentials(sourceId: String): SourceCredentials? = null

    suspend fun clearSourceCredentials(sourceId: String): ReadflowResult<Unit> =
        ReadflowResult.Failure(ReadflowError.unsupported("书源注册器不支持重置凭据"))

    suspend fun removeUserSource(sourceId: String): ReadflowResult<Unit>

    /**
     * Import a versioned source-configuration envelope (JSON text).
     * Implementations must parse, validate against registered adapters, and avoid
     * duplicate sources for identical adapterId/configVersion/configJson when practical.
     */
    suspend fun importUserSourceConfig(rawJson: String): ReadflowResult<SourceDescriptor> =
        ReadflowResult.Failure(ReadflowError.unsupported("书源注册器不支持配置导入"))
}

const val BUILTIN_CALIBRE_SOURCE_ID = "calibre-builtin"

/** Compatibility encoder. Adapter-specific code parses and canonicalizes this object. */
fun legacyBaseUrlConfigJson(baseUrl: String): String =
    "{\"baseUrl\":\"${baseUrl.jsonEscaped()}\"}"

private fun String.jsonEscaped(): String = buildString(length) {
    for (char in this@jsonEscaped) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
