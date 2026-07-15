package dev.readflow.render.epub

internal data class EpubItemLocator(
    val spineIndex: Int,
    val elementIndex: Int,
    val charOffset: Int = 0,
)

internal data class EpubTextLink(
    val start: Int,
    val end: Int,
    val href: String,
    val isExternal: Boolean,
)

internal enum class EpubTextKind {
    Body,
    ListItem,
    Blockquote,
    Preformatted,
    Table,
}

internal enum class EpubTextStyle {
    Bold,
    Italic,
    Code,
    Superscript,
    Subscript,
    Underline,
    Strikethrough,
    ForegroundColor,
    BackgroundColor,
    RelativeSize,
}

internal data class EpubTextStyleSpan(
    val start: Int,
    val end: Int,
    val style: EpubTextStyle,
    val color: Int? = null,
    val scale: Float? = null,
)

internal sealed interface EpubReaderItem {
    val locator: EpubItemLocator

    data class Text(
        override val locator: EpubItemLocator,
        val text: String,
        val links: List<EpubTextLink> = emptyList(),
        val styleSpans: List<EpubTextStyleSpan> = emptyList(),
        val kind: EpubTextKind = EpubTextKind.Body,
        val indentLevel: Int = 0,
        val fragmentIds: List<String> = emptyList(),
        val isCodeBlock: Boolean = false,
        val language: String = "",
        val blockStyle: EpubBlockStyle = EpubBlockStyle(),
    ) : EpubReaderItem

    data class Heading(
        override val locator: EpubItemLocator,
        val level: Int,
        val text: String,
        val links: List<EpubTextLink> = emptyList(),
        val styleSpans: List<EpubTextStyleSpan> = emptyList(),
        val fragmentIds: List<String> = emptyList(),
        val blockStyle: EpubBlockStyle = EpubBlockStyle(),
    ) : EpubReaderItem

    data class Image(
        override val locator: EpubItemLocator,
        val href: String,
        val altText: String?,
        val fragmentIds: List<String> = emptyList(),
        val style: EpubImageStyle = EpubImageStyle(),
        val isInlineContent: Boolean = false,
    ) : EpubReaderItem

    data class Break(
        override val locator: EpubItemLocator,
    ) : EpubReaderItem
}
