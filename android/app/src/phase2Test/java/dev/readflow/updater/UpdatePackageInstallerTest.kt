package dev.readflow.updater

import android.app.ActivityOptions
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
    fun `Huawei pending action never falls through to AppGallery when the APK URI is unavailable`() {
        assertEquals(
            PendingUserActionLaunch.FAILURE,
            pendingUserActionLaunch(
                isHuaweiOrHonor = true,
                hasDownloadedApk = false,
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
    fun `foreground resume uses the downloaded APK when the session confirmation is unavailable`() {
        assertEquals(
            PendingUserActionLaunch.DIRECT_APK_INSTALL,
            pendingUserActionLaunch(
                isHuaweiOrHonor = false,
                hasDownloadedApk = true,
                hasSystemConfirmation = false,
            ),
        )
    }

    @Test
    fun `install status activity opts into background launch delivery on modern Android`() {
        assertEquals(null, installStatusActivityBackgroundLaunchMode(sdkInt = 33))
        @Suppress("DEPRECATION")
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            installStatusActivityBackgroundLaunchMode(sdkInt = 34),
        )
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
            installStatusActivityBackgroundLaunchMode(sdkInt = 36),
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

    @Test
    fun `foreground recovery recommits a persisted committed session`() {
        assertEquals(
            ForegroundInstallRecoveryAction.RECOMMIT_SESSION,
            foregroundInstallRecoveryAction(
                stage = InstallStage.COMMITTED,
                hasRecoverableSession = true,
            ),
        )
    }

    @Test
    fun `foreground recovery preserves an active committed session`() {
        assertEquals(
            ForegroundInstallRecoveryAction.NONE,
            foregroundInstallRecoveryAction(
                stage = InstallStage.COMMITTED,
                hasRecoverableSession = true,
                sessionIsActive = true,
            ),
        )
    }

    @Test
    fun `foreground recovery preserves committed state when the session query fails`() {
        assertEquals(
            ForegroundInstallRecoveryAction.NONE,
            foregroundInstallRecoveryAction(
                stage = InstallStage.COMMITTED,
                hasRecoverableSession = false,
                sessionQueryFailed = true,
            ),
        )
    }

    @Test
    fun `foreground recovery restages a committed session that disappeared`() {
        assertEquals(
            ForegroundInstallRecoveryAction.RESTAGE_DOWNLOADED_APK,
            foregroundInstallRecoveryAction(
                stage = InstallStage.COMMITTED,
                hasRecoverableSession = false,
            ),
        )
    }

    @Test
    fun `foreground recovery reopens the downloaded APK while awaiting user action`() {
        assertEquals(
            ForegroundInstallRecoveryAction.LAUNCH_DOWNLOADED_APK,
            foregroundInstallRecoveryAction(
                stage = InstallStage.AWAITING_USER,
                hasRecoverableSession = true,
            ),
        )
    }

    @Test
    fun `recommit failure only restages after confirming the session disappeared`() {
        assertEquals(
            true,
            shouldRestageAfterRecommitFailure(
                sessionQuerySucceeded = true,
                hasRecoverableSession = false,
            ),
        )
        assertEquals(
            false,
            shouldRestageAfterRecommitFailure(
                sessionQuerySucceeded = false,
                hasRecoverableSession = false,
            ),
        )
        assertEquals(
            false,
            shouldRestageAfterRecommitFailure(
                sessionQuerySucceeded = true,
                hasRecoverableSession = true,
            ),
        )
    }
}
