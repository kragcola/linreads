package dev.readflow.core.calibre

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.copyTo
import java.nio.channels.WritableByteChannel
import kotlinx.serialization.Serializable

/** Raw Calibre `/ajax/search` response (wire shape). */
@Serializable
data class CalibreSearchResult(val total_num: Int, val book_ids: List<Int>)

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
    private val libraryId: String,
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
        http = defaultCalibreHttpClient(allowedBaseUrl = baseUrl),
    )

    private val baseUrl = requireValidCalibreBaseUrl(baseUrl)

    suspend fun search(query: String = "", num: Int = 100, offset: Int = 0): CalibreSearchResult =
        http.get("$baseUrl/ajax/search") {
            parameter("query", query)
            parameter("num", num)
            parameter("offset", offset)
            if (username.isNotBlank()) basicAuth(username, password)
        }.body()

    suspend fun bookMeta(id: Int): CalibreBookMeta =
        http.get("$baseUrl/ajax/book/$id/$libraryId") {
            if (username.isNotBlank()) basicAuth(username, password)
        }.body()

    fun downloadUrl(id: Int, format: String) =
        "$baseUrl/get/$format/$id/$libraryId"

    fun coverUrl(id: Int) = "$baseUrl/get/cover/$id/$libraryId"

    suspend fun downloadTo(id: Int, format: String, output: WritableByteChannel): Long =
        http.prepareGet(downloadUrl(id, format)) {
            if (username.isNotBlank()) basicAuth(username, password)
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

    private companion object {
        const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000L
        const val DOWNLOAD_SOCKET_TIMEOUT_MS = 60_000L
    }
}
