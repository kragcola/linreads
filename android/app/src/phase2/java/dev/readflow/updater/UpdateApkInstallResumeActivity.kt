package dev.readflow.updater

import android.app.Activity
import android.os.Bundle

/** Direct notification target that resumes a live installer task or performs an explicit retry. */
class UpdateApkInstallResumeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloadId = intent.getLongExtra(EXTRA_BRIDGE_DOWNLOAD_ID, NO_DOWNLOAD)
        val apkUri = intent.data
        if (
            downloadId == NO_DOWNLOAD ||
            apkUri == null ||
            !UpdatePackageInstaller.isCurrentDownload(this, downloadId)
        ) {
            finish()
            return
        }

        UpdatePackageInstaller.recoverStaleBridgeLaunch(this, downloadId)
        val state = UpdatePackageInstaller.bridgeStateForDownload(this, downloadId)
        val appTask = UpdatePackageInstaller.bridgeAppTaskForDownload(this, downloadId)
        when (bridgeNotificationAction(state, appTask != null)) {
            BridgeNotificationAction.RESUME_TASK -> {
                val resumed = runCatching { requireNotNull(appTask).moveToFront() }.isSuccess
                if (!resumed) retryInstaller(downloadId, state)
            }
            BridgeNotificationAction.RETRY_INSTALLER -> retryInstaller(downloadId, state)
            BridgeNotificationAction.KEEP_WAITING -> {
                UpdatePackageInstaller.publishBridgeNotification(
                    context = this,
                    downloadId = downloadId,
                    apkUri = apkUri,
                    requiredState = ApkInstallBridgeState.LAUNCHING,
                )
            }
        }
        finish()
    }

    private fun retryInstaller(downloadId: Long, state: ApkInstallBridgeState) {
        val apkUri = intent.data ?: return
        if (
            state == ApkInstallBridgeState.ACTIVE &&
            !UpdatePackageInstaller.updateBridgeStateForDownload(
                this,
                downloadId,
                ApkInstallBridgeState.DEFERRED,
            )
        ) {
            return
        }
        launchApkInstaller(this, downloadId, apkUri, userInitiated = true)
    }

    private companion object {
        const val NO_DOWNLOAD = -1L
    }
}
