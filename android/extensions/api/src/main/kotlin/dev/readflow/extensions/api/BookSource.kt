package dev.readflow.extensions.api

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.DownloadedAsset
import dev.readflow.core.model.ReadflowResult

/**
 * A pluggable source of books (Calibre / OPDS / local). Returns platform-neutral
 * [DownloadedAsset], never java.io.File (R1-2). §7.5.
 */
interface BookSource {
    val sourceId: String          // "calibre" | "opds" | "local"
    val sourceName: String

    suspend fun search(query: String, offset: Int = 0, limit: Int = 100): ReadflowResult<List<BookMeta>>
    suspend fun getMetadata(bookId: String): ReadflowResult<BookMeta>
    suspend fun getDownloadUrl(bookId: String, format: String): ReadflowResult<String>
    suspend fun getCoverUrl(bookId: String): ReadflowResult<String>
    suspend fun download(bookId: String, format: String): ReadflowResult<DownloadedAsset>
    suspend fun getDownloadStatus(bookId: String): DownloadStatus
    suspend fun isAvailable(): Boolean
}
