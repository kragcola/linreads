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
    resourceTextLoader: ((String) -> String?)? = null,
): List<EpubReaderItem> = epubParserGuard(emptyList()) {
    val safeHtml = sanitizeEpubXml(html) ?: return@epubParserGuard emptyList()
    val items = mutableListOf<EpubReaderItem>()
    val rawPreTexts = rawPreTexts(safeHtml).toMutableList()
    val doc = Jsoup.parse(safeHtml, "", Parser.xmlParser())
    val css = EpubCssCascade.create(doc, resourceBaseDir, resourceTextLoader)
    val body = doc.selectFirst("body") ?: doc
    if (css.computedStyle(body).displayNone) return@epubParserGuard emptyList()
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
            css = css,
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
    css: EpubCssCascade,
): TextWithLinks {
    val builder = StringBuilder()
    val links = mutableListOf<EpubTextLink>()
    val styleSpans = mutableListOf<EpubTextStyleSpan>()
    element.childNodes().forEach { node ->
        appendInlineNode(node, builder, links, styleSpans, resourceBaseDir, documentPath, depth = 0, maxDomDepth = maxDomDepth, css = css)
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
    css: EpubCssCascade,
) {
    if (depth > maxDomDepth) return
    if (css.computedStyle(element).displayNone) return
    when (val tag = element.tagName().lowercase()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val text = extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth, css)
            if (text.text.isNotEmpty()) {
                items += EpubReaderItem.Heading(
                    locator = EpubItemLocator(spineIndex, items.size),
                    level = tag.removePrefix("h").toIntOrNull() ?: 1,
                    text = text.text,
                    links = text.links,
                    styleSpans = text.styleSpans,
                    fragmentIds = element.fragmentIds(),
                    blockStyle = css.blockStyle(element),
                )
            }
        }
        "p" -> addParagraphWithImages(spineIndex, items, element, resourceBaseDir, documentPath, maxDomDepth, css)
        "img" -> addImageItem(spineIndex, items, element, resourceBaseDir, css)
        "svg" -> addSvgImageItems(spineIndex, items, element, resourceBaseDir, css)
        "br", "hr" -> items += EpubReaderItem.Break(EpubItemLocator(spineIndex, items.size))
        "table" -> addPlainTextItem(
            spineIndex = spineIndex,
            items = items,
            text = tableText(element),
            kind = EpubTextKind.Table,
            css = css,
            cssStyle = css.computedStyle(element),
            blockStyle = css.blockStyle(element),
        )
        "pre" -> addPlainTextItem(
            spineIndex = spineIndex,
            items = items,
            text = rawPreTexts.takeFirstOrNull() ?: preText(element),
            kind = EpubTextKind.Preformatted,
            isCodeBlock = true,
            language = element.languageClass(),
            css = css,
            cssStyle = css.computedStyle(element),
            blockStyle = css.blockStyle(element),
        )
        "code" -> addPlainTextItem(
            spineIndex = spineIndex,
            items = items,
            text = element.wholeText(),
            kind = EpubTextKind.Preformatted,
            isCodeBlock = true,
            language = element.languageClass(),
            css = css,
            cssStyle = css.computedStyle(element),
            blockStyle = css.blockStyle(element),
        )
        "blockquote" -> addTextItem(
            spineIndex = spineIndex,
            items = items,
            text = extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth, css),
            kind = EpubTextKind.Blockquote,
            blockStyle = css.blockStyle(element),
        )
        "ul", "ol" -> collectListItems(element, spineIndex, resourceBaseDir, documentPath, listLevel, rawPreTexts, items, ordered = tag == "ol", depth = depth + 1, maxDomDepth = maxDomDepth, css = css)
        "style", "script", "link", "meta", "title" -> Unit
        else -> collectContainerOrUnknown(element, spineIndex, resourceBaseDir, documentPath, listLevel, rawPreTexts, items, depth = depth + 1, maxDomDepth = maxDomDepth, css = css)
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
    css: EpubCssCascade,
) {
    if (depth > maxDomDepth) return
    if (addImageOnlyContainerItems(spineIndex, items, element, resourceBaseDir, css)) return
    val blockChildren = element.children().filter { it.tagName().lowercase() !in INLINE_TAGS }
    if (blockChildren.isNotEmpty()) {
        blockChildren.forEach {
            collectReaderItems(it, spineIndex, resourceBaseDir, documentPath, listLevel, rawPreTexts, items, depth = depth + 1, maxDomDepth = maxDomDepth, css = css)
        }
        return
    }
    addTextItem(
        spineIndex,
        items,
        extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth, css),
        element.fragmentIds(),
        blockStyle = css.blockStyle(element),
    )
}

private fun addImageOnlyContainerItems(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    element: Element,
    resourceBaseDir: String,
    css: EpubCssCascade,
): Boolean {
    val images = element.select("img").filter {
        it.attr("src").trim().isNotEmpty() && !css.computedStyle(it).displayNone
    }
    if (images.isEmpty()) return false
    val textWithoutImages = element.clone().apply { select("img").remove() }.text()
    if (textWithoutImages.any { !it.isEpubReaderSpacerChar() }) return false
    images.forEach { addImageItem(spineIndex, items, it, resourceBaseDir, css) }
    return true
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
    css: EpubCssCascade,
) {
    if (depth > maxDomDepth) return
    list.children().filter { it.tagName().equals("li", ignoreCase = true) }.forEachIndexed { index, item ->
        val itemNodes = item.childNodes().filterNot { node ->
            node is Element && node.tagName().lowercase() in setOf("ol", "ul")
        }
        val text = extractTextWithLinksFromNodes(itemNodes, resourceBaseDir, documentPath, maxDomDepth, css)
            .withPrefix(if (ordered) "${index + 1}. " else "• ")
        addTextItem(
            spineIndex = spineIndex,
            items = items,
            text = text,
            fragmentIds = item.fragmentIds(),
            kind = EpubTextKind.ListItem,
            indentLevel = listLevel,
            blockStyle = css.blockStyle(item),
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
                    css = css,
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
    blockStyle: EpubBlockStyle = EpubBlockStyle(),
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
        blockStyle = blockStyle,
    )
}

private fun extractTextWithLinksFromNodes(
    nodes: List<Node>,
    resourceBaseDir: String,
    documentPath: String?,
    maxDomDepth: Int,
    css: EpubCssCascade,
): TextWithLinks {
    val builder = StringBuilder()
    val links = mutableListOf<EpubTextLink>()
    val styleSpans = mutableListOf<EpubTextStyleSpan>()
    nodes.forEach { node ->
        appendInlineNode(node, builder, links, styleSpans, resourceBaseDir, documentPath, depth = 0, maxDomDepth = maxDomDepth, css = css)
    }
    return trimTextWithRanges(builder.toString(), links, styleSpans)
}

// A <p> can wrap a block image, e.g. <p><img .../><br/></p> (common in scanned light novels).
// Walk the direct children in document order: accumulate inline runs into text items and emit
// each direct-child <img> as its own image item. Images nested deeper (footnote markers inside
// <a><sup><img/></sup></a>) are NOT direct children, so they stay inline and never become block
// images. A <p> with no direct-child <img> degrades to the original single text item.
private fun addParagraphWithImages(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    element: Element,
    resourceBaseDir: String,
    documentPath: String?,
    maxDomDepth: Int,
    css: EpubCssCascade,
) {
    val hasDirectImage = element.children().any {
        it.tagName().equals("img", ignoreCase = true) && !css.computedStyle(it).displayNone
    }
    if (!hasDirectImage) {
        addTextItem(
            spineIndex,
            items,
            extractTextWithLinks(element, resourceBaseDir, documentPath, maxDomDepth, css),
            element.fragmentIds(),
            blockStyle = css.blockStyle(element),
        )
        return
    }
    val pendingNodes = mutableListOf<Node>()
    fun flushPendingText() {
        if (pendingNodes.isEmpty()) return
        addTextItem(
            spineIndex,
            items,
            extractTextWithLinksFromNodes(pendingNodes, resourceBaseDir, documentPath, maxDomDepth, css),
            blockStyle = css.blockStyle(element),
        )
        pendingNodes.clear()
    }
    element.childNodes().forEach { node ->
        if (
            node is Element &&
            node.tagName().equals("img", ignoreCase = true) &&
            !css.computedStyle(node).displayNone
        ) {
            flushPendingText()
            addImageItem(spineIndex, items, node, resourceBaseDir, css)
        } else {
            pendingNodes += node
        }
    }
    flushPendingText()
}

private fun addPlainTextItem(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    text: String,
    kind: EpubTextKind,
    isCodeBlock: Boolean = false,
    language: String = "",
    css: EpubCssCascade? = null,
    cssStyle: EpubComputedCssStyle? = null,
    blockStyle: EpubBlockStyle = EpubBlockStyle(),
) {
    val transformed = cssStyle?.let { style ->
        when (style.whiteSpace) {
            EpubWhiteSpace.Pre, EpubWhiteSpace.PreWrap -> text
            else -> text.trim()
        }.let { cssText ->
            when (style.textTransform) {
                EpubTextTransform.None -> cssText
                EpubTextTransform.Uppercase -> cssText.uppercase()
                EpubTextTransform.Lowercase -> cssText.lowercase()
                EpubTextTransform.Capitalize -> cssText.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { part ->
                    if (part.firstOrNull()?.isLetter() == true) part.replaceFirstChar(Char::uppercase) else part
                }
            }
        }
    } ?: text.trim()
    val trimmed = transformed
    if (trimmed.isEmpty()) return
    items += EpubReaderItem.Text(
        locator = EpubItemLocator(spineIndex, items.size),
        text = trimmed,
        kind = kind,
        isCodeBlock = isCodeBlock,
        language = language,
        styleSpans = if (css != null && cssStyle != null) {
            css.textStyleSpans(cssStyle, 0, trimmed.length, inlineBackground = false)
        } else {
            emptyList()
        },
        blockStyle = blockStyle,
    )
}

private fun addImageItem(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    element: Element,
    resourceBaseDir: String,
    css: EpubCssCascade,
) {
    if (css.computedStyle(element).displayNone) return
    val href = element.attr("src").trim()
    if (href.isEmpty()) return
    items += EpubReaderItem.Image(
        locator = EpubItemLocator(spineIndex, items.size),
        href = resolveResourceHref(resourceBaseDir, href),
        altText = element.attr("alt").trim().takeIf { it.isNotEmpty() },
        fragmentIds = element.fragmentIds(),
        style = css.imageStyle(element),
    )
}

// SVG cover/illustration wrappers (common EPUB cover pattern: <svg><image xlink:href=".../cover.jpg"/>).
// Each nested SVG <image> references a raster via xlink:href (SVG 1.1) or href (SVG 2); emit each as an
// image item so covers using this wrapper render like an <img> (审计: SVG-wrapped covers showed blank —
// the dispatch only handled <img> and the <svg> subtree fell through to text extraction, dropping the image).
private fun addSvgImageItems(
    spineIndex: Int,
    items: MutableList<EpubReaderItem>,
    element: Element,
    resourceBaseDir: String,
    css: EpubCssCascade,
) {
    element.select("image").forEach { image ->
        if (css.computedStyle(image).displayNone) return@forEach
        val href = image.attr("xlink:href").trim().ifEmpty { image.attr("href").trim() }
        if (href.isEmpty()) return@forEach
        items += EpubReaderItem.Image(
            locator = EpubItemLocator(spineIndex, items.size),
            href = resolveResourceHref(resourceBaseDir, href),
            altText = null,
            fragmentIds = image.fragmentIds(),
            style = css.imageStyle(image),
        )
    }
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
    css: EpubCssCascade,
) {
    if (depth > maxDomDepth) return
    when (node) {
        is TextNode -> {
            val parent = node.parent() as? Element ?: return
            val style = css.computedStyle(parent)
            if (!style.displayNone) {
                css.appendText(
                    raw = node.getWholeText(),
                    style = style,
                    builder = builder,
                    styleSpans = styleSpans,
                    inlineBackground = parent.tagName().lowercase() in INLINE_TAGS,
                )
            }
        }
        is Element -> appendInlineElement(node, builder, links, styleSpans, resourceBaseDir, documentPath, depth = depth + 1, maxDomDepth = maxDomDepth, css = css)
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
    css: EpubCssCascade,
) {
    if (depth > maxDomDepth) return
    val computedStyle = css.computedStyle(element)
    if (computedStyle.displayNone) return
    when (val tag = element.tagName().lowercase()) {
        "br" -> builder.append('\n')
        "rt", "rp" -> Unit
        "ruby" -> css.appendText(
            raw = rubyText(element, depth, maxDomDepth),
            style = computedStyle,
            builder = builder,
            styleSpans = styleSpans,
            inlineBackground = true,
        )
        else -> {
            val start = builder.length
            element.childNodes().forEach { child ->
                appendInlineNode(child, builder, links, styleSpans, resourceBaseDir, documentPath, depth = depth + 1, maxDomDepth = maxDomDepth, css = css)
            }
            val end = builder.length
            if (start >= end) return
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

private fun rubyText(element: Element, depth: Int, maxDomDepth: Int): String {
    if (depth > maxDomDepth) return ""
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
    return buildString {
        append(base)
        if (annotation.isNotEmpty()) append('(').append(annotation).append(')')
    }
}

private fun trimTextWithRanges(
    rawText: String,
    links: List<EpubTextLink>,
    styleSpans: List<EpubTextStyleSpan>,
): TextWithLinks {
    val first = rawText.indexOfFirst { !it.isEpubReaderSpacerChar() }
    if (first < 0) return TextWithLinks("", emptyList(), emptyList())
    val endExclusive = rawText.indexOfLast { !it.isEpubReaderSpacerChar() } + 1
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

private fun Char.isEpubReaderSpacerChar(): Boolean =
    Character.isWhitespace(this) ||
        Character.isSpaceChar(this) ||
        this in EPUB_INVISIBLE_SPACER_CHARS

private val EPUB_INVISIBLE_SPACER_CHARS = setOf(
    '\u180E', // Mongolian vowel separator, used as an invisible spacer in older generated EPUBs.
    '\u200B', // Zero-width space.
    '\u200C', // Zero-width non-joiner.
    '\u200D', // Zero-width joiner.
    '\u2060', // Word joiner.
    '\uFEFF', // BOM / zero-width no-break space.
)

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

private fun Element.languageClass(): String =
    attr("class")
        .split(" ")
        .firstOrNull { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: ""
