package dev.readflow.updater

internal fun canReuseUpdateDownload(
    savedUrl: String?,
    savedTag: String?,
    requestedUrl: String,
    requestedTag: String?,
): Boolean = requestedTag != null && savedUrl == requestedUrl && savedTag == requestedTag
