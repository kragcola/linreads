package dev.readflow.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val tagName: String, val apkUrl: String, val notes: String)

/**
 * Checks the `dev-latest` GitHub release for a newer build.
 * Uses HttpURLConnection + org.json (Android built-in — no extra deps).
 */
class GitHubUpdateChecker(
    private val repoSlug: String,
    private val currentTag: String,
) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$repoSlug/releases/tags/dev-latest")
            .openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 8_000; readTimeout = 8_000
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            val tagName = root.getString("tag_name")
            if (tagName == currentTag) return@withContext null
            val assets = root.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url"); break
                }
            }
            apkUrl?.let { UpdateInfo(tagName, it, root.optString("body")) }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(info: UpdateInfo, context: Context): File = withContext(Dispatchers.IO) {
        val dest = File(context.cacheDir, "update.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connect()
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        dest
    }

    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
