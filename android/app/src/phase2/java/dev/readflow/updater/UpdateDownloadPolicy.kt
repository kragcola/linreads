package dev.readflow.updater

import android.app.DownloadManager
import java.net.URI

internal const val DOWNLOAD_BACKEND_DOWNLOAD_MANAGER = "download_manager"
internal const val DOWNLOAD_FAILURE_RETRY_CAP = 2

internal enum class PersistedDownloadBackend {
    LEGACY,
    DOWNLOAD_MANAGER,
    APP_HTTP,
}

internal fun persistedDownloadBackend(value: String?): PersistedDownloadBackend = when (value) {
    DOWNLOAD_BACKEND_DOWNLOAD_MANAGER -> PersistedDownloadBackend.DOWNLOAD_MANAGER
    DOWNLOAD_BACKEND_APP_HTTP -> PersistedDownloadBackend.APP_HTTP
    else -> PersistedDownloadBackend.LEGACY
}

/** Only GitHub-owned HTTPS asset URLs may receive the private-repo bearer token. */
internal fun shouldAttachUpdateAuthorization(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true)
}.getOrDefault(false)

/**
 * A failed system DownloadManager task is re-enqueued only while its failure
 * counter stays inside the bounded retry cap. Crossing the cap surfaces the
 * failure instead of looping forever.
 */
internal fun downloadFailureRetry(
    downloadStatus: Int,
    attemptCount: Int,
): Boolean = downloadStatus == DownloadManager.STATUS_FAILED &&
    attemptCount < DOWNLOAD_FAILURE_RETRY_CAP

/**
 * A genuinely new update request starts with a zero failure counter; a
 * replacement triggered by a failed DownloadManager task must preserve the
 * counter so the bounded retry cap is actually reached.
 */
internal fun shouldResetDownloadFailureCount(triggeredByDownloadFailure: Boolean): Boolean =
    !triggeredByDownloadFailure

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

internal fun isAppOwnedDownloadBackend(backend: String?): Boolean =
    backend == DOWNLOAD_BACKEND_APP_HTTP

internal fun shouldHandleDownloadManagerCompletion(backend: String?): Boolean =
    !isAppOwnedDownloadBackend(backend)

@Suppress("UNUSED_PARAMETER")
internal fun isExplicitUpdateRequestEligible(
    versionCode: Long?,
    currentVersionCode: Long,
    reusesPersistedDownload: Boolean,
): Boolean = versionCode == null || versionCode > currentVersionCode

internal fun shouldDeferUpdateReplacement(
    installStage: InstallStage?,
    bridgeState: ApkInstallBridgeState,
): Boolean = when (bridgeState) {
    ApkInstallBridgeState.LAUNCHING,
    ApkInstallBridgeState.ACTIVE,
    -> true
    ApkInstallBridgeState.DEFERRED,
    ApkInstallBridgeState.DEFERRED_WITHOUT_NOTIFICATION,
    ApkInstallBridgeState.FAILED,
    -> false
    ApkInstallBridgeState.NONE -> installStage in setOf(
        InstallStage.STAGING,
        InstallStage.COMMITTED,
        InstallStage.AWAITING_USER,
    )
}

internal fun shouldActivateEnqueuedUpdate(
    metadataCommitted: Boolean,
    replacementDeferredAfterEnqueue: Boolean,
): Boolean = metadataCommitted && !replacementDeferredAfterEnqueue

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
