package dev.readflow.features.settings

import java.util.UUID

data class UpdatePackageInfo(
    val apkUrl: String,
    val notes: String,
    val buildTag: String?,
    val versionCode: Long? = null,
)

internal enum class UpdateArtifactEvent {
    DownloadCancelled,
}

internal data class UpdateArtifactAction(
    val removeDownload: Boolean,
    val clearMetadata: Boolean,
)

internal fun updateArtifactAction(event: UpdateArtifactEvent): UpdateArtifactAction =
    when (event) {
        UpdateArtifactEvent.DownloadCancelled ->
            UpdateArtifactAction(removeDownload = true, clearMetadata = true)
    }

fun createUpdateDownloadFileName(uniqueId: String = UUID.randomUUID().toString()): String =
    "update-$uniqueId.apk"
