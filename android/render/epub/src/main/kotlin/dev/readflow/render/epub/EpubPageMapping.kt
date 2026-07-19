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
    val textSegments: List<EpubPageTextSegment> = emptyList(),
    val endParagraphIndex: Int = paragraphIndex,
    val visualLineCount: Int = 1,
    // Non-empty only for COMPOSITE pages: an ordered vertical stack of text runs and small inline
    // images packed onto one page ("非必要不分页" / Moon+ Reader style). textSegments is also populated
    // (text runs only) so locator/selection/highlight code keeps working without an Image branch.
    val elements: List<EpubPageElement> = emptyList(),
)

// A single laid-out element inside a COMPOSITE page (elements non-empty). The composite renderer
// stacks these vertically; the packer guarantees their combined line cost fits the page budget.
internal sealed interface EpubPageElement {
    data class Text(val segment: EpubPageTextSegment) : EpubPageElement

    data class Image(
        val href: String,
        val altText: String?,
        val paragraphIndex: Int,
        val charOffset: Int,
        val lineCost: Int,
    ) : EpubPageElement
}

internal data class EpubPageTextSegment(
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val textStyle: EpubPageTextStyle = EpubPageTextStyle(),
    val links: List<EpubTextLink> = emptyList(),
    val visualLineCount: Int = 1,
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
        // Legacy bare Page only (EPUB migration). PageText is not a paragraph index.
        is LocatorStrategy.Page -> strategy.index
        is LocatorStrategy.PageText,
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
    // Per-paragraph override for the FIRST page window's line budget. Returns null to use the
    // default linesPerPage. Used to reserve room above a body paragraph that follows a heading so
    // the heading can keep-with-next on the same page instead of being flushed alone.
    firstPageLineBudget: (Int) -> Int? = { null },
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
                            endParagraphIndex = paragraphIndex,
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
            val firstBudget = firstPageLineBudget(paragraphIndex)
                ?.coerceIn(1, linesPerPage)
                ?: linesPerPage
            var isFirstWindow = true
            while (lineStart < lines.size) {
                val windowBudget = if (isFirstWindow) firstBudget else linesPerPage
                val lineEnd = (lineStart + windowBudget - 1).coerceAtMost(lines.lastIndex)
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
                        visualLineCount = lineEnd - lineStart + 1,
                    ),
                )
                lineStart = lineEnd + 1
                isFirstWindow = false
            }
        }
    }
}

private fun packAdjacentShortTextPages(
    pages: List<EpubPageSlice>,
    paras: List<EpubPara>,
    metrics: EpubPageMetrics,
    measurement: EpubPageMeasurement,
    textProvider: (Int) -> String,
): List<EpubPageSlice> {
    if (pages.size < 2) return pages
    val contentHeight = (metrics.viewportHeightPx - metrics.verticalPaddingPx).coerceAtLeast(1)
    val linesPerPage = (contentHeight / metrics.lineHeightPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val packed = mutableListOf<EpubPageSlice>()
    val pendingPages = mutableListOf<EpubPageSlice>()
    var usedLines = 0

    fun flushPages() {
        if (pendingPages.isEmpty()) return
        packed += if (pendingPages.size == 1) {
            pendingPages.single()
        } else if (pendingPages.any { it.kind is EpubPageSliceKind.Image }) {
            // Mixed run (text + small inline images) → one COMPOSITE page that stacks them.
            pageSliceForElements(pendingPages.toList(), measurement, textProvider)
        } else {
            pageSliceForSegments(
                pendingPages.map { page ->
                    page.toTextSegment(textProvider)
                },
                measurement,
            )
        }
        pendingPages.clear()
        usedLines = 0
    }

    pages.forEach { page ->
        val canPack = page.canPackWithAdjacentText()
        if (!canPack) {
            flushPages()
            packed += page
            return@forEach
        }
        if (pendingPages.isNotEmpty() && !pendingPages.last().canPackAcrossSpineWith(page, paras)) {
            flushPages()
        }
        val pageLines = page.lineCount().coerceAtLeast(1)
        val separatorLines = if (pendingPages.isEmpty()) 0 else 1
        if (pendingPages.isNotEmpty() && usedLines + separatorLines + pageLines > linesPerPage) {
            flushPages()
        }
        if (pendingPages.isNotEmpty()) {
            usedLines += separatorLines
        }
        pendingPages += page
        usedLines += pageLines
    }
    flushPages()
    return packed
}

private fun EpubPageSlice.canPackWithAdjacentText(): Boolean =
    // Small inline images (only those reach the packer — large ones are emitted standalone) pack
    // with text so they ride a shared page instead of isolating.
    kind is EpubPageSliceKind.Image ||
        (kind == EpubPageSliceKind.Text && textStyle.isSafeToPackWithAdjacentText())

// Headings are packable so they keep-with-next (a heading never sits alone at the
// bottom/top of a page; it shares the page with the text that follows it). The block
// loop pre-flushes pending text before a heading, so a heading only ever starts a pack
// group — body text is never packed *before* it. Per-segment styling at render time keeps
// the heading visually large while packed body renders at body size.
private fun EpubPageTextStyle.isSafeToPackWithAdjacentText(): Boolean =
    kind in setOf(EpubTextKind.Body, EpubTextKind.ListItem, EpubTextKind.Blockquote)

private fun EpubPageSlice.canPackAcrossSpineWith(next: EpubPageSlice, paras: List<EpubPara>): Boolean {
    val currentSpine = paras.getOrNull(endParagraphIndex)?.spineIndex ?: return false
    val nextSpine = paras.getOrNull(next.paragraphIndex)?.spineIndex ?: return false
    return currentSpine == nextSpine
}

private fun EpubPageSlice.toTextSegment(textProvider: (Int) -> String): EpubPageTextSegment {
    val text = textProvider(paragraphIndex)
    val start = startOffset.coerceIn(0, text.length)
    val end = endOffset.coerceIn(0, text.length).coerceAtLeast(start)
    return EpubPageTextSegment(
        paragraphIndex = paragraphIndex,
        startOffset = startOffset,
        endOffset = endOffset,
        text = text.substring(start, end),
        textStyle = textStyle,
        links = links,
        visualLineCount = lineCount(),
    )
}

private fun pageSliceForSegments(
    segments: List<EpubPageTextSegment>,
    measurement: EpubPageMeasurement,
): EpubPageSlice {
    val first = segments.first()
    val last = segments.last()
    return EpubPageSlice(
        paragraphIndex = first.paragraphIndex,
        startOffset = first.startOffset,
        endOffset = if (first.paragraphIndex == last.paragraphIndex) last.endOffset else first.endOffset,
        textStyle = first.textStyle,
        measurement = measurement,
        links = first.links,
        textSegments = segments,
        endParagraphIndex = last.paragraphIndex,
        visualLineCount = (segments.sumOf { it.visualLineCount } + (segments.size - 1)).coerceAtLeast(1),
    )
}

// Build a COMPOSITE page from a packed run of text pages and small inline-image slices. Elements
// preserve source order for the vertical-stack renderer; textSegments mirrors the text runs only so
// locator/selection/highlight code (which reads textSegments) keeps working with no Image awareness.
private fun pageSliceForElements(
    pages: List<EpubPageSlice>,
    measurement: EpubPageMeasurement,
    textProvider: (Int) -> String,
): EpubPageSlice {
    val elements = pages.map { page ->
        when (val kind = page.kind) {
            is EpubPageSliceKind.Image -> EpubPageElement.Image(
                href = kind.href,
                altText = kind.altText,
                paragraphIndex = page.paragraphIndex,
                charOffset = page.startOffset,
                lineCost = page.lineCount(),
            )
            EpubPageSliceKind.Text -> EpubPageElement.Text(page.toTextSegment(textProvider))
        }
    }
    val textSegments = elements.filterIsInstance<EpubPageElement.Text>().map { it.segment }
    val anchor = pages.first()
    val last = pages.last()
    return EpubPageSlice(
        paragraphIndex = anchor.paragraphIndex,
        startOffset = anchor.startOffset,
        endOffset = if (anchor.paragraphIndex == last.paragraphIndex) last.endOffset else anchor.endOffset,
        textStyle = textSegments.firstOrNull()?.textStyle ?: anchor.textStyle,
        measurement = measurement,
        links = textSegments.firstOrNull()?.links.orEmpty(),
        textSegments = textSegments,
        endParagraphIndex = last.endParagraphIndex.coerceAtLeast(last.paragraphIndex),
        visualLineCount = (pages.sumOf { it.lineCount() } + (pages.size - 1)).coerceAtLeast(1),
        elements = elements,
    )
}

private fun EpubPageSlice.lineCount(): Int =
    if (textSegments.isEmpty()) {
        visualLineCount.coerceAtLeast(1)
    } else {
        textSegments.sumOf { segment ->
            segment.visualLineCount.coerceAtLeast(1)
        }.coerceAtLeast(1)
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
    // "非必要不分页": for a small (inline-class) image return its line cost so it packs onto a shared
    // page with adjacent text instead of isolating. Return null for a large (full-page) image, which
    // keeps the standalone-page behavior. Default null preserves the legacy "every image = a page".
    inlineImageLineCost: (href: String) -> Int? = { null },
): List<EpubPageSlice> {
    val blocks = blockProvider()
    val textStylesByParagraph = blocks
        .filterIsInstance<EpubDisplayBlock.Text>()
        .associate { block -> block.paragraphIndex to block.toPageTextStyle() }
    val textLinksByParagraph = blocks
        .filterIsInstance<EpubDisplayBlock.Text>()
        .associate { block -> block.paragraphIndex to block.links }
    // Keep-with-next: when a body paragraph immediately follows a heading, shrink its FIRST page so
    // the heading can sit on the same page instead of being flushed alone. Without this, a body that
    // wraps to a full page at large fonts pushes the heading past the line budget and it isolates.
    val firstPageBudgetByParagraph = headingFollowerFirstPageBudgets(
        blocks = blocks,
        metrics = metrics,
        textProvider = textProvider,
        textStylesByParagraph = textStylesByParagraph,
        lineBreaker = lineBreaker,
    )
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
        firstPageLineBudget = { paragraphIndex -> firstPageBudgetByParagraph[paragraphIndex] },
    )
    if (blocks.isEmpty()) return textPages

    val pagesByParagraph = textPages.groupBy { it.paragraphIndex }
    val emittedParagraphs = mutableSetOf<Int>()
    val nextImageAnchorOffsets = mutableMapOf<Int, Int>()
    return buildList {
        val pendingTextPages = mutableListOf<EpubPageSlice>()

        fun flushPendingTextPages() {
            if (pendingTextPages.isEmpty()) return
            addAll(packAdjacentShortTextPages(pendingTextPages.toList(), paras, metrics, measurement, textProvider))
            pendingTextPages.clear()
        }

        blocks.forEach { block ->
            when (block) {
                is EpubDisplayBlock.Text -> {
                    // P0-2: Heading保护 - 如果是标题且当前页有内容，先flush到新页
                    if (block.headingLevel != null && pendingTextPages.isNotEmpty()) {
                        flushPendingTextPages()
                    }

                    if (emittedParagraphs.add(block.paragraphIndex)) {
                        pendingTextPages += pagesByParagraph[block.paragraphIndex].orEmpty().map { page ->
                            page.copy(textStyle = block.toPageTextStyle())
                        }
                    }
                }
                is EpubDisplayBlock.Image -> {
                    val imageAnchorOffset = nextImageAnchorOffsets.nextImageAnchorOffsetForParagraph(
                        paras = paras,
                        textProvider = textProvider,
                        paragraphIndex = block.paragraphIndex,
                    )
                    val inlineCost = inlineImageLineCost(block.href)
                    if (inlineCost != null) {
                        // Small image (avatar/logo/icon): flow it into the text stream so the packer
                        // can place it on a shared page with surrounding text ("非必要不分页"). It is a
                        // packable Image slice carrying its line cost in visualLineCount.
                        emittedParagraphs += block.paragraphIndex
                        pendingTextPages += EpubPageSlice(
                            paragraphIndex = block.paragraphIndex,
                            startOffset = imageAnchorOffset,
                            endOffset = imageAnchorOffset,
                            kind = EpubPageSliceKind.Image(block.href, block.altText),
                            endParagraphIndex = block.paragraphIndex,
                            visualLineCount = inlineCost.coerceAtLeast(1),
                        )
                        return@forEach
                    }
                    // Large image (full-page illustration/cover): keep-with-next for a heading that
                    // immediately precedes it (4-koma section title → panel, colophon line → publisher
                    // logo) — ride the orphan heading onto the image page as a caption instead of
                    // flushing it alone. Only pure heading pages are captured; body text already
                    // keeps-with via the heading-follower budget.
                    val headingCaption = if (
                        pendingTextPages.isNotEmpty() &&
                        pendingTextPages.all { it.textStyle.headingLevel != null }
                    ) {
                        pendingTextPages.map { it.toTextSegment(textProvider) }
                            .also { pendingTextPages.clear() }
                    } else {
                        emptyList()
                    }
                    flushPendingTextPages()
                    emittedParagraphs += block.paragraphIndex
                    add(
                        EpubPageSlice(
                            paragraphIndex = block.paragraphIndex,
                            startOffset = imageAnchorOffset,
                            endOffset = imageAnchorOffset,
                            kind = EpubPageSliceKind.Image(block.href, block.altText),
                            textSegments = headingCaption,
                        ),
                    )
                }
                is EpubDisplayBlock.Break -> flushPendingTextPages()
            }
        }
        flushPendingTextPages()
        textPages.forEach { page ->
            if (emittedParagraphs.add(page.paragraphIndex)) {
                pendingTextPages += pagesByParagraph[page.paragraphIndex].orEmpty()
            }
        }
        flushPendingTextPages()
    }
}

private fun EpubDisplayBlock.Text.toPageTextStyle(): EpubPageTextStyle =
    EpubPageTextStyle(
        headingLevel = headingLevel,
        kind = kind,
        indentLevel = indentLevel,
    )

// For each body paragraph that directly follows a heading (in block order), compute a reduced
// FIRST-page line budget = linesPerPage - headingLines - separator. This reserves room so the
// heading keeps-with-next on the same page rather than being flushed alone when the body's first
// page would otherwise fill the viewport. Only the immediately-following packable text block is
// adjusted; if a heading is followed by an image/break, nothing is reserved (no body to share with).
private fun headingFollowerFirstPageBudgets(
    blocks: List<EpubDisplayBlock>,
    metrics: EpubPageMetrics,
    textProvider: (Int) -> String,
    textStylesByParagraph: Map<Int, EpubPageTextStyle>,
    lineBreaker: (text: String, contentWidthPx: Int, textStyle: EpubPageTextStyle) -> List<Pair<Int, Int>>,
): Map<Int, Int> {
    val contentWidth = (metrics.viewportWidthPx - metrics.horizontalPaddingPx).coerceAtLeast(1)
    val contentHeight = (metrics.viewportHeightPx - metrics.verticalPaddingPx).coerceAtLeast(1)
    val linesPerPage = (contentHeight / metrics.lineHeightPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val budgets = mutableMapOf<Int, Int>()
    var reserveForNextBody: Int? = null
    blocks.forEach { block ->
        when (block) {
            is EpubDisplayBlock.Text -> {
                if (block.headingLevel != null) {
                    // Measure via textProvider (the same source the body is paginated from) so cold
                    // blocks with no loaded text are not measured — matching epubMeasuredPagedLayout's
                    // empty-text branch. An empty heading has nothing to keep-with: drop the reserve.
                    val headingText = textProvider(block.paragraphIndex)
                    if (headingText.isEmpty()) {
                        reserveForNextBody = null
                    } else {
                        val style = textStylesByParagraph[block.paragraphIndex] ?: block.toPageTextStyle()
                        val headingLines = lineBreaker(headingText, contentWidth, style).size.coerceAtLeast(1)
                        // heading lines + 1 separator line between heading and body segments.
                        reserveForNextBody = (headingLines + 1).coerceAtMost(linesPerPage - 1)
                    }
                } else {
                    reserveForNextBody?.let { reserve ->
                        budgets[block.paragraphIndex] = (linesPerPage - reserve).coerceAtLeast(1)
                    }
                    reserveForNextBody = null
                }
            }
            // A heading followed by a non-text block has no body to keep-with; drop the reserve.
            is EpubDisplayBlock.Image, is EpubDisplayBlock.Break -> reserveForNextBody = null
        }
    }
    return budgets
}

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
        (
            page.kind is EpubPageSliceKind.Image &&
                page.paragraphIndex == paragraphIndex &&
                charOffsetInParagraph == page.startOffset
        ) ||
            // Inline image living inside a COMPOSITE page.
            page.elements.any { element ->
                element is EpubPageElement.Image &&
                    element.paragraphIndex == paragraphIndex &&
                    charOffsetInParagraph == element.charOffset
            }
    }
    if (imageIndex >= 0) return imageIndex
    val index = pages.indexOfFirst { page ->
        page.containsTextOffset(paragraphIndex, charOffsetInParagraph)
    }
    val boundaryIndex = pages.indexOfFirst { page ->
        page.endsAtTextOffset(paragraphIndex, charOffsetInParagraph)
    }
    return if (index >= 0) {
        index
    } else if (boundaryIndex >= 0) {
        boundaryIndex
    } else {
        pages.indexOfFirst { page ->
            page.kind == EpubPageSliceKind.Text &&
                paragraphIndex in page.paragraphIndex..page.endParagraphIndex
        }.coerceAtLeast(0)
    }
}

private fun EpubPageSlice.containsTextOffset(paragraphIndex: Int, charOffsetInParagraph: Int): Boolean {
    if (kind != EpubPageSliceKind.Text) return false
    if (textSegments.isNotEmpty()) {
        return textSegments.any { segment ->
            segment.paragraphIndex == paragraphIndex &&
                charOffsetInParagraph >= segment.startOffset &&
                charOffsetInParagraph < segment.endOffset.coerceAtLeast(segment.startOffset + 1)
        }
    }
    return this.paragraphIndex == paragraphIndex &&
        charOffsetInParagraph >= startOffset &&
        charOffsetInParagraph < endOffset.coerceAtLeast(startOffset + 1)
}

private fun EpubPageSlice.endsAtTextOffset(paragraphIndex: Int, charOffsetInParagraph: Int): Boolean {
    if (kind != EpubPageSliceKind.Text) return false
    if (textSegments.isNotEmpty()) {
        return textSegments.any { segment ->
            segment.paragraphIndex == paragraphIndex &&
                charOffsetInParagraph == segment.endOffset
        }
    }
    return this.paragraphIndex == paragraphIndex &&
        charOffsetInParagraph == endOffset
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
