package dev.readflow.extensions.api

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.DownloadedAsset
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Local file book source (Phase 1 基建闭环 §11). Imports a file from SAF/Intent Uri
 * into app's private files, registers it in Room as a [BookMeta]. No Calibre/network.
 * Returns platform-neutral [DownloadedAsset] (R1-2: never java.io.File in interface).
 *
 * Phase 1: minimal — accepts a Uri (already user-picked via SAF), copies to files/books/,
 * derives title/author from filename (real metadata extraction deferred to Phase 2+).
 */
class LocalFileBookSource(
    private val context: Context,
) : BookSource {

    override val sourceId = "local"
    override val sourceName = "本地文件"

    override suspend fun search(query: String, offset: Int, limit: Int) =
        ReadflowResult.Failure(ReadflowError.unsupported("本地源不支持搜索"))

    override suspend fun getMetadata(bookId: String) =
        ReadflowResult.Failure(ReadflowError.unsupported("本地源不支持元数据查询"))

    override suspend fun getDownloadUrl(bookId: String, format: String) =
        ReadflowResult.Failure(ReadflowError.unsupported("本地源无下载 URL"))

    override suspend fun getCoverUrl(bookId: String): ReadflowResult<String> =
        ReadflowResult.Success("")

    override suspend fun getDownloadStatus(bookId: String) = DownloadStatus.NOT_DOWNLOADED

    override suspend fun isAvailable() = true

    /**
     * Imports a file from [uri] (SAF/Intent). Copies to files/books/, returns
     * [DownloadedAsset] + derived [BookMeta]. Caller (feature layer) upserts meta to Room.
     */
    suspend fun import(uri: Uri): ReadflowResult<Pair<BookMeta, DownloadedAsset>> =
        withContext(Dispatchers.IO) {
            try {
                // SAF URIs (content://) encode an opaque ID in lastPathSegment, not
                // the real filename. Query OpenableColumns.DISPLAY_NAME to get the
                // actual name, then fall back to the URI path only as a last resort.
                val fileName = context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "unknown.txt"
                val ext = fileName.substringAfterLast('.', "")
                val format = BookFormat.fromExtension(ext)
                if (format == BookFormat.UNKNOWN) {
                    return@withContext ReadflowResult.Failure(
                        ReadflowError.unsupported("不支持的格式: $ext"),
                    )
                }

                val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                val id = UUID.randomUUID().toString()
                val outFile = File(booksDir, "$id.$ext")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                } ?: return@withContext ReadflowResult.Failure(
                    ReadflowError.io("无法读取文件"),
                )

                val title = fileName.substringBeforeLast('.')
                val coverUri = CoverExtractor.extract(context, outFile, format, id)
                val meta = BookMeta(
                    id = id,
                    title = title,
                    author = "未知作者",
                    format = format,
                    coverUrl = coverUri,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                    localUri = Uri.fromFile(outFile).toString(),
                    lastReadAt = null,
                    collectionName = null,
                    progress = 0f,
                )
                val asset = DownloadedAsset(
                    bookId = id,
                    format = format.name,
                    localUri = Uri.fromFile(outFile).toString(),
                    sizeBytes = outFile.length(),
                )
                ReadflowResult.Success(meta to asset)
            } catch (e: Exception) {
                ReadflowResult.Failure(ReadflowError.io(e.message ?: "导入失败"))
            }
        }

    override suspend fun download(bookId: String, format: String) =
        ReadflowResult.Failure(ReadflowError.unsupported("本地源无下载操作"))
}
