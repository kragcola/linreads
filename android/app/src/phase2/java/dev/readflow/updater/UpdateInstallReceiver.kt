package dev.readflow.updater

import android.Manifest
import android.app.ActivityManager
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.readflow.BuildConfig
import dev.readflow.MainActivity
import dev.readflow.features.settings.createUpdateDownloadFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val UPDATE_EXTRA_APK_URL = "apk_url"
internal const val UPDATE_EXTRA_BUILD_TAG = "build_tag"
internal const val UPDATE_EXTRA_VERSION_CODE = "version_code"
internal const val UPDATE_EXTRA_AUTOMATIC = "automatic_update"

internal enum class CompletedUpdateAction { STAGE_INSTALL, REQUEST_UNKNOWN_SOURCES_PERMISSION }

internal fun completedUpdateAction(canRequestPackageInstalls: Boolean): CompletedUpdateAction =
    if (canRequestPackageInstalls) {
        CompletedUpdateAction.STAGE_INSTALL
    } else {
        CompletedUpdateAction.REQUEST_UNKNOWN_SOURCES_PERMISSION
    }

/** Automatic foreground checks must preserve an existing install session; a user retry may replace it. */
internal fun retryRequestedForUpdateRequest(automatic: Boolean): Boolean = !automatic

internal enum class ReusableDownloadAction { STAGE_EXISTING, KEEP_EXISTING, ENQUEUE_NEW }

internal fun automaticDownloadNotificationVisibility(): Int =
    DownloadManager.Request.VISIBILITY_VISIBLE

internal fun reusableDownloadAction(
    downloadStatus: Int?,
    hasDownloadedApk: Boolean,
    retryRequested: Boolean = false,
): ReusableDownloadAction = when {
    downloadStatus == DownloadManager.STATUS_SUCCESSFUL && hasDownloadedApk ->
        ReusableDownloadAction.STAGE_EXISTING
    downloadStatus == DownloadManager.STATUS_RUNNING || downloadStatus == DownloadManager.STATUS_PENDING ->
        if (retryRequested) ReusableDownloadAction.ENQUEUE_NEW else ReusableDownloadAction.KEEP_EXISTING
    else -> ReusableDownloadAction.ENQUEUE_NEW
}

internal fun isInstalledUpdateBuild(savedBuildTag: String?, currentBuildTag: String): Boolean =
    savedBuildTag != null && savedBuildTag == currentBuildTag

internal fun isPendingUpdateInstallable(
    versionCode: Long?,
    currentVersionCode: Long,
): Boolean = versionCode == null || versionCode > currentVersionCode

/** Called from the app's foreground lifecycle, never from a completion receiver. */
internal fun resumePendingUpdateOnForeground(context: Context) {
    val appContext = context.applicationContext
    cancelPostInstallCompletionNotification(appContext)
    val prefs = appContext.updatePreferences()
    if (isInstalledUpdateBuild(prefs.getString(KEY_DOWNLOAD_TAG, null), BuildConfig.BUILD_TAG)) {
        clearInstalledUpdateState(appContext)
        return
    }
    val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
    if (downloadId != NO_DOWNLOAD && !isPersistedUpdateInstallable(appContext, downloadId)) {
        discardPersistedUpdate(appContext, downloadId)
        return
    }
    if (!appContext.packageManager.canRequestPackageInstalls()) {
        if (!prefs.getBoolean(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING, false)) return
        prefs.edit().remove(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING).apply()
        runCatching { appContext.startActivity(unknownSourcesSettingsIntent(appContext)) }
            .onFailure {
                prefs.edit().putBoolean(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING, true).apply()
            }
        return
    }

    prefs.edit().remove(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING).apply()
    if (downloadId == NO_DOWNLOAD) return

    when (appOwnedDownloadState(appContext, downloadId)) {
        DownloadWorkState.COMPLETE -> {
            updateApkUri(appContext, downloadId)?.let { apkUri ->
                stageDownloadedUpdate(appContext, downloadId, apkUri)
                return
            }
            restartPersistedAppDownload(appContext, prefs)
            return
        }
        DownloadWorkState.RUNNING -> {
            restartPersistedAppDownload(appContext, prefs)
            return
        }
        DownloadWorkState.FAILED -> {
            restartPersistedAppDownload(appContext, prefs)
            return
        }
        DownloadWorkState.NONE -> Unit
    }

    if (isAppOwnedDownloadBackend(prefs.getString(KEY_DOWNLOAD_BACKEND, null))) {
        restartPersistedAppDownload(appContext, prefs)
        return
    }

    val apkUri = appContext.getSystemService(DownloadManager::class.java)
        .getUriForDownloadedFile(downloadId)
        ?: return
    synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
        if (shouldDeferCurrentInstallReplacement(appContext, downloadId)) return
        if (
            UpdatePackageInstaller.isCurrentDownload(appContext, downloadId) &&
                UpdatePackageInstaller.resumeAwaitingInstallerOnForeground(appContext, downloadId, apkUri)
        ) {
            return
        }
        UpdatePackageInstaller.requestInstall(appContext, downloadId, apkUri)
    }
}

private fun restartPersistedAppDownload(
    context: Context,
    prefs: android.content.SharedPreferences,
) {
    val apkUrl = prefs.getString(KEY_DOWNLOAD_URL, null) ?: return
    enqueueDownloadManagerUpdate(
        context = context,
        apkUrl = apkUrl,
        buildTag = prefs.getString(KEY_DOWNLOAD_TAG, null),
        versionCode = prefs.optionalVersionCode(KEY_DOWNLOAD_VERSION_CODE),
    )
}

private fun shouldDeferCurrentInstallReplacement(
    context: Context,
    requestedDownloadId: Long? = null,
): Boolean {
    val installDownloadId = UpdatePackageInstaller.currentDownloadId(context)
    if (installDownloadId == NO_DOWNLOAD || installDownloadId == requestedDownloadId) return false
    return shouldDeferUpdateReplacement(
        installStage = UpdatePackageInstaller.stageForDownload(context, installDownloadId),
        bridgeState = UpdatePackageInstaller.bridgeStateForDownload(context, installDownloadId),
    )
}

private fun enqueueDownloadManagerUpdate(
    context: Context,
    apkUrl: String,
    buildTag: String?,
    versionCode: Long?,
    authToken: String = BuildConfig.GITHUB_OTA_TOKEN,
    resetFailures: Boolean = true,
): Long? = synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
    val appContext = context.applicationContext
    val prefs = appContext.updatePreferences()
    val oldId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
    val oldBackend = prefs.getString(KEY_DOWNLOAD_BACKEND, null)
    if (shouldDeferCurrentInstallReplacement(appContext)) return@synchronized null

    val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
        if (authToken.isNotBlank() && shouldAttachUpdateAuthorization(apkUrl)) {
            addRequestHeader("Authorization", "Bearer $authToken")
        }
        setTitle("LinReads 更新下载中")
        setDescription("正在下载新版本…")
        setMimeType("application/vnd.android.package-archive")
        setNotificationVisibility(automaticDownloadNotificationVisibility())
        setDestinationInExternalFilesDir(appContext, null, createUpdateDownloadFileName())
    }
    val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    val downloadId = downloadManager.enqueue(request)
    if (
        !UpdatePackageInstaller.activateDownloadReplacement(
            context = appContext,
            downloadId = downloadId,
            apkUrl = apkUrl,
            buildTag = buildTag,
            versionCode = versionCode,
            resetFailures = shouldResetDownloadFailureCount(
                triggeredByDownloadFailure = !resetFailures,
            ),
        )
    ) {
        runCatching { downloadManager.remove(downloadId) }
        return@synchronized null
    }
    if (oldId != NO_DOWNLOAD) {
        if (isAppOwnedDownloadBackend(oldBackend)) {
            clearDownloadedUpdateArtifact(appContext, oldId)
        } else {
            runCatching { downloadManager.remove(oldId) }
        }
    }
    downloadId
}

internal fun requestExplicitUpdateDownload(
    context: Context,
    apkUrl: String,
    buildTag: String?,
    versionCode: Long?,
): Long? = synchronized(UPDATE_DOWNLOAD_LOCK) {
    if (
        !isExplicitUpdateRequestEligible(
            versionCode = versionCode,
            currentVersionCode = BuildConfig.OTA_VERSION_CODE.toLong(),
            reusesPersistedDownload = false,
        )
    ) {
        return@synchronized null
    }
    enqueueDownloadManagerUpdate(
        context = context.applicationContext,
        apkUrl = apkUrl,
        buildTag = buildTag,
        versionCode = versionCode,
    )
}

/** Handles automatic update starts, explicit retry taps, and DownloadManager completion. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            onDownloadComplete(context, intent)
        } else {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    onInstallRequested(context.applicationContext, intent)
                } catch (error: Throwable) {
                    Log.w(TAG, "update download request failed", error)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun onInstallRequested(context: Context, intent: Intent) =
        synchronized(UPDATE_DOWNLOAD_LOCK) {
            onInstallRequestedLocked(context, intent)
        }

    private fun onInstallRequestedLocked(context: Context, intent: Intent) {
        val apkUrl = intent.getStringExtra(UPDATE_EXTRA_APK_URL) ?: return
        val buildTag = intent.getStringExtra(UPDATE_EXTRA_BUILD_TAG)
        val versionCode = intent.optionalVersionCode(UPDATE_EXTRA_VERSION_CODE)
        val automatic = intent.getBooleanExtra(UPDATE_EXTRA_AUTOMATIC, false)
        if (automatic && !isAutomaticUpdateEligible(
                buildTag = buildTag,
                versionCode = versionCode,
                currentVersionCode = BuildConfig.OTA_VERSION_CODE.toLong(),
            )
        ) {
            return
        }
        val retryRequested = retryRequestedForUpdateRequest(automatic)

        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val prevId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
        val reusableDownload = canReuseUpdateDownload(
            savedUrl = prefs.getString(KEY_DOWNLOAD_URL, null),
            savedTag = prefs.getString(KEY_DOWNLOAD_TAG, null),
            savedVersionCode = prefs.optionalVersionCode(KEY_DOWNLOAD_VERSION_CODE),
            requestedUrl = apkUrl,
            requestedTag = buildTag,
            requestedVersionCode = versionCode,
        )
        if (
            !automatic &&
                !isExplicitUpdateRequestEligible(
                    versionCode = versionCode,
                    currentVersionCode = BuildConfig.OTA_VERSION_CODE.toLong(),
                    reusesPersistedDownload = reusableDownload,
                )
        ) {
            return
        }

        // System DownloadManager is the primary transport because it handles the GitHub CDN and
        // OEM network stack reliably. The WorkManager branch only resumes a legacy app-owned task.
        if (prevId != NO_DOWNLOAD) {
            val appState = appOwnedDownloadState(context, prevId)
            if (appState != DownloadWorkState.NONE) {
                val apkUri = if (appState == DownloadWorkState.COMPLETE) {
                    updateApkUri(context, prevId)
                } else {
                    null
                }
                if (reusableDownload) {
                    when (
                        appOwnedDownloadAction(
                            state = appState,
                            hasApk = apkUri != null,
                            stale = appOwnedDownloadIsStale(context, prevId),
                            retryRequested = retryRequested,
                        )
                    ) {
                        AppOwnedDownloadAction.STAGE_EXISTING -> {
                            stageDownloadedUpdate(context, prevId, requireNotNull(apkUri), retryRequested)
                            return
                        }
                        AppOwnedDownloadAction.KEEP_EXISTING -> return
                        AppOwnedDownloadAction.ENQUEUE_NEW -> Unit
                    }
                }
                enqueueDownloadManagerUpdate(
                    context = context,
                    apkUrl = apkUrl,
                    buildTag = buildTag,
                    versionCode = versionCode,
                    authToken = BuildConfig.GITHUB_OTA_TOKEN,
                )
                return
            }

            if (isAppOwnedDownloadBackend(prefs.getString(KEY_DOWNLOAD_BACKEND, null))) {
                enqueueDownloadManagerUpdate(
                    context = context,
                    apkUrl = apkUrl,
                    buildTag = buildTag,
                    versionCode = versionCode,
                    authToken = BuildConfig.GITHUB_OTA_TOKEN,
                )
                return
            }

            val dm = context.getSystemService(DownloadManager::class.java)
            if (reusableDownload) {
                val q = dm.query(DownloadManager.Query().setFilterById(prevId))
                val status = if (q.moveToFirst()) {
                    q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } else {
                    null
                }
                q.close()
                val apkUri = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    dm.getUriForDownloadedFile(prevId)
                } else {
                    null
                }
                when (reusableDownloadAction(status, apkUri != null, retryRequested)) {
                    ReusableDownloadAction.STAGE_EXISTING -> {
                        stageDownloadedUpdate(
                            context = context,
                            downloadId = prevId,
                            apkUri = requireNotNull(apkUri),
                            retryRequested = retryRequested,
                        )
                        return
                    }
                    ReusableDownloadAction.KEEP_EXISTING -> return
                    ReusableDownloadAction.ENQUEUE_NEW -> Unit
                }
            }
        }

        enqueueDownloadManagerUpdate(
            context = context,
            apkUrl = apkUrl,
            buildTag = buildTag,
            versionCode = versionCode,
            authToken = BuildConfig.GITHUB_OTA_TOKEN,
        )
    }

    private fun onDownloadComplete(context: Context, intent: Intent) {
        val dlId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (!shouldHandleDownloadManagerCompletion(prefs.getString(KEY_DOWNLOAD_BACKEND, null))) return
        val savedId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
        if (dlId != savedId) return

        val dm = context.getSystemService(DownloadManager::class.java)
        val apkUri = dm.getUriForDownloadedFile(dlId)
        if (apkUri != null) {
            resetPersistedDownloadFailures(context, dlId)
            stageDownloadedUpdate(context, dlId, apkUri)
            return
        }

        // A failed current task must not silently stay "downloading": retry inside a bounded cap,
        // then surface the failure with a tap-to-retry notification.
        val outcome = queryDownloadStatus(context, dlId) ?: return
        val (status, reason) = outcome
        val failures = prefs.getInt(KEY_DOWNLOAD_FAILURES, 0)
        if (downloadFailureRetry(status, failures)) {
            Log.w(TAG, "update download ${statusToText(status)} reason=$reason; re-enqueuing")
            prefs.edit().putInt(KEY_DOWNLOAD_FAILURES, failures + 1).apply()
            val apkUrl = prefs.getString(KEY_DOWNLOAD_URL, null)
            val buildTag = prefs.getString(KEY_DOWNLOAD_TAG, null)
            val versionCode = prefs.optionalVersionCode(KEY_DOWNLOAD_VERSION_CODE)
            if (apkUrl != null) {
                enqueueDownloadManagerUpdate(
                    context = context,
                    apkUrl = apkUrl,
                    buildTag = buildTag,
                    versionCode = versionCode,
                    authToken = BuildConfig.GITHUB_OTA_TOKEN,
                    resetFailures = false,
                )
            }
            return
        }

        Log.w(
            TAG,
            "update download ${statusToText(status)} reason=$reason; " +
                "automatic retries exhausted (${DOWNLOAD_FAILURE_RETRY_CAP})",
        )
        postDownloadFailureNotification(context)
    }
}

private const val TAG = "LinReadsUpdate"
private val UPDATE_DOWNLOAD_LOCK = Any()

class UpdatePostInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val tasks = runCatching {
            appContext.getSystemService(ActivityManager::class.java).appTasks
        }.getOrDefault(emptyList())
        val tasksByRole = tasks.groupBy { task ->
            val taskInfo = runCatching { task.taskInfo }.getOrNull()
            val rootComponent = taskInfo?.baseIntent?.component ?: taskInfo?.baseActivity
            postInstallTaskRole(
                packageName = appContext.packageName,
                rootPackageName = rootComponent?.packageName,
                rootClassName = rootComponent?.className,
            )
        }
        val mainTask = tasksByRole[PostInstallTaskRole.MAIN]?.firstOrNull()
        val installerTasks = tasksByRole[PostInstallTaskRole.INSTALLER].orEmpty()
        val prefs = appContext.updatePreferences()
        val actions = postInstallTakeoverActions(
            savedBuildTag = prefs.getString(KEY_DOWNLOAD_TAG, null),
            armedBuildTag = prefs.getString(KEY_POST_INSTALL_ARMED_TAG, null),
            currentBuildTag = BuildConfig.BUILD_TAG,
            handledBuildTag = prefs.getString(KEY_POST_INSTALL_HANDLED_TAG, null),
            hasMainTask = mainTask != null,
            installerTaskCount = installerTasks.size,
        )
        if (actions.isEmpty() || !claimPostInstallTakeover(appContext)) return

        val hadMainTask = mainTask != null
        var installerTaskIndex = 0
        var completionNotificationPosted = false
        actions.forEach { action ->
            when (action) {
                PostInstallTakeoverAction.POST_COMPLETION_NOTIFICATION -> {
                    completionNotificationPosted = postPostInstallCompletionNotification(appContext)
                }
                PostInstallTakeoverAction.MOVE_MAIN_TASK_TO_FRONT -> {
                    runCatching { requireNotNull(mainTask).moveToFront() }
                }
                PostInstallTakeoverAction.LAUNCH_MAIN_ACTIVITY -> {
                    launchMainActivity(appContext)
                }
                PostInstallTakeoverAction.REMOVE_INSTALLER_TASK -> {
                    val installerTask = installerTasks.getOrNull(installerTaskIndex++)
                    if (
                        installerTask != null &&
                        shouldRemovePostInstallInstallerTask(
                            hadMainTask = hadMainTask,
                            completionNotificationPosted = completionNotificationPosted,
                        )
                    ) {
                        runCatching { installerTask.finishAndRemoveTask() }
                    }
                }
            }
        }
    }
}

internal fun stageDownloadedUpdate(
    context: Context,
    downloadId: Long,
    apkUri: Uri,
    retryRequested: Boolean = false,
): Unit = synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
    // Installation ownership and download replacement must change under the same lock.
    if (!isPersistedUpdateInstallable(context, downloadId)) {
        discardPersistedUpdate(context, downloadId)
        return@synchronized
    }
    if (shouldDeferCurrentInstallReplacement(context, downloadId)) return@synchronized
    when (completedUpdateAction(context.packageManager.canRequestPackageInstalls())) {
        CompletedUpdateAction.STAGE_INSTALL -> {
            UpdatePackageInstaller.requestInstall(context, downloadId, apkUri, retryRequested)
        }
        CompletedUpdateAction.REQUEST_UNKNOWN_SOURCES_PERMISSION -> {
            requestUnknownSourcesPermission(context)
        }
    }
}

private fun isPersistedUpdateInstallable(context: Context, downloadId: Long): Boolean {
    val prefs = context.updatePreferences()
    return prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId &&
        isPendingUpdateInstallable(
            versionCode = prefs.optionalVersionCode(KEY_DOWNLOAD_VERSION_CODE),
            currentVersionCode = BuildConfig.OTA_VERSION_CODE.toLong(),
        )
}

private fun discardPersistedUpdate(context: Context, downloadId: Long) {
    val appContext = context.applicationContext
    val prefs = appContext.updatePreferences()
    if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return
    val appOwned = isAppOwnedDownloadBackend(prefs.getString(KEY_DOWNLOAD_BACKEND, null))
    if (appOwned) {
        clearDownloadedUpdateArtifact(appContext, downloadId)
    } else {
        runCatching { appContext.getSystemService(DownloadManager::class.java).remove(downloadId) }
    }
    UpdatePackageInstaller.clearRecordedInstall(appContext)
    prefs.edit()
        .remove(KEY_DOWNLOAD_ID)
        .remove(KEY_DOWNLOAD_URL)
        .remove(KEY_DOWNLOAD_TAG)
        .remove(KEY_DOWNLOAD_VERSION_CODE)
        .remove(KEY_DOWNLOAD_BACKEND)
        .remove(KEY_DOWNLOAD_STATE)
        .remove(KEY_DOWNLOAD_STARTED_AT)
        .remove(KEY_DOWNLOAD_APK_PATH)
        .remove(KEY_DOWNLOAD_BYTES)
        .remove(KEY_DOWNLOAD_TOTAL)
        .remove(KEY_DOWNLOAD_FAILURES)
        .remove(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING)
        .remove(KEY_POST_INSTALL_ARMED_TAG)
        .apply()
    runCatching {
        appContext.getSystemService(NotificationManager::class.java).apply {
            cancel(UPDATE_DETECTION_NOTIFICATION_ID)
            cancel(UNKNOWN_SOURCES_NOTIFICATION_ID)
        }
    }
}

private fun claimPostInstallTakeover(context: Context): Boolean = synchronized(POST_INSTALL_LOCK) {
    val prefs = context.updatePreferences()
    val actions = postInstallTakeoverActions(
        savedBuildTag = prefs.getString(KEY_DOWNLOAD_TAG, null),
        armedBuildTag = prefs.getString(KEY_POST_INSTALL_ARMED_TAG, null),
        currentBuildTag = BuildConfig.BUILD_TAG,
        handledBuildTag = prefs.getString(KEY_POST_INSTALL_HANDLED_TAG, null),
        hasMainTask = false,
        installerTaskCount = 0,
    )
    if (actions.isEmpty()) return@synchronized false
    prefs.edit().putString(KEY_POST_INSTALL_HANDLED_TAG, BuildConfig.BUILD_TAG).commit()
}

internal fun armPostInstallTakeover(context: Context, downloadId: Long): Boolean =
    synchronized(POST_INSTALL_LOCK) {
        val prefs = context.updatePreferences()
        if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return@synchronized false
        val buildTag = prefs.getString(KEY_DOWNLOAD_TAG, null) ?: return@synchronized false
        prefs.edit().putString(KEY_POST_INSTALL_ARMED_TAG, buildTag).commit()
    }

internal fun disarmPostInstallTakeover(context: Context, downloadId: Long) {
    synchronized(POST_INSTALL_LOCK) {
        val prefs = context.updatePreferences()
        if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return@synchronized
        prefs.edit().remove(KEY_POST_INSTALL_ARMED_TAG).apply()
    }
}

private fun clearInstalledUpdateState(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.updatePreferences()
    val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
    if (downloadId != NO_DOWNLOAD) {
        if (isAppOwnedDownloadBackend(prefs.getString(KEY_DOWNLOAD_BACKEND, null))) {
            clearDownloadedUpdateArtifact(appContext, downloadId)
        } else {
            runCatching { appContext.getSystemService(DownloadManager::class.java).remove(downloadId) }
        }
    }
    UpdatePackageInstaller.clearRecordedInstall(appContext)
    prefs.edit()
        .remove(KEY_DOWNLOAD_ID)
        .remove(KEY_DOWNLOAD_URL)
        .remove(KEY_DOWNLOAD_TAG)
        .remove(KEY_DOWNLOAD_VERSION_CODE)
        .remove(KEY_DOWNLOAD_BACKEND)
        .remove(KEY_DOWNLOAD_STATE)
        .remove(KEY_DOWNLOAD_STARTED_AT)
        .remove(KEY_DOWNLOAD_APK_PATH)
        .remove(KEY_DOWNLOAD_BYTES)
        .remove(KEY_DOWNLOAD_TOTAL)
        .remove(KEY_DOWNLOAD_FAILURES)
        .remove(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING)
        .remove(KEY_POST_INSTALL_ARMED_TAG)
        .apply()
    runCatching {
        appContext.getSystemService(NotificationManager::class.java).apply {
            cancel(UPDATE_DETECTION_NOTIFICATION_ID)
            cancel(UNKNOWN_SOURCES_NOTIFICATION_ID)
        }
    }
}

private fun launchMainActivity(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }
}

private fun postPostInstallCompletionNotification(context: Context): Boolean = runCatching {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(UPDATE_CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_HIGH),
    )
    val appNotificationsAllowed = canPostUpdateNotification(context)
    val channelImportance = context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(UPDATE_CHANNEL_ID)
        ?.importance
    if (!shouldPublishInstallNotification(appNotificationsAllowed, channelImportance)) {
        return@runCatching false
    }
    val openApp = PendingIntent.getActivity(
        context,
        POST_INSTALL_NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    NotificationManagerCompat.from(context).notify(
        POST_INSTALL_NOTIFICATION_ID,
        NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("LinReads 更新完成")
            .setContentText("点按返回 LinReads")
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build(),
    )
    true
}.getOrDefault(false)

private fun cancelPostInstallCompletionNotification(context: Context) {
    runCatching {
        context.getSystemService(NotificationManager::class.java)
            .cancel(POST_INSTALL_NOTIFICATION_ID)
    }
}

private fun requestUnknownSourcesPermission(context: Context) {
    val appContext = context.applicationContext
    appContext.updatePreferences().edit()
        .putBoolean(KEY_UNKNOWN_SOURCES_PERMISSION_PENDING, true)
        .apply()
    if (!runCatching { canPostUpdateNotification(appContext) }.getOrDefault(false)) return

    runCatching {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_HIGH),
        )
        val permissionIntent = PendingIntent.getActivity(
            appContext,
            UNKNOWN_SOURCES_NOTIFICATION_ID,
            unknownSourcesSettingsIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(appContext).notify(
            UNKNOWN_SOURCES_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("允许 LinReads 安装更新")
                .setContentText("请允许安装未知应用后继续安装已下载的更新")
                .setAutoCancel(true)
                .setContentIntent(permissionIntent)
                .build(),
        )
    }
}

private fun canPostUpdateNotification(context: Context): Boolean {
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    return shouldPostUpdateNotification(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = permissionGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
}

private fun queryDownloadStatus(
    context: Context,
    downloadId: Long,
): Pair<Int, Int>? = runCatching {
    val dm = context.getSystemService(DownloadManager::class.java)
    dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            status to reason
        } else {
            null
        }
    }
}.getOrNull()

private fun resetPersistedDownloadFailures(context: Context, downloadId: Long) {
    val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return
    prefs.edit().putInt(KEY_DOWNLOAD_FAILURES, 0).apply()
}

private fun statusToText(status: Int): String = when (status) {
    DownloadManager.STATUS_PENDING -> "pending"
    DownloadManager.STATUS_RUNNING -> "running"
    DownloadManager.STATUS_PAUSED -> "paused"
    DownloadManager.STATUS_SUCCESSFUL -> "successful"
    DownloadManager.STATUS_FAILED -> "failed"
    else -> "status=$status"
}

private fun postDownloadFailureNotification(context: Context) {
    runCatching {
        if (!canPostUpdateNotification(context)) return@runCatching
        val appContext = context.applicationContext
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_HIGH),
        )
        val prefs = appContext.getSharedPreferences("update", Context.MODE_PRIVATE)
        val retryIntent = PendingIntent.getBroadcast(
            appContext,
            UPDATE_DETECTION_NOTIFICATION_ID,
            Intent(appContext, UpdateInstallReceiver::class.java).apply {
                putExtra(UPDATE_EXTRA_APK_URL, prefs.getString(KEY_DOWNLOAD_URL, null))
                putExtra(UPDATE_EXTRA_BUILD_TAG, prefs.getString(KEY_DOWNLOAD_TAG, null))
                prefs.optionalVersionCode(KEY_DOWNLOAD_VERSION_CODE)?.let {
                    putExtra(UPDATE_EXTRA_VERSION_CODE, it)
                }
                putExtra(UPDATE_EXTRA_AUTOMATIC, false)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(appContext).notify(
            UPDATE_DETECTION_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("LinReads 更新下载失败")
                .setContentText("点按重试下载")
                .setAutoCancel(true)
                .setContentIntent(retryIntent)
                .build(),
        )
    }
}

private fun unknownSourcesSettingsIntent(context: Context) =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun Context.updatePreferences() = getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)

private fun Intent.optionalVersionCode(key: String): Long? =
    if (hasExtra(key)) getLongExtra(key, NO_VERSION_CODE).takeIf { it > 0L } else null

private fun android.content.SharedPreferences.optionalVersionCode(key: String): Long? =
    getLong(key, NO_VERSION_CODE).takeIf { it > 0L }

private const val UPDATE_PREFS_NAME = "update"
internal const val KEY_DOWNLOAD_ID = "dl_id"
internal const val KEY_DOWNLOAD_URL = "dl_url"
internal const val KEY_DOWNLOAD_TAG = "dl_tag"
private const val KEY_DOWNLOAD_VERSION_CODE = "dl_version_code"
internal const val KEY_DOWNLOAD_FAILURES = "dl_failures"
internal const val KEY_UNKNOWN_SOURCES_PERMISSION_PENDING = "unknown_sources_permission_pending"
internal const val KEY_POST_INSTALL_ARMED_TAG = "post_install_armed_tag"
internal const val KEY_POST_INSTALL_HANDLED_TAG = "post_install_handled_tag"
internal const val UPDATE_CHANNEL_ID = "linreads_update"
internal const val UNKNOWN_SOURCES_NOTIFICATION_ID = 9003
internal const val POST_INSTALL_NOTIFICATION_ID = 9004
private const val NO_DOWNLOAD = -1L
private const val NO_VERSION_CODE = -1L
private val POST_INSTALL_LOCK = Any()
