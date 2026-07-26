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
import kotlinx.coroutines.NonCancellable
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
    private val calibreEndpointProbe: CalibreEndpointProbe? = null,
    private val networkSnapshotProvider: CalibreNetworkSnapshotProvider =
        UnknownCalibreNetworkSnapshotProvider,
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
) : SourceRegistry, VerifiedCalibreEndpointSink {
    private val importMutex = Mutex()
    private val discoveryMutex = Mutex()
    private var discoveryAttempted = false
    private val adapters = sourceAdapters ?: compatibilityAdapterRegistry()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSources(): Flow<List<SourceDescriptor>> =
        settings.calibreBaseUrl.flatMapLatest { legacyUrl ->
            flow {
                recoverPendingCredentialTransactions()
                ensureLegacyCalibreImported(legacyUrl)
                coroutineScope {
                    if (legacyUrl == null) {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            discoverLocalCalibreOnFirstRun(null)
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
        val resolvedSource = when (val result = resolveCalibreEndpoint(stored)) {
            is ReadflowResult.Success -> result.value
            is ReadflowResult.Failure -> return result
        }
        if (!resolvedSource.enabled) {
            return ReadflowResult.Failure(ReadflowError.unsupported("书源已禁用或适配器不可用"))
        }
        return if (resolvedSource.adapterId == SourceAdapterIds.CALIBRE) {
            withContext(Dispatchers.IO) { adapters.open(resolvedSource.toDescriptor()) }
        } else {
            adapters.open(resolvedSource.toDescriptor())
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
        val canonicalConfig = canonicalizeImportedConfigJson(adapterId, configJson)
        val factory = adapters.factory(adapterId)
            ?: return ReadflowResult.Failure(ReadflowError.unsupported("未安装书源适配器：$adapterId"))
        when (val validation = factory.validate(configVersion, canonicalConfig)) {
            is ReadflowResult.Failure -> return validation
            is ReadflowResult.Success -> Unit
        }
        val existing = sourceConfigStore.observeUserSources().first().firstOrNull { row ->
            row.adapterId == adapterId &&
                row.configVersion == configVersion &&
                canonicalizeImportedConfigJson(row.adapterId, row.configJson) == canonicalConfig
        }
        if (existing != null) {
            val pending = credentialMutationFor(
                adapterId = adapterId,
                baseUrl = existing.baseUrl,
                credentials = credentials,
            )
            when (val recovery = reconcileCredentialBindingLocked(existing.id)) {
                is ReadflowResult.Failure -> {
                    if (pending == null) return recovery
                    when (val purged = purgeCredentialEntryIfUnreadableLocked(existing.id)) {
                        is ReadflowResult.Failure -> return purged
                        is ReadflowResult.Success -> if (!purged.value) return recovery
                    }
                }
                is ReadflowResult.Success -> Unit
            }
            if (pending != null) {
                when (val prepared = prepareCredentialMutationLocked(existing.id, pending)) {
                    is ReadflowResult.Failure -> return prepared
                    is ReadflowResult.Success -> Unit
                }
                when (val reconciled = reconcileCredentialBindingLocked(existing.id)) {
                    is ReadflowResult.Failure -> return reconciled
                    is ReadflowResult.Success -> Unit
                }
            }
            return ReadflowResult.Success(adapters.describe(existing.toDescriptor()))
        }
        val id = newUserSourceId()
        val sortOrder = sourceConfigStore.nextSortOrder()
        val now = System.currentTimeMillis()
        val baseUrl = displayBaseUrl(adapterId, canonicalConfig)
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
            configJson = canonicalConfig,
            updatedAt = now,
        )
        val pending = credentialMutationFor(adapterId, baseUrl, credentials)
        if (pending != null) {
            when (val prepared = prepareCredentialMutationLocked(id, pending)) {
                is ReadflowResult.Failure -> return prepared
                is ReadflowResult.Success -> Unit
            }
        }
        val write = writeDescriptorAndReadBackLocked(persisted)
        val reconciled = reconcileAfterDescriptorMutationLocked(id)
        write.throwCancellationAfterCleanup()
        if (!write.matches(persisted)) {
            return descriptorMutationFailure("保存书源失败", write)
        }
        if (reconciled is ReadflowResult.Failure) return reconciled
        return ReadflowResult.Success(adapters.describe(checkNotNull(write.observed).toDescriptor()))
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
        val canonicalConfig = canonicalizeImportedConfigJson(existing.adapterId, configJson)
        val factory = adapters.factory(existing.adapterId)
            ?: return@withLock ReadflowResult.Failure(
                ReadflowError.unsupported("未安装书源适配器：${existing.adapterId}"),
            )
        when (val validation = factory.validate(configVersion, canonicalConfig)) {
            is ReadflowResult.Failure -> return@withLock validation
            is ReadflowResult.Success -> Unit
        }
        val updated = existing.copy(
            name = trimmedName,
            baseUrl = displayBaseUrl(existing.adapterId, canonicalConfig),
            configVersion = configVersion,
            configJson = canonicalConfig,
            updatedAt = System.currentTimeMillis(),
        )
        val pending = credentialMutationFor(existing.adapterId, updated.baseUrl, credentials)
        when (val recovery = reconcileCredentialBindingLocked(sourceId)) {
            is ReadflowResult.Failure -> {
                if (pending == null) return@withLock recovery
                when (val purged = purgeCredentialEntryIfUnreadableLocked(sourceId)) {
                    is ReadflowResult.Failure -> return@withLock purged
                    is ReadflowResult.Success -> if (!purged.value) return@withLock recovery
                }
            }
            is ReadflowResult.Success -> Unit
        }
        if (pending != null) {
            when (val prepared = prepareCredentialMutationLocked(sourceId, pending)) {
                is ReadflowResult.Failure -> return@withLock prepared
                is ReadflowResult.Success -> Unit
            }
        }
        val write = writeDescriptorAndReadBackLocked(updated)
        val reconciled = reconcileAfterDescriptorMutationLocked(sourceId)
        write.throwCancellationAfterCleanup()
        if (!write.matches(updated)) {
            return@withLock descriptorMutationFailure("更新书源失败", write)
        }
        if (reconciled is ReadflowResult.Failure) return@withLock reconciled
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            mirrorBuiltinCalibreSettingLocked(checkNotNull(write.observed).baseUrl)
        }
        ReadflowResult.Success(adapters.describe(checkNotNull(write.observed).toDescriptor()))
    }

    override suspend fun sourceCredentials(sourceId: String): SourceCredentials? = importMutex.withLock {
        if (reconcileCredentialBindingLocked(sourceId) is ReadflowResult.Failure) return@withLock null
        val source = sourceConfigStore.getUserSource(sourceId) ?: return@withLock null
        if (source.adapterId != SourceAdapterIds.CALIBRE) return@withLock null
        if (
            requiresActiveVpnForCalibreHttp(source.baseUrl) &&
            !canUseStoredCalibreCredentials(
                requestUrl = source.baseUrl,
                network = networkSnapshotProvider.snapshot(),
            )
        ) {
            throw CalibreVpnRequiredException()
        }
        withContext(Dispatchers.IO) {
            credentialStore.get(sourceId, calibreCredentialScopeForRequestUrl(source.baseUrl))
        }
    }

    override suspend fun clearSourceCredentials(sourceId: String): ReadflowResult<Unit> =
        importMutex.withLock {
            val source = sourceConfigStore.getUserSource(sourceId)
            if (source == null) {
                return@withLock ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
            }
            if (source.adapterId != SourceAdapterIds.CALIBRE) {
                return@withLock ReadflowResult.Success(Unit)
            }
            when (val recovery = reconcileCredentialBindingLocked(sourceId)) {
                is ReadflowResult.Failure -> {
                    when (val purged = purgeCredentialEntryIfUnreadableLocked(sourceId)) {
                        is ReadflowResult.Failure -> return@withLock purged
                        is ReadflowResult.Success -> {
                            return@withLock if (purged.value) ReadflowResult.Success(Unit) else recovery
                        }
                    }
                }
                is ReadflowResult.Success -> Unit
            }
            val pending = PendingCredentialMutation.Clear(
                calibreCredentialScopeForRequestUrl(source.baseUrl),
            )
            when (val prepared = prepareCredentialMutationLocked(sourceId, pending)) {
                is ReadflowResult.Failure -> return@withLock prepared
                is ReadflowResult.Success -> Unit
            }
            reconcileCredentialBindingLocked(sourceId)
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
            if (sourceConfigStore.getUserSource(sourceId) == null) {
                // Descriptor absence is the committed delete state. Credential cleanup is
                // recoverable and must not make an idempotent retry look unsuccessful.
                if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                    try {
                        settings.setCalibreBaseUrl("")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        return@withLock credentialMutationFailure("保存 Calibre 删除状态失败", error)
                    }
                }
                when (val recovery = reconcileCredentialBindingLocked(sourceId)) {
                    is ReadflowResult.Failure -> {
                        when (val purged = purgeCredentialEntryIfUnreadableLocked(sourceId)) {
                            is ReadflowResult.Failure -> return@withLock purged
                            is ReadflowResult.Success -> Unit
                        }
                    }
                    is ReadflowResult.Success -> Unit
                }
                return@withLock ReadflowResult.Success(Unit)
            }
            when (val recovery = reconcileCredentialBindingLocked(sourceId)) {
                is ReadflowResult.Failure -> {
                    when (val purged = purgeCredentialEntryIfUnreadableLocked(sourceId)) {
                        is ReadflowResult.Failure -> return@withLock purged
                        is ReadflowResult.Success -> if (!purged.value) return@withLock recovery
                    }
                }
                is ReadflowResult.Success -> Unit
            }
            when (
                val prepared = prepareCredentialMutationLocked(
                    sourceId,
                    PendingCredentialMutation.RemoveSource,
                )
            ) {
                is ReadflowResult.Failure -> return@withLock prepared
                is ReadflowResult.Success -> Unit
            }
            val deletion = deleteDescriptorAndReadBackLocked(sourceId)
            val deleteMarkerFailure = if (
                sourceId == BUILTIN_CALIBRE_SOURCE_ID &&
                deletion.observed == null &&
                deletion.readError == null
            ) {
                try {
                    if (deletion.writeError is CancellationException) {
                        withContext(NonCancellable) { settings.setCalibreBaseUrl("") }
                    } else {
                        settings.setCalibreBaseUrl("")
                    }
                    null
                } catch (error: Throwable) {
                    credentialMutationFailure("保存 Calibre 删除状态失败", error)
                }
            } else {
                null
            }
            val reconciled = reconcileAfterDescriptorMutationLocked(sourceId)
            deletion.throwCancellationAfterCleanup()
            if (deletion.observed != null || deletion.readError != null) {
                return@withLock descriptorMutationFailure("删除书源失败", deletion)
            }
            if (deleteMarkerFailure != null) return@withLock deleteMarkerFailure
            if (reconciled is ReadflowResult.Failure) return@withLock reconciled
            ReadflowResult.Success(Unit)
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

    private suspend fun recoverPendingCredentialTransactions(): ReadflowResult<Unit> =
        if (credentialStore === NoOpSourceCredentialStore) {
            ReadflowResult.Success(Unit)
        } else importMutex.withLock {
            val sourceIds = try {
                withContext(Dispatchers.IO) { credentialStore.sourceIdsWithPending() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return@withLock credentialMutationFailure("恢复书源凭据失败", error)
            }
            var firstFailure: ReadflowResult.Failure? = null
            sourceIds.forEach { sourceId ->
                val result = reconcileCredentialBindingLocked(sourceId)
                if (result is ReadflowResult.Failure && firstFailure == null) {
                    firstFailure = result
                }
            }
            firstFailure ?: ReadflowResult.Success(Unit)
        }

    private suspend fun prepareCredentialMutationLocked(
        sourceId: String,
        pending: PendingCredentialMutation,
    ): ReadflowResult<Unit> {
        repeat(MAX_CREDENTIAL_MUTATION_ATTEMPTS) {
            val current = try {
                withContext(Dispatchers.IO) { credentialStore.snapshot(sourceId) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return credentialMutationFailure("读取书源凭据状态失败", error)
            }
            val outcome = try {
                withContext(Dispatchers.IO) {
                    credentialStore.prepare(
                        sourceId = sourceId,
                        expectedRevision = current?.revision ?: 0L,
                        pending = pending,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val observed = runCatching {
                    withContext(Dispatchers.IO) { credentialStore.snapshot(sourceId) }
                }.getOrNull()
                CredentialMutationOutcome.Indeterminate(observed, error)
            }
            when (outcome) {
                is CredentialMutationOutcome.Committed -> return ReadflowResult.Success(Unit)
                is CredentialMutationOutcome.Conflict -> {
                    when (val recovery = reconcileCredentialBindingLocked(sourceId)) {
                        is ReadflowResult.Failure -> return recovery
                        is ReadflowResult.Success -> Unit
                    }
                }
                is CredentialMutationOutcome.Failed -> {
                    return credentialMutationFailure("准备书源凭据失败", outcome.cause)
                }
                is CredentialMutationOutcome.Indeterminate -> {
                    withContext(NonCancellable) {
                        reconcileCredentialBindingLocked(sourceId)
                    }
                    return credentialMutationFailure("准备书源凭据结果不确定，请重试", outcome.cause)
                }
            }
        }
        return ReadflowResult.Failure(ReadflowError.io("书源凭据同时发生修改，请重试"))
    }

    private suspend fun reconcileCredentialBindingLocked(
        sourceId: String,
    ): ReadflowResult<Unit> {
        val source = try {
            sourceConfigStore.getUserSource(sourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return credentialMutationFailure("读取书源状态失败", error)
        }
        val binding = when {
            source == null -> DescriptorBinding.Absent
            source.adapterId == SourceAdapterIds.CALIBRE -> DescriptorBinding.Calibre(
                calibreCredentialScopeForRequestUrl(source.baseUrl),
            )
            else -> DescriptorBinding.OtherAdapter
        }
        val snapshot = try {
            withContext(Dispatchers.IO) { credentialStore.snapshot(sourceId) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return credentialMutationFailure("读取书源凭据状态失败", error)
        }
        if (
            sourceId == BUILTIN_CALIBRE_SOURCE_ID &&
            binding == DescriptorBinding.Absent &&
            snapshot?.pending == PendingCredentialMutation.RemoveSource
        ) {
            try {
                settings.setCalibreBaseUrl("")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return credentialMutationFailure("保存 Calibre 删除状态失败", error)
            }
        }
        var lastIndeterminate: CredentialMutationOutcome.Indeterminate? = null
        repeat(MAX_CREDENTIAL_MUTATION_ATTEMPTS) {
            val outcome = try {
                withContext(Dispatchers.IO) { credentialStore.reconcile(sourceId, binding) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val observed = runCatching {
                    withContext(Dispatchers.IO) { credentialStore.snapshot(sourceId) }
                }.getOrNull()
                CredentialMutationOutcome.Indeterminate(observed, error)
            }
            when (outcome) {
                is CredentialMutationOutcome.Committed -> return ReadflowResult.Success(Unit)
                is CredentialMutationOutcome.Conflict -> Unit
                is CredentialMutationOutcome.Failed -> {
                    return credentialMutationFailure("收敛书源凭据失败", outcome.cause)
                }
                is CredentialMutationOutcome.Indeterminate -> lastIndeterminate = outcome
            }
        }
        return credentialMutationFailure(
            "收敛书源凭据结果不确定，请重试",
            lastIndeterminate?.cause ?: IllegalStateException("Credential reconciliation conflict"),
        )
    }

    private suspend fun purgeCredentialEntryLocked(sourceId: String): ReadflowResult<Unit> =
        try {
            withContext(Dispatchers.IO) { credentialStore.remove(sourceId) }
            ReadflowResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            credentialMutationFailure("清理损坏的书源凭据失败", error)
        }

    private suspend fun purgeCredentialEntryIfUnreadableLocked(
        sourceId: String,
    ): ReadflowResult<Boolean> {
        try {
            withContext(Dispatchers.IO) { credentialStore.snapshot(sourceId) }
            return ReadflowResult.Success(false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return when (val purged = purgeCredentialEntryLocked(sourceId)) {
                is ReadflowResult.Failure -> purged
                is ReadflowResult.Success -> ReadflowResult.Success(true)
            }
        }
    }

    private fun credentialMutationFor(
        adapterId: String,
        baseUrl: String,
        credentials: SourceCredentials?,
    ): PendingCredentialMutation? {
        if (adapterId != SourceAdapterIds.CALIBRE || credentials == null) return null
        val targetScope = calibreCredentialScopeForRequestUrl(baseUrl)
        return if (credentials.isEmpty) {
            PendingCredentialMutation.Clear(targetScope)
        } else {
            PendingCredentialMutation.Activate(
                CredentialGrant(scopes = setOf(targetScope), credentials = credentials),
            )
        }
    }

    private suspend fun writeDescriptorAndReadBackLocked(
        target: PersistedBookSource,
    ): DescriptorMutationObservation {
        var writeError: Throwable? = null
        try {
            sourceConfigStore.upsertUserSource(target)
        } catch (error: Throwable) {
            writeError = error
        }
        return readDescriptorAfterMutationLocked(target.id, writeError)
    }

    private suspend fun deleteDescriptorAndReadBackLocked(
        sourceId: String,
    ): DescriptorMutationObservation {
        var writeError: Throwable? = null
        try {
            sourceConfigStore.deleteUserSource(sourceId)
        } catch (error: Throwable) {
            writeError = error
        }
        return readDescriptorAfterMutationLocked(sourceId, writeError)
    }

    private suspend fun readDescriptorAfterMutationLocked(
        sourceId: String,
        writeError: Throwable?,
    ): DescriptorMutationObservation = withContext(NonCancellable) {
        try {
            DescriptorMutationObservation(
                observed = sourceConfigStore.getUserSource(sourceId),
                writeError = writeError,
                readError = null,
            )
        } catch (error: Throwable) {
            DescriptorMutationObservation(
                observed = null,
                writeError = writeError,
                readError = error,
            )
        }
    }

    private suspend fun reconcileAfterDescriptorMutationLocked(
        sourceId: String,
    ): ReadflowResult<Unit> = withContext(NonCancellable) {
        reconcileCredentialBindingLocked(sourceId)
    }

    private suspend fun mirrorBuiltinCalibreSettingLocked(baseUrl: String) {
        val current = settings.calibreBaseUrl.first()
        if (current == baseUrl) return
        try {
            settings.setCalibreBaseUrl(baseUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Room is authoritative. observeSources repairs this compatibility mirror later.
        }
    }

    private fun credentialMutationFailure(
        prefix: String,
        error: Throwable,
    ): ReadflowResult.Failure = ReadflowResult.Failure(
        ReadflowError.io(error.message?.let { "$prefix：$it" } ?: prefix),
    )

    private fun descriptorMutationFailure(
        fallbackMessage: String,
        mutation: DescriptorMutationObservation,
    ): ReadflowResult.Failure {
        val error = mutation.readError ?: mutation.writeError
        return ReadflowResult.Failure(
            ReadflowError.io(error?.message ?: fallbackMessage),
        )
    }

    private suspend fun ensureLegacyCalibreImported(rawUrl: String?) {
        if (rawUrl == null) {
            val observed = sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
                ?.takeIf { it.adapterId == SourceAdapterIds.CALIBRE }
                ?: return
            importMutex.withLock {
                val current = sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
                if (current?.adapterId == SourceAdapterIds.CALIBRE &&
                    settings.calibreBaseUrl.first() == null
                ) {
                    mirrorBuiltinCalibreSettingLocked(current.baseUrl)
                }
            }
            return
        }
        if (rawUrl.isBlank()) {
            importMutex.withLock {
                val existing = sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
                if (existing != null) {
                    val removal = PendingCredentialMutation.RemoveSource
                    when (prepareCredentialMutationLocked(BUILTIN_CALIBRE_SOURCE_ID, removal)) {
                        is ReadflowResult.Failure -> {
                            when (
                                val purged = purgeCredentialEntryIfUnreadableLocked(
                                    BUILTIN_CALIBRE_SOURCE_ID,
                                )
                            ) {
                                is ReadflowResult.Failure -> return@withLock
                                is ReadflowResult.Success -> if (!purged.value) return@withLock
                            }
                            if (
                                prepareCredentialMutationLocked(
                                    BUILTIN_CALIBRE_SOURCE_ID,
                                    removal,
                                ) is ReadflowResult.Failure
                            ) {
                                return@withLock
                            }
                        }
                        is ReadflowResult.Success -> Unit
                    }
                    val deletion = deleteDescriptorAndReadBackLocked(BUILTIN_CALIBRE_SOURCE_ID)
                    reconcileAfterDescriptorMutationLocked(BUILTIN_CALIBRE_SOURCE_ID)
                    deletion.throwCancellationAfterCleanup()
                } else {
                    if (
                        reconcileCredentialBindingLocked(BUILTIN_CALIBRE_SOURCE_ID) is ReadflowResult.Failure
                    ) {
                        purgeCredentialEntryIfUnreadableLocked(BUILTIN_CALIBRE_SOURCE_ID)
                    }
                }
            }
            return
        }
        importMutex.withLock {
            val existing = sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
            if (existing != null) {
                if (existing.adapterId == SourceAdapterIds.CALIBRE) {
                    // The legacy preference is only an import source. Once the structured
                    // builtin exists it is authoritative; otherwise a stale string could reset
                    // libraryId, detach credential scope, and replace an endpoint before probe.
                    mirrorBuiltinCalibreSettingLocked(existing.baseUrl)
                }
                return
            }
            val pendingRemoval = try {
                withContext(Dispatchers.IO) {
                    credentialStore.snapshot(BUILTIN_CALIBRE_SOURCE_ID)?.pending
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // An unreadable journal may contain a committed removal intent. Importing the
                // legacy URL in that state could resurrect a source the user already deleted.
                return@withLock
            } == PendingCredentialMutation.RemoveSource
            if (pendingRemoval) return@withLock
            val normalized = runCatching {
                canonicalizeTailscaleServeCalibreUrl(rawUrl)
            }.getOrNull() ?: return
            val now = System.currentTimeMillis()
            val imported = PersistedBookSource(
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
            )
            val write = writeDescriptorAndReadBackLocked(imported)
            reconcileAfterDescriptorMutationLocked(BUILTIN_CALIBRE_SOURCE_ID)
            write.throwCancellationAfterCleanup()
            if (write.matches(imported)) {
                mirrorBuiltinCalibreSettingLocked(normalized)
            }
        }
    }

    override suspend fun persistVerifiedCalibreEndpoint(
        baseUrl: String,
    ): ReadflowResult<Unit> = importMutex.withLock {
        val verified = runCatching { requireValidCalibreBaseUrl(baseUrl) }.getOrElse { error ->
            return@withLock ReadflowResult.Failure(
                ReadflowError.parse(error.message ?: "Calibre 地址无效"),
            )
        }
        when (val recovery = reconcileCredentialBindingLocked(BUILTIN_CALIBRE_SOURCE_ID)) {
            is ReadflowResult.Failure -> return@withLock recovery
            is ReadflowResult.Success -> Unit
        }
        val current = sourceConfigStore.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
        if (current == null) {
            return@withLock when (val created = createVerifiedBuiltinCalibreLocked(verified)) {
                is ReadflowResult.Success -> ReadflowResult.Success(Unit)
                is ReadflowResult.Failure -> created
            }
        }
        if (current.adapterId != SourceAdapterIds.CALIBRE) {
            return@withLock ReadflowResult.Failure(
                ReadflowError.unsupported("内置 Calibre 书源标识已被其他适配器占用"),
            )
        }
        val credentials = withContext(Dispatchers.IO) {
            credentialStore.get(current.id, calibreCredentialScopeForRequestUrl(current.baseUrl))
        }
        when (
            val updated = persistVerifiedCalibreEndpointLocked(
                source = current,
                verifiedBaseUrl = verified,
                credentials = credentials,
            )
        ) {
            is ReadflowResult.Success -> ReadflowResult.Success(Unit)
            is ReadflowResult.Failure -> updated
        }
    }

    private suspend fun createVerifiedBuiltinCalibreLocked(
        verifiedBaseUrl: String,
    ): ReadflowResult<PersistedBookSource> {
        val now = System.currentTimeMillis()
        val created = PersistedBookSource(
            id = BUILTIN_CALIBRE_SOURCE_ID,
            kind = "CALIBRE",
            name = "Calibre",
            baseUrl = verifiedBaseUrl,
            enabled = true,
            sortOrder = sourceConfigStore.nextSortOrder(),
            createdAt = now,
            isBuiltin = true,
            adapterId = SourceAdapterIds.CALIBRE,
            configVersion = 1,
            configJson = calibreSourceConfigJson(verifiedBaseUrl),
            updatedAt = now,
        )
        val write = writeDescriptorAndReadBackLocked(created)
        val reconciled = withContext(NonCancellable) {
            val result = reconcileCredentialBindingLocked(BUILTIN_CALIBRE_SOURCE_ID)
            if (write.matches(created)) {
                mirrorBuiltinCalibreSettingLocked(verifiedBaseUrl)
            }
            result
        }
        write.throwCancellationAfterCleanup()
        if (!write.matches(created)) {
            return descriptorMutationFailure("保存已验证 Calibre 地址失败", write)
        }
        if (reconciled is ReadflowResult.Failure) return reconciled
        return ReadflowResult.Success(checkNotNull(write.observed))
    }

    private suspend fun resolveCalibreEndpoint(
        source: PersistedBookSource,
    ): ReadflowResult<PersistedBookSource> = importMutex.withLock {
        val current = sourceConfigStore.getUserSource(source.id)
            ?: return@withLock ReadflowResult.Failure(ReadflowError.notFound("source", source.id))
        if (current.adapterId != SourceAdapterIds.CALIBRE) return@withLock ReadflowResult.Success(current)
        when (val recovery = reconcileCredentialBindingLocked(current.id)) {
            is ReadflowResult.Failure -> return@withLock recovery
            is ReadflowResult.Success -> Unit
        }
        if (calibreEndpointCandidates(current.baseUrl).size <= 1) {
            return@withLock ReadflowResult.Success(current)
        }
        val probe = calibreEndpointProbe ?: return@withLock ReadflowResult.Success(current)

        // Credential reads, endpoint verification, and the resulting migration are one transaction.
        // Otherwise a credential cleared while the probe is suspended could be restored from a
        // stale snapshot when the fallback endpoint is persisted.
        val credentials = withContext(Dispatchers.IO) {
            credentialStore.get(current.id, calibreCredentialScopeForRequestUrl(current.baseUrl))
        }
        when (val result = probe.probe(current.baseUrl, credentials)) {
            is CalibreProbeResult.Success -> {
                if (result.baseUrl == current.baseUrl) {
                    ReadflowResult.Success(current)
                } else {
                    persistVerifiedCalibreEndpointLocked(current, result.baseUrl, credentials)
                }
            }
            is CalibreProbeResult.AuthenticationRequired -> ReadflowResult.Failure(
                ReadflowError(
                    kind = ReadflowError.Kind.AUTH,
                    message = "Calibre 服务器需要认证，请在当前书源设置中填写用户名和密码",
                ),
            )
            is CalibreProbeResult.Failure -> ReadflowResult.Failure(
                ReadflowError.network(
                    code = null,
                    message = "${result.message}。${result.nextStep}",
                ),
            )
        }
    }

    private suspend fun persistVerifiedCalibreEndpointLocked(
        source: PersistedBookSource,
        verifiedBaseUrl: String,
        credentials: SourceCredentials?,
    ): ReadflowResult<PersistedBookSource> {
        val current = sourceConfigStore.getUserSource(source.id)
            ?: return ReadflowResult.Failure(ReadflowError.notFound("source", source.id))
        if (current.adapterId != SourceAdapterIds.CALIBRE || current.baseUrl != source.baseUrl) {
            reconcileCredentialBindingLocked(current.id)
            return ReadflowResult.Success(current)
        }
        val verified = runCatching { requireValidCalibreBaseUrl(verifiedBaseUrl) }.getOrElse { error ->
            return ReadflowResult.Failure(ReadflowError.parse(error.message ?: "Calibre 地址无效"))
        }
        val updated = current.copy(
            baseUrl = verified,
            configJson = calibreSourceConfigJson(
                verified,
                current.toDescriptor().calibreConfig().libraryId,
            ),
            updatedAt = System.currentTimeMillis(),
        )
        val newScope = calibreCredentialScopeForRequestUrl(updated.baseUrl)
        val hasCredentials = credentials != null && !credentials.isEmpty
        val credentialTransition = calibreCredentialTransition(
            currentUrl = current.baseUrl,
            verifiedUrl = updated.baseUrl,
            network = networkSnapshotProvider.snapshot(),
        )
        val pending = when {
            !hasCredentials || credentialTransition == CalibreCredentialTransition.UNCHANGED -> null
            credentialTransition == CalibreCredentialTransition.MIGRATE_TRUSTED_FALLBACK -> {
                PendingCredentialMutation.Activate(
                    CredentialGrant(setOf(newScope), checkNotNull(credentials)),
                )
            }
            else -> PendingCredentialMutation.Clear(newScope)
        }
        if (pending != null) {
            when (val prepared = prepareCredentialMutationLocked(current.id, pending)) {
                is ReadflowResult.Failure -> return prepared
                is ReadflowResult.Success -> Unit
            }
        }
        val write = writeDescriptorAndReadBackLocked(updated)
        val reconciled = reconcileAfterDescriptorMutationLocked(current.id)
        write.throwCancellationAfterCleanup()
        if (!write.matches(updated)) {
            return descriptorMutationFailure("保存已验证 Calibre 地址失败", write)
        }
        if (reconciled is ReadflowResult.Failure) return reconciled
        if (updated.id == BUILTIN_CALIBRE_SOURCE_ID) {
            mirrorBuiltinCalibreSettingLocked(updated.baseUrl)
        }
        return ReadflowResult.Success(checkNotNull(write.observed))
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
                when (createVerifiedBuiltinCalibreLocked(normalized)) {
                    is ReadflowResult.Success -> normalized
                    is ReadflowResult.Failure -> null
                }
            }
            discoveryAttempted = true
            persistedUrl
        }
    }

    private fun compatibilityAdapterRegistry(): SourceAdapterRegistry = DefaultSourceAdapterRegistry(
        setOf(
            calibreCatalogFactory?.let { catalogFactory ->
                val calibreFactory = CalibreSourceAdapterFactory(
                    booksDir,
                    credentialStore::get,
                    networkSnapshotProvider,
                )
                DelegatingSourceAdapterFactory(
                    adapterId = SourceAdapterIds.CALIBRE,
                    capabilities = calibreFactory.capabilities(1, "{}"),
                    validate = calibreFactory::validate,
                ) { descriptor -> ReadflowResult.Success(catalogFactory(descriptor)) }
            } ?: CalibreSourceAdapterFactory(
                booksDir,
                credentialStore::get,
                networkSnapshotProvider,
            ),
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

private const val MAX_CREDENTIAL_MUTATION_ATTEMPTS = 2

private data class DescriptorMutationObservation(
    val observed: PersistedBookSource?,
    val writeError: Throwable?,
    val readError: Throwable?,
) {
    fun matches(target: PersistedBookSource): Boolean = observed?.let { current ->
        current.id == target.id &&
            current.kind == target.kind &&
            current.name == target.name &&
            current.baseUrl == target.baseUrl &&
            current.enabled == target.enabled &&
            current.adapterId == target.adapterId &&
            current.configVersion == target.configVersion &&
            current.configJson == target.configJson
    } == true

    fun throwCancellationAfterCleanup() {
        (writeError as? CancellationException)?.let { throw it }
        (readError as? CancellationException)?.let { throw it }
    }
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
