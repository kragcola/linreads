package dev.readflow.updater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
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

class UpdateInstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(
            EXTRA_SESSION_ID,
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION),
        )
        if (sessionId == NO_SESSION) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.installConfirmationIntent() ?: run {
                    UpdatePackageInstaller.markFailed(context, sessionId, "系统未返回安装确认页面")
                    postInstallFailureNotification(context, "系统未返回安装确认页面")
                    return
                }
                UpdatePackageInstaller.markAwaitingUser(context, sessionId)
                // Always post a notification rather than auto-starting the confirmation activity.
                // Calling startActivity() from a BroadcastReceiver with FLAG_ACTIVITY_NEW_TASK
                // unconditionally brings the OEM installer (or app store on some tablets) to the
                // foreground, causing the unwanted app-store redirect after each OTA install.
                // A user-initiated tap on the notification opens the confirmation gracefully.
                if (canPostInstallNotification(context)) {
                    postInstallConfirmationNotification(context, sessionId, confirmation)
                } else {
                    // No notification permission — try direct launch as a last resort.
                    runCatching {
                        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        UpdatePackageInstaller.markFailed(
                            context,
                            sessionId,
                            "无法打开系统安装确认页面，请返回 LinReads 重试",
                        )
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdatePackageInstaller.clearCompleted(context, sessionId)
                context.getSystemService(NotificationManager::class.java).cancel(INSTALL_NOTIFICATION_ID)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "系统安装失败（状态 $status）"
                UpdatePackageInstaller.markFailed(context, sessionId, message)
                postInstallFailureNotification(context, message)
            }
        }
    }
}

private fun statusIntent(context: Context, sessionId: Int): PendingIntent {
    val intent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
        data = Uri.parse("linreads://update-install/$sessionId")
        putExtra(EXTRA_SESSION_ID, sessionId)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(context, sessionId, intent, flags)
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

private fun postInstallConfirmationNotification(context: Context, sessionId: Int, confirmation: Intent) {
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
}

private fun postInstallFailureNotification(context: Context, message: String) {
    if (!canPostInstallNotification(context)) return
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
