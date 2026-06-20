package dev.readflow.updater

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Handles "install update" tap and DownloadManager completion. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            onDownloadComplete(context, intent)
        } else {
            onInstallRequested(context, intent)
        }
    }

    private fun onInstallRequested(context: Context, intent: Intent) {
        val apkUrl = intent.getStringExtra("apk_url") ?: return

        // Clean up previous download record (and its file) before starting a new one.
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val prevId = prefs.getLong("dl_id", -1)
        if (prevId != -1L) {
            context.getSystemService(DownloadManager::class.java).remove(prevId)
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        val dm = context.getSystemService(DownloadManager::class.java)
        val dlId = dm.enqueue(
            DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("LinReads 更新下载中")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalFilesDir(context, null, "update.apk")
            }
        )
        context.getSharedPreferences("update", Context.MODE_PRIVATE)
            .edit().putLong("dl_id", dlId).apply()
    }

    private fun onDownloadComplete(context: Context, intent: Intent) {
        val dlId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val savedId = context.getSharedPreferences("update", Context.MODE_PRIVATE)
            .getLong("dl_id", -1)
        if (dlId != savedId) return

        val dm = context.getSystemService(DownloadManager::class.java)
        val apkUri = dm.getUriForDownloadedFile(dlId) ?: return

        // Post "tap to install" notification — startActivity directly is blocked on API 29+
        val installIntent = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("linreads_update", "应用更新", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.notify(
            9002,
            androidx.core.app.NotificationCompat.Builder(context, "linreads_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("LinReads 下载完成")
                .setContentText("点击安装新版本")
                .setAutoCancel(true)
                .setContentIntent(installIntent)
                .build()
        )
    }
}
