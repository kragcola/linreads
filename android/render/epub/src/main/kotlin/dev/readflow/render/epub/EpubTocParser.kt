package dev.readflow.render.epub

import dev.readflow.core.model.TocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class EpubParsedTocEntry(
    val title: String,
    val href: String,
    val level: Int,
)

internal fun parseNavTocEntries(html: String): List<EpubParsedTocEntry> {
    val doc = Jsoup.parse(html, "", Parser.xmlParser())
    val nav = doc.select("nav").firstOrNull { element ->
        val type = element.attr("epub:type").ifBlank { element.attr("type") }
        type.split(Regex("\\s+")).any { it.equals("toc", ignoreCase = true) }
    } ?: return emptyList()
    val rootList = nav.childElements("ol").firstOrNull()
        ?: nav.childElements("ul").firstOrNull()
        ?: return emptyList()
    return buildList { collectNavList(rootList, level = 0, entries = this) }
}

internal fun parseNcxTocEntries(xml: String): List<EpubParsedTocEntry> {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val navMap = doc.selectFirst("navMap") ?: return emptyList()
    return buildList {
        navMap.childElements("navPoint").forEach { point ->
            collectNcxPoint(point, level = 0, entries = this)
        }
    }
}

internal fun buildEpubToc(
    parsedEntries: List<EpubParsedTocEntry>,
    tocDocumentPath: String,
    spinePaths: List<String>,
    paras: List<EpubPara>,
    fragmentTargets: Map<String, EpubTargetPosition> = emptyMap(),
): List<TocEntry> {
    if (parsedEntries.isEmpty() || spinePaths.isEmpty() || paras.isEmpty()) return emptyList()
    val sourceBaseDir = epubParentDir(tocDocumentPath)
    val spineIndexByPath = spinePaths.mapIndexed { index, path -> epubNormalizePath(path) to index }.toMap()
    val firstParaIndexBySpine = mutableMapOf<Int, Int>()
    paras.forEachIndexed { index, para ->
        firstParaIndexBySpine.putIfAbsent(para.spineIndex, index)
    }
    return parsedEntries.mapNotNull { entry ->
        val hrefPath = epubHrefPath(entry.href)
        if (hrefPath.isEmpty()) return@mapNotNull null
        val resolvedPath = epubResolvePath(sourceBaseDir, hrefPath)
        val resolvedHref = epubResolveHref(sourceBaseDir, entry.href)
        val spineIndex = spineIndexByPath[resolvedPath] ?: return@mapNotNull null
        val target = fragmentTargets[resolvedHref]
            ?: firstParaIndexBySpine[spineIndex]?.let { EpubTargetPosition(it) }
            ?: return@mapNotNull null
        TocEntry(
            title = entry.title.take(120),
            locator = epubLocatorForTarget(paras, target),
            level = entry.level,
        )
    }
}

internal fun epubResolveHref(baseDir: String, href: String): String {
    val path = epubHrefPath(href)
    val fragment = href.substringAfter('#', missingDelimiterValue = "").substringBefore('?').trim()
    val resolvedPath = epubResolvePath(baseDir, path)
    return if (fragment.isEmpty()) resolvedPath else "$resolvedPath#$fragment"
}

internal fun epubResolvePath(baseDir: String, href: String): String {
    val hrefPath = epubHrefPath(href).trimStart('/')
    val rawPath = when {
        hrefPath.isEmpty() -> baseDir
        href.startsWith("/") -> hrefPath
        baseDir.isEmpty() -> hrefPath
        else -> "$baseDir/$hrefPath"
    }
    return epubNormalizePath(rawPath)
}

internal fun epubParentDir(path: String): String = path.substringBeforeLast('/', "")

internal fun epubHrefPath(href: String): String =
    href.substringBefore('#').substringBefore('?').trim()

internal fun epubNormalizePath(path: String): String {
    val segments = mutableListOf<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

private fun collectNavList(
    list: Element,
    level: Int,
    entries: MutableList<EpubParsedTocEntry>,
) {
    list.childElements("li").forEach { item ->
        val anchor = item.childElements("a").firstOrNull()
        val title = anchor?.text()?.trim().orEmpty()
        val href = anchor?.attr("href")?.trim().orEmpty()
        if (title.isNotEmpty() && href.isNotEmpty()) {
            entries += EpubParsedTocEntry(title = title, href = href, level = level)
        }
        item.childElements("ol").forEach { collectNavList(it, level + 1, entries) }
        item.childElements("ul").forEach { collectNavList(it, level + 1, entries) }
    }
}

private fun collectNcxPoint(
    point: Element,
    level: Int,
    entries: MutableList<EpubParsedTocEntry>,
) {
    val label = point.childElements("navLabel")
        .firstOrNull()
        ?.childElements("text")
        ?.firstOrNull()
        ?.text()
        ?.trim()
        .orEmpty()
    val href = point.childElements("content").firstOrNull()?.attr("src")?.trim().orEmpty()
    if (label.isNotEmpty() && href.isNotEmpty()) {
        entries += EpubParsedTocEntry(title = label, href = href, level = level)
    }
    point.childElements("navPoint").forEach { nested ->
        collectNcxPoint(nested, level + 1, entries)
    }
}

private fun Element.childElements(tagName: String): List<Element> =
    children().filter { it.tagName().equals(tagName, ignoreCase = true) }
