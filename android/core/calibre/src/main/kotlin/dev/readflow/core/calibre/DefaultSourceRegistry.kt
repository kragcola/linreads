package dev.readflow.core.calibre

import dev.readflow.core.database.PersistedBookSource
import dev.readflow.core.database.SourceConfigStore
import dev.readflow.core.database.newUserSourceId
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.DefaultSourceAdapterRegistry
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.SourceAdapterFactory
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceAdapterRegistry
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.SourceRegistry
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistent registry. Every source, including migrated Calibre, is opened by adapter id. */
class DefaultSourceRegistry(
    private val settings: SettingsRepository,
    private val sourceConfigStore: SourceConfigStore,
    private val booksDir: File,
    private val calibreCatalogFactory: (SourceDescriptor) -> OnlineBookCatalog = { descriptor ->
        val config = descriptor.calibreConfig()
        CalibreOnlineCatalog(
            client = CalibreClient(config.baseUrl, libraryId = config.libraryId),
            booksDir = booksDir,
            descriptor = descriptor.copy(baseUrl = requireValidCalibreBaseUrl(config.baseUrl)),
        )
    },
    private val genericCatalogFactory: (SourceDescriptor) -> OnlineBookCatalog = { descriptor ->
        val wireFormat = if (descriptor.adapterId == SourceAdapterIds.OPDS) {
            GenericCatalogWireFormat.OPDS
        } else {
            GenericCatalogWireFormat.JSON
        }
        GenericHttpOnlineCatalog(descriptor = descriptor, booksDir = booksDir, wireFormat = wireFormat)
    },
    sourceAdapters: SourceAdapterRegistry? = null,
) : SourceRegistry {
    private val importMutex = Mutex()
    private val adapters = sourceAdapters ?: compatibilityAdapterRegistry()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSources(): Flow<List<SourceDescriptor>> =
        settings.calibreBaseUrl.flatMapLatest { legacyUrl ->
            flow {
                ensureLegacyCalibreImported(legacyUrl)
                emitAll(
                    sourceConfigStore.observeUserSources().map { rows ->
                        rows.map { adapters.describe(it.toDescriptor()) }
                    },
                )
            }
        }

    override suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog> {
        ensureLegacyCalibreImported(settings.calibreBaseUrl.first())
        val stored = sourceConfigStore.getUserSource(sourceId)
            ?: return ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
        if (!stored.enabled) {
            return ReadflowResult.Failure(ReadflowError.unsupported("书源已禁用或适配器不可用"))
        }
        return adapters.open(stored.toDescriptor())
    }

    override suspend fun addUserSource(
        adapterId: String,
        name: String,
        configVersion: Int,
        configJson: String,
    ): ReadflowResult<SourceDescriptor> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return ReadflowResult.Failure(ReadflowError.parse("请填写书源名称"))
        }
        val factory = adapters.factory(adapterId)
            ?: return ReadflowResult.Failure(ReadflowError.unsupported("未安装书源适配器：$adapterId"))
        when (val validation = factory.validate(configVersion, configJson)) {
            is ReadflowResult.Failure -> return validation
            is ReadflowResult.Success -> Unit
        }
        val id = newUserSourceId()
        val sortOrder = sourceConfigStore.nextSortOrder()
        val now = System.currentTimeMillis()
        val baseUrl = displayBaseUrl(adapterId, configJson)
        val persisted = PersistedBookSource(
            id = id,
            kind = legacyKind(adapterId),
            name = trimmedName,
            baseUrl = baseUrl,
            enabled = true,
            sortOrder = sortOrder,
            createdAt = now,
            adapterId = adapterId,
            configVersion = configVersion,
            configJson = configJson,
            updatedAt = now,
        )
        sourceConfigStore.upsertUserSource(persisted)
        return ReadflowResult.Success(adapters.describe(persisted.toDescriptor()))
    }

    @Suppress("DEPRECATION")
    override suspend fun addUserSource(
        kind: SourceKind,
        name: String,
        baseUrl: String,
    ): ReadflowResult<SourceDescriptor> {
        val configJson = when (kind.adapterId) {
            SourceAdapterIds.CALIBRE -> calibreSourceConfigJson(baseUrl)
            SourceAdapterIds.OPDS, SourceAdapterIds.JSON_HTTP -> httpCatalogSourceConfigJson(baseUrl)
            SourceAdapterIds.HTML_RULES_V1 -> return ReadflowResult.Failure(
                ReadflowError.parse("HTML 规则源需要完整规则配置"),
            )
            else -> return ReadflowResult.Failure(ReadflowError.unsupported("未安装书源适配器：${kind.adapterId}"))
        }
        return addUserSource(kind.adapterId, name, 1, configJson)
    }

    override suspend fun removeUserSource(sourceId: String): ReadflowResult<Unit> {
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            return ReadflowResult.Failure(ReadflowError.unsupported("迁移的 Calibre 源请在设置中修改"))
        }
        sourceConfigStore.deleteUserSource(sourceId)
        return ReadflowResult.Success(Unit)
    }

    private suspend fun ensureLegacyCalibreImported(rawUrl: String?) {
        if (rawUrl.isNullOrBlank()) return
        importMutex.withLock {
            val normalized = runCatching { requireValidCalibreBaseUrl(rawUrl) }.getOrNull() ?: return
            val existing = sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
            if (existing != null) {
                if (existing.adapterId == SourceAdapterIds.CALIBRE && existing.baseUrl != normalized) {
                    sourceConfigStore.upsertUserSource(
                        existing.copy(
                            baseUrl = normalized,
                            configVersion = 1,
                            configJson = calibreSourceConfigJson(normalized),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                return
            }
            val now = System.currentTimeMillis()
            sourceConfigStore.upsertUserSource(
                PersistedBookSource(
                    id = BUILTIN_CALIBRE_SOURCE_ID,
                    kind = "CALIBRE",
                    name = "Calibre",
                    baseUrl = normalized,
                    enabled = true,
                    sortOrder = sourceConfigStore.nextSortOrder(),
                    createdAt = now,
                    isBuiltin = true,
                    adapterId = SourceAdapterIds.CALIBRE,
                    configVersion = 1,
                    configJson = calibreSourceConfigJson(normalized),
                    updatedAt = now,
                ),
            )
        }
    }

    private fun compatibilityAdapterRegistry(): SourceAdapterRegistry = DefaultSourceAdapterRegistry(
        setOf(
            DelegatingSourceAdapterFactory(
                adapterId = SourceAdapterIds.CALIBRE,
                capabilities = CalibreSourceAdapterFactory(booksDir).capabilities(1, "{}"),
                validate = CalibreSourceAdapterFactory(booksDir)::validate,
            ) { descriptor ->
                ReadflowResult.Success(calibreCatalogFactory(descriptor))
            },
            DelegatingSourceAdapterFactory(
                adapterId = SourceAdapterIds.OPDS,
                capabilities = OpdsSourceAdapterFactory(booksDir).capabilities(1, "{}"),
                validate = OpdsSourceAdapterFactory(booksDir)::validate,
            ) { descriptor -> ReadflowResult.Success(genericCatalogFactory(descriptor)) },
            DelegatingSourceAdapterFactory(
                adapterId = SourceAdapterIds.JSON_HTTP,
                capabilities = JsonHttpSourceAdapterFactory(booksDir).capabilities(1, "{}"),
                validate = JsonHttpSourceAdapterFactory(booksDir)::validate,
            ) { descriptor -> ReadflowResult.Success(genericCatalogFactory(descriptor)) },
            HtmlRulesV1SourceAdapterFactory(booksDir),
        ),
    )
}

private class DelegatingSourceAdapterFactory(
    override val adapterId: String,
    private val capabilities: SourceCapabilities,
    private val validate: (Int, String) -> ReadflowResult<Unit>,
    private val opener: (SourceDescriptor) -> ReadflowResult<OnlineBookCatalog>,
) : SourceAdapterFactory {
    override val latestConfigVersion = 1
    override fun capabilities(configVersion: Int, configJson: String) = capabilities
    override fun validate(configVersion: Int, configJson: String) = validate.invoke(configVersion, configJson)
    override fun open(descriptor: SourceDescriptor) = opener(descriptor)
}

private fun PersistedBookSource.toDescriptor(): SourceDescriptor = SourceDescriptor(
    id = id,
    adapterId = adapterId,
    name = name,
    configVersion = configVersion,
    configJson = configJson,
    baseUrl = baseUrl,
    enabled = enabled,
    isBuiltin = id == BUILTIN_CALIBRE_SOURCE_ID,
)

private fun legacyKind(adapterId: String): String = when (adapterId) {
    SourceAdapterIds.CALIBRE -> "CALIBRE"
    SourceAdapterIds.OPDS -> "OPDS"
    SourceAdapterIds.JSON_HTTP -> "JSON_HTTP"
    SourceAdapterIds.HTML_RULES_V1 -> "HTML_RULES_V1"
    else -> adapterId
}
