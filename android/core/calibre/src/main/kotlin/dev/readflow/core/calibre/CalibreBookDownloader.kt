package dev.readflow.core.calibre

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CalibreDownloadedBook(
    val meta: BookMeta,
    val file: File,
)

class CalibreBookDownloader(
    private val client: CalibreClient,
    private val booksDir: File,
) {
    suspend fun download(meta: CalibreBookMeta): ReadflowResult<CalibreDownloadedBook> =
        withContext(Dispatchers.IO) {
            val choice = meta.bestDownloadFormat()
                ?: return@withContext ReadflowResult.Failure(
                    ReadflowError.unsupported("Calibre 书籍没有可下载的 EPUB/AZW3/MOBI/PDF 格式"),
                )
            runCatching {
                booksDir.mkdirs()
                val bookId = "calibre-${meta.id}"
                val outFile = File(booksDir, "$bookId.${choice.remoteFormat.lowercase()}")
                outFile.writeBytes(client.downloadBytes(meta.id, choice.remoteFormat))
                ReadflowResult.Success(
                    CalibreDownloadedBook(
                        meta = BookMeta(
                            id = bookId,
                            title = meta.title,
                            author = meta.authors.joinToString(", ").ifEmpty { "Unknown" },
                            format = choice.bookFormat,
                            coverUrl = client.coverUrl(meta.id),
                            downloadStatus = DownloadStatus.DOWNLOADED,
                            localUri = outFile.toURI().toString(),
                        ),
                        file = outFile,
                    ),
                )
            }.getOrElse { error ->
                ReadflowResult.Failure(ReadflowError.io(error.message ?: "Calibre 下载失败"))
            }
        }
}
