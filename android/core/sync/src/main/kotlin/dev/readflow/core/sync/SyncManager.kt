package dev.readflow.core.sync

import dev.readflow.core.model.Bookmark
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReadingProgress

/**
 * Orchestrates offline-first sync (LWW for progress, Union for bookmarks; §7.6, F10).
 * [syncProgress] returns the remote winner when remote clearly wins; null otherwise or if sync is off.
 */
class SyncManager(private val backend: SyncBackend) {

    val isSyncEnabled: Boolean get() = backend.isAvailable

    /**
     * Pulls first so local progress is compared against the last known remote value before any push.
     * A near-clock rollback keeps local progress instead of silently jumping backwards (§7.7 F6).
     */
    suspend fun syncProgress(bookId: String, local: ReadingProgress): ReadingProgress? {
        if (!isSyncEnabled) return null
        val remote = when (val result = backend.pullProgress(bookId)) {
            is ReadflowResult.Success -> result.value
            is ReadflowResult.Failure -> return null
        }
        if (remote == null) {
            backend.pushProgress(bookId, local)
            return null
        }
        return when (progressWinner(local, remote)) {
            ProgressWinner.Local -> {
                backend.pushProgress(bookId, local)
                null
            }
            ProgressWinner.Remote -> remote
        }
    }

    suspend fun syncBookmark(bookmark: Bookmark) {
        if (!isSyncEnabled) return
        backend.pushBookmark(bookmark)
    }

    private enum class ProgressWinner {
        Local,
        Remote,
    }

    private fun progressWinner(local: ReadingProgress, remote: ReadingProgress): ProgressWinner {
        val timestampDelta = remote.updatedAt - local.updatedAt
        if (
            timestampDelta.magnitude() < SUSPECT_CLOCK_DRIFT_MS &&
            remote.totalProgression() < local.totalProgression()
        ) {
            return ProgressWinner.Local
        }
        return when {
            remote.updatedAt > local.updatedAt -> ProgressWinner.Remote
            remote.updatedAt < local.updatedAt -> ProgressWinner.Local
            remote.deviceId > local.deviceId -> ProgressWinner.Remote
            else -> ProgressWinner.Local
        }
    }

    private fun ReadingProgress.totalProgression(): Float =
        (locator.totalProgression ?: progressPercent).coerceIn(0f, 1f)

    private fun Long.magnitude(): Long = if (this < 0) -this else this

    private companion object {
        const val SUSPECT_CLOCK_DRIFT_MS = 5 * 60 * 1_000L
    }
}
