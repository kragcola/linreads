package dev.readflow.core.database

data class DownloadedCacheBook(
    val id: String,
    val localUri: String?,
    val lastReadAt: Long?,
)

class DownloadedBookCachePlanner(
    private val cacheLimit: Int = DEFAULT_CACHE_LIMIT,
) {
    init {
        require(cacheLimit > 0) { "cacheLimit must be positive" }
    }

    fun evictions(
        candidates: List<DownloadedCacheBook>,
        protectedBookId: String? = null,
    ): List<DownloadedCacheBook> {
        val eligible = candidates.filter { it.id != protectedBookId }
        val protectedCount = candidates.count { it.id == protectedBookId }
        val keepCount = (cacheLimit - protectedCount).coerceAtLeast(0)
        return eligible
            .sortedWith(compareByDescending<DownloadedCacheBook> { it.lastReadAt ?: Long.MIN_VALUE }.thenBy { it.id })
            .drop(keepCount)
            .sortedWith(compareBy<DownloadedCacheBook> { it.lastReadAt ?: Long.MIN_VALUE }.thenBy { it.id })
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT = 5
        const val REMOTE_CACHE_ID_PREFIX = "calibre-"
    }
}
