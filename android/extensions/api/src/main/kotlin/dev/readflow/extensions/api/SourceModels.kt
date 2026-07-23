package dev.readflow.extensions.api

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.flow.Flow

/** Supported online / remote catalog source kinds (generic adapters only). */
enum class SourceKind {
    CALIBRE,
    OPDS,
    JSON_HTTP,
}

/**
 * Persistable / UI descriptor for a book source.
 * [id] is stable across sessions; builtin Calibre uses a fixed id.
 */
data class SourceDescriptor(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val isBuiltin: Boolean = false,
)

/**
 * Catalog row with optional filter fields that [BookMeta] does not carry.
 * Adapters populate whatever the wire format exposes; missing fields stay empty.
 */
data class OnlineCatalogEntry(
    val meta: BookMeta,
    val series: String? = null,
    val tags: List<String> = emptyList(),
    val availableFormats: List<String> = emptyList(),
    val downloadUrl: String? = null,
    val previewUrl: String? = null,
)

/** Result filters applied client-side when the adapter returns a page of entries. */
data class OnlineCatalogFilter(
    val author: String = "",
    val series: String = "",
    val format: String = "",
    val tag: String = "",
) {
    val isEmpty: Boolean
        get() = author.isBlank() && series.isBlank() && format.isBlank() && tag.isBlank()
}

/**
 * Opened catalog session for one source. Close after search/download work.
 * Implementations must not leave partial files on the shelf on cancel/failure.
 */
interface OnlineBookCatalog : AutoCloseable {
    val descriptor: SourceDescriptor

    suspend fun search(
        query: String,
        filter: OnlineCatalogFilter = OnlineCatalogFilter(),
        offset: Int = 0,
        limit: Int = 100,
    ): ReadflowResult<List<OnlineCatalogEntry>>

    suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta>

    /**
     * Returns a validated preview URL for the entry (same-origin / policy-checked).
     * Failure means the URL is unsafe or unavailable — never return an unchecked redirect target.
     */
    suspend fun previewUrl(entry: OnlineCatalogEntry): ReadflowResult<String>

    override fun close() = Unit
}

/**
 * Registry of configured sources (builtin Calibre + user OPDS/JSON).
 * Source configs are auditable descriptors; no hardcoded third-party site scrapers.
 */
interface SourceRegistry {
    fun observeSources(): Flow<List<SourceDescriptor>>

    suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog>

    suspend fun addUserSource(
        kind: SourceKind,
        name: String,
        baseUrl: String,
    ): ReadflowResult<SourceDescriptor>

    suspend fun removeUserSource(sourceId: String): ReadflowResult<Unit>
}

/** Fixed id for the settings-backed Calibre entry (not stored in book_sources). */
const val BUILTIN_CALIBRE_SOURCE_ID = "calibre-builtin"
