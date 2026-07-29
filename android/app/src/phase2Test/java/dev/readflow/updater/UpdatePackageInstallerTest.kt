package dev.readflow.updater

import android.app.ActivityOptions
import android.content.Intent
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
    fun `direct APK installer stays in the isolated bridge task and returns there`() {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, directApkInstallerFlags())
        assertEquals(true, directApkInstallerReturnsResult())
    }

    @Test
    fun `APK install bridge always starts a separately recoverable task`() {
        val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

        assertEquals(requiredFlags, apkInstallBridgeFlags(callerIsActivity = false))
        assertEquals(
            requiredFlags,
            apkInstallBridgeFlags(callerIsActivity = true),
            "the no-history status Activity must not own the installer bridge task",
        )
    }

    @Test
    fun `active installer task returns to foreground when its notification is unavailable`() {
        assertEquals(
            ActiveBridgeForegroundAction.KEEP_NOTIFICATION_ROUTE,
            activeBridgeForegroundAction(notificationPosted = true),
        )
        assertEquals(
            ActiveBridgeForegroundAction.MOVE_TASK_TO_FRONT,
            activeBridgeForegroundAction(notificationPosted = false),
        )
    }

    @Test
    fun `superseded worker cannot publish a stale install failure`() {
        assertEquals(true, shouldPublishStagingFailureNotification(true))
        assertEquals(false, shouldPublishStagingFailureNotification(false))
    }

    @Test
    fun `recreated install bridge waits for the existing installer instead of launching twice`() {
        assertEquals(
            ApkInstallBridgeAction.LAUNCH_INSTALLER,
            apkInstallBridgeAction(
                installerAlreadyLaunched = false,
                hasApkUri = true,
                persistedState = ApkInstallBridgeState.LAUNCHING,
            ),
        )
        assertEquals(
            ApkInstallBridgeAction.AWAIT_RESULT,
            apkInstallBridgeAction(installerAlreadyLaunched = true, hasApkUri = true),
        )
        assertEquals(
            ApkInstallBridgeAction.FINISH,
            apkInstallBridgeAction(installerAlreadyLaunched = false, hasApkUri = false),
        )
    }

    @Test
    fun `persisted bridge state only lets a claimed bridge launch the installer`() {
        assertEquals(
            ApkInstallBridgeAction.AWAIT_RESULT,
            apkInstallBridgeAction(
                installerAlreadyLaunched = false,
                hasApkUri = true,
                persistedState = ApkInstallBridgeState.ACTIVE,
            ),
            "an ACTIVE bridge must wait after process recreation",
        )
        listOf(ApkInstallBridgeState.DEFERRED, ApkInstallBridgeState.FAILED).forEach { state ->
            assertEquals(
                ApkInstallBridgeAction.FINISH,
                apkInstallBridgeAction(
                    installerAlreadyLaunched = false,
                    hasApkUri = true,
                    persistedState = state,
                ),
                "$state must not reopen from an automatic entry",
            )
            assertEquals(
                ApkInstallBridgeAction.FINISH,
                apkInstallBridgeAction(
                    installerAlreadyLaunched = false,
                    hasApkUri = true,
                    persistedState = state,
                ),
                "$state must be claimed before a retry Activity can launch",
            )
        }
        assertEquals(
            BridgeLaunchClaimAction.REJECT,
            bridgeLaunchClaimAction(
                ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
                userInitiated = false,
            ),
        )
        assertEquals(
            BridgeLaunchClaimAction.CLAIM,
            bridgeLaunchClaimAction(
                ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
                userInitiated = true,
            ),
        )
    }

    @Test
    fun `bridge launch claim is atomic across automatic and user initiated entries`() {
        assertEquals(
            BridgeLaunchClaimAction.CLAIM,
            bridgeLaunchClaimAction(ApkInstallBridgeState.NONE, userInitiated = false),
        )
        listOf(ApkInstallBridgeState.LAUNCHING, ApkInstallBridgeState.ACTIVE).forEach { state ->
            assertEquals(
                BridgeLaunchClaimAction.KEEP_WAITING,
                bridgeLaunchClaimAction(state, userInitiated = false),
                "$state must not create a second bridge",
            )
            assertEquals(
                BridgeLaunchClaimAction.KEEP_WAITING,
                bridgeLaunchClaimAction(state, userInitiated = true),
                "$state must not create a second bridge from a notification tap",
            )
        }
        listOf(ApkInstallBridgeState.DEFERRED, ApkInstallBridgeState.FAILED).forEach { state ->
            assertEquals(
                BridgeLaunchClaimAction.REJECT,
                bridgeLaunchClaimAction(state, userInitiated = false),
                "$state requires an explicit retry",
            )
            assertEquals(
                BridgeLaunchClaimAction.CLAIM,
                bridgeLaunchClaimAction(state, userInitiated = true),
                "$state can be retried explicitly",
            )
        }
    }

    @Test
    fun `install notification resumes a live task or retries a missing bridge`() {
        assertEquals(
            ApkInstallRetryEntryPoint.ACTIVITY,
            apkInstallRetryEntryPoint(),
            "Android 12+ forbids notification BroadcastReceiver-to-Activity trampolines",
        )
        assertEquals(
            BridgeNotificationAction.RESUME_TASK,
            bridgeNotificationAction(ApkInstallBridgeState.ACTIVE, hasBridgeTask = true),
        )
        assertEquals(
            BridgeNotificationAction.RETRY_INSTALLER,
            bridgeNotificationAction(ApkInstallBridgeState.ACTIVE, hasBridgeTask = false),
        )
        listOf(
            ApkInstallBridgeState.NONE,
            ApkInstallBridgeState.DEFERRED,
            ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
            ApkInstallBridgeState.FAILED,
        ).forEach { state ->
            assertEquals(
                BridgeNotificationAction.RETRY_INSTALLER,
                bridgeNotificationAction(state, hasBridgeTask = false),
            )
        }
        assertEquals(
            BridgeNotificationAction.KEEP_WAITING,
            bridgeNotificationAction(ApkInstallBridgeState.LAUNCHING, hasBridgeTask = false),
        )
    }

    @Test
    fun `disabled install channel is not treated as a visible retry route`() {
        assertEquals(
            true,
            shouldPublishInstallNotification(
                appNotificationsAllowed = true,
                channelImportance = null,
            ),
        )
        assertEquals(
            true,
            shouldPublishInstallNotification(
                appNotificationsAllowed = true,
                channelImportance = android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        assertEquals(
            false,
            shouldPublishInstallNotification(
                appNotificationsAllowed = true,
                channelImportance = android.app.NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertEquals(
            false,
            shouldPublishInstallNotification(
                appNotificationsAllowed = false,
                channelImportance = android.app.NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    @Test
    fun `missing notification route defers once instead of looping or stranding the update`() {
        assertEquals(
            ApkInstallBridgeState.DEFERRED,
            bridgeStateForRetryRoute(notificationPosted = true),
        )
        assertEquals(
            ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
            bridgeStateForRetryRoute(notificationPosted = false),
        )
        assertEquals(
            BridgeForegroundFallbackAction.ARM_NEXT_FOREGROUND,
            bridgeForegroundFallbackAction(ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION),
        )
        assertEquals(
            BridgeForegroundFallbackAction.KEEP_EXPLICIT_RETRY,
            bridgeForegroundFallbackAction(ApkInstallBridgeState.DEFERRED),
        )
        assertEquals(
            BridgeForegroundFallbackAction.NONE,
            bridgeForegroundFallbackAction(ApkInstallBridgeState.NONE),
        )
    }

    @Test
    fun `bridge notification publication is scoped to the current download and state`() {
        assertEquals(
            false,
            canPublishBridgeNotification(
                currentDownloadId = 43L,
                requestedDownloadId = 42L,
                currentState = ApkInstallBridgeState.DEFERRED,
                requiredState = ApkInstallBridgeState.DEFERRED,
            ),
        )
        assertEquals(
            false,
            canPublishBridgeNotification(
                currentDownloadId = 42L,
                requestedDownloadId = 42L,
                currentState = ApkInstallBridgeState.ACTIVE,
                requiredState = ApkInstallBridgeState.DEFERRED,
            ),
        )
        assertEquals(
            true,
            canPublishBridgeNotification(
                currentDownloadId = 42L,
                requestedDownloadId = 42L,
                currentState = ApkInstallBridgeState.DEFERRED,
                requiredState = ApkInstallBridgeState.DEFERRED,
            ),
        )
        assertEquals(
            true,
            canPublishBridgeNotification(
                currentDownloadId = 42L,
                requestedDownloadId = 42L,
                currentState = ApkInstallBridgeState.ACTIVE,
                requiredState = null,
            ),
        )
    }

    @Test
    fun `abandoned launching claim is recoverable without racing a live bridge`() {
        assertEquals(
            false,
            isBridgeLaunchStale(
                state = ApkInstallBridgeState.LAUNCHING,
                hasBridgeTask = true,
                claimedProcessId = 10,
                currentProcessId = 11,
                claimedAtElapsedMs = 1_000L,
                nowElapsedMs = 20_000L,
            ),
            "a recorded task remains the source of truth",
        )
        assertEquals(
            true,
            isBridgeLaunchStale(
                state = ApkInstallBridgeState.LAUNCHING,
                hasBridgeTask = false,
                claimedProcessId = 10,
                currentProcessId = 11,
                claimedAtElapsedMs = 1_000L,
                nowElapsedMs = 1_100L,
            ),
            "a claim from a dead process must be released immediately",
        )
        assertEquals(
            false,
            isBridgeLaunchStale(
                state = ApkInstallBridgeState.LAUNCHING,
                hasBridgeTask = false,
                claimedProcessId = 10,
                currentProcessId = 10,
                claimedAtElapsedMs = 1_000L,
                nowElapsedMs = 10_999L,
            ),
        )
        assertEquals(
            true,
            isBridgeLaunchStale(
                state = ApkInstallBridgeState.LAUNCHING,
                hasBridgeTask = false,
                claimedProcessId = 10,
                currentProcessId = 10,
                claimedAtElapsedMs = 1_000L,
                nowElapsedMs = 11_000L,
            ),
            "a silently aborted start must not suppress recovery forever",
        )
        assertEquals(
            true,
            isBridgeLaunchStale(
                state = ApkInstallBridgeState.LAUNCHING,
                hasBridgeTask = false,
                claimedProcessId = 10,
                currentProcessId = 10,
                claimedAtElapsedMs = 20_000L,
                nowElapsedMs = 100L,
            ),
            "elapsed realtime moving backwards means the device rebooted",
        )
    }

    @Test
    fun `install bridge closes when an OEM installer returns without an activity result`() {
        assertEquals(false, shouldCloseInstallBridgeOnResume(false, true))
        assertEquals(false, shouldCloseInstallBridgeOnResume(true, false))
        assertEquals(true, shouldCloseInstallBridgeOnResume(true, true))
        assertEquals(
            false,
            shouldCloseInstallBridgeOnResume(
                installerLaunched = true,
                bridgePausedForInstaller = true,
                returningToLinReads = true,
            ),
        )
        assertEquals(
            true,
            shouldCloseInstallBridgeOnResume(
                installerLaunched = true,
                bridgePausedForInstaller = false,
                recoveredActiveWithoutSavedState = true,
            ),
            "an ACTIVE bridge recreated without a Bundle must not stay transparent forever",
        )
    }

    @Test
    fun `install bridge task is removed only after MainActivity launches`() {
        assertEquals(false, shouldRemoveInstallBridgeTask(mainActivityLaunchSucceeded = false))
        assertEquals(true, shouldRemoveInstallBridgeTask(mainActivityLaunchSucceeded = true))
    }

    @Test
    fun `automatic bridge reopen suppression is scoped to the current download`() {
        val currentDownloadId = 42L
        listOf(
            ApkInstallBridgeState.LAUNCHING,
            ApkInstallBridgeState.ACTIVE,
            ApkInstallBridgeState.DEFERRED,
            ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
            ApkInstallBridgeState.FAILED,
        ).forEach { state ->
            assertEquals(
                true,
                shouldSuppressAutomaticBridgeReopen(
                    currentDownloadId = currentDownloadId,
                    bridgeDownloadId = currentDownloadId,
                    bridgeState = state,
                ),
                "$state for the current download must suppress automatic reopen",
            )
        }
        assertEquals(
            false,
            shouldSuppressAutomaticBridgeReopen(
                currentDownloadId = currentDownloadId,
                bridgeDownloadId = currentDownloadId,
                bridgeState = ApkInstallBridgeState.NONE,
            ),
            "NONE must not suppress automatic reopen",
        )
        ApkInstallBridgeState.values().forEach { state ->
            assertEquals(
                false,
                shouldSuppressAutomaticBridgeReopen(
                    currentDownloadId = currentDownloadId,
                    bridgeDownloadId = currentDownloadId - 1,
                    bridgeState = state,
                ),
                "$state from an older download must not suppress automatic reopen",
            )
        }
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
    fun `foreground recovery does not reopen an installer deferred to explicit user retry`() {
        assertEquals(
            ForegroundInstallRecoveryAction.KEEP_AWAITING,
            foregroundInstallRecoveryAction(
                stage = InstallStage.AWAITING_USER,
                hasRecoverableSession = true,
                bridgeRetrySuppressed = true,
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
