package dev.readflow.calibre

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(val total_num: Int, val book_ids: List<Int>)

@Serializable
data class BookMeta(
    val id: Int,
    val title: List<String>,
    val authors: List<String>,
    val formats: List<String>,
    val tags: List<String> = emptyList(),
    val series: String? = null,
)

class CalibreClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val libraryId: String = "calibre-library",
) {
    private val http = HttpClient {
        install(ContentNegotiation) { json() }
    }

    suspend fun search(query: String = "", num: Int = 100, offset: Int = 0): SearchResult =
        http.get("$baseUrl/ajax/search") {
            parameter("query", query)
            parameter("num", num)
            parameter("offset", offset)
            if (username.isNotBlank()) basicAuth(username, password)
        }.body()

    suspend fun bookMeta(id: Int): BookMeta =
        http.get("$baseUrl/ajax/book/$id/$libraryId") {
            if (username.isNotBlank()) basicAuth(username, password)
        }.body()

    fun downloadUrl(id: Int, format: String) =
        "$baseUrl/get/$format/$id/$libraryId"
}
