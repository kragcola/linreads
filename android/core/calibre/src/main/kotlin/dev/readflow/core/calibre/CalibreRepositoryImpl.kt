package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import java.io.File

class CalibreRepositoryImpl(
    private val client: CalibreClient,
    booksDir: File? = null,
) : CalibreRepository {

    private val downloader = booksDir?.let { CalibreBookDownloader(client, it) }

    override suspend fun search(query: String, offset: Int, limit: Int): ReadflowResult<List<BookMeta>> =
        runCatching {
            val ids = client.search(query, limit, offset).book_ids
            ReadflowResult.Success(ids.map { id -> client.bookMeta(id).toBookMeta() })
        }.getOrElse { e -> ReadflowResult.Failure(ReadflowError.network(null, e.message ?: "Calibre error")) }

    override suspend fun metadata(bookId: String): ReadflowResult<BookMeta> =
        runCatching {
            ReadflowResult.Success(client.bookMeta(bookId.toInt()).toBookMeta())
        }.getOrElse { e -> ReadflowResult.Failure(ReadflowError.network(null, e.message ?: "Calibre error")) }

    override suspend fun download(bookId: String): ReadflowResult<BookMeta> =
        runCatching {
            val downloader = downloader
                ?: return ReadflowResult.Failure(ReadflowError.io("Calibre 下载目录未配置"))
            val meta = client.bookMeta(bookId.toInt())
            when (val result = downloader.download(meta)) {
                is ReadflowResult.Success -> ReadflowResult.Success(result.value.meta)
                is ReadflowResult.Failure -> result
            }
        }.getOrElse { e -> ReadflowResult.Failure(ReadflowError.network(null, e.message ?: "Calibre error")) }

    private fun CalibreBookMeta.toBookMeta() = BookMeta(
        id = id.toString(),
        title = title,
        author = authors.joinToString(", ").ifEmpty { "Unknown" },
        format = bestDownloadFormat()?.bookFormat ?: BookFormat.UNKNOWN,
        coverUrl = client.coverUrl(id),
    )
}
