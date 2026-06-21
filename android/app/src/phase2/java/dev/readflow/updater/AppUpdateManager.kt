package dev.readflow.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.readflow.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Checks for a newer GitHub release on every app foreground.
 * If found, posts a system notification; tapping it downloads and launches the installer.
 * Does not touch any feature module — lives entirely in :app.
 */
object AppUpdateManager {

    private const val CHANNEL_ID = "linreads_update"
    private const val NOTIF_ID = 9001
    private const val PREFS_NAME = "update"
    private const val KEY_CACHED_NOTES = "cached_notes"

    private val checker = GitHubUpdateChecker(
        repoSlug = BuildConfig.GITHUB_REPO,
        currentTag = BuildConfig.BUILD_TAG,
    )

    fun checkOnForeground(context: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val info = runCatching { checker.check() }.getOrNull() ?: return@launch
            postNotification(context.applicationContext, info)
        }
    }

    /** Returns (apkUrl, notes) if newer build available, null otherwise. */
    suspend fun checkForUpdate(context: Context): Pair<String, String>? {
        val info = checker.check() ?: return null
        val notes = extractNotes(info.notes)
        // Cache notes for always-visible display
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CACHED_NOTES, notes).apply()
        return info.apkUrl to notes
    }

    /** Get the last cached update notes (survives app restart). */
    fun getCachedNotes(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_NOTES, "") ?: ""

    /** Extract human-readable notes from release body, keeping only real content. */
    private fun extractNotes(raw: String): String {
        // Split at "---" separator: content before separator is the commit message
        val parts = raw.split("\n---\n", limit = 2)
        val body = parts.firstOrNull()?.trim() ?: ""
        // If body is empty, try filtering technical metadata (old format fallback)
        if (body.isBlank()) {
            return raw.lineSequence()
                .filterNot { it.startsWith("BUILD_TAG:") || it.startsWith("Commit:") ||
                            it.startsWith("Branch:") || it.startsWith("Time:") ||
                            it.startsWith("---") }
                .joinToString("\n").trim()
        }
        return body
    }

    private fun postNotification(ctx: Context, info: UpdateInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_HIGH),
        )

        // Tapping the notification triggers download + install via a BroadcastReceiver
        val installIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, UpdateInstallReceiver::class.java).apply {
                putExtra("apk_url", info.apkUrl)
                putExtra("tag_name", info.tagName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("LinReads 新版本可用")
                .setContentText("${info.tagName}  —  点击下载安装")
                .setAutoCancel(true)
                .setContentIntent(installIntent)
                .build(),
        )
    }
}
