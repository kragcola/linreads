package dev.readflow.render.epub

import java.util.IdentityHashMap
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal enum class EpubCssUnit {
    Px,
    Em,
    Percent,
}

internal data class EpubCssLength(
    val value: Float,
    val unit: EpubCssUnit,
)

internal data class EpubCssInsets(
    val top: EpubCssLength? = null,
    val right: EpubCssLength? = null,
    val bottom: EpubCssLength? = null,
    val left: EpubCssLength? = null,
) {
    companion object {
        fun all(value: EpubCssLength) = EpubCssInsets(value, value, value, value)
    }
}

internal enum class EpubCssBorderStyle {
    Solid,
    Dashed,
    Dotted,
    Double,
}

internal data class EpubCssBorder(
    val width: EpubCssLength,
    val style: EpubCssBorderStyle,
    val color: Int? = null,
)

internal data class EpubCssBorders(
    val top: EpubCssBorder? = null,
    val right: EpubCssBorder? = null,
    val bottom: EpubCssBorder? = null,
    val left: EpubCssBorder? = null,
)

internal enum class EpubTextAlign {
    Start,
    Center,
    End,
    Justify,
}

internal data class EpubBlockStyle(
    val textAlign: EpubTextAlign? = null,
    val textIndent: EpubCssLength? = null,
    val margin: EpubCssInsets = EpubCssInsets(),
    val padding: EpubCssInsets = EpubCssInsets(),
    val backgroundColor: Int? = null,
    val borders: EpubCssBorders = EpubCssBorders(),
    val lineHeightMultiplier: Float? = null,
    val headingStyleResolved: Boolean = false,
)

internal data class EpubImageStyle(
    val width: EpubCssLength? = null,
    val height: EpubCssLength? = null,
    val maxWidth: EpubCssLength? = null,
    val maxHeight: EpubCssLength? = null,
    val alignment: EpubTextAlign? = null,
)

internal enum class EpubWhiteSpace {
    Normal,
    NoWrap,
    Pre,
    PreWrap,
    PreLine,
}

internal enum class EpubTextTransform {
    None,
    Uppercase,
    Lowercase,
    Capitalize,
}

internal enum class EpubVerticalAlign {
    Baseline,
    Superscript,
    Subscript,
}

internal data class EpubComputedCssStyle(
    val values: Map<String, String>,
    val fontWeight: Int,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val color: Int?,
    val backgroundColor: Int?,
    val fontSizeScale: Float,
    val monospace: Boolean,
    val verticalAlign: EpubVerticalAlign,
    val whiteSpace: EpubWhiteSpace,
    val textTransform: EpubTextTransform,
    val displayNone: Boolean,
)

internal class EpubCssCascade private constructor(
    private val rulesById: Map<String, List<EpubCssRule>>,
    private val rulesByClass: Map<String, List<EpubCssRule>>,
    private val rulesByTag: Map<String, List<EpubCssRule>>,
    private val universalRules: List<EpubCssRule>,
) {
    private val computedCache = IdentityHashMap<Element, EpubComputedCssStyle>()

    fun computedStyle(element: Element): EpubComputedCssStyle =
        computedCache[element] ?: computeStyle(element).also { computedCache[element] = it }

    fun blockStyle(element: Element): EpubBlockStyle {
        val style = computedStyle(element)
        val values = style.values
        return EpubBlockStyle(
            textAlign = parseTextAlign(values["text-align"]),
            textIndent = parseCssLength(values["text-indent"]),
            margin = parseInsets(values, "margin"),
            padding = parseInsets(values, "padding"),
            backgroundColor = style.backgroundColor,
            borders = parseBorders(values, style.color),
            lineHeightMultiplier = parseLineHeight(values["line-height"], style.fontSizeScale),
            headingStyleResolved = element.tagName().lowercase() in HEADING_TAGS,
        )
    }

    fun imageStyle(element: Element): EpubImageStyle {
        val values = computedStyle(element).values
        val centered = values["margin-left"]?.equals("auto", ignoreCase = true) == true &&
            values["margin-right"]?.equals("auto", ignoreCase = true) == true
        return EpubImageStyle(
            width = parseCssLength(values["width"]),
            height = parseCssLength(values["height"]),
            maxWidth = parseCssLength(values["max-width"]),
            maxHeight = parseCssLength(values["max-height"]),
            alignment = if (centered) EpubTextAlign.Center else parseTextAlign(values["text-align"]),
        )
    }

    fun appendText(
        raw: String,
        style: EpubComputedCssStyle,
        builder: StringBuilder,
        styleSpans: MutableList<EpubTextStyleSpan>,
        inlineBackground: Boolean,
    ) {
        val normalized = normalizeWhiteSpace(raw, style.whiteSpace, builder.lastOrNull())
        val transformed = transformText(normalized, style.textTransform)
        if (transformed.isEmpty()) return
        val start = builder.length
        builder.append(transformed)
        val end = builder.length
        styleSpans += textStyleSpans(style, start, end, inlineBackground)
    }

    fun textStyleSpans(
        style: EpubComputedCssStyle,
        start: Int,
        end: Int,
        inlineBackground: Boolean = true,
    ): List<EpubTextStyleSpan> {
        if (start >= end) return emptyList()
        return buildList {
            if (style.fontWeight >= 600) add(EpubTextStyleSpan(start, end, EpubTextStyle.Bold))
            if (style.italic) add(EpubTextStyleSpan(start, end, EpubTextStyle.Italic))
            if (style.monospace) add(EpubTextStyleSpan(start, end, EpubTextStyle.Code))
            when (style.verticalAlign) {
                EpubVerticalAlign.Superscript -> add(EpubTextStyleSpan(start, end, EpubTextStyle.Superscript))
                EpubVerticalAlign.Subscript -> add(EpubTextStyleSpan(start, end, EpubTextStyle.Subscript))
                EpubVerticalAlign.Baseline -> Unit
            }
            if (style.underline) add(EpubTextStyleSpan(start, end, EpubTextStyle.Underline))
            if (style.strikethrough) add(EpubTextStyleSpan(start, end, EpubTextStyle.Strikethrough))
            style.color?.let { add(EpubTextStyleSpan(start, end, EpubTextStyle.ForegroundColor, color = it)) }
            if (inlineBackground) {
                style.backgroundColor?.let {
                    add(EpubTextStyleSpan(start, end, EpubTextStyle.BackgroundColor, color = it))
                }
            }
            if (style.fontSizeScale != 1f) {
                add(EpubTextStyleSpan(start, end, EpubTextStyle.RelativeSize, scale = style.fontSizeScale))
            }
        }
    }

    fun transformText(raw: String, style: EpubComputedCssStyle): String =
        transformText(raw, style.textTransform)

    private fun computeStyle(element: Element): EpubComputedCssStyle {
        val parent = element.parent()
        val parentStyle = parent?.let(::computedStyle)
        val values = mutableMapOf<String, String>()
        INHERITED_PROPERTIES.forEach { property ->
            parentStyle?.values?.get(property)?.let { values[property] = it }
        }

        val candidates = mutableMapOf<String, EpubCssCandidate>()
        userAgentDeclarations(element).forEachIndexed { index, declaration ->
            candidates.accept(declaration, specificity = -1, order = index)
        }
        candidateRules(element).forEach { rule ->
            if (rule.selector.matches(element)) {
                rule.declarations.forEachIndexed { index, declaration ->
                    candidates.accept(
                        declaration,
                        specificity = rule.selector.specificity,
                        order = rule.order * DECLARATION_ORDER_STRIDE + index,
                    )
                }
            }
        }
        parseDeclarations(element.attr("style")).forEachIndexed { index, declaration ->
            candidates.accept(
                declaration,
                specificity = INLINE_SPECIFICITY,
                order = INLINE_ORDER + index,
            )
        }
        candidates.forEach { (property, candidate) ->
            when (candidate.value.lowercase()) {
                "inherit" -> parentStyle?.values?.get(property)?.let { values[property] = it }
                "initial" -> values.remove(property)
                "unset" -> if (property !in INHERITED_PROPERTIES) values.remove(property)
                else -> values[property] = candidate.value
            }
        }

        val decoration = values["text-decoration-line"] ?: values["text-decoration"].orEmpty()
        val parentFontSizeScale = parentStyle?.fontSizeScale ?: 1f
        val fontSizeCandidate = candidates["font-size"]?.value?.lowercase()
        val fontSizeScale = when (fontSizeCandidate) {
            null, "inherit", "unset" -> parentFontSizeScale
            "initial" -> 1f
            else -> parseFontSizeScale(fontSizeCandidate, parentFontSizeScale)
        }
        val family = values["font-family"].orEmpty().lowercase()
        return EpubComputedCssStyle(
            values = values,
            fontWeight = parseFontWeight(values["font-weight"]),
            italic = values["font-style"]?.lowercase() in setOf("italic", "oblique"),
            underline = decoration.split(Regex("\\s+")).any { it == "underline" },
            strikethrough = decoration.split(Regex("\\s+")).any { it == "line-through" },
            color = parseCssColor(values["color"]),
            backgroundColor = parseCssColor(values["background-color"] ?: backgroundColorFromShorthand(values["background"])),
            fontSizeScale = fontSizeScale,
            monospace = family.contains("monospace") || element.tagName().lowercase() in MONOSPACE_TAGS,
            verticalAlign = when (values["vertical-align"]?.lowercase()) {
                "super", "sup" -> EpubVerticalAlign.Superscript
                "sub" -> EpubVerticalAlign.Subscript
                else -> EpubVerticalAlign.Baseline
            },
            whiteSpace = parseWhiteSpace(values["white-space"]),
            textTransform = parseTextTransform(values["text-transform"]),
            displayNone = values["display"]?.equals("none", ignoreCase = true) == true ||
                values["visibility"]?.equals("hidden", ignoreCase = true) == true,
        )
    }

    private fun candidateRules(element: Element): Sequence<EpubCssRule> = sequence {
        val seen = mutableSetOf<EpubCssRule>()
        element.id().takeIf { it.isNotEmpty() }?.let { id ->
            rulesById[id].orEmpty().forEach { if (seen.add(it)) yield(it) }
        }
        element.classNames().forEach { className ->
            rulesByClass[className].orEmpty().forEach { if (seen.add(it)) yield(it) }
        }
        rulesByTag[element.tagName().lowercase()].orEmpty().forEach { if (seen.add(it)) yield(it) }
        universalRules.forEach { if (seen.add(it)) yield(it) }
    }

    companion object {
        fun create(
            document: Document,
            resourceBaseDir: String,
            resourceTextLoader: ((String) -> String?)?,
        ): EpubCssCascade {
            val rules = mutableListOf<EpubCssRule>()
            val loadedPaths = mutableSetOf<String>()
            var order = 0

            fun addCss(css: String, baseDir: String, importDepth: Int) {
                if (rules.size >= MAX_CSS_RULES) return
                if (importDepth <= MAX_IMPORT_DEPTH && resourceTextLoader != null) {
                    CSS_IMPORT.findAll(css).forEach { match ->
                        if (rules.size >= MAX_CSS_RULES) return@forEach
                        val href = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty().trim()
                        val path = epubSafeResolvePath(baseDir, href) ?: return@forEach
                        if (loadedPaths.add(path)) {
                            resourceTextLoader(path)?.let { imported ->
                                addCss(imported.take(MAX_CSS_CHARS), epubParentDir(path), importDepth + 1)
                            }
                        }
                    }
                }
                if (rules.size >= MAX_CSS_RULES) return
                parseCssRules(
                    css = CSS_IMPORT.replace(css, ""),
                    startingOrder = order,
                    maxRules = MAX_CSS_RULES - rules.size,
                ).forEach { rule ->
                    rules += rule
                    order = maxOf(order, rule.order + 1)
                }
            }

            document.select("link, style").forEach { element ->
                if (rules.size >= MAX_CSS_RULES) return@forEach
                when (element.tagName().lowercase()) {
                    "style" -> addCss(element.data().ifBlank { element.html() }, resourceBaseDir, 0)
                    "link" -> {
                        val rels = element.attr("rel").split(Regex("\\s+")).map { it.lowercase() }
                        if ("stylesheet" !in rels || resourceTextLoader == null) return@forEach
                        val href = element.attr("href").trim()
                        val path = epubSafeResolvePath(resourceBaseDir, href) ?: return@forEach
                        if (loadedPaths.add(path)) {
                            resourceTextLoader(path)?.let { css ->
                                addCss(css.take(MAX_CSS_CHARS), epubParentDir(path), 0)
                            }
                        }
                    }
                }
            }

            val byId = mutableMapOf<String, MutableList<EpubCssRule>>()
            val byClass = mutableMapOf<String, MutableList<EpubCssRule>>()
            val byTag = mutableMapOf<String, MutableList<EpubCssRule>>()
            val universal = mutableListOf<EpubCssRule>()
            rules.forEach { rule ->
                when (val key = rule.selector.indexKey) {
                    is EpubCssIndexKey.Id -> byId.getOrPut(key.value) { mutableListOf() } += rule
                    is EpubCssIndexKey.Class -> byClass.getOrPut(key.value) { mutableListOf() } += rule
                    is EpubCssIndexKey.Tag -> byTag.getOrPut(key.value) { mutableListOf() } += rule
                    EpubCssIndexKey.Universal -> universal += rule
                }
            }
            return EpubCssCascade(byId, byClass, byTag, universal)
        }
    }
}

private data class EpubCssDeclaration(
    val property: String,
    val value: String,
    val important: Boolean,
)

private data class EpubCssCandidate(
    val value: String,
    val important: Boolean,
    val specificity: Int,
    val order: Int,
)

private data class EpubCssRule(
    val selector: EpubCssSelector,
    val declarations: List<EpubCssDeclaration>,
    val order: Int,
)

private sealed interface EpubCssIndexKey {
    data class Id(val value: String) : EpubCssIndexKey
    data class Class(val value: String) : EpubCssIndexKey
    data class Tag(val value: String) : EpubCssIndexKey
    data object Universal : EpubCssIndexKey
}

private data class EpubCssSelector(
    val parts: List<EpubCssCompoundSelector>,
) {
    val specificity: Int = parts.sumOf { it.specificity }
    val indexKey: EpubCssIndexKey = parts.lastOrNull()?.let { part ->
        when {
            part.id != null -> EpubCssIndexKey.Id(part.id)
            part.classes.isNotEmpty() -> EpubCssIndexKey.Class(part.classes.first())
            part.tag != null && part.tag != "*" -> EpubCssIndexKey.Tag(part.tag)
            else -> EpubCssIndexKey.Universal
        }
    } ?: EpubCssIndexKey.Universal

    fun matches(element: Element): Boolean {
        var current: Element? = element
        for (index in parts.indices.reversed()) {
            val part = parts[index]
            if (index == parts.lastIndex) {
                if (current == null || !part.matches(current)) return false
                current = current.parent()
            } else {
                current = generateSequence(current) { it.parent() }.firstOrNull(part::matches)?.parent()
                    ?: return false
            }
        }
        return true
    }
}

private data class EpubCssCompoundSelector(
    val tag: String?,
    val id: String?,
    val classes: List<String>,
) {
    val specificity: Int = (if (id != null) 100 else 0) + classes.size * 10 + if (tag != null && tag != "*") 1 else 0

    fun matches(element: Element): Boolean =
        (tag == null || tag == "*" || element.tagName().equals(tag, ignoreCase = true)) &&
            (id == null || element.id() == id) &&
            classes.all(element::hasClass)
}

private fun MutableMap<String, EpubCssCandidate>.accept(
    declaration: EpubCssDeclaration,
    specificity: Int,
    order: Int,
) {
    val candidate = EpubCssCandidate(declaration.value, declaration.important, specificity, order)
    val current = this[declaration.property]
    if (
        current == null ||
        candidate.important && !current.important ||
        candidate.important == current.important && candidate.specificity > current.specificity ||
        candidate.important == current.important && candidate.specificity == current.specificity && candidate.order >= current.order
    ) {
        this[declaration.property] = candidate
    }
}

private fun parseCssRules(css: String, startingOrder: Int, maxRules: Int): List<EpubCssRule> {
    if (maxRules <= 0) return emptyList()
    val cleaned = CSS_COMMENTS.replace(css, "")
    val rules = mutableListOf<EpubCssRule>()
    var order = startingOrder
    for (match in CSS_RULE.findAll(cleaned)) {
        if (rules.size >= maxRules) break
        val selectorText = match.groupValues[1].trim()
        if (selectorText.startsWith("@")) continue
        val declarations = parseDeclarations(match.groupValues[2])
        if (declarations.isEmpty()) continue
        for (rawSelector in selectorText.split(',').take(MAX_SELECTORS_PER_RULE)) {
            if (rules.size >= maxRules) break
            parseSelector(rawSelector)?.let { selector ->
                rules += EpubCssRule(selector, declarations, order++)
            }
        }
    }
    return rules
}

private fun parseSelector(raw: String): EpubCssSelector? {
    val selector = raw.trim()
    if (selector.isEmpty() || selector.any { it in ">+~[]():" }) return null
    val parts = selector.split(Regex("\\s+")).map { token ->
        if (!SIMPLE_SELECTOR.matches(token)) return null
        val tag = SIMPLE_SELECTOR_TAG.find(token)?.value?.lowercase()
        val id = SIMPLE_SELECTOR_ID.find(token)?.groupValues?.get(1)
        val classes = SIMPLE_SELECTOR_CLASS.findAll(token).map { it.groupValues[1] }.toList()
        EpubCssCompoundSelector(tag, id, classes)
    }
    return EpubCssSelector(parts)
}

private fun parseDeclarations(raw: String): List<EpubCssDeclaration> =
    splitCssValues(raw, ';').flatMap { part ->
        val colon = indexOfCssDelimiter(part, ':')
        if (colon <= 0) return@flatMap emptyList()
        val property = part.substring(0, colon).trim().lowercase()
        var value = part.substring(colon + 1).trim()
        if (property.isEmpty() || value.isEmpty()) return@flatMap emptyList()
        val important = IMPORTANT_SUFFIX.containsMatchIn(value)
        value = IMPORTANT_SUFFIX.replace(value, "").trim()
        expandDeclaration(property, value, important)
    }

private fun expandDeclaration(property: String, value: String, important: Boolean): List<EpubCssDeclaration> =
    when (property) {
        "margin", "padding" -> {
            val values = splitCssWhitespace(value)
            val expanded = expandBoxValues(values) ?: return emptyList()
            listOf("top", "right", "bottom", "left").mapIndexed { index, side ->
                EpubCssDeclaration("$property-$side", expanded[index], important)
            }
        }
        "border" -> listOf("top", "right", "bottom", "left").map { side ->
            EpubCssDeclaration("border-$side", value, important)
        }
        "background" -> listOf(EpubCssDeclaration(property, value, important))
        "text-decoration" -> listOf(
            EpubCssDeclaration(property, value, important),
            EpubCssDeclaration("text-decoration-line", value, important),
        )
        else -> listOf(EpubCssDeclaration(property, value, important))
    }

private fun userAgentDeclarations(element: Element): List<EpubCssDeclaration> =
    when (element.tagName().lowercase()) {
        "strong", "b", "th" -> declarations("font-weight: 700")
        "em", "i", "cite", "var" -> declarations("font-style: italic")
        "code", "kbd", "samp", "tt" -> declarations("font-family: monospace")
        "u", "ins" -> declarations("text-decoration: underline")
        "s", "strike", "del" -> declarations("text-decoration: line-through")
        "sup" -> declarations("vertical-align: super")
        "sub" -> declarations("vertical-align: sub")
        "small" -> declarations("font-size: 0.8em")
        "big" -> declarations("font-size: 1.25em")
        "pre" -> declarations("white-space: pre; font-family: monospace")
        "mark" -> declarations("background-color: #ffff00")
        "center" -> declarations("text-align: center")
        "blockquote" -> declarations("margin-left: 2em; margin-right: 2em")
        "h1" -> declarations("font-size: 1.5em; font-weight: 700; text-align: center")
        "h2" -> declarations("font-size: 1.3em; font-weight: 700; text-align: center")
        "h3" -> declarations("font-size: 1.15em; font-weight: 700; text-align: center")
        else -> emptyList()
    }

private fun declarations(raw: String): List<EpubCssDeclaration> = parseDeclarations(raw)

private fun parseInsets(values: Map<String, String>, prefix: String) = EpubCssInsets(
    top = parseCssLength(values["$prefix-top"]),
    right = parseCssLength(values["$prefix-right"]),
    bottom = parseCssLength(values["$prefix-bottom"]),
    left = parseCssLength(values["$prefix-left"]),
)

private fun parseBorders(values: Map<String, String>, currentColor: Int?): EpubCssBorders = EpubCssBorders(
    top = parseBorder(values, "top", currentColor),
    right = parseBorder(values, "right", currentColor),
    bottom = parseBorder(values, "bottom", currentColor),
    left = parseBorder(values, "left", currentColor),
)

private fun parseBorder(values: Map<String, String>, side: String, currentColor: Int?): EpubCssBorder? {
    val shorthand = values["border-$side"]
    val tokens = shorthand?.let(::splitCssWhitespace).orEmpty()
    val width = parseCssLength(values["border-$side-width"])
        ?: tokens.firstNotNullOfOrNull(::parseCssLength)
        ?: return null
    val style = parseBorderStyle(values["border-$side-style"])
        ?: tokens.firstNotNullOfOrNull(::parseBorderStyle)
        ?: EpubCssBorderStyle.Solid
    val color = parseCssColor(values["border-$side-color"])
        ?: tokens.firstNotNullOfOrNull(::parseCssColor)
        ?: currentColor
    return EpubCssBorder(width, style, color)
}

internal fun parseCssLength(raw: String?): EpubCssLength? {
    val value = raw?.trim()?.lowercase() ?: return null
    if (value == "0") return EpubCssLength(0f, EpubCssUnit.Px)
    val match = CSS_LENGTH.matchEntire(value) ?: return when (value) {
        "thin" -> EpubCssLength(1f, EpubCssUnit.Px)
        "medium" -> EpubCssLength(2f, EpubCssUnit.Px)
        "thick" -> EpubCssLength(3f, EpubCssUnit.Px)
        else -> null
    }
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2]) {
        "em", "rem" -> EpubCssLength(number, EpubCssUnit.Em)
        "%" -> EpubCssLength(number, EpubCssUnit.Percent)
        "pt" -> EpubCssLength(number * 4f / 3f, EpubCssUnit.Px)
        "pc" -> EpubCssLength(number * 16f, EpubCssUnit.Px)
        "in" -> EpubCssLength(number * 96f, EpubCssUnit.Px)
        "cm" -> EpubCssLength(number * 96f / 2.54f, EpubCssUnit.Px)
        "mm" -> EpubCssLength(number * 96f / 25.4f, EpubCssUnit.Px)
        "px", "" -> EpubCssLength(number, EpubCssUnit.Px)
        else -> null
    }
}

internal fun parseCssColor(raw: String?): Int? {
    val value = raw?.trim()?.lowercase() ?: return null
    if (value == "transparent") return 0
    CSS_HEX.matchEntire(value)?.let { match ->
        val hex = match.groupValues[1]
        return when (hex.length) {
            3 -> (0xFF000000L or hex.map { "$it$it".toLong(16) }.foldIndexed(0L) { index, acc, part ->
                acc or (part shl ((2 - index) * 8))
            }).toInt()
            4 -> {
                val red = "${hex[0]}${hex[0]}".toLong(16)
                val green = "${hex[1]}${hex[1]}".toLong(16)
                val blue = "${hex[2]}${hex[2]}".toLong(16)
                val alpha = "${hex[3]}${hex[3]}".toLong(16)
                ((alpha shl 24) or (red shl 16) or (green shl 8) or blue).toInt()
            }
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> {
                val rgb = hex.substring(0, 6).toLong(16)
                val alpha = hex.substring(6, 8).toLong(16)
                ((alpha shl 24) or rgb).toInt()
            }
            else -> null
        }
    }
    CSS_RGB.matchEntire(value)?.let { match ->
        val alpha = match.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        val red = parseRgbChannel(match.groupValues[1]) ?: return null
        val green = parseRgbChannel(match.groupValues[2]) ?: return null
        val blue = parseRgbChannel(match.groupValues[3]) ?: return null
        return ((alpha * 255f + 0.5f).toInt() shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return CSS_NAMED_COLORS[value]
}

private fun parseRgbChannel(raw: String): Int? =
    if (raw.endsWith('%')) {
        raw.dropLast(1).toFloatOrNull()?.let { (it.coerceIn(0f, 100f) * 2.55f + 0.5f).toInt() }
    } else {
        raw.toFloatOrNull()?.coerceIn(0f, 255f)?.toInt()
    }

private fun parseBorderStyle(raw: String?): EpubCssBorderStyle? =
    when (raw?.trim()?.lowercase()) {
        "solid" -> EpubCssBorderStyle.Solid
        "dashed" -> EpubCssBorderStyle.Dashed
        "dotted" -> EpubCssBorderStyle.Dotted
        "double" -> EpubCssBorderStyle.Double
        else -> null
    }

private fun parseTextAlign(raw: String?): EpubTextAlign? =
    when (raw?.trim()?.lowercase()) {
        "left", "start" -> EpubTextAlign.Start
        "center" -> EpubTextAlign.Center
        "right", "end" -> EpubTextAlign.End
        "justify", "justify-all" -> EpubTextAlign.Justify
        else -> null
    }

private fun parseFontWeight(raw: String?): Int =
    when (val value = raw?.trim()?.lowercase()) {
        null, "", "normal" -> 400
        "bold", "bolder" -> 700
        "lighter" -> 300
        else -> value.toIntOrNull()?.coerceIn(100, 900) ?: 400
    }

private fun parseFontSizeScale(raw: String?, parentScale: Float): Float {
    val value = raw?.trim()?.lowercase() ?: return parentScale
    return when (value) {
        "xx-small" -> 0.6f
        "x-small" -> 0.75f
        "small", "smaller" -> parentScale * 0.8f
        "medium" -> 1f
        "large", "larger" -> parentScale * 1.2f
        "x-large" -> 1.5f
        "xx-large" -> 2f
        else -> parseCssLength(value)?.let { length ->
            when (length.unit) {
                EpubCssUnit.Em -> parentScale * length.value
                EpubCssUnit.Percent -> parentScale * length.value / 100f
                EpubCssUnit.Px -> length.value / DEFAULT_CSS_FONT_SIZE_PX
            }
        } ?: parentScale
    }.coerceIn(0.5f, 4f)
}

private fun parseLineHeight(raw: String?, fontSizeScale: Float): Float? {
    val value = raw?.trim()?.lowercase() ?: return null
    if (value == "normal") return null
    value.toFloatOrNull()?.let { return it.coerceIn(0.8f, 4f) }
    val length = parseCssLength(value) ?: return null
    return when (length.unit) {
        EpubCssUnit.Em -> length.value
        EpubCssUnit.Percent -> length.value / 100f
        EpubCssUnit.Px -> length.value / (DEFAULT_CSS_FONT_SIZE_PX * fontSizeScale)
    }.coerceIn(0.8f, 4f)
}

private fun parseWhiteSpace(raw: String?): EpubWhiteSpace =
    when (raw?.trim()?.lowercase()) {
        "nowrap" -> EpubWhiteSpace.NoWrap
        "pre" -> EpubWhiteSpace.Pre
        "pre-wrap", "break-spaces" -> EpubWhiteSpace.PreWrap
        "pre-line" -> EpubWhiteSpace.PreLine
        else -> EpubWhiteSpace.Normal
    }

private fun parseTextTransform(raw: String?): EpubTextTransform =
    when (raw?.trim()?.lowercase()) {
        "uppercase" -> EpubTextTransform.Uppercase
        "lowercase" -> EpubTextTransform.Lowercase
        "capitalize" -> EpubTextTransform.Capitalize
        else -> EpubTextTransform.None
    }

private fun normalizeWhiteSpace(raw: String, mode: EpubWhiteSpace, prior: Char?): String {
    val normalizedNewlines = raw.replace("\r\n", "\n").replace('\r', '\n')
    val value = when (mode) {
        EpubWhiteSpace.Pre, EpubWhiteSpace.PreWrap -> normalizedNewlines
        EpubWhiteSpace.PreLine -> normalizedNewlines
            .replace(Regex("[\\t\\u000B\\u000C ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
        EpubWhiteSpace.Normal, EpubWhiteSpace.NoWrap -> normalizedNewlines.replace(Regex("\\s+"), " ")
    }
    return if (
        mode in setOf(EpubWhiteSpace.Normal, EpubWhiteSpace.NoWrap) &&
        prior?.isWhitespace() == true &&
        value.startsWith(' ')
    ) value.drop(1) else value
}

private fun transformText(raw: String, transform: EpubTextTransform): String =
    when (transform) {
        EpubTextTransform.None -> raw
        EpubTextTransform.Uppercase -> raw.uppercase()
        EpubTextTransform.Lowercase -> raw.lowercase()
        EpubTextTransform.Capitalize -> raw.split(WORD_BOUNDARY).joinToString("") { part ->
            if (part.firstOrNull()?.isLetter() == true) part.replaceFirstChar(Char::uppercase) else part
        }
    }

private fun backgroundColorFromShorthand(raw: String?): String? =
    raw?.let(::splitCssWhitespace)?.firstOrNull { parseCssColor(it) != null }

private fun expandBoxValues(values: List<String>): List<String>? =
    when (values.size) {
        1 -> listOf(values[0], values[0], values[0], values[0])
        2 -> listOf(values[0], values[1], values[0], values[1])
        3 -> listOf(values[0], values[1], values[2], values[1])
        4 -> values
        else -> null
    }

private fun splitCssWhitespace(value: String): List<String> =
    splitCssValues(value, ' ').filter { it.isNotBlank() }

private fun splitCssValues(value: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var quote: Char? = null
    var parentheses = 0
    value.forEachIndexed { index, char ->
        when {
            quote != null && char == quote -> quote = null
            quote != null -> Unit
            char == '\'' || char == '"' -> quote = char
            char == '(' -> parentheses++
            char == ')' -> parentheses = (parentheses - 1).coerceAtLeast(0)
            char == delimiter && parentheses == 0 -> {
                if (delimiter != ' ' || index > start) result += value.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    result += value.substring(start).trim()
    return result
}

private fun indexOfCssDelimiter(value: String, delimiter: Char): Int {
    var quote: Char? = null
    var parentheses = 0
    value.forEachIndexed { index, char ->
        when {
            quote != null && char == quote -> quote = null
            quote != null -> Unit
            char == '\'' || char == '"' -> quote = char
            char == '(' -> parentheses++
            char == ')' -> parentheses = (parentheses - 1).coerceAtLeast(0)
            char == delimiter && parentheses == 0 -> return index
        }
    }
    return -1
}

private val INHERITED_PROPERTIES = setOf(
    "color",
    "font-family",
    "font-size",
    "font-style",
    "font-weight",
    "line-height",
    "text-align",
    "text-decoration",
    "text-decoration-line",
    "text-indent",
    "text-transform",
    "visibility",
    "white-space",
)
private val MONOSPACE_TAGS = setOf("code", "kbd", "samp", "tt", "pre")
private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val CSS_COMMENTS = Regex("/\\*[\\s\\S]*?\\*/")
private val CSS_RULE = Regex("([^{}]+)\\{([^{}]*)}")
private val CSS_IMPORT = Regex(
    "@import\\s+(?:url\\(\\s*['\"]?([^'\")\\s]+)['\"]?\\s*\\)|['\"]([^'\"]+)['\"])\\s*[^;]*;",
    RegexOption.IGNORE_CASE,
)
private val IMPORTANT_SUFFIX = Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE)
private val SIMPLE_SELECTOR = Regex("(?:\\*|[A-Za-z][A-Za-z0-9_-]*)?(?:[.#][A-Za-z_][A-Za-z0-9_-]*)*")
private val SIMPLE_SELECTOR_TAG = Regex("^(\\*|[A-Za-z][A-Za-z0-9_-]*)")
private val SIMPLE_SELECTOR_ID = Regex("#([A-Za-z_][A-Za-z0-9_-]*)")
private val SIMPLE_SELECTOR_CLASS = Regex("\\.([A-Za-z_][A-Za-z0-9_-]*)")
private val CSS_LENGTH = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(px|em|rem|%|pt|pc|in|cm|mm)?")
private val CSS_HEX = Regex("#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})")
private val CSS_RGB = Regex(
    "rgba?\\(\\s*([+-]?[\\d.]+%?)\\s*,\\s*([+-]?[\\d.]+%?)\\s*,\\s*([+-]?[\\d.]+%?)(?:\\s*,\\s*([\\d.]+))?\\s*\\)",
)
private val WORD_BOUNDARY = Regex("(?<=\\s)|(?=\\s)")
private val CSS_NAMED_COLORS = mapOf(
    "black" to 0xFF000000.toInt(),
    "silver" to 0xFFC0C0C0.toInt(),
    "gray" to 0xFF808080.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "maroon" to 0xFF800000.toInt(),
    "red" to 0xFFFF0000.toInt(),
    "purple" to 0xFF800080.toInt(),
    "fuchsia" to 0xFFFF00FF.toInt(),
    "green" to 0xFF008000.toInt(),
    "lime" to 0xFF00FF00.toInt(),
    "olive" to 0xFF808000.toInt(),
    "yellow" to 0xFFFFFF00.toInt(),
    "navy" to 0xFF000080.toInt(),
    "blue" to 0xFF0000FF.toInt(),
    "teal" to 0xFF008080.toInt(),
    "aqua" to 0xFF00FFFF.toInt(),
    "orange" to 0xFFFFA500.toInt(),
)
private const val DEFAULT_CSS_FONT_SIZE_PX = 16f
private const val INLINE_SPECIFICITY = 1_000
private const val INLINE_ORDER = 1_000_000_000
private const val DECLARATION_ORDER_STRIDE = 128
private const val MAX_CSS_RULES = 4_096
private const val MAX_SELECTORS_PER_RULE = 32
private const val MAX_IMPORT_DEPTH = 4
private const val MAX_CSS_CHARS = 1024 * 1024
