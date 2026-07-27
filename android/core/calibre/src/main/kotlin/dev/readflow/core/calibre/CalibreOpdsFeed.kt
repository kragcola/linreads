package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.RemoteBookKey
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.http.encodeURLPathPart
import java.net.URI
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class CalibreOpdsFeed(
    val publications: List<OnlineCatalogEntry>,
    val navigation: List<CalibreOpdsNavigation>,
    val searchTemplate: String?,
    val nextUrl: String?,
)

internal data class CalibreOpdsNavigation(
    val id: String?,
    val title: String?,
    val url: String,
)

internal data class CalibreOpdsBookIdentity(
    val bookId: String,
    val libraryId: String?,
)

internal fun parseCalibreOpdsFeed(
    body: String,
    descriptor: SourceDescriptor,
    feedUrl: String,
): CalibreOpdsFeed {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(body.reader())

    val publications = mutableListOf<OnlineCatalogEntry>()
    val navigation = mutableListOf<CalibreOpdsNavigation>()
    var searchTemplate: String? = null
    var nextUrl: String? = null
    var sawFeed = false
    var inEntry = false
    var inAuthorDepth = -1
    var title: String? = null
    var id: String? = null
    val authors = mutableListOf<String>()
    val tags = linkedSetOf<String>()
    var series: String? = null
    var contentText = ""
    val acquisitions = mutableListOf<OpdsAcquisitionCandidate>()
    val covers = mutableListOf<RankedOpdsCover>()
    var navigationUrl: String? = null

    fun resetEntry() {
        title = null
        id = null
        authors.clear()
        tags.clear()
        series = null
        contentText = ""
        acquisitions.clear()
        covers.clear()
        navigationUrl = null
        inAuthorDepth = -1
    }

    fun flushEntry() {
        if (!inEntry) return
        extractCalibreDisplayMetadata(contentText).let { metadata ->
            tags += metadata.tags
            if (series.isNullOrBlank()) series = metadata.series
        }
        val bestAcquisition = selectPreferredOpdsAcquisition(acquisitions)
        if (bestAcquisition != null) {
            val rawId = id?.takeIf(String::isNotBlank) ?: bestAcquisition.url
            val remoteIdentity = calibreOpdsBookIdentity(bestAcquisition.url)?.bookId ?: rawId
            val bookId = if (descriptor.id == BUILTIN_CALIBRE_SOURCE_ID) {
                remoteIdentity
            } else {
                stableRemoteBookId(descriptor.id, remoteIdentity)
            }
            val formatHint = bestAcquisition.formatHint
            val coverUrl = covers.minByOrNull(RankedOpdsCover::rank)?.url
            publications += OnlineCatalogEntry(
                meta = BookMeta(
                    id = bookId,
                    title = title?.takeIf(String::isNotBlank) ?: "未命名",
                    author = authors.joinToString(", ").ifBlank { "Unknown" },
                    format = BookFormat.fromExtension(formatHint.orEmpty()),
                    coverUrl = coverUrl,
                    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                ),
                remoteKey = RemoteBookKey(descriptor.id, remoteIdentity),
                authors = authors.toList(),
                series = series,
                tags = tags.toList(),
                availableFormats = buildList {
                    if (!formatHint.isNullOrBlank()) add(formatHint)
                    acquisitions.mapNotNull(OpdsAcquisitionCandidate::formatHint)
                        .filterNot { it.equals(formatHint, ignoreCase = true) }
                        .distinctBy(String::lowercase)
                        .let(::addAll)
                },
                downloadUrl = bestAcquisition.url,
                previewUrl = coverUrl,
            )
        } else {
            navigationUrl?.let { url ->
                navigation += CalibreOpdsNavigation(id = id, title = title, url = url)
            }
        }
        inEntry = false
        resetEntry()
    }

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val name = parser.name.lowercase()
                when {
                    parser.depth == 1 && name == "feed" -> sawFeed = true
                    name == "entry" -> {
                        flushEntry()
                        resetEntry()
                        inEntry = true
                    }
                    inEntry && name == "author" -> inAuthorDepth = parser.depth
                    inEntry && name == "title" -> title = parser.nextText()
                    inEntry && name == "id" -> id = parser.nextText()
                    inEntry && name == "name" && inAuthorDepth > 0 -> {
                        parser.nextText().trim().takeIf(String::isNotEmpty)?.let(authors::add)
                    }
                    inEntry && name == "category" -> {
                        (parser.getAttributeValue(null, "term") ?: parser.getAttributeValue(null, "label"))
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?.let(tags::add)
                    }
                    inEntry && name == "series" -> series = parser.nextText().trim().ifBlank { null }
                    inEntry && name == "content" -> contentText = readOpdsElementText(parser)
                    name == "link" -> {
                        val rel = parser.getAttributeValue(null, "rel").orEmpty()
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val href = parser.getAttributeValue(null, "href")
                        if (!href.isNullOrBlank()) {
                            if (inEntry) {
                                val absolute = resolveOpdsHref(href, feedUrl)
                                when {
                                    isOpdsAcquisition(rel, type) -> acquisitions += OpdsAcquisitionCandidate(
                                        url = absolute,
                                        type = type,
                                        formatHint = opdsFormatHint(type, absolute),
                                    )
                                    isOpdsCover(rel, type) -> covers += RankedOpdsCover(
                                        url = absolute,
                                        rank = opdsCoverRank(rel),
                                    )
                                    isOpdsNavigation(rel, type) && navigationUrl == null -> navigationUrl = absolute
                                }
                            } else {
                                when {
                                    rel.tokens().contains("search") -> {
                                        searchTemplate = resolveOpdsSearchTemplate(href, feedUrl)
                                    }
                                    rel.tokens().contains("next") -> nextUrl = resolveOpdsHref(href, feedUrl)
                                }
                            }
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> {
                val name = parser.name.lowercase()
                if (name == "entry") flushEntry()
                if (name == "author" && parser.depth == inAuthorDepth) inAuthorDepth = -1
            }
        }
        event = parser.next()
    }
    flushEntry()
    require(sawFeed) { "响应不是 Atom OPDS feed" }
    return CalibreOpdsFeed(
        publications = publications,
        navigation = navigation,
        searchTemplate = searchTemplate,
        nextUrl = nextUrl,
    )
}

internal fun expandCalibreOpdsSearchTemplate(template: String, query: String): String {
    val encoded = query.encodeURLPathPart()
    return template
        .replace("{searchTerms}", encoded)
        .replace("{searchTerms?}", encoded)
}

internal fun calibreOpdsBookIdentity(downloadUrl: String): CalibreOpdsBookIdentity? = runCatching {
    val segments = URI(downloadUrl).path.split('/').filter(String::isNotBlank)
    val getIndex = segments.indexOfLast { it.equals("get", ignoreCase = true) }
    if (getIndex < 0 || getIndex + 2 >= segments.size) return@runCatching null
    val bookId = segments[getIndex + 2].takeIf { it.toLongOrNull() != null } ?: return@runCatching null
    CalibreOpdsBookIdentity(
        bookId = bookId,
        libraryId = segments.getOrNull(getIndex + 3)?.takeIf(String::isNotBlank),
    )
}.getOrNull()

internal fun isCalibreTitleNavigation(url: String): Boolean = runCatching {
    val rawSegment = URI(url).rawPath.substringAfterLast('/')
    if (rawSegment.length % 2 != 0 || rawSegment.any { it.digitToIntOrNull(16) == null }) return@runCatching false
    val decoded = rawSegment.chunked(2)
        .map { it.toInt(16).toChar() }
        .joinToString("")
    decoded.equals("Otitle", ignoreCase = true)
}.getOrDefault(false)

private data class RankedOpdsCover(val url: String, val rank: Int)

private data class CalibreDisplayMetadata(val tags: List<String>, val series: String?)

private fun extractCalibreDisplayMetadata(content: String): CalibreDisplayMetadata {
    val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val tags = lines.firstNotNullOfOrNull { line ->
        TAGS_LINE.matchEntire(line)?.groupValues?.get(1)
    }.orEmpty().split(',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty)
    val series = lines.firstNotNullOfOrNull { line ->
        SERIES_LINE.matchEntire(line)?.groupValues?.get(1)
    }?.replace(SERIES_INDEX_SUFFIX, "")?.trim()?.ifBlank { null }
    return CalibreDisplayMetadata(tags = tags, series = series)
}

private fun readOpdsElementText(parser: XmlPullParser): String {
    val startDepth = parser.depth
    return buildString {
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> append(parser.text.orEmpty())
                XmlPullParser.START_TAG -> if (parser.name.equals("br", ignoreCase = true)) append('\n')
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("div", ignoreCase = true) || parser.name.equals("p", ignoreCase = true)) {
                        append('\n')
                    }
                    if (parser.depth == startDepth) break
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
    }
}

private fun resolveOpdsSearchTemplate(href: String, baseUrl: String): String {
    val protected = href
        .replace("{searchTerms?}", SEARCH_TERMS_OPTIONAL_SENTINEL)
        .replace("{searchTerms}", SEARCH_TERMS_SENTINEL)
    return resolveOpdsHref(protected, baseUrl)
        .replace(SEARCH_TERMS_OPTIONAL_SENTINEL, "{searchTerms?}")
        .replace(SEARCH_TERMS_SENTINEL, "{searchTerms}")
}

private fun resolveOpdsHref(href: String, baseUrl: String): String =
    if (href.startsWith("http://") || href.startsWith("https://")) href else URI(baseUrl).resolve(href).toString()

private fun isOpdsAcquisition(rel: String, type: String): Boolean =
    rel.contains("acquisition", ignoreCase = true) || opdsFormatHint(type, "") != null

private fun isOpdsCover(rel: String, type: String): Boolean =
    rel.contains("cover", ignoreCase = true) ||
        rel.contains("image", ignoreCase = true) ||
        rel.contains("thumbnail", ignoreCase = true) ||
        type.startsWith("image/", ignoreCase = true)

private fun isOpdsNavigation(rel: String, type: String): Boolean =
    !rel.contains("acquisition", ignoreCase = true) &&
        (type.contains("atom+xml", ignoreCase = true) || rel.isBlank() || rel.tokens().contains("subsection"))

private fun opdsCoverRank(rel: String): Int = when {
    rel.contains("thumbnail", ignoreCase = true) -> 2
    rel.endsWith("/image", ignoreCase = true) || rel.endsWith("/cover", ignoreCase = true) -> 0
    else -> 1
}

private fun opdsFormatHint(type: String, url: String): String? {
    calibreGetPathFormat(url)?.let { return it }
    val mime = type.substringBefore(';').trim().lowercase()
    return when {
        mime.contains("epub") -> "epub"
        mime.contains("x-mobi8-ebook") || mime.contains("azw3") -> "azw3"
        mime.contains("mobipocket") -> "mobi"
        mime == "application/pdf" || mime.endsWith("/pdf") -> "pdf"
        mime == "text/plain" -> "txt"
        mime == "text/markdown" || mime == "text/x-markdown" -> "md"
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        mime.contains("comicbook+zip") || mime.contains("cbz") -> "cbz"
        else -> runCatching { URI(url).path.substringAfterLast('.', "") }
            .getOrNull()
            ?.takeIf { BookFormat.fromExtension(it) != BookFormat.UNKNOWN }
    }
}

private fun calibreGetPathFormat(url: String): String? = runCatching {
    val segments = URI(url).rawPath.split('/').filter(String::isNotBlank)
    val getIndex = segments.indexOfLast { it.equals("get", ignoreCase = true) }
    if (getIndex < 0) return@runCatching null
    segments.getOrNull(getIndex + 1)
        ?.lowercase()
        ?.takeIf { BookFormat.fromExtension(it) != BookFormat.UNKNOWN }
}.getOrNull()

private fun String.tokens(): Set<String> = split(Regex("\\s+")).filter(String::isNotBlank).map(String::lowercase).toSet()

private val TAGS_LINE = Regex("""(?i)^(?:tags?|标签|標籤)\s*[:：]\s*(.+)$""")
private val SERIES_LINE = Regex("""(?i)^(?:series|丛书|叢書|系列)\s*[:：]\s*(.+)$""")
private val SERIES_INDEX_SUFFIX = Regex("""\s*\[[0-9.]+]\s*$""")
private const val SEARCH_TERMS_SENTINEL = "READFLOW_SEARCH_TERMS_REQUIRED"
private const val SEARCH_TERMS_OPTIONAL_SENTINEL = "READFLOW_SEARCH_TERMS_OPTIONAL"
