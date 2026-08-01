package dev.readflow.updater

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal const val KEY_DOWNLOAD_BACKEND = "dl_backend"
internal const val KEY_DOWNLOAD_STATE = "dl_state"
internal const val KEY_DOWNLOAD_STARTED_AT = "dl_started_at"
internal const val KEY_DOWNLOAD_APK_PATH = "dl_apk_path"
internal const val KEY_DOWNLOAD_BYTES = "dl_bytes"
internal const val KEY_DOWNLOAD_TOTAL = "dl_total"
internal const val DOWNLOAD_BACKEND_APP_HTTP = "app_http"
internal const val DOWNLOAD_STATE_RUNNING = "running"
internal const val DOWNLOAD_STATE_COMPLETE = "complete"
internal const val DOWNLOAD_STATE_FAILED = "failed"
internal val UPDATE_DOWNLOAD_STATE_LOCK = Any()

private const val INPUT_DOWNLOAD_ID = "download_id"
private const val INPUT_DOWNLOAD_URL = "download_url"
private const val UPDATE_PREFS_NAME = "update"
private const val KEY_DOWNLOAD_VERSION_CODE = "dl_version_code"
private const val NO_DOWNLOAD = -1L
private const val MAX_REDIRECTS = 5
private const val MAX_RETRY_ATTEMPTS = 5
private const val UPDATE_DIRECTORY = "updates"
private const val APK_FILE_PREFIX = "update-"
private const val APK_FILE_SUFFIX = ".apk"
private const val PART_FILE_SUFFIX = ".part"
private const val STALE_DOWNLOAD_WINDOW_MS = 120_000L
private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
private val NEXT_DOWNLOAD_ID = AtomicLong(System.currentTimeMillis())

private val downloadHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(3, TimeUnit.MINUTES)
    .retryOnConnectionFailure(true)
    .build()

internal fun updateDownloadWorkName(downloadId: Long): String =
    "linreads-update-download-$downloadId"

internal fun enqueueAppOwnedUpdate(
    context: Context,
    apkUrl: String,
    buildTag: String?,
    versionCode: Long?,
    retryRequested: Boolean,
): Long {
    return synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
        val appContext = context.applicationContext
        val downloadId = NEXT_DOWNLOAD_ID.incrementAndGet()
        val prefs = appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        val oldId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
        if (oldId != NO_DOWNLOAD && oldId != downloadId) {
            UpdatePackageInstaller.clearRecordedInstall(appContext)
            runCatching {
                WorkManager.getInstance(appContext).cancelUniqueWork(updateDownloadWorkName(oldId))
            }
            clearDownloadedUpdateArtifact(appContext, oldId)
        }
        val editor = prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_URL, apkUrl)
            .putString(KEY_DOWNLOAD_TAG, buildTag)
            .putString(KEY_DOWNLOAD_BACKEND, DOWNLOAD_BACKEND_APP_HTTP)
            .putString(KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_RUNNING)
            .putLong(KEY_DOWNLOAD_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_DOWNLOAD_APK_PATH)
            .remove(KEY_DOWNLOAD_BYTES)
            .remove(KEY_DOWNLOAD_TOTAL)
        if (versionCode == null) {
            editor.remove(KEY_DOWNLOAD_VERSION_CODE)
        } else {
            editor.putLong(KEY_DOWNLOAD_VERSION_CODE, versionCode)
        }
        editor.commit()

        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                Data.Builder()
                    .putLong(INPUT_DOWNLOAD_ID, downloadId)
                    .putString(INPUT_DOWNLOAD_URL, apkUrl)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS,
            )
            .build()
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                updateDownloadWorkName(downloadId),
                if (retryRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure {
            prefs.edit()
                .putString(KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_FAILED)
                .apply()
        }
        downloadId
    }
}

internal fun appOwnedDownloadState(context: Context, downloadId: Long): DownloadWorkState {
    return synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
        val prefs = context.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        if (
            prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId ||
            prefs.getString(KEY_DOWNLOAD_BACKEND, null) != DOWNLOAD_BACKEND_APP_HTTP
        ) {
            return@synchronized DownloadWorkState.NONE
        }
        when (prefs.getString(KEY_DOWNLOAD_STATE, null)) {
            DOWNLOAD_STATE_RUNNING -> DownloadWorkState.RUNNING
            DOWNLOAD_STATE_COMPLETE -> DownloadWorkState.COMPLETE
            DOWNLOAD_STATE_FAILED -> DownloadWorkState.FAILED
            else -> DownloadWorkState.NONE
        }
    }
}

internal fun appOwnedDownloadIsStale(context: Context, downloadId: Long): Boolean {
    return synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
        val prefs = context.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        shouldRetryStaleDownload(
            state = appOwnedDownloadState(context, downloadId),
            startedAtEpochMs = prefs.getLong(KEY_DOWNLOAD_STARTED_AT, 0L),
            nowEpochMs = System.currentTimeMillis(),
            staleAfterMs = STALE_DOWNLOAD_WINDOW_MS,
        )
    }
}

internal fun updateApkUri(context: Context, downloadId: Long): Uri? {
    return synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId) return@synchronized null
        val path = prefs.getString(KEY_DOWNLOAD_APK_PATH, null)
        if (path != null) {
            val file = File(path)
            if (file.isFile && file.length() > 0L) {
                return@synchronized runCatching {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
            }
        }
        runCatching {
            appContext.getSystemService(android.app.DownloadManager::class.java)
                .getUriForDownloadedFile(downloadId)
        }.getOrNull()
    }
}

internal fun clearDownloadedUpdateArtifact(context: Context, downloadId: Long) {
    synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(updateDownloadWorkName(downloadId))
        }
        val path = prefs.getString(KEY_DOWNLOAD_APK_PATH, null)
        if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId) {
            path?.let { runCatching { File(it).delete() } }
        }
        runCatching { updateApkFile(appContext, downloadId).delete() }
        updatePartFile(appContext, downloadId).let { runCatching { it.delete() } }
        val editor = prefs.edit()
        if (prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId) {
            editor.remove(KEY_DOWNLOAD_BACKEND)
                .remove(KEY_DOWNLOAD_STATE)
                .remove(KEY_DOWNLOAD_STARTED_AT)
                .remove(KEY_DOWNLOAD_APK_PATH)
                .remove(KEY_DOWNLOAD_BYTES)
                .remove(KEY_DOWNLOAD_TOTAL)
                .apply()
        }
    }
}

class UpdateDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {

    override fun doWork(): Result {
        val downloadId = inputData.getLong(INPUT_DOWNLOAD_ID, NO_DOWNLOAD)
        val downloadUrl = inputData.getString(INPUT_DOWNLOAD_URL)
        if (downloadId == NO_DOWNLOAD || downloadUrl.isNullOrBlank()) return Result.failure()

        val prefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        if (
            prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) != downloadId ||
            prefs.getString(KEY_DOWNLOAD_BACKEND, null) != DOWNLOAD_BACKEND_APP_HTTP
        ) {
            return Result.success()
        }
        val started = synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
            if (!isCurrentDownload(downloadId)) {
                false
            } else {
                prefs.edit()
                    .putString(KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_RUNNING)
                    .putLong(KEY_DOWNLOAD_STARTED_AT, System.currentTimeMillis())
                    .commit()
            }
        }
        if (!started) return Result.success()

        return runCatching {
            downloadApk(downloadId, downloadUrl)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if (!isCurrentDownload(downloadId)) return@fold Result.success()
                if (runAttemptCount < MAX_RETRY_ATTEMPTS && error !is InvalidUpdateArtifactException) {
                    Result.retry()
                } else {
                    synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
                        if (isCurrentDownload(downloadId)) {
                            prefs.edit()
                                .putString(KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_FAILED)
                                .apply()
                        }
                    }
                    Result.failure()
                }
            },
        )
    }

    private fun downloadApk(downloadId: Long, downloadUrl: String) {
        val appContext = applicationContext
        if (!isCurrentDownload(downloadId)) throw SupersededDownloadException()
        val partFile = updatePartFile(appContext, downloadId)
        val finalFile = updateApkFile(appContext, downloadId)
        partFile.parentFile?.mkdirs()
        val existingBytes = partFile.length().takeIf { it > 0L } ?: 0L
        val response = executeDownload(downloadUrl, existingBytes)
        response.use { result ->
            val mode = downloadWriteMode(
                existingBytes = existingBytes,
                responseCode = result.code,
                contentRangeStart = parseContentRangeStart(result.header("Content-Range")),
            )
            if (!result.isSuccessful || mode == DownloadWriteMode.RETRY) {
                if (mode == DownloadWriteMode.RETRY && existingBytes > 0L) {
                    runCatching { partFile.delete() }
                }
                throw IOException("update download HTTP ${result.code}")
            }
            val body = result.body ?: throw IOException("update download has no body")
            val append = mode == DownloadWriteMode.APPEND
            val expectedBytes = if (body.contentLength() >= 0L) {
                if (append) existingBytes + body.contentLength() else body.contentLength()
            } else {
                -1L
            }
            var written = if (append) existingBytes else 0L
            var lastProgress = written
            body.byteStream().use { input ->
                FileOutputStream(partFile, append).buffered(64 * 1024).use { output ->
                    copyBody(
                        input = input,
                        output = output,
                        downloadId = downloadId,
                        written = written,
                        lastProgress = lastProgress,
                    )
                }
            }
            written = partFile.length()
            if (expectedBytes > 0L && written != expectedBytes) {
                throw IOException("update download size mismatch: $written/$expectedBytes")
            }
        }

        if (!isCurrentDownload(downloadId)) throw SupersededDownloadException()
        if (!finalFile.parentFile!!.exists() && !finalFile.parentFile!!.mkdirs()) {
            throw IOException("cannot create update directory")
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            throw IOException("cannot finalize update download")
        }
        if (finalFile.length() <= 0L) throw InvalidUpdateArtifactException("empty update APK")
        if (!isCurrentDownload(downloadId)) {
            runCatching { finalFile.delete() }
            throw SupersededDownloadException()
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            finalFile,
        )
        val recorded = synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
            if (!isCurrentDownload(downloadId)) {
                false
            } else {
                appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_COMPLETE)
                    .putString(KEY_DOWNLOAD_APK_PATH, finalFile.absolutePath)
                    .putLong(KEY_DOWNLOAD_BYTES, finalFile.length())
                    .putLong(KEY_DOWNLOAD_TOTAL, finalFile.length())
                    .commit()
            }
        }
        if (!recorded) {
            runCatching { finalFile.delete() }
            throw SupersededDownloadException()
        }
        stageDownloadedUpdate(appContext, downloadId, uri)
    }

    private fun copyBody(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        downloadId: Long,
        written: Long,
        lastProgress: Long,
    ) {
        var totalWritten = written
        var lastReported = lastProgress
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (isStopped) throw IOException("update download stopped")
            if (!isCurrentDownload(downloadId)) throw SupersededDownloadException()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            totalWritten += read
            if (totalWritten - lastReported >= PROGRESS_INTERVAL_BYTES) {
                synchronized(UPDATE_DOWNLOAD_STATE_LOCK) {
                    if (isCurrentDownload(downloadId)) {
                        applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putLong(KEY_DOWNLOAD_BYTES, totalWritten)
                            .putLong(KEY_DOWNLOAD_STARTED_AT, System.currentTimeMillis())
                            .apply()
                    }
                }
                setProgressAsync(
                    Data.Builder()
                        .putLong(KEY_DOWNLOAD_BYTES, totalWritten)
                        .putLong(KEY_DOWNLOAD_TOTAL, -1L)
                        .build(),
                )
                lastReported = totalWritten
            }
        }
        output.flush()
    }

    private fun executeDownload(downloadUrl: String, rangeStart: Long): Response {
        var currentUrl = downloadUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val parsedUrl = runCatching { URI(currentUrl) }
                .getOrElse { throw IOException("invalid update URL", it) }
            if (!parsedUrl.scheme.equals("https", ignoreCase = true)) {
                throw IOException("update download is not HTTPS")
            }
            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .apply {
                    val token = dev.readflow.BuildConfig.GITHUB_OTA_TOKEN
                    if (
                        token.isNotBlank() &&
                        redirectAuthorizationAllowed(downloadUrl, currentUrl)
                    ) {
                        header("Authorization", "Bearer $token")
                    }
                    if (rangeStart > 0L) header("Range", "bytes=$rangeStart-")
                }
                .build()
            val response = downloadHttpClient.newCall(request).execute()
            if (response.code !in 300..399) return response
            if (redirectCount == MAX_REDIRECTS) {
                response.close()
                throw IOException("too many update download redirects")
            }
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw IOException("update redirect has no location")
            val nextUrl = parsedUrl.resolve(location)
            if (!nextUrl.scheme.equals("https", ignoreCase = true)) {
                throw IOException("update redirect is not HTTPS")
            }
            currentUrl = nextUrl.toString()
        }
        error("unreachable")
    }

    private fun isCurrentDownload(downloadId: Long): Boolean =
        applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
            .let { prefs ->
                prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD) == downloadId &&
                    prefs.getString(KEY_DOWNLOAD_BACKEND, null) == DOWNLOAD_BACKEND_APP_HTTP
            }
}

private class InvalidUpdateArtifactException(message: String) : IOException(message)

private class SupersededDownloadException : IOException()

private fun updateDirectory(context: Context): File = File(context.filesDir, UPDATE_DIRECTORY)

private fun updateApkFile(context: Context, downloadId: Long): File =
    File(updateDirectory(context), "$APK_FILE_PREFIX$downloadId$APK_FILE_SUFFIX")

private fun updatePartFile(context: Context, downloadId: Long): File =
    File(updateDirectory(context), "$APK_FILE_PREFIX$downloadId$PART_FILE_SUFFIX")
