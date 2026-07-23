package dev.readflow.core.calibre

import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val sourceConfigJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class CalibreSourceConfig(
    val baseUrl: String,
    val libraryId: String = "calibre-library",
)

@Serializable
data class HttpCatalogSourceConfig(
    val baseUrl: String,
)

@Serializable
data class HtmlRulesV1Config(
    val searchUrlTemplate: String,
    val allowedHosts: List<String>,
    val allowLanHttp: Boolean = false,
    val charset: String = "UTF-8",
    val search: HtmlSearchRules,
    val detail: HtmlDetailRules,
    val chapter: HtmlChapterRules,
)

@Serializable
data class HtmlSearchRules(
    val itemSelector: String,
    val titleSelector: String,
    val authorSelector: String,
    val detailLinkSelector: String,
    val seriesSelector: String? = null,
)

@Serializable
data class HtmlDetailRules(
    val chapterItemSelector: String,
    val chapterLinkSelector: String,
)

@Serializable
data class HtmlChapterRules(
    val titleSelector: String? = null,
    val bodySelector: String,
    val nextPageSelector: String? = null,
)

fun calibreSourceConfigJson(baseUrl: String, libraryId: String = "calibre-library"): String =
    sourceConfigJson.encodeToString(CalibreSourceConfig(baseUrl, libraryId))

fun httpCatalogSourceConfigJson(baseUrl: String): String =
    sourceConfigJson.encodeToString(HttpCatalogSourceConfig(baseUrl))

fun htmlRulesV1ConfigJson(config: HtmlRulesV1Config): String = sourceConfigJson.encodeToString(config)

internal fun SourceDescriptor.calibreConfig(): CalibreSourceConfig =
    sourceConfigJson.decodeFromString(configJson)

internal fun SourceDescriptor.httpCatalogConfig(): HttpCatalogSourceConfig =
    sourceConfigJson.decodeFromString(configJson)

internal fun SourceDescriptor.htmlRulesConfig(): HtmlRulesV1Config =
    sourceConfigJson.decodeFromString(configJson)

internal fun displayBaseUrl(adapterId: String, configJson: String): String = runCatching {
    when (adapterId) {
        SourceAdapterIds.CALIBRE -> sourceConfigJson.decodeFromString<CalibreSourceConfig>(configJson).baseUrl
        SourceAdapterIds.OPDS, SourceAdapterIds.JSON_HTTP ->
            sourceConfigJson.decodeFromString<HttpCatalogSourceConfig>(configJson).baseUrl
        SourceAdapterIds.HTML_RULES_V1 ->
            sourceConfigJson.decodeFromString<HtmlRulesV1Config>(configJson).searchUrlTemplate
        else -> ""
    }
}.getOrDefault("")
