package dev.readflow.core.model

const val LEGACY_REMOTE_BOOK_ID_PREFIX = "calibre-"
const val SOURCE_SCOPED_REMOTE_BOOK_ID_PREFIX = "remote-"

fun String.isRemoteBookId(): Boolean =
    startsWith(LEGACY_REMOTE_BOOK_ID_PREFIX) || startsWith(SOURCE_SCOPED_REMOTE_BOOK_ID_PREFIX)

val BookMeta.hasDownloadedRemoteAsset: Boolean
    get() = id.isRemoteBookId() &&
        downloadStatus == DownloadStatus.DOWNLOADED &&
        localUri != null

val BookMeta.isOfflineReadable: Boolean
    get() = localUri != null &&
        (!id.isRemoteBookId() || downloadStatus == DownloadStatus.DOWNLOADED)
