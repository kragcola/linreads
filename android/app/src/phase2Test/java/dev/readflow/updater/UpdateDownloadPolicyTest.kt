package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateDownloadPolicyTest {
    @Test
    fun `completed app-owned download stages only when its artifact is present`() {
        assertEquals(
            AppOwnedDownloadAction.STAGE_EXISTING,
            appOwnedDownloadAction(
                state = DownloadWorkState.COMPLETE,
                hasApk = true,
                stale = false,
                retryRequested = false,
            ),
        )
        assertEquals(
            AppOwnedDownloadAction.ENQUEUE_NEW,
            appOwnedDownloadAction(
                state = DownloadWorkState.COMPLETE,
                hasApk = false,
                stale = false,
                retryRequested = false,
            ),
        )
    }

    @Test
    fun `fresh app-owned download is kept unless explicitly retried`() {
        assertEquals(
            AppOwnedDownloadAction.KEEP_EXISTING,
            appOwnedDownloadAction(
                state = DownloadWorkState.RUNNING,
                hasApk = false,
                stale = false,
                retryRequested = false,
            ),
        )
        assertEquals(
            AppOwnedDownloadAction.ENQUEUE_NEW,
            appOwnedDownloadAction(
                state = DownloadWorkState.RUNNING,
                hasApk = false,
                stale = true,
                retryRequested = false,
            ),
        )
        assertEquals(
            AppOwnedDownloadAction.ENQUEUE_NEW,
            appOwnedDownloadAction(
                state = DownloadWorkState.RUNNING,
                hasApk = false,
                stale = false,
                retryRequested = true,
            ),
        )
    }

    @Test
    fun `matching range response appends to a partial file`() {
        assertEquals(
            DownloadWriteMode.APPEND,
            downloadWriteMode(existingBytes = 128L, responseCode = 206, contentRangeStart = 128L),
        )
    }

    @Test
    fun `server restart response replaces stale partial file`() {
        assertEquals(
            DownloadWriteMode.RESTART,
            downloadWriteMode(existingBytes = 128L, responseCode = 200, contentRangeStart = null),
        )
    }

    @Test
    fun `range response from the wrong offset retries without appending`() {
        assertEquals(
            DownloadWriteMode.RETRY,
            downloadWriteMode(existingBytes = 128L, responseCode = 206, contentRangeStart = 64L),
        )
    }

    @Test
    fun `authorization is kept on origin but removed after cross host redirect`() {
        val origin = "https://github.com/kragcola/linreads/releases/download/dev-latest/app.apk"
        assertTrue(redirectAuthorizationAllowed(origin, origin))
        assertFalse(
            redirectAuthorizationAllowed(
                origin,
                "https://release-assets.githubusercontent.com/github-production-release-asset/app.apk",
            ),
        )
    }

    @Test
    fun `running download is retried only after the stale window`() {
        assertFalse(
            shouldRetryStaleDownload(
                state = DownloadWorkState.RUNNING,
                startedAtEpochMs = 1_000L,
                nowEpochMs = 90_000L,
            ),
        )
        assertTrue(
            shouldRetryStaleDownload(
                state = DownloadWorkState.RUNNING,
                startedAtEpochMs = 1_000L,
                nowEpochMs = 121_000L,
            ),
        )
    }
}
