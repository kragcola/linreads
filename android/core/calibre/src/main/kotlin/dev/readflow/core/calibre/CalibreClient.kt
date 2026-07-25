package dev.readflow.core.calibre

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.copyTo
import java.nio.channels.WritableByteChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Raw Calibre `/ajax/search` response (wire shape). */
@Serializable
data class CalibreSearchResult(
    val total_num: Int,
    val book_ids: List<Int>,
    val library_id: String? = null,
)

/** Raw Calibre `/ajax/book/<id>` metadata (wire shape; mapped to core:model BookMeta later). */
@Serializable
data class CalibreBookMeta(
    val id: Int,
    val title: String,
    val authors: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val series: String? = null,
)

/**
 * Thin HTTP client over the Calibre Content Server REST API.
 * baseUrl/credentials are injected (never hardcoded, C2). Phase 1 scaffold:
 * method shapes are real, business mapping/repository logic lands with feature work.
 */
class CalibreClient internal constructor(
    baseUrl: String,
    private val username: String,
    private val password: String,
    libraryId: String,
    private val http: HttpClient,
) : AutoCloseable {
    constructor(
        baseUrl: String,
        username: String = "",
        password: String = "",
        libraryId: String = "calibre-library",
    ) : this(
        baseUrl = baseUrl,
        username = username,
        password = password,
        libraryId = libraryId,
        http = defaultCalibreHttpClient(
            allowedBaseUrl = baseUrl,
            username = username,
            password = password,
        ),
    )

    private val baseUrl = requireValidCalibreBaseUrl(baseUrl)
    private val configuredLibraryId = libraryId.trim().ifBlank { DEFAULT_LIBRARY_ID }
    private val usesDefaultLibraryDiscovery = configuredLibraryId == DEFAULT_LIBRARY_ID

    @Volatile
    private var discoveredLibraryId: String? = null

    @Volatile
    private var libraryDiscoveryCompleted = !usesDefaultLibraryDiscovery

    private val libraryDiscoveryMutex = Mutex()

    suspend fun search(query: String = "", num: Int = 100, offset: Int = 0): CalibreSearchResult {
        val result = http.get(searchUrl()) {
            parameter("query", query)
            parameter("num", num)
            parameter("offset", offset)
        }.body<CalibreSearchResult>()
        if (usesDefaultLibraryDiscovery) {
            result.library_id
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { discoveredLibraryId = it }
        }
        libraryDiscoveryCompleted = true
        return result
    }

    suspend fun bookMeta(id: Int): CalibreBookMeta {
        ensureLibraryDiscovered()
        return http.get("$baseUrl/ajax/book/$id/${libraryPathSegment()}").body()
    }

    suspend fun bookMetas(ids: List<Int>): Map<Int, CalibreBookMeta> {
        if (ids.isEmpty()) return emptyMap()
        ensureLibraryDiscovered()
        return http.get(booksUrl()) {
            parameter("ids", ids.joinToString(","))
        }.body<Map<String, CalibreBookMeta?>>()
            .values
            .filterNotNull()
            .associateBy(CalibreBookMeta::id)
    }

    fun downloadUrl(id: Int, format: String) =
        "$baseUrl/get/$format/$id/${libraryPathSegment()}"

    fun coverUrl(id: Int) = "$baseUrl/get/cover/$id/${libraryPathSegment()}"

    suspend fun downloadTo(id: Int, format: String, output: WritableByteChannel): Long =
        http.prepareGet(downloadUrl(id, format)) {
            timeout {
                connectTimeoutMillis = DOWNLOAD_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            response.bodyAsChannel().copyTo(output)
        }

    override fun close() {
        http.close()
    }

    private fun libraryPathSegment(): String =
        (discoveredLibraryId ?: configuredLibraryId).encodeURLPathPart()

    private fun searchUrl(): String =
        if (usesDefaultLibraryDiscovery) {
            "$baseUrl/ajax/search"
        } else {
            "$baseUrl/ajax/search/${libraryPathSegment()}"
        }

    private fun booksUrl(): String =
        if (usesDefaultLibraryDiscovery) {
            "$baseUrl/ajax/books"
        } else {
            "$baseUrl/ajax/books/${libraryPathSegment()}"
        }

    private suspend fun ensureLibraryDiscovered() {
        if (libraryDiscoveryCompleted) return
        libraryDiscoveryMutex.withLock {
            if (!libraryDiscoveryCompleted) search(query = "", num = 1, offset = 0)
        }
    }

    private companion object {
        const val DEFAULT_LIBRARY_ID = "calibre-library"
        const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000L
        const val DOWNLOAD_SOCKET_TIMEOUT_MS = 60_000L
    }
}
