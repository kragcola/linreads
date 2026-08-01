package dev.readflow.updater

import android.app.DownloadManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateInstallReceiverPolicyTest {

    @Test
    fun `automatic download notification disappears when the APK is complete`() {
        assertEquals(
            DownloadManager.Request.VISIBILITY_VISIBLE,
            automaticDownloadNotificationVisibility(),
        )
    }

    @Test
    fun `automatic requests do not retry replace while explicit taps do`() {
        assertFalse(retryRequestedForUpdateRequest(automatic = true))
        assertTrue(retryRequestedForUpdateRequest(automatic = false))
    }

    @Test
    fun `repeat automatic request keeps a reusable running download`() {
        assertEquals(
            ReusableDownloadAction.KEEP_EXISTING,
            reusableDownloadAction(
                downloadStatus = DownloadManager.STATUS_RUNNING,
                hasDownloadedApk = false,
            ),
        )
        assertEquals(
            ReusableDownloadAction.KEEP_EXISTING,
            reusableDownloadAction(
                downloadStatus = DownloadManager.STATUS_PENDING,
                hasDownloadedApk = false,
            ),
        )
    }

    @Test
    fun `explicit retry replaces a stalled running or pending download`() {
        assertEquals(
            ReusableDownloadAction.ENQUEUE_NEW,
            reusableDownloadAction(
                downloadStatus = DownloadManager.STATUS_RUNNING,
                hasDownloadedApk = false,
                retryRequested = true,
            ),
        )
        assertEquals(
            ReusableDownloadAction.ENQUEUE_NEW,
            reusableDownloadAction(
                downloadStatus = DownloadManager.STATUS_PENDING,
                hasDownloadedApk = false,
                retryRequested = true,
            ),
        )
    }
}
