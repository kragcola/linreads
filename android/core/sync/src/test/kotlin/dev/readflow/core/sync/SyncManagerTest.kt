package dev.readflow.core.sync

import dev.readflow.core.model.Bookmark
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadingProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncManagerTest {

    @Test
    fun newerRemoteRollbackInsideDriftWindowDoesNotWin() = runBlocking {
        val local = progress(updatedAt = 1_000_000L, totalProgression = 0.62f, deviceId = "phone")
        val remote = progress(updatedAt = 1_120_000L, totalProgression = 0.41f, deviceId = "tablet")
        val backend = FakeSyncBackend(remote)

        val winner = SyncManager(backend).syncProgress("book-1", local)

        assertNull(winner)
        assertEquals(listOf("pull:book-1", "push:book-1:0.62"), backend.calls)
    }

    @Test
    fun equalTimestampRollbackDoesNotWinByDeviceIdTieBreak() = runBlocking {
        val local = progress(updatedAt = 1_000_000L, totalProgression = 0.62f, deviceId = "phone")
        val remote = progress(updatedAt = 1_000_000L, totalProgression = 0.41f, deviceId = "tablet")
        val backend = FakeSyncBackend(remote)

        val winner = SyncManager(backend).syncProgress("book-1", local)

        assertNull(winner)
        assertEquals(listOf("pull:book-1", "push:book-1:0.62"), backend.calls)
    }

    @Test
    fun newerRemoteOutsideDriftWindowStillWinsByTimestamp() = runBlocking {
        val local = progress(updatedAt = 1_000_000L, totalProgression = 0.62f, deviceId = "phone")
        val remote = progress(updatedAt = 1_301_000L, totalProgression = 0.41f, deviceId = "tablet")
        val backend = FakeSyncBackend(remote)

        val winner = SyncManager(backend).syncProgress("book-1", local)

        assertEquals(remote, winner)
        assertEquals(listOf("pull:book-1"), backend.calls)
    }

    @Test
    fun equalTimestampUsesDeviceIdTieBreak() = runBlocking {
        val local = progress(updatedAt = 1_000_000L, totalProgression = 0.62f, deviceId = "phone")
        val remote = progress(updatedAt = 1_000_000L, totalProgression = 0.63f, deviceId = "tablet")
        val backend = FakeSyncBackend(remote)

        val winner = SyncManager(backend).syncProgress("book-1", local)

        assertEquals(remote, winner)
        assertEquals(listOf("pull:book-1"), backend.calls)
    }

    @Test
    fun missingRemoteProgressPushesLocalAfterPull() = runBlocking {
        val local = progress(updatedAt = 1_000_000L, totalProgression = 0.62f, deviceId = "phone")
        val backend = FakeSyncBackend(remoteProgress = null)

        val winner = SyncManager(backend).syncProgress("book-1", local)

        assertNull(winner)
        assertEquals(listOf("pull:book-1", "push:book-1:0.62"), backend.calls)
    }

    @Test
    fun pullFailureDoesNotPushLocalAndRiskOverwritingRemoteProgress() = runBlocking {
        val local = progress(updatedAt = 1_000_000L, totalProgression = 0.62f, deviceId = "phone")
        val backend = FakeSyncBackend(
            remoteProgress = null,
            pullFailure = ReadflowError.network(null, "offline"),
        )

        val winner = SyncManager(backend).syncProgress("book-1", local)

        assertNull(winner)
        assertEquals(listOf("pull:book-1"), backend.calls)
    }

    private fun progress(
        updatedAt: Long,
        totalProgression: Float,
        deviceId: String,
    ): ReadingProgress =
        ReadingProgress(
            bookId = "book-1",
            locator = Locator(
                strategy = LocatorStrategy.Page(index = (totalProgression * 100).toInt(), total = 100),
                totalProgression = totalProgression,
            ),
            progressPercent = totalProgression,
            updatedAt = updatedAt,
            deviceId = deviceId,
        )

    private class FakeSyncBackend(
        private val remoteProgress: ReadingProgress?,
        private val pullFailure: ReadflowError? = null,
    ) : SyncBackend {
        val calls = mutableListOf<String>()
        override val backendId = "fake"
        override val isAvailable = true

        override suspend fun pushProgress(bookId: String, progress: ReadingProgress): ReadflowResult<Unit> {
            calls += "push:$bookId:${progress.progressPercent}"
            return ReadflowResult.Success(Unit)
        }

        override suspend fun pullProgress(bookId: String): ReadflowResult<ReadingProgress?> {
            calls += "pull:$bookId"
            return pullFailure?.let { ReadflowResult.Failure(it) } ?: ReadflowResult.Success(remoteProgress)
        }

        override suspend fun pushBookmark(bookmark: Bookmark): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit)

        override suspend fun pullBookmarks(bookId: String): ReadflowResult<List<Bookmark>> =
            ReadflowResult.Success(emptyList())
    }
}
