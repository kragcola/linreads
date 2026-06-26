package dev.readflow.render.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

internal fun parseReaderItemsFromHtml(
    spineIndex: Int,
    html: String,
    resourceBaseDir: String = "",
    documentPath: String? = null,
    maxDomDepth: Int = EPUB_MAX_DOM_DEPTH,
): List<EpubReaderItem> = epubParserGuard(emptyList()) {
    val safeHtml = sanitizeEpubXml(html) ?: return@epubParserGuard emptyList()
    val items = mutableListOf<EpubReaderItem>()
    val rawPreTexts = rawPreTexts(safeHtml).toMutableList()
    val doc = Jsoup.parse(safeHtml, "", Parser.xmlParser())
    val body = doc.selectFirst("body") ?: doc
    body.children().forEach { element ->
        collectReaderItems(
            element = element,
            spineIndex = spineIndex,
            resourceBaseDir = resourceBaseDir,
            documentPath = documentPath,
            listLevel = 0,
            rawPreTexts = rawPreTexts,
            items = items,
            depth = 0,
            maxDomDepth = maxDomDepth,
        )
    }
    items
}

private data class TextWithLinks(
    val text: String,
    val links: List<EpubTextLink>,
    val styleSpans: List<EpubTextStyleSpan>,
)

private fun extractTextWithLinks(
    element: Element,
    resourceBaseDir: String,
    documentPath: String?,
    maxDomDepth: Int,
): TextWithLinks {
    val builder = StringBuilder()
    val links = mutableListOf<EpubTextLink>()
    val styleSpans = mutableListOf<EpubTextStyleSpan>()
    element.childNodes().forEach { node ->
        appendInlineNode(node, builder, links, styleSpans, resourceBaseDir, documentPath, depth = 0, maxDomDepth = maxDomDepth)
    }
    return trimTextWithRanges(builder.toString(), links, styleSpans)
}

private fun collectReaderItems(
    element: Element,
    spineIndex: Int,
    resourceBaseDir: String,
    documentPath: String?,
    listLevel: Int,
    rawPreTexts: MutableList<String>,
    items: MutableList<EpubReaderItem>,
    depth: Int,
    maxDomDepth: Int,
) {
    if (depth > maxDomDepth) return
    when (val tag = element.tagName().lowercase()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val text = extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth)
            if (text.text.isNotEmpty()) {
                items += EpubReaderItem.Heading(
                    locator = EpubItemLocator(spineIndex, items.size),
                    level = tag.removePrefix("h").toIntOrNull() ?: 1,
                    text = text.text,
                    links = text.links,
                    styleSpans = text.styleSpans,
                    fragmentIds = element.fragmentIds(),
                )
            }
        }
        "p" -> addTextItem(spineIndex, items, extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth), element.fragmentIds())
        "img" -> addImageItem(spineIndex, items, element, resourceBaseDir)
        "br", "hr" -> items += EpubReaderItem.Break(EpubItemLocator(spineIndex, items.size))
        "table" -> addPlainTextItem(
            spineIndex = spineIndex,
            items = items,
            text = tableText(element),
            kind = EpubTextKind.Table,
        )
        "pre" -> addPlainTextItem(
            spineIndex = spineIndex,
            items = items,
            text = rawPreTexts.takeFirstOrNull() ?: preText(element),
            kind = EpubTextKind.Preformatted,
        )
        "blockquote" -> addTextItem(
            spineIndex = spineIndex,
            items = items,
            text = extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth),
            kind = EpubTextKind.Blockquote,
        )
        "ul", "ol" -> collectListItems(element, spineIndex, resourceBaseDir, documentPath, listLevel, rawPreTexts, items, ordered = tag == "ol", depth = depth + 1, maxDomDepth = maxDomDepth)
        "style", "script", "link", "meta", "title" -> Unit
        else -> collectContainerOrUnknown(element, spineIndex, resourceBaseDir, documentPath, listLevel, rawPreTexts, items, depth = depth + 1, maxDomDepth = maxDomDepth)
    }
}

private fun collectContainerOrUnknown(
    element: Element,
    spineIndex: Int,
    resourceBaseDir: String,
    documentPath: String?,
    listLevel: Int,
    rawPreTexts: MutableList<String>,
    items: MutableList<EpubReaderItem>,
    depth: Int,
    maxDomDepth: Int,
) {
    if (depth > maxDomDepth) return
    val blockChildren = element.children().filter { it.tagName().lowercase() !in INLINE_TAGS }
    if (blockChildren.isNotEmpty()) {
        blockChildren.forEach {
            collectReaderItems(it, spineIndex, resourceBaseDir, documentPath, listLevel, rawPreTexts, items, depth = depth + 1, maxDomDepth = maxDomDepth)
        }
        return
    }
    addTextItem(spineIndex, items, extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth), element.fragmentIds())
}

private fun collectListItems(
    list: Element,
    spineIndex: Int,
    resourceBaseDir: String,
    documentPath: String?,
    listLevel: Int,
    rawPreTexts: MutableList<String>,
    items: MutableList<EpubReaderItem>,
    ordered: Boolean,
    depth: Int,
    maxDomDepth: Int,
) {
    if (depth > maxDomDepth) return
    list.children().filter { it.tagName().equals("li", ignoreCase = true) }.forEachIndexed { index, item ->
        val itemWithoutNestedLists = item.clone().apply { select("ol, ul").remove() }
        val text = extractTextWithLinks(itemWithoutNestedLists, resourceBaseDir, documentPath, maxDomDepth)
            .withPrefix(if (ordered) "${index + 1}. " else "• ")
        addTextItem(
            spineIndex = spineIndex,
            items = items,
            text = text,
            fragmentIds = item.fragmentIds(),
            kind = EpubTextKind.ListItem,
            indentLevel = listLevel,
        )
        item.children()
            .filter { it.tagName().equals("ul", ignoreCase = true) || it.tagName().equals("ol", ignoreCase = true) }
            .forEach { nested ->
                collectListItems(
                    list = nested,
                    spineIndex = spineIndex,
                    resourceBaseDir = resourceBaseDir,
                    documentPath = documentPath,
                    listLevel = listLevel + 1,
                    rawPreTexts = rawPreTexts,
                    items = items,
                    ordered = nested.tagName().equals("ol", ignoreCase = true),
                    depth = depth + 1,
                    maxDomDepth = maxDomDepth,
                )
            }
    }
}

private fun addTextItem(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    text: TextWithLinks,
    fragmentIds: List<String> = emptyList(),
    kind: EpubTextKind = EpubTextKind.Body,
    indentLevel: Int = 0,
) {
    if (text.text.isEmpty()) return
    items += EpubReaderItem.Text(
        locator = EpubItemLocator(spineIndex, items.size),
        text = text.text,
        links = text.links,
        styleSpans = text.styleSpans,
        kind = kind,
        indentLevel = indentLevel,
        fragmentIds = fragmentIds,
    )
}

private fun addPlainTextItem(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    text: String,
    kind: EpubTextKind,
) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    items += EpubReaderItem.Text(
        locator = EpubItemLocator(spineIndex, items.size),
        text = trimmed,
        kind = kind,
    )
}

private fun addImageItem(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    element: Element,
    resourceBaseDir: String,
) {
    val href = element.attr("src").trim()
    if (href.isEmpty()) return
    items += EpubReaderItem.Image(
        locator = EpubItemLocator(spineIndex, items.size),
        href = resolveResourceHref(resourceBaseDir, href),
        altText = element.attr("alt").trim().takeIf { it.isNotEmpty() },
        fragmentIds = element.fragmentIds(),
    )
}

private fun Element.fragmentIds(): List<String> =
    buildList {
        addFragmentId(attr("id"))
        addFragmentId(attr("name"))
        select("[id], [name]").forEach { element ->
            addFragmentId(element.attr("id"))
            addFragmentId(element.attr("name"))
        }
    }.distinct()

private fun MutableList<String>.addFragmentId(value: String) {
    val id = value.trim()
    if (id.isNotEmpty()) add(id)
}

private fun resolveResourceHref(resourceBaseDir: String, href: String): String {
    if (
        resourceBaseDir.isBlank() ||
        epubIsExternalHref(href) ||
        href.startsWith("data:", ignoreCase = true)
    ) {
        return href
    }
    return epubResolvePath(resourceBaseDir, href)
}

private fun resolveLinkHref(resourceBaseDir: String, documentPath: String?, href: String): String {
    if (
        resourceBaseDir.isBlank() ||
        epubIsExternalHref(href) ||
        href.startsWith("data:", ignoreCase = true)
    ) {
        return href
    }
    val path = epubHrefPath(href)
    val fragment = href.substringAfter('#', missingDelimiterValue = "").substringBefore('?').trim()
    val resolvedPath = when {
        path.isNotEmpty() -> epubResolvePath(resourceBaseDir, path)
        !documentPath.isNullOrBlank() -> epubNormalizePath(documentPath)
        else -> ""
    }
    return when {
        resolvedPath.isEmpty() && fragment.isNotEmpty() -> "#$fragment"
        fragment.isNotEmpty() -> "$resolvedPath#$fragment"
        else -> resolvedPath
    }
}

private fun epubIsExternalHref(href: String): Boolean =
    href.startsWith("http://", ignoreCase = true) ||
        href.startsWith("https://", ignoreCase = true) ||
        href.startsWith("mailto:", ignoreCase = true) ||
        href.startsWith("tel:", ignoreCase = true)

private fun appendInlineNode(
    node: Node,
    builder: StringBuilder,
    links: MutableList<EpubTextLink>,
    styleSpans: MutableList<EpubTextStyleSpan>,
    resourceBaseDir: String,
    documentPath: String?,
    depth: Int,
    maxDomDepth: Int,
) {
    if (depth > maxDomDepth) return
    when (node) {
        is TextNode -> builder.append(node.text())
        is Element -> appendInlineElement(node, builder, links, styleSpans, resourceBaseDir, documentPath, depth = depth + 1, maxDomDepth = maxDomDepth)
    }
}

private fun appendInlineElement(
    element: Element,
    builder: StringBuilder,
    links: MutableList<EpubTextLink>,
    styleSpans: MutableList<EpubTextStyleSpan>,
    resourceBaseDir: String,
    documentPath: String?,
    depth: Int,
    maxDomDepth: Int,
) {
    if (depth > maxDomDepth) return
    when (val tag = element.tagName().lowercase()) {
        "br" -> builder.append('\n')
        "rt", "rp" -> Unit
        "ruby" -> appendRuby(element, builder, depth, maxDomDepth)
        else -> {
            val start = builder.length
            element.childNodes().forEach { child ->
                appendInlineNode(child, builder, links, styleSpans, resourceBaseDir, documentPath, depth = depth + 1, maxDomDepth = maxDomDepth)
            }
            val end = builder.length
            if (start >= end) return
            styleForTag(tag)?.let { style ->
                styleSpans += EpubTextStyleSpan(start = start, end = end, style = style)
            }
            if (tag == "a") {
                val href = element.attr("href").trim()
                if (href.isNotEmpty()) {
                    val resolved = resolveLinkHref(resourceBaseDir, documentPath, href)
                    links += EpubTextLink(
                        start = start,
                        end = end,
                        href = resolved,
                        isExternal = epubIsExternalHref(resolved),
                    )
                }
            }
        }
    }
}

private fun appendRuby(element: Element, builder: StringBuilder, depth: Int, maxDomDepth: Int) {
    if (depth > maxDomDepth) return
    val base = StringBuilder()
    element.childNodes().forEach { child ->
        if (child is Element && child.tagName().lowercase() in setOf("rt", "rp")) return@forEach
        when (child) {
            is TextNode -> base.append(child.text())
            is Element -> if (depth + 1 <= maxDomDepth) base.append(child.text())
        }
    }
    val annotation = element.children()
        .filter { it.tagName().equals("rt", ignoreCase = true) }
        .joinToString(" ") { it.text().trim() }
        .trim()
    builder.append(base.toString())
    if (annotation.isNotEmpty()) {
        builder.append('(').append(annotation).append(')')
    }
}

private fun styleForTag(tag: String): EpubTextStyle? =
    when (tag) {
        "strong", "b" -> EpubTextStyle.Bold
        "em", "i" -> EpubTextStyle.Italic
        "code", "kbd", "samp" -> EpubTextStyle.Code
        "sup" -> EpubTextStyle.Superscript
        "sub" -> EpubTextStyle.Subscript
        else -> null
    }

private fun trimTextWithRanges(
    rawText: String,
    links: List<EpubTextLink>,
    styleSpans: List<EpubTextStyleSpan>,
): TextWithLinks {
    val first = rawText.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return TextWithLinks("", emptyList(), emptyList())
    val endExclusive = rawText.indexOfLast { !it.isWhitespace() } + 1
    val text = rawText.substring(first, endExclusive)
    return TextWithLinks(
        text = text,
        links = links.mapNotNull { it.trimmed(first, endExclusive) },
        styleSpans = styleSpans.mapNotNull { it.trimmed(first, endExclusive) },
    )
}

private fun TextWithLinks.withPrefix(prefix: String): TextWithLinks {
    if (text.isEmpty()) return this
    val offset = prefix.length
    return TextWithLinks(
        text = prefix + text,
        links = links.map { it.copy(start = it.start + offset, end = it.end + offset) },
        styleSpans = styleSpans.map { it.copy(start = it.start + offset, end = it.end + offset) },
    )
}

private fun EpubTextLink.trimmed(first: Int, endExclusive: Int): EpubTextLink? {
    val clippedStart = start.coerceAtLeast(first)
    val clippedEnd = end.coerceAtMost(endExclusive)
    if (clippedStart >= clippedEnd) return null
    return copy(start = clippedStart - first, end = clippedEnd - first)
}

private fun EpubTextStyleSpan.trimmed(first: Int, endExclusive: Int): EpubTextStyleSpan? {
    val clippedStart = start.coerceAtLeast(first)
    val clippedEnd = end.coerceAtMost(endExclusive)
    if (clippedStart >= clippedEnd) return null
    return copy(start = clippedStart - first, end = clippedEnd - first)
}

private fun tableText(table: Element): String {
    val rows = table.select("tr").mapNotNull { row ->
        row.select("th, td")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
    }
    return rows.joinToString("\n").ifEmpty { table.text().trim() }
}

private fun preText(element: Element): String =
    Parser.unescapeEntities(
        element.html()
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), ""),
        false,
    ).trim('\n', '\r')

private fun rawPreTexts(html: String): List<String> =
    Regex("<pre\\b[^>]*>(.*?)</pre>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .map { match ->
            Parser.unescapeEntities(
                match.groupValues[1]
                    .replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace(Regex("<[^>]+>"), ""),
                false,
            ).trim('\n', '\r')
        }
        .toList()

private fun MutableList<String>.takeFirstOrNull(): String? =
    if (isEmpty()) null else removeAt(0)

private val INLINE_TAGS = setOf(
    "a",
    "abbr",
    "b",
    "bdi",
    "bdo",
    "cite",
    "code",
    "em",
    "i",
    "kbd",
    "mark",
    "q",
    "rp",
    "rt",
    "ruby",
    "s",
    "samp",
    "small",
    "span",
    "strong",
    "sub",
    "sup",
    "time",
    "u",
    "var",
)

internal fun epubParasFromReaderItems(items: List<EpubReaderItem>): List<EpubPara> {
    val plan = epubParagraphPlan(items)
    return epubParasFromParagraphPlan(plan)
}
