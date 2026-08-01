package dev.readflow.updater

import java.net.URI

internal enum class DownloadWriteMode {
    APPEND,
    RESTART,
    RETRY,
}

internal enum class DownloadWorkState {
    NONE,
    RUNNING,
    COMPLETE,
    FAILED,
}

internal enum class AppOwnedDownloadAction {
    STAGE_EXISTING,
    KEEP_EXISTING,
    ENQUEUE_NEW,
}

internal fun appOwnedDownloadAction(
    state: DownloadWorkState,
    hasApk: Boolean,
    stale: Boolean,
    retryRequested: Boolean,
): AppOwnedDownloadAction = when {
    state == DownloadWorkState.COMPLETE && hasApk -> AppOwnedDownloadAction.STAGE_EXISTING
    state == DownloadWorkState.RUNNING && !stale && !retryRequested ->
        AppOwnedDownloadAction.KEEP_EXISTING
    else -> AppOwnedDownloadAction.ENQUEUE_NEW
}

internal fun downloadWriteMode(
    existingBytes: Long,
    responseCode: Int,
    contentRangeStart: Long?,
): DownloadWriteMode = when {
    existingBytes <= 0L && responseCode == 200 -> DownloadWriteMode.RESTART
    existingBytes > 0L && responseCode == 206 && contentRangeStart == existingBytes ->
        DownloadWriteMode.APPEND
    responseCode == 200 -> DownloadWriteMode.RESTART
    else -> DownloadWriteMode.RETRY
}

internal fun redirectAuthorizationAllowed(
    originUrl: String,
    requestUrl: String,
): Boolean = runCatching {
    URI(originUrl).host.equals(URI(requestUrl).host, ignoreCase = true)
}.getOrDefault(false)

internal fun parseContentRangeStart(value: String?): Long? = value
    ?.substringBefore('/')
    ?.substringAfter(' ', "")
    ?.substringBefore('-')
    ?.toLongOrNull()

internal fun shouldRetryStaleDownload(
    state: DownloadWorkState,
    startedAtEpochMs: Long,
    nowEpochMs: Long,
    staleAfterMs: Long = 120_000L,
): Boolean = when (state) {
    DownloadWorkState.NONE,
    DownloadWorkState.FAILED,
    -> true
    DownloadWorkState.COMPLETE -> false
    DownloadWorkState.RUNNING ->
        startedAtEpochMs <= 0L || nowEpochMs - startedAtEpochMs >= staleAfterMs
}
