package dev.readflow.updater

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.readflow.MainActivity

/** Keeps OEM package installers inside a LinReads-owned task and closes that task on return. */
class UpdateApkInstallActivity : Activity() {
    private var installerLaunched = false
    private var pausedForInstaller = false
    private var returningToLinReads = false
    private var recoveredActiveWithoutSavedState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloadId = bridgeDownloadId()
        val persistedState = if (downloadId == NO_BRIDGE_DOWNLOAD) {
            ApkInstallBridgeState.NONE
        } else {
            UpdatePackageInstaller.bridgeStateForDownload(this, downloadId)
        }
        val recordedTaskId = if (downloadId == NO_BRIDGE_DOWNLOAD) {
            null
        } else {
            UpdatePackageInstaller.bridgeTaskIdForDownload(this, downloadId)
        }
        if (
            persistedState == ApkInstallBridgeState.ACTIVE &&
            recordedTaskId != null &&
            recordedTaskId != taskId
        ) {
            finishAndRemoveTask()
            return
        }
        installerLaunched = savedInstanceState?.getBoolean(STATE_INSTALLER_LAUNCHED) == true ||
            persistedState == ApkInstallBridgeState.ACTIVE
        pausedForInstaller = savedInstanceState?.getBoolean(STATE_PAUSED_FOR_INSTALLER) == true
        recoveredActiveWithoutSavedState =
            savedInstanceState == null && persistedState == ApkInstallBridgeState.ACTIVE
        when (
            apkInstallBridgeAction(
                installerAlreadyLaunched = installerLaunched,
                hasApkUri = intent.data != null,
                persistedState = persistedState,
            )
        ) {
            ApkInstallBridgeAction.LAUNCH_INSTALLER -> launchInstaller()
            ApkInstallBridgeAction.AWAIT_RESULT -> Unit
            ApkInstallBridgeAction.FINISH -> returnToLinReads()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INSTALLER_LAUNCHED, installerLaunched)
        outState.putBoolean(STATE_PAUSED_FOR_INSTALLER, pausedForInstaller)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (installerLaunched) pausedForInstaller = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (
            shouldCloseInstallBridgeOnResume(
                installerLaunched = installerLaunched,
                bridgePausedForInstaller = pausedForInstaller,
                returningToLinReads = returningToLinReads,
                recoveredActiveWithoutSavedState = recoveredActiveWithoutSavedState,
            )
        ) {
            deferInstallerRetry()
            returnToLinReads()
        }
    }

    @Deprecated("Activity result is required by ACTION_INSTALL_PACKAGE on the OEM installer path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_APK) {
            if (resultCode != RESULT_OK) deferInstallerRetry()
            returnToLinReads()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchInstaller() {
        val downloadId = bridgeDownloadId()
        val apkUri = intent.data ?: run {
            returnToLinReads()
            return
        }
        if (
            downloadId == NO_BRIDGE_DOWNLOAD ||
            !UpdatePackageInstaller.activateBridgeForDownload(
                this,
                downloadId,
                taskId,
            )
        ) {
            returnToLinReads()
            return
        }
        installerLaunched = true
        runCatching {
            startActivityForResult(apkInstallIntent(apkUri), REQUEST_INSTALL_APK)
        }.onFailure {
            val stateUpdated = UpdatePackageInstaller.updateBridgeStateForDownload(
                this,
                downloadId,
                ApkInstallBridgeState.FAILED,
            )
            if (stateUpdated) {
                postRetryNotification(downloadId, apkUri, ApkInstallBridgeState.FAILED)
            }
            returnToLinReads()
        }
    }

    private fun deferInstallerRetry() {
        val downloadId = bridgeDownloadId()
        val apkUri = intent.data
        if (downloadId == NO_BRIDGE_DOWNLOAD || apkUri == null) return
        val stateUpdated = UpdatePackageInstaller.updateBridgeStateForDownload(
            this,
            downloadId,
            ApkInstallBridgeState.DEFERRED,
        )
        if (stateUpdated) {
            postRetryNotification(downloadId, apkUri, ApkInstallBridgeState.DEFERRED)
        }
    }

    private fun postRetryNotification(
        downloadId: Long,
        apkUri: android.net.Uri,
        requiredState: ApkInstallBridgeState,
    ) {
        UpdatePackageInstaller.publishBridgeNotification(
            context = this,
            downloadId = downloadId,
            apkUri = apkUri,
            requiredState = requiredState,
            deferWhenUnavailable = true,
        )
    }

    private fun returnToLinReads() {
        if (returningToLinReads) return
        returningToLinReads = true
        val mainActivityLaunched = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }.isSuccess
        if (shouldRemoveInstallBridgeTask(mainActivityLaunched)) finishAndRemoveTask()
    }

    private fun bridgeDownloadId(): Long =
        intent.getLongExtra(EXTRA_BRIDGE_DOWNLOAD_ID, NO_BRIDGE_DOWNLOAD)

    private companion object {
        const val NO_BRIDGE_DOWNLOAD = -1L
        const val STATE_INSTALLER_LAUNCHED = "installer_launched"
        const val STATE_PAUSED_FOR_INSTALLER = "paused_for_installer"
        const val REQUEST_INSTALL_APK = 2001
    }
}
