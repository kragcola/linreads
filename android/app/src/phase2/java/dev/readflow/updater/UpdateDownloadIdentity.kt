package dev.readflow.updater

internal fun canReuseUpdateDownload(
    savedUrl: String?,
    savedTag: String?,
    requestedUrl: String,
    requestedTag: String?,
    savedVersionCode: Long? = null,
    requestedVersionCode: Long? = null,
): Boolean =
    !requestedTag.isNullOrBlank() &&
        requestedVersionCode != null &&
        savedUrl == requestedUrl &&
        savedTag == requestedTag &&
        savedVersionCode == requestedVersionCode
