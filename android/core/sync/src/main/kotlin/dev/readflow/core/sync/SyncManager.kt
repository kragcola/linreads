package dev.readflow.core.sync

import dev.readflow.core.model.ReadingProgress

/**
 * Orchestrates offline-first sync (LWW for progress, Union for bookmarks; §7.6, F10).
 * [syncProgress] returns the remote winner when remote is newer; null otherwise or if sync is off.
 */
class SyncManager(private val backend: SyncBackend) {

    val isSyncEnabled: Boolean get() = backend.isAvailable

    /** Push local; returns remote [ReadingProgress] if it wins LWW, else null. */
    suspend fun syncProgress(bookId: String, local: ReadingProgress): ReadingProgress? {
        if (!isSyncEnabled) return null
        backend.pushProgress(bookId, local)
        val remote = backend.pullProgress(bookId).getOrNull() ?: return null
        return if (remote.updatedAt > local.updatedAt) remote else null
    }
}
