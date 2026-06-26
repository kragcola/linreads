package dev.readflow.core.calibre

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.ReadflowResult

/**
 * Repository facade the feature layer depends on. Phase 1 scaffold: contract only —
 * real mapping from [CalibreClient] wire types to [BookMeta] lands with feature work.
 */
interface CalibreRepository {
    suspend fun search(query: String, offset: Int = 0, limit: Int = 100): ReadflowResult<List<BookMeta>>
    suspend fun metadata(bookId: String): ReadflowResult<BookMeta>
    suspend fun download(bookId: String): ReadflowResult<BookMeta>
}
