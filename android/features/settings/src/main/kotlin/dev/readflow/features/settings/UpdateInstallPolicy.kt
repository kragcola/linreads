package dev.readflow.features.settings

import java.util.UUID

data class UpdatePackageInfo(
    val apkUrl: String,
    val notes: String,
    val buildTag: String?,
    val versionCode: Long? = null,
)

internal data class UpdateDownloadMetadata(
    val apkUrl: String,
    val buildTag: String?,
    val versionCode: Long? = null,
)

internal fun updateDownloadMetadata(update: UpdatePackageInfo): UpdateDownloadMetadata =
    UpdateDownloadMetadata(
        apkUrl = update.apkUrl,
        buildTag = update.buildTag,
        versionCode = update.versionCode,
    )

internal enum class UpdateArtifactEvent {
    DownloadCancelled,
    ReplacedByNewDownload,
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

    }

fun createUpdateDownloadFileName(uniqueId: String = UUID.randomUUID().toString()): String =
    "update-$uniqueId.apk"
