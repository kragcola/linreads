package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat

data class CalibreDownloadFormat(
    val remoteFormat: String,
    val bookFormat: BookFormat,
)

fun CalibreBookMeta.bestDownloadFormat(): CalibreDownloadFormat? {
    val byFormat = formats.associateBy { BookFormat.fromExtension(it) }
    return DOWNLOAD_PRIORITY.firstNotNullOfOrNull { format ->
        byFormat[format]?.let { remote -> CalibreDownloadFormat(remote, format) }
    }
}

private val DOWNLOAD_PRIORITY = listOf(
    BookFormat.EPUB,
    BookFormat.AZW3,
    BookFormat.MOBI,
    BookFormat.PDF,
)
