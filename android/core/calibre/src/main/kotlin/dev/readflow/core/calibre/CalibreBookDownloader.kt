package dev.readflow.core.calibre

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CalibreDownloadedBook(
    val meta: BookMeta,
    val file: File,
)

class CalibreBookDownloader(
    private val client: CalibreClient,
    private val booksDir: File,
) {
    suspend fun download(
        meta: CalibreBookMeta,
        localBookId: String = "calibre-${meta.id}",
    ): ReadflowResult<CalibreDownloadedBook> =
        withContext(Dispatchers.IO) {
            val choice = meta.bestDownloadFormat()
                ?: return@withContext ReadflowResult.Failure(
                    ReadflowError.unsupported("Calibre 书籍没有可下载的 EPUB/AZW3/MOBI/PDF 格式"),
                )
            runCatching {
                booksDir.mkdirs()
                val bookId = localBookId
                val outFile = File(booksDir, "$bookId.${choice.remoteFormat.lowercase()}")
                val stagingFile = File.createTempFile("$bookId-", ".part", booksDir)
                try {
                    val downloadedBytes = stagingFile.outputStream().channel.use { channel ->
                        client.downloadTo(meta.id, choice.remoteFormat, channel)
                    }
                    require(downloadedBytes > 0L && stagingFile.length() > 0L) { "Calibre 返回了空文件" }
                    try {
                        Files.move(
                            stagingFile.toPath(),
                            outFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            stagingFile.toPath(),
                            outFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    stagingFile.delete()
                }
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
                if (error is CancellationException) throw error
                ReadflowResult.Failure(ReadflowError.io(error.message ?: "Calibre 下载失败"))
            }
        }
}
