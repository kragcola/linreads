package dev.readflow.updater

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.readflow.MainActivity

internal enum class InstallStage {
    STAGING,
    COMMITTED,
    AWAITING_USER,
    FAILED,
}

internal enum class InstallEnqueueAction { START, KEEP_EXISTING }

internal enum class PendingUserActionLaunch { DIRECT_APK_INSTALL, SYSTEM_CONFIRMATION, FAILURE }

internal enum class ForegroundInstallRecoveryAction {
    NONE,
    KEEP_AWAITING,
    LAUNCH_DOWNLOADED_APK,
    RECOMMIT_SESSION,
    RESTAGE_DOWNLOADED_APK,
}

internal fun foregroundInstallRecoveryAction(
    stage: InstallStage?,
    hasRecoverableSession: Boolean,
    sessionIsActive: Boolean = false,
    sessionQueryFailed: Boolean = false,
    bridgeRetrySuppressed: Boolean = false,
): ForegroundInstallRecoveryAction = when {
    stage == InstallStage.AWAITING_USER -> ForegroundInstallRecoveryAction.KEEP_AWAITING
    stage == InstallStage.COMMITTED && sessionQueryFailed -> ForegroundInstallRecoveryAction.NONE
    stage == InstallStage.COMMITTED && sessionIsActive -> ForegroundInstallRecoveryAction.NONE
    stage == InstallStage.COMMITTED && hasRecoverableSession ->
        ForegroundInstallRecoveryAction.RECOMMIT_SESSION
    stage == InstallStage.COMMITTED -> ForegroundInstallRecoveryAction.RESTAGE_DOWNLOADED_APK
    else -> ForegroundInstallRecoveryAction.NONE
}

internal fun shouldRestageAfterRecommitFailure(
    sessionQuerySucceeded: Boolean,
    hasRecoverableSession: Boolean,
): Boolean = sessionQuerySucceeded && !hasRecoverableSession

/** Requests the Android self-update path that can complete without a confirmation sheet. */
internal fun selfUpdateUserActionRequirement(sdkInt: Int): Int? =
    if (sdkInt >= Build.VERSION_CODES.S) {
        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
    } else {
        null
    }

internal fun pendingUserActionLaunch(
    isHuaweiOrHonor: Boolean = false,
    hasDownloadedApk: Boolean,
    hasSystemConfirmation: Boolean,
): PendingUserActionLaunch = when {
    hasSystemConfirmation -> PendingUserActionLaunch.SYSTEM_CONFIRMATION
    else -> PendingUserActionLaunch.FAILURE
}

internal fun installStatusActivityBackgroundLaunchMode(sdkInt: Int): Int? = when {
    sdkInt >= Build.VERSION_CODES.BAKLAVA ->
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
        @Suppress("DEPRECATION")
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }
    else -> null
}

internal enum class InstallStatusAction(val opensMainActivity: Boolean) {
    HANDLE_PENDING_USER_ACTION(opensMainActivity = false),
    COMPLETE(opensMainActivity = true),
    FAIL(opensMainActivity = false),
}

internal fun installStatusAction(status: Int): InstallStatusAction = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallStatusAction.HANDLE_PENDING_USER_ACTION
    PackageInstaller.STATUS_SUCCESS -> InstallStatusAction.COMPLETE
    else -> InstallStatusAction.FAIL
}

internal fun isHuaweiOrHonorDevice(manufacturer: String?, brand: String?): Boolean {
    val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
    return names.any { it == "huawei" || it == "honor" }
}

internal fun directApkInstallerFlags(): Int = Intent.FLAG_GRANT_READ_URI_PERMISSION

internal fun directApkInstallerReturnsResult(): Boolean = true

internal enum class ApkInstallBridgeAction { LAUNCH_INSTALLER, AWAIT_RESULT, FINISH }

internal fun apkInstallBridgeAction(
    installerAlreadyLaunched: Boolean,
    hasApkUri: Boolean,
    persistedState: ApkInstallBridgeState = ApkInstallBridgeState.NONE,
): ApkInstallBridgeAction = when {
    !hasApkUri -> ApkInstallBridgeAction.FINISH
    installerAlreadyLaunched || persistedState == ApkInstallBridgeState.ACTIVE ->
        ApkInstallBridgeAction.AWAIT_RESULT
    persistedState == ApkInstallBridgeState.LAUNCHING -> ApkInstallBridgeAction.LAUNCH_INSTALLER
    else -> ApkInstallBridgeAction.FINISH
}

internal fun shouldCloseInstallBridgeOnResume(
    installerLaunched: Boolean,
    bridgePausedForInstaller: Boolean,
    returningToLinReads: Boolean = false,
    recoveredActiveWithoutSavedState: Boolean = false,
): Boolean = installerLaunched &&
    (bridgePausedForInstaller || recoveredActiveWithoutSavedState) &&
    !returningToLinReads

internal fun shouldRemoveInstallBridgeTask(mainActivityLaunchSucceeded: Boolean): Boolean =
    mainActivityLaunchSucceeded

internal enum class ApkInstallBridgeState {
    NONE,
    LAUNCHING,
    ACTIVE,
    DEFERRED,
    DEFERRED_WITHOUT_NOTIFICATION,
    FAILED,
}

internal enum class BridgeLaunchClaimAction { CLAIM, KEEP_WAITING, REJECT }

internal fun bridgeLaunchClaimAction(
    state: ApkInstallBridgeState,
    userInitiated: Boolean,
): BridgeLaunchClaimAction = when (state) {
    ApkInstallBridgeState.NONE -> BridgeLaunchClaimAction.CLAIM
    ApkInstallBridgeState.LAUNCHING,
    ApkInstallBridgeState.ACTIVE,
    -> BridgeLaunchClaimAction.KEEP_WAITING
    ApkInstallBridgeState.DEFERRED,
    ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
    ApkInstallBridgeState.FAILED,
    -> if (userInitiated) BridgeLaunchClaimAction.CLAIM else BridgeLaunchClaimAction.REJECT
}

internal enum class BridgeNotificationAction { RESUME_TASK, RETRY_INSTALLER, KEEP_WAITING }

internal enum class ApkInstallRetryEntryPoint { ACTIVITY }

internal fun apkInstallRetryEntryPoint(): ApkInstallRetryEntryPoint =
    ApkInstallRetryEntryPoint.ACTIVITY

internal fun bridgeNotificationAction(
    state: ApkInstallBridgeState,
    hasBridgeTask: Boolean,
): BridgeNotificationAction = when {
    state == ApkInstallBridgeState.ACTIVE && hasBridgeTask -> BridgeNotificationAction.RESUME_TASK
    state == ApkInstallBridgeState.LAUNCHING -> BridgeNotificationAction.KEEP_WAITING
    else -> BridgeNotificationAction.RETRY_INSTALLER
}

internal fun bridgeStateForRetryRoute(notificationPosted: Boolean): ApkInstallBridgeState =
    if (notificationPosted) {
        ApkInstallBridgeState.DEFERRED
    } else {
        ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION
    }

internal fun canPublishBridgeNotification(
    currentDownloadId: Long,
    requestedDownloadId: Long,
    currentState: ApkInstallBridgeState,
    requiredState: ApkInstallBridgeState?,
): Boolean = currentDownloadId == requestedDownloadId &&
    (requiredState == null || currentState == requiredState)

internal fun shouldPublishInstallNotification(
    appNotificationsAllowed: Boolean,
    channelImportance: Int?,
): Boolean = appNotificationsAllowed && channelImportance != NotificationManager.IMPORTANCE_NONE

internal fun apkInstallBridgeFlags(callerIsActivity: Boolean): Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

internal enum class ActiveBridgeForegroundAction { KEEP_NOTIFICATION_ROUTE, MOVE_TASK_TO_FRONT }

internal fun activeBridgeForegroundAction(
    notificationPosted: Boolean,
): ActiveBridgeForegroundAction = if (notificationPosted) {
    ActiveBridgeForegroundAction.KEEP_NOTIFICATION_ROUTE
} else {
    ActiveBridgeForegroundAction.MOVE_TASK_TO_FRONT
}

internal fun shouldPublishStagingFailureNotification(
    failureBelongsToCurrentDownload: Boolean,
): Boolean = failureBelongsToCurrentDownload

internal fun isBridgeLaunchStale(
    state: ApkInstallBridgeState,
    hasBridgeTask: Boolean,
    claimedProcessId: Int?,
    currentProcessId: Int,
    claimedAtElapsedMs: Long?,
    nowElapsedMs: Long,
    timeoutMs: Long = BRIDGE_LAUNCH_TIMEOUT_MS,
): Boolean {
    if (state != ApkInstallBridgeState.LAUNCHING || hasBridgeTask) return false
    if (claimedProcessId == null || claimedProcessId != currentProcessId) return true
    val claimedAt = claimedAtElapsedMs ?: return true
    val age = nowElapsedMs - claimedAt
    return age < 0L || age >= timeoutMs
}

internal enum class BridgeForegroundFallbackAction {
    NONE,
    KEEP_EXPLICIT_RETRY,
    ARM_NEXT_FOREGROUND,
}

internal fun bridgeForegroundFallbackAction(
    state: ApkInstallBridgeState,
): BridgeForegroundFallbackAction = when (state) {
    ApkInstallBridgeState.NONE -> BridgeForegroundFallbackAction.NONE
    ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION ->
        BridgeForegroundFallbackAction.ARM_NEXT_FOREGROUND
    else -> BridgeForegroundFallbackAction.KEEP_EXPLICIT_RETRY
}

internal fun shouldSuppressAutomaticBridgeReopen(
    currentDownloadId: Long,
    bridgeDownloadId: Long?,
    bridgeState: ApkInstallBridgeState,
): Boolean = bridgeDownloadId == currentDownloadId && bridgeState != ApkInstallBridgeState.NONE

internal fun installEnqueueAction(
    currentDownloadId: Long,
    currentStage: InstallStage?,
    requestedDownloadId: Long,
    retryRequested: Boolean = false,
): InstallEnqueueAction = when {
    currentDownloadId != requestedDownloadId -> InstallEnqueueAction.START
    retryRequested && currentStage in setOf(
        InstallStage.COMMITTED,
        InstallStage.AWAITING_USER,
        InstallStage.FAILED,
    ) ->
        InstallEnqueueAction.START
    currentStage in setOf(InstallStage.STAGING, InstallStage.COMMITTED, InstallStage.AWAITING_USER) ->
        InstallEnqueueAction.KEEP_EXISTING
    else -> InstallEnqueueAction.START
}

internal object UpdatePackageInstaller {
    private val lock = Any()

    fun requestInstall(
        context: Context,
        downloadId: Long,
        apkUri: Uri,
        retryRequested: Boolean = false,
    ): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) return false
        val appContext = context.applicationContext
        val previousSessionId: Int
        val previousDownloadId: Long
        synchronized(lock) {
            val prefs = appContext.installPreferences()
            if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return false
            previousDownloadId = prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD)
            val currentStage = prefs.installStage()
            if (
                previousDownloadId != downloadId &&
                    shouldDeferUpdateReplacement(currentStage, prefs.installBridgeState())
            ) {
                return true
            }
            val action = installEnqueueAction(
                currentDownloadId = previousDownloadId,
                currentStage = currentStage,
                requestedDownloadId = downloadId,
                retryRequested = retryRequested,
            )
            if (action == InstallEnqueueAction.KEEP_EXISTING) return true

            previousSessionId = prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
            val expectedVersion = prefs.getLong(KEY_DOWNLOAD_VERSION_CODE, -1L).takeIf { it > 0L }
            val editor = prefs.edit()
                .putLong(KEY_INSTALL_DOWNLOAD_ID, downloadId)
                .putString(KEY_INSTALL_STAGE, InstallStage.STAGING.name)
                .remove(KEY_INSTALL_SESSION_ID)
                .remove(KEY_INSTALL_EXPECTED_VERSION_CODE)
                .remove(KEY_INSTALL_ERROR)
                .remove(KEY_INSTALL_BRIDGE_STATE)
                .remove(KEY_INSTALL_BRIDGE_TASK_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
            expectedVersion?.let { editor.putLong(KEY_INSTALL_EXPECTED_VERSION_CODE, it) }
            editor.apply()
        }

        if (previousDownloadId != NO_DOWNLOAD && previousDownloadId != downloadId) {
            WorkManager.getInstance(appContext).cancelUniqueWork(workName(previousDownloadId))
        }
        if (previousSessionId != NO_SESSION) {
            runCatching { appContext.packageManager.packageInstaller.abandonSession(previousSessionId) }
        }

        val work = OneTimeWorkRequestBuilder<UpdateInstallWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(INPUT_DOWNLOAD_ID, downloadId)
                    .putString(INPUT_APK_URI, apkUri.toString())
                    .build(),
            )
            .build()
        return runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                workName(downloadId),
                if (retryRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                work,
            )
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                recordStagingFailure(appContext, downloadId, error)
                false
            },
        )
    }

    fun markAwaitingUser(context: Context, sessionId: Int) {
        updateStageForSession(context, sessionId, InstallStage.AWAITING_USER)
    }

    fun isCurrentSession(context: Context, sessionId: Int): Boolean = synchronized(lock) {
        context.installPreferences().getInt(KEY_INSTALL_SESSION_ID, NO_SESSION) == sessionId
    }

    fun currentSessionId(context: Context): Int = synchronized(lock) {
        context.installPreferences().getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
    }

    fun expectedVersionForCurrentSession(context: Context): Long? = synchronized(lock) {
        val prefs = context.installPreferences()
        if (prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION) == NO_SESSION) return@synchronized null
        prefs.getLong(KEY_INSTALL_EXPECTED_VERSION_CODE, -1L).takeIf { it > 0L }
    }

    fun commitSession(context: Context, sessionId: Int, statusSender: IntentSender): Boolean {
        if (!isCurrentSession(context, sessionId)) return false
        return runCatching {
            context.packageManager.packageInstaller.openSession(sessionId).use { session ->
                session.commit(statusSender)
            }
        }.isSuccess
    }

    fun commitSessionWithInAppCallback(context: Context, sessionId: Int): Boolean =
        commitSession(context, sessionId, statusIntent(context, sessionId).intentSender)

    fun downloadIdForSession(context: Context, sessionId: Int): Long? = synchronized(lock) {
        val prefs = context.installPreferences()
        prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD).takeIf {
            it != NO_DOWNLOAD && prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION) == sessionId
        }
    }

    fun markFailed(context: Context, sessionId: Int, message: String) {
        val prefs = context.installPreferences()
        synchronized(lock) {
            if (prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION) != sessionId) return
            prefs.edit()
                .putString(KEY_INSTALL_STAGE, InstallStage.FAILED.name)
                .putString(KEY_INSTALL_ERROR, message)
                .apply()
        }
    }

    fun clearCompleted(context: Context, sessionId: Int) {
        val prefs = context.installPreferences()
        synchronized(lock) {
            if (prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION) != sessionId) return
            prefs.edit()
                .remove(KEY_INSTALL_DOWNLOAD_ID)
                .remove(KEY_INSTALL_SESSION_ID)
                .remove(KEY_INSTALL_EXPECTED_VERSION_CODE)
                .remove(KEY_INSTALL_STAGE)
                .remove(KEY_INSTALL_ERROR)
                .remove(KEY_INSTALL_BRIDGE_STATE)
                .remove(KEY_INSTALL_BRIDGE_TASK_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
                .apply()
        }
    }

    fun clearRecordedInstall(context: Context) {
        val appContext = context.applicationContext
        val sessionId = synchronized(lock) {
            val prefs = appContext.installPreferences()
            val recorded = prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
            prefs.edit()
                .remove(KEY_INSTALL_DOWNLOAD_ID)
                .remove(KEY_INSTALL_SESSION_ID)
                .remove(KEY_INSTALL_EXPECTED_VERSION_CODE)
                .remove(KEY_INSTALL_STAGE)
                .remove(KEY_INSTALL_ERROR)
                .remove(KEY_INSTALL_BRIDGE_STATE)
                .remove(KEY_INSTALL_BRIDGE_TASK_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
                .apply()
            recorded
        }
        if (sessionId != NO_SESSION) {
            runCatching { appContext.packageManager.packageInstaller.abandonSession(sessionId) }
        }
        runCatching {
            appContext.getSystemService(NotificationManager::class.java).cancel(INSTALL_NOTIFICATION_ID)
        }
    }

    fun activateDownloadReplacement(
        context: Context,
        downloadId: Long,
        apkUrl: String,
        buildTag: String?,
        versionCode: Long?,
    ): Boolean {
        val appContext = context.applicationContext
        var previousInstallDownloadId = NO_DOWNLOAD
        var previousSessionId = NO_SESSION
        val activated = synchronized(lock) {
            val prefs = appContext.installPreferences()
            val replacementDeferred = shouldDeferUpdateReplacement(
                installStage = prefs.installStage(),
                bridgeState = prefs.installBridgeState(),
            )
            if (replacementDeferred) return@synchronized false

            previousInstallDownloadId = prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD)
            previousSessionId = prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
            val editor = prefs.edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_DOWNLOAD_URL, apkUrl)
                .putString(KEY_DOWNLOAD_TAG, buildTag)
                .putString(KEY_DOWNLOAD_BACKEND, DOWNLOAD_BACKEND_DOWNLOAD_MANAGER)
                .remove(KEY_DOWNLOAD_STATE)
                .remove(KEY_DOWNLOAD_STARTED_AT)
                .remove(KEY_DOWNLOAD_APK_PATH)
                .remove(KEY_DOWNLOAD_BYTES)
                .remove(KEY_DOWNLOAD_TOTAL)
                .remove(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING)
                .remove(KEY_POST_INSTALL_ARMED_TAG)
                .remove(KEY_INSTALL_DOWNLOAD_ID)
                .remove(KEY_INSTALL_SESSION_ID)
                .remove(KEY_INSTALL_EXPECTED_VERSION_CODE)
                .remove(KEY_INSTALL_STAGE)
                .remove(KEY_INSTALL_ERROR)
                .remove(KEY_INSTALL_BRIDGE_STATE)
                .remove(KEY_INSTALL_BRIDGE_TASK_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
            if (versionCode == null) {
                editor.remove(KEY_DOWNLOAD_VERSION_CODE)
            } else {
                editor.putLong(KEY_DOWNLOAD_VERSION_CODE, versionCode)
            }
            shouldActivateEnqueuedUpdate(
                metadataCommitted = editor.commit(),
                replacementDeferredAfterEnqueue = replacementDeferred,
            )
        }
        if (!activated) return false

        if (previousInstallDownloadId != NO_DOWNLOAD) {
            WorkManager.getInstance(appContext).cancelUniqueWork(workName(previousInstallDownloadId))
        }
        if (previousSessionId != NO_SESSION) {
            runCatching { appContext.packageManager.packageInstaller.abandonSession(previousSessionId) }
        }
        runCatching {
            appContext.getSystemService(NotificationManager::class.java).cancel(INSTALL_NOTIFICATION_ID)
        }
        return true
    }

    fun recordSession(context: Context, downloadId: Long, sessionId: Int) {
        synchronized(lock) {
            val prefs = context.installPreferences()
            check(prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId) {
                "安装包已被较新的下载替换"
            }
            prefs.edit().putInt(KEY_INSTALL_SESSION_ID, sessionId).apply()
        }
    }

    fun updateStageForSession(context: Context, sessionId: Int, stage: InstallStage) {
        synchronized(lock) {
            val prefs = context.installPreferences()
            if (prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION) != sessionId) return
            prefs.edit().putString(KEY_INSTALL_STAGE, stage.name).apply()
        }
    }

    fun recordStagingFailure(
        context: Context,
        downloadId: Long,
        error: Throwable,
        publishNotification: Boolean = false,
    ): Boolean = synchronized(lock) {
        val prefs = context.installPreferences()
        val belongsToCurrentDownload =
            prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId
        if (belongsToCurrentDownload) {
            prefs.edit()
                .putString(KEY_INSTALL_STAGE, InstallStage.FAILED.name)
                .putString(KEY_INSTALL_ERROR, error.message ?: error.javaClass.simpleName)
                .apply()
        }
        if (
            publishNotification &&
            shouldPublishStagingFailureNotification(belongsToCurrentDownload)
        ) {
            postInstallFailureNotification(context, error.message ?: "无法准备更新安装")
        }
        belongsToCurrentDownload
    }

    fun abandonRecordedSession(context: Context) {
        val sessionId = context.installPreferences().getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
        if (sessionId != NO_SESSION) {
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            context.installPreferences().edit().remove(KEY_INSTALL_SESSION_ID).apply()
        }
    }

    fun isCurrentDownload(context: Context, downloadId: Long): Boolean =
        context.installPreferences().getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId

    fun currentDownloadId(context: Context): Long = synchronized(lock) {
        context.installPreferences().getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD)
    }

    fun stageForDownload(context: Context, downloadId: Long): InstallStage? {
        val prefs = context.installPreferences()
        return prefs.installStage().takeIf {
            prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId
        }
    }

    fun bridgeStateForDownload(context: Context, downloadId: Long): ApkInstallBridgeState {
        val prefs = context.installPreferences()
        return prefs.installBridgeState().takeIf {
            prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId
        } ?: ApkInstallBridgeState.NONE
    }

    fun bridgeTaskIdForDownload(context: Context, downloadId: Long): Int? {
        val prefs = context.installPreferences()
        if (prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return null
        return prefs.getInt(KEY_INSTALL_BRIDGE_TASK_ID, NO_BRIDGE_TASK).takeIf {
            it != NO_BRIDGE_TASK
        }
    }

    fun bridgeAppTaskForDownload(
        context: Context,
        downloadId: Long,
    ): ActivityManager.AppTask? {
        val taskId = bridgeTaskIdForDownload(context, downloadId) ?: return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                it.taskInfo.taskId == taskId
            }
        }.getOrNull()
    }

    fun claimBridgeLaunch(
        context: Context,
        downloadId: Long,
        userInitiated: Boolean,
    ): BridgeLaunchClaimAction = synchronized(lock) {
        val prefs = context.installPreferences()
        if (prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) {
            return@synchronized BridgeLaunchClaimAction.REJECT
        }
        val action = bridgeLaunchClaimAction(prefs.installBridgeState(), userInitiated)
        if (action == BridgeLaunchClaimAction.CLAIM) {
            val committed = prefs.edit()
                .putString(KEY_INSTALL_BRIDGE_STATE, ApkInstallBridgeState.LAUNCHING.name)
                .remove(KEY_INSTALL_BRIDGE_TASK_ID)
                .putInt(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID, Process.myPid())
                .putLong(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS, SystemClock.elapsedRealtime())
                .commit()
            if (!committed) return@synchronized BridgeLaunchClaimAction.REJECT
        }
        action
    }

    fun activateBridgeForDownload(
        context: Context,
        downloadId: Long,
        taskId: Int,
    ): Boolean = synchronized(lock) {
        val prefs = context.installPreferences()
        if (
            prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId ||
            prefs.installBridgeState() != ApkInstallBridgeState.LAUNCHING
        ) {
            return@synchronized false
        }
        prefs.edit()
            .putString(KEY_INSTALL_BRIDGE_STATE, ApkInstallBridgeState.ACTIVE.name)
            .putInt(KEY_INSTALL_BRIDGE_TASK_ID, taskId)
            .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
            .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
            .commit()
    }

    fun updateBridgeStateForDownload(
        context: Context,
        downloadId: Long,
        state: ApkInstallBridgeState,
    ): Boolean = synchronized(lock) {
        val prefs = context.installPreferences()
        if (prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) {
            return@synchronized false
        }
        val editor = prefs.edit()
        if (state == ApkInstallBridgeState.NONE) {
            editor.remove(KEY_INSTALL_BRIDGE_STATE)
        } else {
            editor.putString(KEY_INSTALL_BRIDGE_STATE, state.name)
        }
        if (state !in setOf(ApkInstallBridgeState.LAUNCHING, ApkInstallBridgeState.ACTIVE)) {
            editor.remove(KEY_INSTALL_BRIDGE_TASK_ID)
        }
        if (state != ApkInstallBridgeState.LAUNCHING) {
            editor.remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
            editor.remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
        }
        editor.commit()
    }

    fun recoverStaleBridgeLaunch(context: Context, downloadId: Long): Boolean = synchronized(lock) {
        val prefs = context.installPreferences()
        if (prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) {
            return@synchronized false
        }
        if (
            !isBridgeLaunchStale(
                state = prefs.installBridgeState(),
                hasBridgeTask = prefs.getInt(KEY_INSTALL_BRIDGE_TASK_ID, NO_BRIDGE_TASK) !=
                    NO_BRIDGE_TASK,
                claimedProcessId = prefs.getInt(
                    KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID,
                    NO_BRIDGE_CLAIM_PROCESS,
                ).takeIf { it != NO_BRIDGE_CLAIM_PROCESS },
                currentProcessId = Process.myPid(),
                claimedAtElapsedMs = prefs.getLong(
                    KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS,
                    NO_BRIDGE_CLAIM_TIME,
                ).takeIf { it != NO_BRIDGE_CLAIM_TIME },
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
        ) {
            return@synchronized false
        }
        prefs.edit()
            .remove(KEY_INSTALL_BRIDGE_STATE)
            .remove(KEY_INSTALL_BRIDGE_TASK_ID)
            .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
            .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
            .commit()
    }

    fun publishBridgeNotification(
        context: Context,
        downloadId: Long,
        apkUri: Uri,
        requiredState: ApkInstallBridgeState? = null,
        deferWhenUnavailable: Boolean = false,
    ): Boolean = synchronized(lock) {
        val prefs = context.installPreferences()
        if (
            !canPublishBridgeNotification(
                currentDownloadId = prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD),
                requestedDownloadId = downloadId,
                currentState = prefs.installBridgeState(),
                requiredState = requiredState,
            )
        ) {
            return@synchronized false
        }
        val posted = postApkInstallNotification(context, downloadId, apkUri)
        if (!posted && deferWhenUnavailable) {
            prefs.edit()
                .putString(
                    KEY_INSTALL_BRIDGE_STATE,
                    bridgeStateForRetryRoute(notificationPosted = false).name,
                )
                .remove(KEY_INSTALL_BRIDGE_TASK_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
                .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
                .commit()
        }
        posted
    }

    private fun armBridgeRetryForNextForeground(
        context: Context,
        downloadId: Long,
    ): Boolean = synchronized(lock) {
        val prefs = context.installPreferences()
        if (
            prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId ||
            prefs.installBridgeState() != ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION
        ) {
            return@synchronized false
        }
        prefs.edit()
            .remove(KEY_INSTALL_BRIDGE_STATE)
            .remove(KEY_INSTALL_BRIDGE_TASK_ID)
            .remove(KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID)
            .remove(KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS)
            .commit()
    }

    fun resumeAwaitingInstallerOnForeground(
        context: Context,
        downloadId: Long,
        apkUri: Uri,
    ): Boolean {
        recoverStaleBridgeLaunch(context, downloadId)
        val stage = stageForDownload(context, downloadId)
        val prefs = context.installPreferences()
        val sessionId = prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
        val bridgeDownloadId = prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD)
            .takeIf { it != NO_DOWNLOAD }
        val bridgeState = prefs.installBridgeState()
        when (bridgeState) {
            ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION -> {
                if (armBridgeRetryForNextForeground(context, downloadId)) return true
            }
            ApkInstallBridgeState.DEFERRED,
            ApkInstallBridgeState.FAILED,
            -> {
                publishBridgeNotification(
                    context = context,
                    downloadId = downloadId,
                    apkUri = apkUri,
                    requiredState = bridgeState,
                    deferWhenUnavailable = true,
                )
                return true
            }
            ApkInstallBridgeState.ACTIVE -> {
                val appTask = bridgeAppTaskForDownload(context, downloadId)
                if (appTask == null) {
                    if (
                        updateBridgeStateForDownload(
                            context,
                            downloadId,
                            ApkInstallBridgeState.DEFERRED,
                        )
                    ) {
                        publishBridgeNotification(
                            context = context,
                            downloadId = downloadId,
                            apkUri = apkUri,
                            requiredState = ApkInstallBridgeState.DEFERRED,
                            deferWhenUnavailable = true,
                        )
                    }
                } else {
                    val notificationPosted = publishBridgeNotification(
                        context = context,
                        downloadId = downloadId,
                        apkUri = apkUri,
                        requiredState = ApkInstallBridgeState.ACTIVE,
                    )
                    if (
                        activeBridgeForegroundAction(notificationPosted) ==
                        ActiveBridgeForegroundAction.MOVE_TASK_TO_FRONT
                    ) {
                        runCatching { appTask.moveToFront() }
                    }
                }
                return true
            }
            ApkInstallBridgeState.LAUNCHING -> {
                publishBridgeNotification(
                    context = context,
                    downloadId = downloadId,
                    apkUri = apkUri,
                    requiredState = ApkInstallBridgeState.LAUNCHING,
                )
                return true
            }
            ApkInstallBridgeState.NONE -> Unit
        }
        val sessionInfoResult = if (sessionId == NO_SESSION) {
            Result.success(null)
        } else {
            runCatching { context.packageManager.packageInstaller.getSessionInfo(sessionId) }
        }
        val sessionInfo = sessionInfoResult.getOrNull()
        return when (
            foregroundInstallRecoveryAction(
                stage = stage,
                hasRecoverableSession = sessionInfo != null,
                sessionIsActive = sessionInfo?.isActive == true,
                sessionQueryFailed = sessionInfoResult.isFailure,
                bridgeRetrySuppressed = shouldSuppressAutomaticBridgeReopen(
                    currentDownloadId = downloadId,
                    bridgeDownloadId = bridgeDownloadId,
                    bridgeState = bridgeState,
                ),
            )
        ) {
            ForegroundInstallRecoveryAction.NONE -> false
            ForegroundInstallRecoveryAction.KEEP_AWAITING -> true
            ForegroundInstallRecoveryAction.LAUNCH_DOWNLOADED_APK -> {
                // startActivity() can be silently aborted by the system. Keep a user-triggered
                // route alive until PackageInstaller reaches a terminal state.
                publishBridgeNotification(context, downloadId, apkUri)
                cancelUpdateDetectionNotification(context)
                launchApkInstaller(context, downloadId, apkUri)
                true
            }
            ForegroundInstallRecoveryAction.RECOMMIT_SESSION -> {
                val expectedVersion = expectedVersionForCurrentSession(context)
                val helperPrepared = expectedVersion != null && UpdateHelperBridge.prepareCallback(
                    context = context,
                    sessionId = sessionId,
                    expectedVersion = expectedVersion,
                )
                val recommitted = helperPrepared || commitSessionWithInAppCallback(context, sessionId)
                if (!recommitted) {
                    val followUpQuery = runCatching {
                        context.packageManager.packageInstaller.getSessionInfo(sessionId)
                    }
                    if (
                        shouldRestageAfterRecommitFailure(
                            sessionQuerySucceeded = followUpQuery.isSuccess,
                            hasRecoverableSession = followUpQuery.getOrNull() != null,
                        )
                    ) {
                        requestInstall(context, downloadId, apkUri, retryRequested = true)
                    }
                }
                true
            }
            ForegroundInstallRecoveryAction.RESTAGE_DOWNLOADED_APK -> {
                requestInstall(context, downloadId, apkUri, retryRequested = true)
                true
            }
        }
    }
}

class UpdateInstallWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {

    override fun doWork(): Result {
        val downloadId = inputData.getLong(INPUT_DOWNLOAD_ID, NO_DOWNLOAD)
        val apkUri = inputData.getString(INPUT_APK_URI)?.let(Uri::parse)
        if (downloadId == NO_DOWNLOAD || apkUri == null) return Result.failure()
        if (!UpdatePackageInstaller.isCurrentDownload(applicationContext, downloadId)) return Result.success()
        if (!applicationContext.packageManager.canRequestPackageInstalls()) return Result.failure()
        if (UpdatePackageInstaller.stageForDownload(applicationContext, downloadId) in
            setOf(InstallStage.COMMITTED, InstallStage.AWAITING_USER)
        ) {
            return Result.success()
        }

        var sessionId = NO_SESSION
        return try {
            UpdatePackageInstaller.abandonRecordedSession(applicationContext)
            val packageInstaller = applicationContext.packageManager.packageInstaller
            val size = applicationContext.contentResolver.openAssetFileDescriptor(apkUri, "r")
                ?.use { descriptor -> descriptor.length }
                ?: -1L
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(applicationContext.packageName)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (size > 0) setSize(size)
                selfUpdateUserActionRequirement(Build.VERSION.SDK_INT)?.let(::setRequireUserAction)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
            sessionId = packageInstaller.createSession(params)
            UpdatePackageInstaller.recordSession(applicationContext, downloadId, sessionId)

            packageInstaller.openSession(sessionId).use { session ->
                applicationContext.contentResolver.openInputStream(apkUri).use { input ->
                    requireNotNull(input) { "无法读取下载完成的安装包" }
                    session.openWrite(APK_SESSION_NAME, 0, size).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (isStopped || !UpdatePackageInstaller.isCurrentDownload(applicationContext, downloadId)) {
                                throw InstallSupersededException()
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        session.fsync(output)
                    }
                }
                UpdatePackageInstaller.updateStageForSession(
                    applicationContext,
                    sessionId,
                    InstallStage.COMMITTED,
                )
                if (!armPostInstallTakeover(applicationContext, downloadId)) {
                    throw InstallSupersededException()
                }
            }
            val expectedVersion = UpdatePackageInstaller.expectedVersionForCurrentSession(applicationContext)
            val helperPrepared = expectedVersion != null && UpdateHelperBridge.prepareCallback(
                context = applicationContext,
                sessionId = sessionId,
                expectedVersion = expectedVersion,
            )
            if (
                !helperPrepared &&
                !UpdatePackageInstaller.commitSessionWithInAppCallback(applicationContext, sessionId)
            ) {
                error("无法提交系统安装会话")
            }
            Result.success()
        } catch (_: InstallSupersededException) {
            disarmPostInstallTakeover(applicationContext, downloadId)
            if (sessionId != NO_SESSION) {
                runCatching { applicationContext.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            Result.success()
        } catch (error: Throwable) {
            disarmPostInstallTakeover(applicationContext, downloadId)
            if (sessionId != NO_SESSION) {
                runCatching { applicationContext.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            UpdatePackageInstaller.recordStagingFailure(
                context = applicationContext,
                downloadId = downloadId,
                error = error,
                publishNotification = true,
            )
            Result.failure()
        }
    }
}

class UpdateInstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeStatusIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeStatusIntent(intent)
    }

    private fun consumeStatusIntent(statusIntent: Intent) {
        handleInstallStatus(this, statusIntent)
        finish()
    }
}

class UpdateInstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handleInstallStatus(context, intent)
    }
}

private fun handleInstallStatus(context: Context, intent: Intent) {
    val sessionId = intent.getIntExtra(
        EXTRA_SESSION_ID,
        intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION),
    )
    if (sessionId == NO_SESSION) return
    if (!UpdatePackageInstaller.isCurrentSession(context, sessionId)) return
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    when (installStatusAction(status)) {
        InstallStatusAction.HANDLE_PENDING_USER_ACTION -> {
            UpdatePackageInstaller.markAwaitingUser(context, sessionId)
            val dlId = UpdatePackageInstaller.downloadIdForSession(context, sessionId)
            val apkUri = dlId?.let { downloadId ->
                context.getSystemService(DownloadManager::class.java)
                    .getUriForDownloadedFile(downloadId)
            }
            val confirmation = intent.installConfirmationIntent()
            when (
                pendingUserActionLaunch(
                    isHuaweiOrHonor = isHuaweiOrHonorDevice(
                        manufacturer = Build.MANUFACTURER,
                        brand = Build.BRAND,
                    ),
                    hasDownloadedApk = apkUri != null,
                    hasSystemConfirmation = confirmation != null,
                )
            ) {
                PendingUserActionLaunch.DIRECT_APK_INSTALL -> {
                    val uri = requireNotNull(apkUri)
                    val downloadId = requireNotNull(dlId)
                    UpdatePackageInstaller.publishBridgeNotification(context, downloadId, uri)
                    cancelUpdateDetectionNotification(context)
                    launchApkInstaller(context, downloadId, uri)
                }
                PendingUserActionLaunch.SYSTEM_CONFIRMATION -> {
                    val systemConfirmation = requireNotNull(confirmation)
                    postSystemConfirmationNotification(context, sessionId, systemConfirmation)
                    cancelUpdateDetectionNotification(context)
                    launchSystemConfirmation(context, systemConfirmation)
                }
                PendingUserActionLaunch.FAILURE -> {
                    dlId?.let { downloadId -> disarmPostInstallTakeover(context, downloadId) }
                    UpdatePackageInstaller.markFailed(context, sessionId, "系统未返回安装确认页面")
                    cancelUpdateDetectionNotification(context)
                    postInstallFailureNotification(context, "系统未返回安装确认页面")
                }
            }
        }
        InstallStatusAction.COMPLETE -> {
            UpdatePackageInstaller.clearCompleted(context, sessionId)
            cancelUpdateNotifications(context)
            launchMainAfterInstall(context)
        }
        InstallStatusAction.FAIL -> {
            UpdatePackageInstaller.downloadIdForSession(context, sessionId)?.let { downloadId ->
                disarmPostInstallTakeover(context, downloadId)
            }
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?: "系统安装失败（状态 $status）"
            UpdatePackageInstaller.markFailed(context, sessionId, message)
            postInstallFailureNotification(context, message)
        }
    }
}

private fun launchMainAfterInstall(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
    )
}.isSuccess

private fun statusIntent(context: Context, sessionId: Int): PendingIntent {
    val intent = Intent(context, UpdateInstallStatusActivity::class.java).apply {
        data = Uri.parse("linreads://update-install/$sessionId")
        putExtra(EXTRA_SESSION_ID, sessionId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val options = installStatusActivityBackgroundLaunchMode(Build.VERSION.SDK_INT)?.let { mode ->
        ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(mode)
            .toBundle()
    }
    return PendingIntent.getActivity(context, sessionId, intent, flags, options)
}

private fun Intent.installConfirmationIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

private fun canPostInstallNotification(context: Context): Boolean {
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val appNotificationsAllowed = shouldPostUpdateNotification(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = permissionGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
    val channelImportance = context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(INSTALL_CHANNEL_ID)
        ?.importance
    return shouldPublishInstallNotification(appNotificationsAllowed, channelImportance)
}

internal fun launchApkInstaller(
    context: Context,
    downloadId: Long,
    apkUri: Uri,
    userInitiated: Boolean = false,
): Boolean {
    return when (
        UpdatePackageInstaller.claimBridgeLaunch(context, downloadId, userInitiated)
    ) {
        BridgeLaunchClaimAction.KEEP_WAITING -> true
        BridgeLaunchClaimAction.REJECT -> false
        BridgeLaunchClaimAction.CLAIM -> runCatching {
            context.startActivity(
                apkInstallBridgeIntent(
                    context = context,
                    downloadId = downloadId,
                    apkUri = apkUri,
                ),
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                val stateUpdated = UpdatePackageInstaller.updateBridgeStateForDownload(
                    context,
                    downloadId,
                    ApkInstallBridgeState.FAILED,
                )
                if (stateUpdated) {
                    UpdatePackageInstaller.publishBridgeNotification(
                        context = context,
                        downloadId = downloadId,
                        apkUri = apkUri,
                        requiredState = ApkInstallBridgeState.FAILED,
                        deferWhenUnavailable = true,
                    )
                }
                false
            },
        )
    }
}

internal fun apkInstallIntent(apkUri: Uri) = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
    data = apkUri
    addFlags(directApkInstallerFlags())
    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    putExtra(Intent.EXTRA_RETURN_RESULT, directApkInstallerReturnsResult())
}

private fun apkInstallBridgeIntent(
    context: Context,
    downloadId: Long,
    apkUri: Uri,
) =
    Intent(context, UpdateApkInstallActivity::class.java).apply {
        data = apkUri
        putExtra(EXTRA_BRIDGE_DOWNLOAD_ID, downloadId)
        addFlags(apkInstallBridgeFlags(callerIsActivity = context is Activity))
    }

private fun launchSystemConfirmation(context: Context, confirmation: Intent): Boolean =
    runCatching {
        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

private fun postSystemConfirmationNotification(
    context: Context,
    sessionId: Int,
    confirmation: Intent,
): Boolean = runCatching {
    if (!canPostInstallNotification(context)) {
        false
    } else {
        createInstallNotificationChannel(context)
        val pendingConfirmation = PendingIntent.getActivity(
            context,
            sessionId,
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            INSTALL_NOTIFICATION_ID,
            NotificationCompat.Builder(context, INSTALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("LinReads 更新已准备好")
                .setContentText("点击确认安装")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingConfirmation)
                .build(),
        )
        true
    }
}.getOrDefault(false)

private fun postApkInstallNotification(context: Context, downloadId: Long, apkUri: Uri): Boolean =
    runCatching {
        if (!canPostInstallNotification(context)) {
            false
        } else {
            createInstallNotificationChannel(context)
            val pendingInstaller = PendingIntent.getActivity(
                context,
                downloadId.hashCode(),
                Intent(context, UpdateApkInstallResumeActivity::class.java).apply {
                    data = apkUri
                    putExtra(EXTRA_BRIDGE_DOWNLOAD_ID, downloadId)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(NotificationManager::class.java).notify(
                INSTALL_NOTIFICATION_ID,
                NotificationCompat.Builder(context, INSTALL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("LinReads 更新已准备好")
                    .setContentText("点击确认安装")
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingInstaller)
                    .build(),
            )
            true
        }
    }.getOrDefault(false)

private fun postInstallFailureNotification(context: Context, message: String) {
    runCatching {
        if (!canPostInstallNotification(context)) return@runCatching
        createInstallNotificationChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            INSTALL_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            INSTALL_NOTIFICATION_ID,
            NotificationCompat.Builder(context, INSTALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("LinReads 更新安装失败")
                .setContentText(message.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build(),
        )
    }
}

private fun cancelUpdateNotifications(context: Context) {
    runCatching {
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(UPDATE_DETECTION_NOTIFICATION_ID)
            cancel(INSTALL_NOTIFICATION_ID)
        }
    }
}

private fun cancelUpdateDetectionNotification(context: Context) {
    runCatching {
        context.getSystemService(NotificationManager::class.java)
            .cancel(UPDATE_DETECTION_NOTIFICATION_ID)
    }
}

private fun createInstallNotificationChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(INSTALL_CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_HIGH),
    )
}

private fun android.content.SharedPreferences.installStage(): InstallStage? =
    getString(KEY_INSTALL_STAGE, null)
        ?.let { value -> runCatching { InstallStage.valueOf(value) }.getOrNull() }

private fun android.content.SharedPreferences.installBridgeState(): ApkInstallBridgeState =
    getString(KEY_INSTALL_BRIDGE_STATE, null)
        ?.let { value -> runCatching { ApkInstallBridgeState.valueOf(value) }.getOrNull() }
        ?: ApkInstallBridgeState.NONE

private fun Context.installPreferences() = getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)

private fun workName(downloadId: Long) = "linreads-update-install-$downloadId"

private class InstallSupersededException : Exception()

private const val UPDATE_PREFS_NAME = "update"
private const val KEY_INSTALL_DOWNLOAD_ID = "install_dl_id"
private const val KEY_INSTALL_SESSION_ID = "install_session_id"
private const val KEY_INSTALL_EXPECTED_VERSION_CODE = "install_expected_version_code"
private const val KEY_INSTALL_STAGE = "install_stage"
private const val KEY_INSTALL_ERROR = "install_error"
private const val KEY_INSTALL_BRIDGE_STATE = "install_bridge_state"
private const val KEY_INSTALL_BRIDGE_TASK_ID = "install_bridge_task_id"
private const val KEY_INSTALL_BRIDGE_CLAIM_PROCESS_ID = "install_bridge_claim_process_id"
private const val KEY_INSTALL_BRIDGE_CLAIM_ELAPSED_MS = "install_bridge_claim_elapsed_ms"
private const val KEY_DOWNLOAD_VERSION_CODE = "dl_version_code"
private const val INPUT_DOWNLOAD_ID = "download_id"
private const val INPUT_APK_URI = "apk_uri"
private const val EXTRA_SESSION_ID = "linreads_install_session_id"
internal const val EXTRA_BRIDGE_DOWNLOAD_ID = "linreads_install_download_id"
private const val APK_SESSION_NAME = "base.apk"
private const val INSTALL_CHANNEL_ID = "linreads_update"
private const val INSTALL_NOTIFICATION_ID = 9002
private const val NO_DOWNLOAD = -1L
private const val NO_SESSION = -1
private const val NO_BRIDGE_TASK = -1
private const val NO_BRIDGE_CLAIM_PROCESS = -1
private const val NO_BRIDGE_CLAIM_TIME = -1L
private const val BRIDGE_LAUNCH_TIMEOUT_MS = 10_000L
