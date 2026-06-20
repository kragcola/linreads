package dev.readflow.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val tagName: String, val apkUrl: String, val notes: String)

/**
 * Checks the `dev-latest` GitHub release for a newer build.
 * Version identity: CI embeds `BUILD_TAG: <tag>` in the release body; we compare
 * that against BuildConfig.BUILD_TAG.  If absent (old release) we always show the
 * notification so the user is never stuck on a stale install.
 */
class GitHubUpdateChecker(
    private val repoSlug: String,
    private val currentTag: String,
) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$repoSlug/releases/tags/dev-latest")
            .openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            val token = dev.readflow.BuildConfig.GITHUB_OTA_TOKEN
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 8_000; readTimeout = 8_000
        }
        // HTTP status check is OUTSIDE the try block so IOException propagates to the caller.
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw IOException("HTTP $code — check GITHUB_OTA_TOKEN / repo visibility")
        }
        try {
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            val body = root.optString("body", "")

            val releaseBuildTag = body.lineSequence()
                .firstOrNull { it.startsWith("BUILD_TAG:") }
                ?.substringAfter("BUILD_TAG:")?.trim()

            if (releaseBuildTag != null && releaseBuildTag == currentTag) return@withContext null

            val assets = root.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url"); break
                }
            }
            apkUrl?.let { UpdateInfo(root.getString("tag_name"), it, body) }
                ?: throw IOException("release has no APK asset")
        } catch (e: IOException) {
            throw e   // propagate — caller's runCatching will surface it
        } catch (_: Exception) {
            null      // JSON/parse error: treat as no update (don't crash)
        } finally {
            conn.disconnect()
        }
    }

}
