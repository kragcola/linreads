package dev.readflow.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val buildTag: String?,
    val apkUrl: String,
    val notes: String,
    val versionCode: Long? = null,
)

/**
 * Checks the `dev-latest` GitHub release for a newer build.
 * CI embeds both a build tag and a monotonically increasing version code in the release body.
 * A release without a version code remains available for an explicit manual update, but never
 * enters the unattended update path.
 */
class GitHubUpdateChecker(
    private val repoSlug: String,
    private val currentTag: String,
    private val currentVersionCode: Long,
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

            val releaseBuildTag = releaseBuildTagFromBody(body)
            val releaseVersionCode = releaseVersionCodeFromBody(body)

            if (!shouldOfferRelease(
                    releaseBuildTag = releaseBuildTag,
                    releaseVersionCode = releaseVersionCode,
                    currentTag = currentTag,
                    currentVersionCode = currentVersionCode,
                )
            ) {
                return@withContext null
            }

            val assets = root.getJSONArray("assets")
            val apkAssetName = otaApkAssetName(releaseVersionCode)
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == apkAssetName) {
                    apkUrl = a.getString("browser_download_url"); break
                }
            }
            apkUrl?.let {
                UpdateInfo(
                    tagName = root.getString("tag_name"),
                    buildTag = releaseBuildTag,
                    apkUrl = it,
                    notes = body,
                    versionCode = releaseVersionCode,
                )
            }
                ?: throw IOException("release has no $apkAssetName asset")
        } catch (error: CancellationException) {
            throw error
        } catch (e: IOException) {
            throw e   // propagate — caller's runCatching will surface it
        } catch (e: Exception) {
            throw IOException("update check failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

}

internal fun releaseBuildTagFromBody(body: String): String? =
    releaseMetadataValue(body, "BUILD_TAG")

internal fun releaseVersionCodeFromBody(body: String): Long? =
    releaseMetadataValue(body, "VERSION_CODE")
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

internal fun otaApkAssetName(versionCode: Long?): String =
    versionCode?.takeIf { it > 0L }?.let { "app-ota-$it.apk" } ?: OTA_APK_ASSET_NAME

internal fun shouldOfferRelease(
    releaseBuildTag: String?,
    releaseVersionCode: Long?,
    currentTag: String,
    currentVersionCode: Long,
): Boolean = when {
    releaseVersionCode != null ->
        releaseVersionCode > currentVersionCode &&
            isVerifiedCiBuildIdentity(releaseBuildTag, releaseVersionCode)
    else -> releaseBuildTag != currentTag
}

internal fun isVerifiedCiBuildIdentity(buildTag: String?, versionCode: Long?): Boolean {
    if (buildTag == null || versionCode == null || versionCode <= 0L) return false
    val match = CI_BUILD_TAG.matchEntire(buildTag) ?: return false
    return match.groupValues[1].toLongOrNull() == versionCode
}

private fun releaseMetadataValue(body: String, key: String): String? {
    val lines = body.lineSequence().toList()
    val metadataStart = lines.indexOfLast { it.trim() == "---" } + 1
    if (metadataStart <= 0) return null
    return lines.drop(metadataStart)
        .firstOrNull { it.trimStart().startsWith("$key:") }
        ?.substringAfter("$key:")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private const val OTA_APK_ASSET_NAME = "app-ota.apk"
private val CI_BUILD_TAG = Regex("^dev-([1-9]\\d*)-([0-9a-fA-F]{40})$")
