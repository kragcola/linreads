package dev.readflow.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class PersistedBookSource(
    val id: String,
    val kind: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val isBuiltin: Boolean = false,
    val adapterId: String = legacyAdapterId(kind),
    val configVersion: Int = 1,
    val configJson: String = legacySourceConfigJson(baseUrl),
    val updatedAt: Long = createdAt,
)

interface SourceConfigStore {
    fun observeUserSources(): Flow<List<PersistedBookSource>>
    suspend fun getUserSource(id: String): PersistedBookSource?
    suspend fun upsertUserSource(source: PersistedBookSource)
    suspend fun deleteUserSource(id: String)
    suspend fun nextSortOrder(): Int
}

class RoomSourceConfigStore(
    private val dao: BookSourceDao,
) : SourceConfigStore {
    override fun observeUserSources(): Flow<List<PersistedBookSource>> =
        dao.observeAll().map { rows -> rows.map { it.toPersisted() } }

    override suspend fun getUserSource(id: String): PersistedBookSource? =
        dao.getById(id)?.toPersisted()

    override suspend fun upsertUserSource(source: PersistedBookSource) {
        val existing = dao.getById(source.id)
        val entity = source.toEntity(
            sortOrder = existing?.sortOrder ?: source.sortOrder,
            createdAt = existing?.createdAt ?: source.createdAt.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
        )
        dao.upsert(entity)
    }

    override suspend fun deleteUserSource(id: String) {
        dao.deleteById(id)
    }

    override suspend fun nextSortOrder(): Int = dao.maxSortOrder() + 1
}

fun newUserSourceId(): String = "source-${UUID.randomUUID()}"

private fun BookSourceEntity.toPersisted(): PersistedBookSource {
    val isLegacyRow = adapterId.isBlank()
    return PersistedBookSource(
        id = id,
        kind = kind,
        name = name,
        baseUrl = baseUrl,
        enabled = enabled,
        sortOrder = sortOrder,
        createdAt = createdAt,
        isBuiltin = false,
        adapterId = adapterId.takeIf { it.isNotBlank() } ?: legacyAdapterId(kind),
        configVersion = configVersion,
        configJson = if (isLegacyRow && (configJson.isBlank() || configJson == "{}")) {
            legacySourceConfigJson(baseUrl)
        } else {
            configJson
        },
        updatedAt = updatedAt.takeIf { it > 0L } ?: createdAt,
    )
}

private fun PersistedBookSource.toEntity(
    sortOrder: Int = this.sortOrder,
    createdAt: Long = this.createdAt,
) = BookSourceEntity(
    id = id,
    kind = kind.ifBlank { legacyKind(adapterId) },
    name = name,
    baseUrl = baseUrl,
    enabled = enabled,
    sortOrder = sortOrder,
    createdAt = createdAt,
    adapterId = adapterId,
    configVersion = configVersion,
    configJson = configJson,
    updatedAt = updatedAt.takeIf { it > 0L } ?: createdAt,
)

private fun legacyAdapterId(kind: String): String = when (kind.uppercase()) {
    "CALIBRE" -> "calibre"
    "OPDS" -> "opds"
    "JSON_HTTP" -> "json-http"
    "HTML_RULES_V1" -> "html-rules-v1"
    else -> "unknown:${kind.lowercase()}"
}

private fun legacyKind(adapterId: String): String = when (adapterId) {
    "calibre" -> "CALIBRE"
    "opds" -> "OPDS"
    "json-http" -> "JSON_HTTP"
    "html-rules-v1" -> "HTML_RULES_V1"
    else -> adapterId
}

private fun legacySourceConfigJson(baseUrl: String): String =
    "{\"baseUrl\":\"${baseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
