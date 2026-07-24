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
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.SourceRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistent registry. Every source, including migrated Calibre, is opened by adapter id. */
class DefaultSourceRegistry(
    private val settings: SettingsRepository,
    private val sourceConfigStore: SourceConfigStore,
    private val booksDir: File,
    private val credentialStore: SourceCredentialStore = NoOpSourceCredentialStore,
    private val calibreServiceDiscovery: CalibreServiceDiscovery? = null,
    private val calibreCatalogFactory: ((SourceDescriptor) -> OnlineBookCatalog)? = null,
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
    private val discoveryMutex = Mutex()
    private var discoveryAttempted = false
    private val adapters = sourceAdapters ?: compatibilityAdapterRegistry()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSources(): Flow<List<SourceDescriptor>> =
        settings.calibreBaseUrl.flatMapLatest { legacyUrl ->
            flow {
                ensureLegacyCalibreImported(legacyUrl)
                coroutineScope {
                    if (legacyUrl == null) {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            discoverLocalCalibreOnFirstRun(null)?.let {
                                ensureLegacyCalibreImported(it)
                            }
                        }
                    }
                    emitAll(
                        sourceConfigStore.observeUserSources().map { rows ->
                            rows.map { adapters.describe(it.toDescriptor()) }
                        },
                    )
                }
            }
        }

    override suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog> {
        ensureLegacyCalibreImported(settings.calibreBaseUrl.first())
        val stored = sourceConfigStore.getUserSource(sourceId)
            ?: return ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
        if (!stored.enabled) {
            return ReadflowResult.Failure(ReadflowError.unsupported("书源已禁用或适配器不可用"))
        }
        return if (stored.adapterId == SourceAdapterIds.CALIBRE) {
            withContext(Dispatchers.IO) { adapters.open(stored.toDescriptor()) }
        } else {
            adapters.open(stored.toDescriptor())
        }
    }

    override suspend fun addUserSource(
        adapterId: String,
        name: String,
        configVersion: Int,
        configJson: String,
        credentials: SourceCredentials?,
    ): ReadflowResult<SourceDescriptor> = importMutex.withLock {
        addUserSourceLocked(adapterId, name, configVersion, configJson, credentials)
    }

    private suspend fun addUserSourceLocked(
        adapterId: String,
        name: String,
        configVersion: Int,
        configJson: String,
        credentials: SourceCredentials? = null,
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
        try {
            sourceConfigStore.upsertUserSource(persisted)
            if (adapterId == SourceAdapterIds.CALIBRE && credentials != null) {
                withContext(Dispatchers.IO) {
                    credentialStore.put(id, calibreCredentialScopeForRequestUrl(baseUrl), credentials)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { sourceConfigStore.deleteUserSource(id) }
            runCatching { withContext(Dispatchers.IO) { credentialStore.remove(id) } }
            return ReadflowResult.Failure(ReadflowError.io(error.message ?: "保存书源失败"))
        }
        return ReadflowResult.Success(adapters.describe(persisted.toDescriptor()))
    }

    override suspend fun updateUserSource(
        sourceId: String,
        name: String,
        configVersion: Int,
        configJson: String,
        credentials: SourceCredentials?,
    ): ReadflowResult<SourceDescriptor> = importMutex.withLock {
        val existing = sourceConfigStore.getUserSource(sourceId)
            ?: return@withLock ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return@withLock ReadflowResult.Failure(ReadflowError.parse("请填写书源名称"))
        }
        val factory = adapters.factory(existing.adapterId)
            ?: return@withLock ReadflowResult.Failure(
                ReadflowError.unsupported("未安装书源适配器：${existing.adapterId}"),
            )
        when (val validation = factory.validate(configVersion, configJson)) {
            is ReadflowResult.Failure -> return@withLock validation
            is ReadflowResult.Success -> Unit
        }
        val updated = existing.copy(
            name = trimmedName,
            baseUrl = displayBaseUrl(existing.adapterId, configJson),
            configVersion = configVersion,
            configJson = configJson,
            updatedAt = System.currentTimeMillis(),
        )
        try {
            sourceConfigStore.upsertUserSource(updated)
            if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                settings.setCalibreBaseUrl(updated.baseUrl)
            }
            if (existing.adapterId == SourceAdapterIds.CALIBRE && credentials != null) {
                withContext(Dispatchers.IO) {
                    credentialStore.put(
                        sourceId,
                        calibreCredentialScopeForRequestUrl(updated.baseUrl),
                        credentials,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { sourceConfigStore.upsertUserSource(existing) }
            if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                runCatching { settings.setCalibreBaseUrl(existing.baseUrl) }
            }
            return@withLock ReadflowResult.Failure(ReadflowError.io(error.message ?: "更新书源失败"))
        }
        ReadflowResult.Success(adapters.describe(updated.toDescriptor()))
    }

    override suspend fun sourceCredentials(sourceId: String): SourceCredentials? {
        val source = sourceConfigStore.getUserSource(sourceId) ?: return null
        if (source.adapterId != SourceAdapterIds.CALIBRE) return null
        return withContext(Dispatchers.IO) {
            credentialStore.get(sourceId, calibreCredentialScopeForRequestUrl(source.baseUrl))
        }
    }

    override suspend fun clearSourceCredentials(sourceId: String): ReadflowResult<Unit> =
        importMutex.withLock {
            if (sourceConfigStore.getUserSource(sourceId) == null) {
                return@withLock ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
            }
            try {
                withContext(Dispatchers.IO) { credentialStore.remove(sourceId) }
                ReadflowResult.Success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ReadflowResult.Failure(ReadflowError.io(error.message ?: "重置书源凭据失败"))
            }
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
        return addUserSource(kind.adapterId, name, 1, configJson, credentials = null)
    }

    override suspend fun removeUserSource(sourceId: String): ReadflowResult<Unit> =
        importMutex.withLock {
            try {
                if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                    // Clearing the legacy value is the durable deletion marker. A later observer
                    // removes any fixed-id row or credential left by an interrupted deletion.
                    settings.setCalibreBaseUrl("")
                }
                withContext(Dispatchers.IO) { credentialStore.remove(sourceId) }
                sourceConfigStore.deleteUserSource(sourceId)
                ReadflowResult.Success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ReadflowResult.Failure(ReadflowError.io(error.message ?: "删除书源失败"))
            }
        }

    /**
     * Import a versioned source-configuration envelope.
     * Order: parse → schema/name → registered adapter → factory.validate →
     * canonicalize → dedup → persist. Concurrent imports are serialized.
     * Never executes scripts or code from the configuration payload.
     */
    override suspend fun importUserSourceConfig(rawJson: String): ReadflowResult<SourceDescriptor> =
        importMutex.withLock {
            val parsed = when (val parseResult = parseSourceConfigImportEnvelope(rawJson)) {
                is ReadflowResult.Failure -> return@withLock parseResult
                is ReadflowResult.Success -> parseResult.value
            }
            val factory = adapters.factory(parsed.adapterId)
                ?: return@withLock ReadflowResult.Failure(
                    ReadflowError.unsupported("未安装书源适配器：${parsed.adapterId}"),
                )
            when (val validation = factory.validate(parsed.configVersion, parsed.configJson)) {
                is ReadflowResult.Failure -> return@withLock validation
                is ReadflowResult.Success -> Unit
            }
            val canonicalConfig = canonicalizeImportedConfigJson(parsed.adapterId, parsed.configJson)
            val existing = sourceConfigStore.observeUserSources().first().firstOrNull { row ->
                row.adapterId == parsed.adapterId &&
                    row.configVersion == parsed.configVersion &&
                    canonicalizeImportedConfigJson(row.adapterId, row.configJson) == canonicalConfig
            }
            if (existing != null) {
                val reusable = if (existing.enabled) {
                    existing
                } else {
                    existing.copy(
                        baseUrl = displayBaseUrl(parsed.adapterId, canonicalConfig),
                        enabled = true,
                        configJson = canonicalConfig,
                        updatedAt = System.currentTimeMillis(),
                    ).also { sourceConfigStore.upsertUserSource(it) }
                }
                return@withLock ReadflowResult.Success(adapters.describe(reusable.toDescriptor()))
            }
            addUserSourceLocked(
                adapterId = parsed.adapterId,
                name = parsed.name,
                configVersion = parsed.configVersion,
                configJson = canonicalConfig,
            )
        }

    /** Document-picker entry: bound the read size before parsing. */
    suspend fun importUserSourceConfig(input: java.io.InputStream): ReadflowResult<SourceDescriptor> {
        val raw = when (val read = readBoundedSourceConfigBytes(input)) {
            is ReadflowResult.Failure -> return read
            is ReadflowResult.Success -> read.value
        }
        return importUserSourceConfig(raw)
    }

    private suspend fun ensureLegacyCalibreImported(rawUrl: String?) {
        if (rawUrl == null) return
        if (rawUrl.isBlank()) {
            importMutex.withLock {
                withContext(Dispatchers.IO) {
                    credentialStore.remove(BUILTIN_CALIBRE_SOURCE_ID)
                }
                sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.let {
                    sourceConfigStore.deleteUserSource(BUILTIN_CALIBRE_SOURCE_ID)
                }
            }
            return
        }
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

    private suspend fun discoverLocalCalibreOnFirstRun(configuredUrl: String?): String? {
        if (configuredUrl != null) return configuredUrl
        val discovery = calibreServiceDiscovery ?: return null
        return discoveryMutex.withLock {
            if (discoveryAttempted) return@withLock settings.calibreBaseUrl.first()
            val alreadyHasCalibre = sourceConfigStore.observeUserSources().first().any { source ->
                source.adapterId == SourceAdapterIds.CALIBRE
            }
            if (alreadyHasCalibre) {
                discoveryAttempted = true
                return@withLock null
            }
            val discoveryResult = discovery.discover()
            val found = discoveryResult as? CalibreDiscoveryResult.Found
            if (found == null) {
                discoveryAttempted = true
                return@withLock null
            }
            val normalized = runCatching { requireValidCalibreBaseUrl(found.baseUrl) }.getOrNull()
            if (normalized == null) {
                discoveryAttempted = true
                return@withLock null
            }
            val persistedUrl = importMutex.withLock persist@{
                val current = settings.calibreBaseUrl.first()
                if (current != null) return@persist current
                val calibreWasAdded = sourceConfigStore.observeUserSources().first().any { source ->
                    source.adapterId == SourceAdapterIds.CALIBRE
                }
                if (calibreWasAdded) return@persist null
                settings.setCalibreBaseUrl(normalized)
                normalized
            }
            discoveryAttempted = true
            persistedUrl
        }
    }

    private fun compatibilityAdapterRegistry(): SourceAdapterRegistry = DefaultSourceAdapterRegistry(
        setOf(
            calibreCatalogFactory?.let { catalogFactory ->
                val calibreFactory = CalibreSourceAdapterFactory(booksDir, credentialStore::get)
                DelegatingSourceAdapterFactory(
                    adapterId = SourceAdapterIds.CALIBRE,
                    capabilities = calibreFactory.capabilities(1, "{}"),
                    validate = calibreFactory::validate,
                ) { descriptor -> ReadflowResult.Success(catalogFactory(descriptor)) }
            } ?: CalibreSourceAdapterFactory(booksDir, credentialStore::get),
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
