package dev.readflow.core.sync

import dev.readflow.core.model.Bookmark
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReadingProgress

/** Pluggable sync backend (§7.6). Phase 2+ adds Korro/WebDav/KOSync implementations. */
interface SyncBackend {
    val backendId: String
    val isAvailable: Boolean
    suspend fun pushProgress(bookId: String, progress: ReadingProgress): ReadflowResult<Unit>
    suspend fun pullProgress(bookId: String): ReadflowResult<ReadingProgress?>
    suspend fun pushBookmark(bookmark: Bookmark): ReadflowResult<Unit>
    suspend fun pullBookmarks(bookId: String): ReadflowResult<List<Bookmark>>
}

/** Phase 1 default: no remote sync. Settings "sync" section stays disabled. */
class NoOpSyncBackend : SyncBackend {
    override val backendId = "noop"
    override val isAvailable = false
    override suspend fun pushProgress(bookId: String, progress: ReadingProgress) =
        ReadflowResult.Success(Unit)
    override suspend fun pullProgress(bookId: String) =
        ReadflowResult.Success(null)
    override suspend fun pushBookmark(bookmark: Bookmark) =
        ReadflowResult.Success(Unit)
    override suspend fun pullBookmarks(bookId: String) =
        ReadflowResult.Success(emptyList<Bookmark>())
}
