package dev.readflow.updater

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdatePackageInstallerTest {

    @Test
    fun `self updates request no user action on Android S and newer`() {
        assertEquals(null, selfUpdateUserActionRequirement(sdkInt = 30))
        assertEquals(
            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
            selfUpdateUserActionRequirement(sdkInt = 31),
        )
        assertEquals(
            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
            selfUpdateUserActionRequirement(sdkInt = 36),
        )
    }

    @Test
    fun `Huawei pending action uses the downloaded APK instead of AppGallery confirmation`() {
        assertEquals(
            PendingUserActionLaunch.DIRECT_APK_INSTALL,
            pendingUserActionLaunch(
                isHuaweiOrHonor = true,
                hasDownloadedApk = true,
                hasSystemConfirmation = true,
            ),
        )
    }

    @Test
    fun `standard Android preserves its PackageInstaller confirmation session`() {
        assertEquals(
            PendingUserActionLaunch.SYSTEM_CONFIRMATION,
            pendingUserActionLaunch(
                isHuaweiOrHonor = false,
                hasDownloadedApk = true,
                hasSystemConfirmation = true,
            ),
        )
    }

    @Test
    fun duplicateDownloadCompletionKeepsTheActiveStagingWorker() {
        assertEquals(
            InstallEnqueueAction.KEEP_EXISTING,
            installEnqueueAction(
                currentDownloadId = 42,
                currentStage = InstallStage.STAGING,
                requestedDownloadId = 42,
            ),
        )
    }

    @Test
    fun duplicateDownloadCompletionNeverCommitsASecondSession() {
        listOf(InstallStage.COMMITTED, InstallStage.AWAITING_USER).forEach { stage ->
            assertEquals(
                InstallEnqueueAction.KEEP_EXISTING,
                installEnqueueAction(
                    currentDownloadId = 42,
                    currentStage = stage,
                    requestedDownloadId = 42,
                ),
            )
        }
    }

    @Test
    fun persistedStagingKeepsTheRecoverableWorkAndFailureCanRestart() {
        assertEquals(
            InstallEnqueueAction.KEEP_EXISTING,
            installEnqueueAction(42, InstallStage.STAGING, 42),
        )
        assertEquals(
            InstallEnqueueAction.START,
            installEnqueueAction(42, InstallStage.FAILED, 42),
        )
    }

    @Test
    fun explicitRetryRebuildsCommittedAndAwaitingUserSessions() {
        listOf(InstallStage.COMMITTED, InstallStage.AWAITING_USER).forEach { stage ->
            assertEquals(
                InstallEnqueueAction.START,
                installEnqueueAction(
                    currentDownloadId = 42,
                    currentStage = stage,
                    requestedDownloadId = 42,
                    retryRequested = true,
                ),
            )
        }
    }

    @Test
    fun `automatic foreground resume keeps an awaiting installer session`() {
        assertEquals(
            InstallEnqueueAction.KEEP_EXISTING,
            installEnqueueAction(
                currentDownloadId = 42,
                currentStage = InstallStage.AWAITING_USER,
                requestedDownloadId = 42,
                retryRequested = retryRequestedForUpdateRequest(automatic = true),
            ),
        )
    }
}
