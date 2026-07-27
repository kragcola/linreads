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

/** Calibre omits `id` from metadata objects; identity comes from the request or response map key. */
@Serializable
private data class CalibreBookMetaWire(
    val title: String,
    val authors: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val series: String? = null,
) {
    fun normalized(id: Int) = CalibreBookMeta(
        id = id,
        title = title,
        authors = authors,
        formats = formats,
        tags = tags,
        series = series,
    )
}

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
    private val networkSnapshotProvider: CalibreNetworkSnapshotProvider =
        UnknownCalibreNetworkSnapshotProvider,
) : AutoCloseable {
    constructor(
        baseUrl: String,
        username: String = "",
        password: String = "",
        libraryId: String = DEFAULT_CALIBRE_LIBRARY_ID,
        networkSnapshotProvider: CalibreNetworkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
    ) : this(
        baseUrl = baseUrl,
        username = username,
        password = password,
        libraryId = libraryId,
        http = defaultCalibreHttpClient(
            allowedBaseUrl = requireCalibreAjaxBaseUrl(baseUrl),
            username = username,
            password = password,
            networkSnapshotProvider = networkSnapshotProvider,
        ),
        networkSnapshotProvider = networkSnapshotProvider,
    )

    private val baseUrl = requireCalibreAjaxBaseUrl(baseUrl)
    private val configuredLibraryId = libraryId.trim().ifBlank { DEFAULT_CALIBRE_LIBRARY_ID }
    private val usesDefaultLibraryDiscovery = configuredLibraryId == DEFAULT_CALIBRE_LIBRARY_ID

    @Volatile
    private var discoveredLibraryId: String? = null

    @Volatile
    private var libraryDiscoveryCompleted = !usesDefaultLibraryDiscovery

    private val libraryDiscoveryMutex = Mutex()

    suspend fun search(query: String = "", num: Int = 100, offset: Int = 0): CalibreSearchResult {
        val url = searchUrl()
        val result = withCalibreRequestContext(CalibreRequestPhase.AJAX_SEARCH, url) {
            http.get(url) {
                parameter("query", query)
                parameter("num", num)
                parameter("offset", offset)
            }.body<CalibreSearchResult>()
        }
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
        val url = "$baseUrl/ajax/book/$id/${libraryPathSegment()}"
        return withCalibreRequestContext(CalibreRequestPhase.AJAX_BOOK, url) {
            http.get(url).body<CalibreBookMetaWire>().normalized(id)
        }
    }

    suspend fun bookMetas(ids: List<Int>): Map<Int, CalibreBookMeta> {
        if (ids.isEmpty()) return emptyMap()
        ensureLibraryDiscovered()
        val url = booksUrl()
        return withCalibreRequestContext(CalibreRequestPhase.AJAX_METADATA, url) {
            http.get(url) {
                parameter("ids", ids.joinToString(","))
            }.body<Map<String, CalibreBookMetaWire?>>()
        }
            .mapNotNull { (rawId, wire) ->
                val id = rawId.toIntOrNull() ?: return@mapNotNull null
                wire?.normalized(id)?.let { id to it }
            }
            .toMap()
    }

    fun downloadUrl(id: Int, format: String) =
        "$baseUrl/get/$format/$id/${libraryPathSegment()}"

    fun coverUrl(id: Int) = "$baseUrl/get/cover/$id/${libraryPathSegment()}"

    suspend fun downloadTo(id: Int, format: String, output: WritableByteChannel): Long =
        downloadUrl(id, format).let { url ->
            withCalibreRequestContext(CalibreRequestPhase.AJAX_DOWNLOAD, url) {
                http.prepareGet(url) {
                    timeout {
                        connectTimeoutMillis = DOWNLOAD_CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                }.execute { response ->
                    response.bodyAsChannel().copyTo(output)
                }
            }
        }

    override fun close() {
        http.close()
    }

    internal fun toReadflowError(error: Throwable) = error.toCalibreReadflowError(
        baseUrl = baseUrl,
        network = networkSnapshotProvider.snapshot(),
    )

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
        const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000L
        const val DOWNLOAD_SOCKET_TIMEOUT_MS = 60_000L
    }
}
