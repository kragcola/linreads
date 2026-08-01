package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateDownloadPolicyTest {
    @Test
    fun `system download manager is distinct from legacy app owned transport`() {
        assertTrue(isAppOwnedDownloadBackend(DOWNLOAD_BACKEND_APP_HTTP))
        assertFalse(isAppOwnedDownloadBackend(DOWNLOAD_BACKEND_DOWNLOAD_MANAGER))
        assertFalse(isAppOwnedDownloadBackend(null))
        assertFalse(shouldHandleDownloadManagerCompletion(DOWNLOAD_BACKEND_APP_HTTP))
        assertTrue(shouldHandleDownloadManagerCompletion(DOWNLOAD_BACKEND_DOWNLOAD_MANAGER))
        assertTrue(shouldHandleDownloadManagerCompletion(null))
    }

    @Test
    fun `persisted backend mapping treats missing marker as legacy`() {
        assertEquals(PersistedDownloadBackend.APP_HTTP, persistedDownloadBackend(DOWNLOAD_BACKEND_APP_HTTP))
        assertEquals(
            PersistedDownloadBackend.DOWNLOAD_MANAGER,
            persistedDownloadBackend(DOWNLOAD_BACKEND_DOWNLOAD_MANAGER),
        )
        assertEquals(PersistedDownloadBackend.LEGACY, persistedDownloadBackend(null))
    }

    @Test
    fun `update authorization is limited to the github origin`() {
        assertTrue(
            shouldAttachUpdateAuthorization(
                "https://github.com/kragcola/linreads/releases/download/dev-latest/app.apk",
            ),
        )
        assertFalse(shouldAttachUpdateAuthorization("http://github.com/kragcola/linreads/app.apk"))
        assertFalse(shouldAttachUpdateAuthorization("https://example.com/app.apk"))
    }

    @Test
    fun `explicit update can retry its persisted identity but not a stale version`() {
        assertTrue(
            isExplicitUpdateRequestEligible(
                versionCode = 100L,
                currentVersionCode = 100L,
                reusesPersistedDownload = true,
            ),
        )
        assertFalse(
            isExplicitUpdateRequestEligible(
                versionCode = 100L,
                currentVersionCode = 100L,
                reusesPersistedDownload = false,
            ),
        )
        assertTrue(
            isExplicitUpdateRequestEligible(
                versionCode = 101L,
                currentVersionCode = 100L,
                reusesPersistedDownload = false,
            ),
        )
    }

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

    @Test
    fun `failed app owned download is eligible for foreground migration`() {
        assertTrue(
            shouldRetryStaleDownload(
                state = DownloadWorkState.FAILED,
                startedAtEpochMs = 0L,
                nowEpochMs = 1L,
            ),
        )
    }
}
