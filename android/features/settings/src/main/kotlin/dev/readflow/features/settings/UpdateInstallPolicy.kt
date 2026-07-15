package dev.readflow.features.settings

import android.content.Intent
import android.net.Uri
import java.util.UUID

data class UpdatePackageInfo(
    val apkUrl: String,
    val notes: String,
    val buildTag: String?,
)

internal data class UpdateDownloadMetadata(
    val apkUrl: String,
    val buildTag: String?,
)

internal fun updateDownloadMetadata(update: UpdatePackageInfo): UpdateDownloadMetadata =
    UpdateDownloadMetadata(
        apkUrl = update.apkUrl,
        buildTag = update.buildTag,
    )

internal enum class UpdateArtifactEvent {
    DownloadCancelled,
    ReplacedByNewDownload,
    InstallerLaunched,
}

internal data class UpdateArtifactAction(
    val removeDownload: Boolean,
    val clearMetadata: Boolean,
)

internal fun updateArtifactAction(event: UpdateArtifactEvent): UpdateArtifactAction =
    when (event) {
        UpdateArtifactEvent.DownloadCancelled ->
            UpdateArtifactAction(removeDownload = true, clearMetadata = true)

        UpdateArtifactEvent.ReplacedByNewDownload ->
            UpdateArtifactAction(removeDownload = false, clearMetadata = true)

        UpdateArtifactEvent.InstallerLaunched ->
            UpdateArtifactAction(removeDownload = false, clearMetadata = false)
    }

fun createUpdateDownloadFileName(uniqueId: String = UUID.randomUUID().toString()): String =
    "update-$uniqueId.apk"

internal data class UpdateInstallRequestSpec(
    val action: String,
    val mimeType: String,
    val grantReadPermission: Boolean,
    val launchInNewTask: Boolean,
)

internal fun updateInstallRequestSpec(launchInNewTask: Boolean): UpdateInstallRequestSpec =
    UpdateInstallRequestSpec(
        action = Intent.ACTION_INSTALL_PACKAGE,
        mimeType = APK_MIME_TYPE,
        grantReadPermission = true,
        launchInNewTask = launchInNewTask,
    )

fun createUpdateInstallIntent(apkUri: Uri, launchInNewTask: Boolean): Intent {
    val spec = updateInstallRequestSpec(launchInNewTask)
    return Intent(spec.action).apply {
        setDataAndType(apkUri, spec.mimeType)
        if (spec.grantReadPermission) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (spec.launchInNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
