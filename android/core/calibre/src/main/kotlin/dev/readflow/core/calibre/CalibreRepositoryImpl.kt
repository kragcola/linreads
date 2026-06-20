package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult

class CalibreRepositoryImpl(private val client: CalibreClient) : CalibreRepository {

    override suspend fun search(query: String, offset: Int, limit: Int): ReadflowResult<List<BookMeta>> =
        runCatching {
            val ids = client.search(query, limit, offset).book_ids
            ReadflowResult.Success(ids.map { id -> client.bookMeta(id).toBookMeta() })
        }.getOrElse { e -> ReadflowResult.Failure(ReadflowError.network(null, e.message ?: "Calibre error")) }

    override suspend fun metadata(bookId: String): ReadflowResult<BookMeta> =
        runCatching {
            ReadflowResult.Success(client.bookMeta(bookId.toInt()).toBookMeta())
        }.getOrElse { e -> ReadflowResult.Failure(ReadflowError.network(null, e.message ?: "Calibre error")) }

    private fun CalibreBookMeta.toBookMeta() = BookMeta(
        id = id.toString(),
        title = title,
        author = authors.joinToString(", ").ifEmpty { "Unknown" },
        format = formats.firstNotNullOfOrNull { fmt ->
            BookFormat.fromExtension(fmt.lowercase()).takeIf { it != BookFormat.UNKNOWN }
        } ?: BookFormat.UNKNOWN,
        coverUrl = client.coverUrl(id),
    )
}
