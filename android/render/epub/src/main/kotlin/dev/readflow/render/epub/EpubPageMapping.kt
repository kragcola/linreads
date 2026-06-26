package dev.readflow.render.epub

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy

internal data class EpubPageSlice(
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val kind: EpubPageSliceKind = EpubPageSliceKind.Text,
    val textStyle: EpubPageTextStyle = EpubPageTextStyle(),
    val measurement: EpubPageMeasurement = EpubPageMeasurement.StaticLayout,
    val links: List<EpubTextLink> = emptyList(),
)

internal enum class EpubPageMeasurement {
    Indexed,
    StaticLayout,
    ComposeTextLayoutResult,
}

internal sealed interface EpubPageSliceKind {
    data object Text : EpubPageSliceKind

    data class Image(
        val href: String,
        val altText: String?,
    ) : EpubPageSliceKind
}

internal data class EpubPageTextStyle(
    val headingLevel: Int? = null,
    val kind: EpubTextKind = EpubTextKind.Body,
    val indentLevel: Int = 0,
)

internal data class EpubPageMetrics(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val horizontalPaddingPx: Int,
    val verticalPaddingPx: Int,
    val averageCharacterWidthPx: Float,
    val lineHeightPx: Float,
)

internal fun epubIndexFromLocator(locator: Locator, totalItems: Int): Int {
    val total = totalItems.coerceAtLeast(1)
    val index = when (val strategy = locator.strategy) {
        is LocatorStrategy.Section -> strategy.elementIndex
        is LocatorStrategy.Page -> strategy.index
        is LocatorStrategy.ByteOffset,
        LocatorStrategy.Unknown,
        -> locator.totalProgression?.let { (it * total).toInt() } ?: 0
    }
    return index.coerceIn(0, total - 1)
}

internal fun epubLocatorForIndex(paras: List<EpubPara>, index: Int): Locator =
    epubLocatorForOffset(paras, index, paragraphOffset = 0)

internal fun epubLocatorForTarget(paras: List<EpubPara>, target: EpubTargetPosition): Locator =
    epubLocatorForOffset(
        paras = paras,
        index = target.paragraphIndex,
        paragraphOffset = target.paragraphOffset,
    )

internal fun epubLocatorForOffset(paras: List<EpubPara>, index: Int, paragraphOffset: Int): Locator =
    if (paras.isEmpty()) {
        Locator(LocatorStrategy.Unknown)
    } else {
        val total = paras.size
        val safeIndex = index.coerceIn(0, total - 1)
        val para = paras[safeIndex]
        val paragraphCharCount = (para.spineCharEnd - para.spineCharStart).coerceAtLeast(0)
        val safeOffset = paragraphOffset.coerceAtLeast(0)
        val boundedOffset = safeOffset.coerceAtMost(paragraphCharCount)
        val totalChars = epubTotalChars(paras)
        val ratio = if (totalChars > 0) {
            (para.documentCharStart + boundedOffset).toFloat() / totalChars
        } else {
            safeIndex.toFloat() / total
        }
        Locator(
            strategy = LocatorStrategy.Section(
                spineIndex = para.spineIndex,
                elementIndex = safeIndex,
                charOffset = para.spineCharStart + safeOffset,
            ),
            progression = ratio,
            totalProgression = ratio,
        )
    }

internal fun epubPagedLayout(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    charsPerPage: Int,
): List<EpubPageSlice> =
    epubPagedLayout(
        paras = paras,
        textProvider = textProvider,
        charsPerPage = charsPerPage,
        preferBoundary = false,
    )

internal fun epubViewportPagedLayout(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    metrics: EpubPageMetrics,
): List<EpubPageSlice> {
    val contentWidth = (metrics.viewportWidthPx - metrics.horizontalPaddingPx).coerceAtLeast(1)
    val contentHeight = (metrics.viewportHeightPx - metrics.verticalPaddingPx).coerceAtLeast(1)
    val charsPerLine = (contentWidth / metrics.averageCharacterWidthPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val linesPerPage = (contentHeight / metrics.lineHeightPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val charsPerPage = (charsPerLine * linesPerPage).coerceAtLeast(1)
    return epubPagedLayout(
        paras = paras,
        textProvider = textProvider,
        charsPerPage = charsPerPage,
        preferBoundary = true,
    )
}

internal fun epubStaticLayoutPagedLayout(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    metrics: EpubPageMetrics,
    textPaint: TextPaint,
    lineSpacingMultiplier: Float,
): List<EpubPageSlice> =
    epubMeasuredPagedLayout(
        paras = paras,
        textProvider = textProvider,
        metrics = metrics,
        lineBreaker = { text, contentWidth, _ ->
            epubStaticLayoutLines(
                text = text,
                contentWidthPx = contentWidth,
                textPaint = textPaint,
                lineSpacingMultiplier = lineSpacingMultiplier,
            )
        },
    )

internal fun epubStaticLayoutLines(
    text: String,
    contentWidthPx: Int,
    textPaint: TextPaint,
    lineSpacingMultiplier: Float,
): List<Pair<Int, Int>> =
    StaticLayout.Builder
        .obtain(text, 0, text.length, textPaint, contentWidthPx)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, lineSpacingMultiplier.coerceAtLeast(0.1f))
        .setIncludePad(false)
        .build()
        .let { layout ->
            if (layout.lineCount <= 0) {
                emptyList()
            } else {
                (0 until layout.lineCount).map { lineIndex ->
                    layout.getLineStart(lineIndex).coerceIn(0, text.length) to
                        layout.getLineEnd(lineIndex).coerceIn(0, text.length)
                }
            }
        }

internal fun epubMeasuredPagedLayout(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    metrics: EpubPageMetrics,
    lineBreaker: (text: String, contentWidthPx: Int, textStyle: EpubPageTextStyle) -> List<Pair<Int, Int>>,
    textStyleProvider: (Int) -> EpubPageTextStyle = { EpubPageTextStyle() },
    linkProvider: (Int) -> List<EpubTextLink> = { emptyList() },
    measurement: EpubPageMeasurement = EpubPageMeasurement.StaticLayout,
): List<EpubPageSlice> {
    val contentWidth = (metrics.viewportWidthPx - metrics.horizontalPaddingPx).coerceAtLeast(1)
    val contentHeight = (metrics.viewportHeightPx - metrics.verticalPaddingPx).coerceAtLeast(1)
    val linesPerPage = (contentHeight / metrics.lineHeightPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    return buildList {
        paras.indices.forEach { paragraphIndex ->
            val para = paras[paragraphIndex]
            val text = textProvider(paragraphIndex)
            val textStyle = textStyleProvider(paragraphIndex)
            val links = linkProvider(paragraphIndex)
            if (text.isEmpty()) {
                val textLength = (para.spineCharEnd - para.spineCharStart).coerceAtLeast(0)
                addAll(
                    epubViewportPagedLayout(
                        paras = listOf(para),
                        textProvider = { "" },
                        metrics = metrics,
                    ).map {
                        it.copy(
                            paragraphIndex = paragraphIndex,
                            textStyle = textStyle,
                            measurement = measurement,
                            links = links.pageLocalLinks(it.startOffset, it.endOffset),
                        )
                    },
                )
                if (textLength == 0 && none { it.paragraphIndex == paragraphIndex }) {
                    add(
                        EpubPageSlice(
                            paragraphIndex,
                            startOffset = 0,
                            endOffset = 0,
                            textStyle = textStyle,
                            measurement = measurement,
                            links = emptyList(),
                        ),
                    )
                }
                return@forEach
            }
            val lines = lineBreaker(text, contentWidth, textStyle)
                .filter { (start, end) -> start in 0..text.length && end in start..text.length }
            if (lines.isEmpty()) {
                add(
                    EpubPageSlice(
                        paragraphIndex = paragraphIndex,
                        startOffset = 0,
                        endOffset = text.length,
                        textStyle = textStyle,
                        measurement = measurement,
                        links = links.pageLocalLinks(0, text.length),
                    ),
                )
                return@forEach
            }
            var lineStart = 0
            while (lineStart < lines.size) {
                val lineEnd = (lineStart + linesPerPage - 1).coerceAtMost(lines.lastIndex)
                val startOffset = lines[lineStart].first
                val endOffset = lines[lineEnd].second
                add(
                    EpubPageSlice(
                        paragraphIndex = paragraphIndex,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        textStyle = textStyle,
                        measurement = measurement,
                        links = links.pageLocalLinks(startOffset, endOffset),
                    ),
                )
                lineStart = lineEnd + 1
            }
        }
    }
}

internal data class EpubTextLayoutLineRange(
    val start: Int,
    val end: Int,
)

internal fun epubComposeMeasuredPagedLayout(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    metrics: EpubPageMetrics,
    textLayoutLines: (
        text: String,
        contentWidthPx: Int,
        textStyle: EpubPageTextStyle,
    ) -> List<EpubTextLayoutLineRange>,
    textStyleProvider: (Int) -> EpubPageTextStyle = { EpubPageTextStyle() },
): List<EpubPageSlice> =
    epubMeasuredPagedLayout(
        paras = paras,
        textProvider = textProvider,
        metrics = metrics,
        lineBreaker = { text, contentWidth, textStyle ->
            textLayoutLines(text, contentWidth, textStyle).map { it.start to it.end }
        },
        textStyleProvider = textStyleProvider,
        measurement = EpubPageMeasurement.ComposeTextLayoutResult,
    )

internal fun epubPagedLayoutWithBlocks(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    blockProvider: () -> List<EpubDisplayBlock>,
    metrics: EpubPageMetrics,
    lineBreaker: (text: String, contentWidthPx: Int, textStyle: EpubPageTextStyle) -> List<Pair<Int, Int>>,
    measurement: EpubPageMeasurement = EpubPageMeasurement.StaticLayout,
): List<EpubPageSlice> {
    val blocks = blockProvider()
    val textStylesByParagraph = blocks
        .filterIsInstance<EpubDisplayBlock.Text>()
        .associate { block -> block.paragraphIndex to block.toPageTextStyle() }
    val textLinksByParagraph = blocks
        .filterIsInstance<EpubDisplayBlock.Text>()
        .associate { block -> block.paragraphIndex to block.links }
    val textPages = epubMeasuredPagedLayout(
        paras = paras,
        textProvider = textProvider,
        metrics = metrics,
        lineBreaker = { text, contentWidth, textStyle ->
            lineBreaker(text, contentWidth, textStyle)
        },
        textStyleProvider = { paragraphIndex ->
            textStylesByParagraph[paragraphIndex] ?: EpubPageTextStyle()
        },
        linkProvider = { paragraphIndex ->
            textLinksByParagraph[paragraphIndex].orEmpty()
        },
        measurement = measurement,
    )
    if (blocks.isEmpty()) return textPages

    val pagesByParagraph = textPages.groupBy { it.paragraphIndex }
    val emittedParagraphs = mutableSetOf<Int>()
    val nextImageAnchorOffsets = mutableMapOf<Int, Int>()
    return buildList {
        blocks.forEach { block ->
            when (block) {
                is EpubDisplayBlock.Text -> if (emittedParagraphs.add(block.paragraphIndex)) {
                    addAll(
                        pagesByParagraph[block.paragraphIndex].orEmpty().map { page ->
                            page.copy(textStyle = block.toPageTextStyle())
                        },
                    )
                }
                is EpubDisplayBlock.Image -> {
                    emittedParagraphs += block.paragraphIndex
                    val imageAnchorOffset = nextImageAnchorOffsets.nextImageAnchorOffsetForParagraph(
                        paras = paras,
                        textProvider = textProvider,
                        paragraphIndex = block.paragraphIndex,
                    )
                    add(
                        EpubPageSlice(
                            paragraphIndex = block.paragraphIndex,
                            startOffset = imageAnchorOffset,
                            endOffset = imageAnchorOffset,
                            kind = EpubPageSliceKind.Image(block.href, block.altText),
                        ),
                    )
                }
                is EpubDisplayBlock.Break -> Unit
            }
        }
        textPages.forEach { page ->
            if (emittedParagraphs.add(page.paragraphIndex)) {
                addAll(pagesByParagraph[page.paragraphIndex].orEmpty())
            }
        }
    }
}

private fun EpubDisplayBlock.Text.toPageTextStyle(): EpubPageTextStyle =
    EpubPageTextStyle(
        headingLevel = headingLevel,
        kind = kind,
        indentLevel = indentLevel,
    )

private fun List<EpubTextLink>.pageLocalLinks(startOffset: Int, endOffset: Int): List<EpubTextLink> =
    mapNotNull { link ->
        val start = maxOf(link.start, startOffset)
        val end = minOf(link.end, endOffset)
        if (start < end) {
            link.copy(
                start = start - startOffset,
                end = end - startOffset,
            )
        } else {
            null
        }
    }

private fun epubPagedLayout(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    charsPerPage: Int,
    preferBoundary: Boolean,
): List<EpubPageSlice> {
    val pageSize = charsPerPage.coerceAtLeast(1)
    return buildList {
        paras.indices.forEach { paragraphIndex ->
            val para = paras[paragraphIndex]
            val text = textProvider(paragraphIndex)
            val textLength = text.length
                .takeIf { it > 0 }
                ?: (para.spineCharEnd - para.spineCharStart).coerceAtLeast(0)
            if (textLength == 0) {
                add(EpubPageSlice(paragraphIndex, startOffset = 0, endOffset = 0))
                return@forEach
            }
            var start = 0
            while (start < textLength) {
                val rawEnd = (start + pageSize).coerceAtMost(textLength)
                val end = if (preferBoundary && text.isNotEmpty()) {
                    epubPageBoundary(text, start, rawEnd, textLength)
                } else {
                    rawEnd
                }
                add(EpubPageSlice(paragraphIndex, startOffset = start, endOffset = end))
                start = end
            }
        }
    }
}

private fun epubPageBoundary(text: String, start: Int, rawEnd: Int, textLength: Int): Int {
    if (rawEnd >= textLength) return textLength
    if (rawEnd > start && text.getOrNull(rawEnd)?.isWhitespace() == true) return rawEnd
    val minBoundary = start + ((rawEnd - start) * 0.6f).toInt().coerceAtLeast(1)
    for (index in rawEnd downTo minBoundary) {
        if (index > start && text.getOrNull(index - 1)?.isWhitespace() == true) {
            return index
        }
    }
    return rawEnd
}

internal fun epubPageIndexFromLocator(
    locator: Locator,
    pages: List<EpubPageSlice>,
    paras: List<EpubPara>,
): Int {
    if (pages.isEmpty()) return 0
    (locator.strategy as? LocatorStrategy.Page)?.let { page ->
        return page.index.coerceIn(0, pages.lastIndex)
    }
    val paragraphIndex = epubIndexFromLocator(locator, paras.size)
    val charOffsetInParagraph = when (val strategy = locator.strategy) {
        is LocatorStrategy.Section -> {
            val paraStart = paras.getOrNull(paragraphIndex)?.spineCharStart ?: 0
            (strategy.charOffset - paraStart).coerceAtLeast(0)
        }
        else -> 0
    }
    val imageIndex = pages.indexOfFirst { page ->
        page.kind is EpubPageSliceKind.Image &&
            page.paragraphIndex == paragraphIndex &&
            charOffsetInParagraph == page.startOffset
    }
    if (imageIndex >= 0) return imageIndex
    val index = pages.indexOfFirst { page ->
        page.paragraphIndex == paragraphIndex &&
            charOffsetInParagraph >= page.startOffset &&
            charOffsetInParagraph < page.endOffset.coerceAtLeast(page.startOffset + 1)
    }
    return if (index >= 0) index else pages.indexOfFirst { it.paragraphIndex == paragraphIndex }.coerceAtLeast(0)
}

internal fun epubLocatorForPageSlice(paras: List<EpubPara>, page: EpubPageSlice): Locator {
    val offset = if (page.kind is EpubPageSliceKind.Image) {
        page.startOffset.takeIf { it > 0 } ?: imageAnchorOffset(paras, textProvider = { "" }, page.paragraphIndex)
    } else {
        page.startOffset
    }
    return epubLocatorForOffset(paras, page.paragraphIndex, offset)
}

private fun imageAnchorOffset(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    paragraphIndex: Int,
): Int {
    val para = paras.getOrNull(paragraphIndex) ?: return 0
    val textLength = textProvider(paragraphIndex).length.takeIf { it > 0 }
        ?: (para.spineCharEnd - para.spineCharStart).coerceAtLeast(0)
    return textLength
}

private fun MutableMap<Int, Int>.nextImageAnchorOffsetForParagraph(
    paras: List<EpubPara>,
    textProvider: (Int) -> String,
    paragraphIndex: Int,
): Int {
    val anchor = getOrPut(paragraphIndex) {
        imageAnchorOffset(paras, textProvider, paragraphIndex)
    }
    this[paragraphIndex] = anchor + 1
    return anchor
}
