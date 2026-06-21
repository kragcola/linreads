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
        val authToken = intent.getStringExtra("auth_token") ?: ""

        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val prevId = prefs.getLong("dl_id", -1)

        // Check if we have a completed download already
        if (prevId != -1L) {
            val dm = context.getSystemService(DownloadManager::class.java)
            val q = dm.query(DownloadManager.Query().setFilterById(prevId))
            if (q.moveToFirst()) {
                val status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                q.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // Old completed download exists - verify it's still valid
                        val uri = dm.getUriForDownloadedFile(prevId)
                        if (uri != null) {
                            launchInstaller(context, uri)
                            return
                        }
                        // File missing, clean up and re-download
                        dm.remove(prevId)
                    }
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        // Download already in progress - do nothing
                        return
                    }
                    else -> {
                        // Failed/paused - clean up and restart
                        dm.remove(prevId)
                    }
                }
            } else {
                q.close()
            }
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
                if (authToken.isNotEmpty()) addRequestHeader("Authorization", "Bearer $authToken")
                setTitle("LinReads 更新下载中")
                setDescription("正在下载新版本…")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, null, "update.apk")
            }
        )
        prefs.edit().putLong("dl_id", dlId).apply()
    }

    private fun launchInstaller(context: Context, uri: Uri) {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun onDownloadComplete(context: Context, intent: Intent) {
        val dlId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val savedId = context.getSharedPreferences("update", Context.MODE_PRIVATE)
            .getLong("dl_id", -1)
        if (dlId != savedId) return

        val dm = context.getSystemService(DownloadManager::class.java)
        val apkUri = dm.getUriForDownloadedFile(dlId) ?: return

        // Automatically launch installer after download completes
        launchInstaller(context, apkUri)

        // Also post a notification as a fallback if auto-install doesn't trigger
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
