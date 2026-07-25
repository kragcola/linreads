package dev.readflow.features.settings

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
