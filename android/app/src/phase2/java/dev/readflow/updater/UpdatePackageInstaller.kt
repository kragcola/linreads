package dev.readflow.updater

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    LAUNCH_DOWNLOADED_APK,
    RECOMMIT_SESSION,
    RESTAGE_DOWNLOADED_APK,
}

internal fun foregroundInstallRecoveryAction(
    stage: InstallStage?,
    hasRecoverableSession: Boolean,
    sessionIsActive: Boolean = false,
    sessionQueryFailed: Boolean = false,
): ForegroundInstallRecoveryAction = when {
    stage == InstallStage.AWAITING_USER -> ForegroundInstallRecoveryAction.LAUNCH_DOWNLOADED_APK
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
    isHuaweiOrHonor && hasDownloadedApk -> PendingUserActionLaunch.DIRECT_APK_INSTALL
    isHuaweiOrHonor -> PendingUserActionLaunch.FAILURE
    hasSystemConfirmation -> PendingUserActionLaunch.SYSTEM_CONFIRMATION
    hasDownloadedApk -> PendingUserActionLaunch.DIRECT_APK_INSTALL
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

internal fun isHuaweiOrHonorDevice(manufacturer: String?, brand: String?): Boolean {
    val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
    return names.any { it == "huawei" || it == "honor" }
}

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
            previousDownloadId = prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD)
            val currentStage = prefs.installStage()
            val action = installEnqueueAction(
                currentDownloadId = previousDownloadId,
                currentStage = currentStage,
                requestedDownloadId = downloadId,
                retryRequested = retryRequested,
            )
            if (action == InstallEnqueueAction.KEEP_EXISTING) return true

            previousSessionId = prefs.getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
            prefs.edit()
                .putLong(KEY_INSTALL_DOWNLOAD_ID, downloadId)
                .putString(KEY_INSTALL_STAGE, InstallStage.STAGING.name)
                .remove(KEY_INSTALL_SESSION_ID)
                .remove(KEY_INSTALL_ERROR)
                .apply()
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
                .remove(KEY_INSTALL_STAGE)
                .remove(KEY_INSTALL_ERROR)
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
                .remove(KEY_INSTALL_STAGE)
                .remove(KEY_INSTALL_ERROR)
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

    fun recordStagingFailure(context: Context, downloadId: Long, error: Throwable) {
        synchronized(lock) {
            val prefs = context.installPreferences()
            if (prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return
            prefs.edit()
                .putString(KEY_INSTALL_STAGE, InstallStage.FAILED.name)
                .putString(KEY_INSTALL_ERROR, error.message ?: error.javaClass.simpleName)
                .apply()
        }
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

    fun stageForDownload(context: Context, downloadId: Long): InstallStage? {
        val prefs = context.installPreferences()
        return prefs.installStage().takeIf {
            prefs.getLong(KEY_INSTALL_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId
        }
    }

    fun resumeAwaitingInstallerOnForeground(
        context: Context,
        downloadId: Long,
        apkUri: Uri,
    ): Boolean {
        val stage = stageForDownload(context, downloadId)
        val sessionId = context.installPreferences().getInt(KEY_INSTALL_SESSION_ID, NO_SESSION)
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
            )
        ) {
            ForegroundInstallRecoveryAction.NONE -> false
            ForegroundInstallRecoveryAction.LAUNCH_DOWNLOADED_APK -> {
                // startActivity() can be silently aborted by the system. Keep a user-triggered
                // route alive until PackageInstaller reaches a terminal state.
                postApkInstallNotification(context, downloadId.hashCode(), apkUri)
                cancelUpdateDetectionNotification(context)
                launchApkInstaller(context, apkUri)
                true
            }
            ForegroundInstallRecoveryAction.RECOMMIT_SESSION -> {
                val recommitted = runCatching {
                    context.packageManager.packageInstaller.openSession(sessionId).use { session ->
                        session.commit(statusIntent(context, sessionId).intentSender)
                    }
                }.isSuccess
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
                session.commit(statusIntent(applicationContext, sessionId).intentSender)
            }
            Result.success()
        } catch (_: InstallSupersededException) {
            if (sessionId != NO_SESSION) {
                runCatching { applicationContext.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            Result.success()
        } catch (error: Throwable) {
            if (sessionId != NO_SESSION) {
                runCatching { applicationContext.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            UpdatePackageInstaller.recordStagingFailure(applicationContext, downloadId, error)
            postInstallFailureNotification(applicationContext, error.message ?: "无法准备更新安装")
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
        finishAndRemoveTask()
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
    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
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
                    postApkInstallNotification(context, sessionId, uri)
                    cancelUpdateDetectionNotification(context)
                    launchApkInstaller(context, uri)
                }
                PendingUserActionLaunch.SYSTEM_CONFIRMATION -> {
                    val systemConfirmation = requireNotNull(confirmation)
                    postSystemConfirmationNotification(context, sessionId, systemConfirmation)
                    cancelUpdateDetectionNotification(context)
                    launchSystemConfirmation(context, systemConfirmation)
                }
                PendingUserActionLaunch.FAILURE -> {
                    UpdatePackageInstaller.markFailed(context, sessionId, "系统未返回安装确认页面")
                    cancelUpdateDetectionNotification(context)
                    postInstallFailureNotification(context, "系统未返回安装确认页面")
                }
            }
        }
        PackageInstaller.STATUS_SUCCESS -> {
            UpdatePackageInstaller.clearCompleted(context, sessionId)
            cancelUpdateNotifications(context)
        }
        else -> {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?: "系统安装失败（状态 $status）"
            UpdatePackageInstaller.markFailed(context, sessionId, message)
            postInstallFailureNotification(context, message)
        }
    }
}

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
    return shouldPostUpdateNotification(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = permissionGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
}

private fun launchApkInstaller(context: Context, apkUri: Uri): Boolean = runCatching {
    context.startActivity(apkInstallIntent(apkUri))
}.isSuccess

private fun apkInstallIntent(apkUri: Uri) = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
    data = apkUri
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    putExtra(Intent.EXTRA_RETURN_RESULT, false)
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

private fun postApkInstallNotification(context: Context, sessionId: Int, apkUri: Uri): Boolean =
    runCatching {
        if (!canPostInstallNotification(context)) {
            false
        } else {
            createInstallNotificationChannel(context)
            val pendingInstaller = PendingIntent.getActivity(
                context,
                sessionId,
                apkInstallIntent(apkUri),
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

private fun Context.installPreferences() = getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)

private fun workName(downloadId: Long) = "linreads-update-install-$downloadId"

private class InstallSupersededException : Exception()

private const val UPDATE_PREFS_NAME = "update"
private const val KEY_INSTALL_DOWNLOAD_ID = "install_dl_id"
private const val KEY_INSTALL_SESSION_ID = "install_session_id"
private const val KEY_INSTALL_STAGE = "install_stage"
private const val KEY_INSTALL_ERROR = "install_error"
private const val INPUT_DOWNLOAD_ID = "download_id"
private const val INPUT_APK_URI = "apk_uri"
private const val EXTRA_SESSION_ID = "linreads_install_session_id"
private const val APK_SESSION_NAME = "base.apk"
private const val INSTALL_CHANNEL_ID = "linreads_update"
private const val INSTALL_NOTIFICATION_ID = 9002
private const val NO_DOWNLOAD = -1L
private const val NO_SESSION = -1
