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
    fun `download manager never attaches the OTA token`() {
        assertFalse(
            shouldAttachUpdateAuthorization(
                "https://github.com/kragcola/linreads/releases/download/dev-latest/app.apk",
            ),
        )
        assertFalse(shouldAttachUpdateAuthorization("http://github.com/kragcola/linreads/app.apk"))
        assertFalse(shouldAttachUpdateAuthorization("https://example.com/app.apk"))
    }

    @Test
    fun `explicit update rejects a known installed version even when its identity is persisted`() {
        assertFalse(
            isExplicitUpdateRequestEligible(
                versionCode = 100L,
                currentVersionCode = 100L,
                reusesPersistedDownload = true,
            ),
        )
        assertFalse(
            isExplicitUpdateRequestEligible(
                versionCode = 99L,
                currentVersionCode = 100L,
                reusesPersistedDownload = true,
            ),
        )
        assertTrue(
            isExplicitUpdateRequestEligible(
                versionCode = 101L,
                currentVersionCode = 100L,
                reusesPersistedDownload = false,
            ),
        )
        assertTrue(
            isExplicitUpdateRequestEligible(
                versionCode = null,
                currentVersionCode = 100L,
                reusesPersistedDownload = false,
            ),
        )
    }

    @Test
    fun `active installer ownership defers download replacement`() {
        listOf(InstallStage.STAGING, InstallStage.COMMITTED, InstallStage.AWAITING_USER).forEach { stage ->
            assertTrue(invokeShouldDeferUpdateReplacement(stage, ApkInstallBridgeState.NONE))
        }
        listOf(ApkInstallBridgeState.LAUNCHING, ApkInstallBridgeState.ACTIVE).forEach { bridgeState ->
            assertTrue(invokeShouldDeferUpdateReplacement(null, bridgeState))
        }
        assertFalse(invokeShouldDeferUpdateReplacement(InstallStage.FAILED, ApkInstallBridgeState.NONE))
        listOf(
            ApkInstallBridgeState.DEFERRED,
            ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
            ApkInstallBridgeState.FAILED,
        ).forEach { bridgeState ->
            listOf(null, InstallStage.COMMITTED, InstallStage.AWAITING_USER).forEach { stage ->
                assertFalse(invokeShouldDeferUpdateReplacement(stage, bridgeState))
            }
        }
    }

    @Test
    fun `replacement activates only after metadata commit while installer stays replaceable`() {
        assertTrue(
            invokeShouldActivateEnqueuedUpdate(
                metadataCommitted = true,
                replacementDeferredAfterEnqueue = false,
            ),
        )
        assertFalse(
            invokeShouldActivateEnqueuedUpdate(
                metadataCommitted = false,
                replacementDeferredAfterEnqueue = false,
            ),
        )
        assertFalse(
            invokeShouldActivateEnqueuedUpdate(
                metadataCommitted = true,
                replacementDeferredAfterEnqueue = true,
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

    private fun invokeShouldDeferUpdateReplacement(
        installStage: InstallStage?,
        bridgeState: ApkInstallBridgeState,
    ): Boolean {
        val method = runCatching {
            Class.forName("dev.readflow.updater.UpdateDownloadPolicyKt").getDeclaredMethod(
                "shouldDeferUpdateReplacement",
                InstallStage::class.java,
                ApkInstallBridgeState::class.java,
            )
        }.getOrNull()
        assertTrue(method != null, "UpdateDownloadPolicy must define shouldDeferUpdateReplacement")
        return requireNotNull(method).invoke(null, installStage, bridgeState) as Boolean
    }

    private fun invokeShouldActivateEnqueuedUpdate(
        metadataCommitted: Boolean,
        replacementDeferredAfterEnqueue: Boolean,
    ): Boolean {
        val method = runCatching {
            Class.forName("dev.readflow.updater.UpdateDownloadPolicyKt").getDeclaredMethod(
                "shouldActivateEnqueuedUpdate",
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            )
        }.getOrNull()
        assertTrue(method != null, "UpdateDownloadPolicy must define shouldActivateEnqueuedUpdate")
        return requireNotNull(method).invoke(
            null,
            metadataCommitted,
            replacementDeferredAfterEnqueue,
        ) as Boolean
    }
}
