package dev.readflow.core.calibre

import dev.readflow.core.database.PersistedBookSource
import dev.readflow.core.database.SourceConfigStore
import dev.readflow.core.database.newUserSourceId
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.SourceRegistry
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Combines builtin Calibre (settings URL) with user OPDS/JSON sources from [SourceConfigStore].
 */
class DefaultSourceRegistry(
    private val settings: SettingsRepository,
    private val sourceConfigStore: SourceConfigStore,
    private val booksDir: File,
    private val calibreCatalogFactory: (baseUrl: String) -> OnlineBookCatalog = { baseUrl ->
        CalibreOnlineCatalog(baseUrl = baseUrl, booksDir = booksDir)
    },
    private val genericCatalogFactory: (SourceDescriptor) -> OnlineBookCatalog = { descriptor ->
        GenericHttpOnlineCatalog(descriptor = descriptor, booksDir = booksDir)
    },
) : SourceRegistry {

    override fun observeSources(): Flow<List<SourceDescriptor>> =
        combine(settings.calibreBaseUrl, sourceConfigStore.observeUserSources()) { calibreUrl, userSources ->
            buildList {
                val normalized = calibreUrl?.takeIf { it.isNotBlank() }?.let {
                    runCatching { requireValidCalibreBaseUrl(it) }.getOrNull()
                }
                add(
                    SourceDescriptor(
                        id = BUILTIN_CALIBRE_SOURCE_ID,
                        kind = SourceKind.CALIBRE,
                        name = "Calibre",
                        baseUrl = normalized.orEmpty(),
                        enabled = !normalized.isNullOrBlank(),
                        isBuiltin = true,
                    ),
                )
                userSources
                    .filter { it.enabled }
                    .forEach { add(it.toDescriptor()) }
            }
        }

    override suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog> {
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            val baseUrl = settings.calibreBaseUrl.first()
            if (baseUrl.isNullOrBlank()) {
                return ReadflowResult.Failure(ReadflowError.network(null, "请先在设置中连接 Calibre"))
            }
            return runCatching {
                ReadflowResult.Success(calibreCatalogFactory(baseUrl))
            }.getOrElse { error ->
                ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "服务器地址无效"))
            }
        }
        val stored = sourceConfigStore.getUserSource(sourceId)
            ?: return ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
        if (!stored.enabled) {
            return ReadflowResult.Failure(ReadflowError.network(null, "书源已禁用"))
        }
        val descriptor = stored.toDescriptor()
        return runCatching {
            requireValidCalibreBaseUrl(descriptor.baseUrl)
            ReadflowResult.Success(genericCatalogFactory(descriptor))
        }.getOrElse { error ->
            ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "书源地址无效"))
        }
    }

    override suspend fun addUserSource(
        kind: SourceKind,
        name: String,
        baseUrl: String,
    ): ReadflowResult<SourceDescriptor> {
        if (kind == SourceKind.CALIBRE) {
            return ReadflowResult.Failure(ReadflowError.unsupported("Calibre 请在设置中配置"))
        }
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return ReadflowResult.Failure(ReadflowError.parse("请填写书源名称"))
        }
        val validation = validateCalibreBaseUrl(baseUrl)
        if (!validation.isValid) {
            return ReadflowResult.Failure(ReadflowError.network(null, validation.errorMessage ?: "地址无效"))
        }
        val id = newUserSourceId()
        val sortOrder = sourceConfigStore.nextSortOrder()
        val now = System.currentTimeMillis()
        val persisted = PersistedBookSource(
            id = id,
            kind = kind.name,
            name = trimmedName,
            baseUrl = validation.normalizedUrl,
            enabled = true,
            sortOrder = sortOrder,
            createdAt = now,
        )
        sourceConfigStore.upsertUserSource(persisted)
        return ReadflowResult.Success(persisted.toDescriptor())
    }

    override suspend fun removeUserSource(sourceId: String): ReadflowResult<Unit> {
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            return ReadflowResult.Failure(ReadflowError.unsupported("内置 Calibre 源不可删除"))
        }
        sourceConfigStore.deleteUserSource(sourceId)
        return ReadflowResult.Success(Unit)
    }
}

private fun PersistedBookSource.toDescriptor(): SourceDescriptor {
    val kind = runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.JSON_HTTP)
    return SourceDescriptor(
        id = id,
        kind = kind,
        name = name,
        baseUrl = baseUrl,
        enabled = enabled,
        isBuiltin = false,
    )
}
