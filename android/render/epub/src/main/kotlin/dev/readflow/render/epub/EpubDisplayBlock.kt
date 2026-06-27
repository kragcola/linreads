package dev.readflow.render.epub

internal sealed interface EpubDisplayBlock {
    val paragraphIndex: Int

    data class Text(
        val text: String,
        val headingLevel: Int?,
        override val paragraphIndex: Int,
        val links: List<EpubTextLink> = emptyList(),
        val styleSpans: List<EpubTextStyleSpan> = emptyList(),
        val kind: EpubTextKind = EpubTextKind.Body,
        val indentLevel: Int = 0,
        val fragmentIds: List<String> = emptyList(),
        val isCodeBlock: Boolean = false,
        val language: String = "",
    ) : EpubDisplayBlock

    data class Image(
        val href: String,
        val altText: String?,
        override val paragraphIndex: Int,
        val fragmentIds: List<String> = emptyList(),
    ) : EpubDisplayBlock

    data class Break(
        override val paragraphIndex: Int,
    ) : EpubDisplayBlock
}

internal fun epubDisplayBlocks(items: List<EpubReaderItem>): List<EpubDisplayBlock> =
    buildList {
        val paragraphPlan = epubParagraphPlan(items)
        items.forEachIndexed { itemIndex, item ->
            val paragraphIndex = paragraphPlan.itemParagraphIndexes[itemIndex]
            when (item) {
                is EpubReaderItem.Heading -> {
                    add(
                        EpubDisplayBlock.Text(
                            text = item.text,
                            headingLevel = item.level,
                            paragraphIndex = paragraphIndex,
                            links = item.links,
                            styleSpans = item.styleSpans,
                            fragmentIds = item.fragmentIds,
                        ),
                    )
                }
                is EpubReaderItem.Text -> {
                    add(
                        EpubDisplayBlock.Text(
                            text = item.text,
                            headingLevel = null,
                            paragraphIndex = paragraphIndex,
                            links = item.links,
                            styleSpans = item.styleSpans,
                            kind = item.kind,
                            indentLevel = item.indentLevel,
                            fragmentIds = item.fragmentIds,
                            isCodeBlock = item.isCodeBlock,
                            language = item.language,
                        ),
                    )
                }
                is EpubReaderItem.Image -> add(
                    EpubDisplayBlock.Image(
                        href = item.href,
                        altText = item.altText,
                        paragraphIndex = paragraphIndex,
                        fragmentIds = item.fragmentIds,
                    ),
                )
                is EpubReaderItem.Break -> add(EpubDisplayBlock.Break(paragraphIndex))
            }
        }
    }
