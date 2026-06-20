package dev.readflow.core.calibre

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
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
class CalibreClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val libraryId: String = "calibre-library",
) {
    private val http = HttpClient {
        install(ContentNegotiation) { json() }
    }

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
}
