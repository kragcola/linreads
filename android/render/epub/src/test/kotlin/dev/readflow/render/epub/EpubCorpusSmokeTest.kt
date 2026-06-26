package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

class EpubCorpusSmokeTest {

    @Test
    fun `real epub corpus parses without crashing and reports coverage metrics`() {
        val corpusDir = System.getProperty("readflow.epubCorpusDir")?.let(::File)
        assumeTrue(corpusDir?.isDirectory == true, "Set -Dreadflow.epubCorpusDir=/path/to/epubs to run corpus smoke.")
        val epubs = corpusDir
            ?.listFiles { file -> file.isFile && file.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(epubs.size >= 3, "Need at least 3 EPUB files for corpus smoke.")

        var hasChinese = false
        var hasMixedLanguage = false
        var hasImageHeavy = false
        var hasMultiSpine = false
        var hasTable = false
        var hasList = false
        var hasFixedLayoutFallback = false
        var hasInlineStyles = false
        var hasPackageLocalImages = false
        var hasImageAltText = false
        var hasLinks = false
        var hasTargetedInternalLinks = false
        var hasCharacterProgression = false
        var hasReaderItemLocators = false
        var hasParsedToc = false

        epubs.forEach { file ->
            val book = EpubParser().parseBook(file)
            val lazyBook = EpubParser().parseLazyBook(file)
            validateCharacterProgression(file, book.paras)
            validateReaderItemLocators(file, book)
            validateTocLocators(file, book)
            validateLazyBookIndex(file, book, lazyBook)
            val imageStats = validateImageItems(file, book)
            val linkStats = validateLinks(file, book)
            val degradationStats = validateDegradation(file, book, lazyBook)
            val text = book.items.joinToString(separator = "\n") { item ->
                when (item) {
                    is EpubReaderItem.Heading -> item.text
                    is EpubReaderItem.Text -> item.text
                    is EpubReaderItem.Break,
                    is EpubReaderItem.Image,
                    -> ""
                }
            }
            val cjkChars = text.count { it.isCjk() }
            val latinLetters = text.count { it.isLatinLetter() }
            val images = book.items.count { it is EpubReaderItem.Image }
            val links = book.items.sumOf { item ->
                when (item) {
                    is EpubReaderItem.Heading -> item.links.size
                    is EpubReaderItem.Text -> item.links.size
                    is EpubReaderItem.Break,
                    is EpubReaderItem.Image,
                    -> 0
                }
            }
            val tables = book.items.count { it is EpubReaderItem.Text && it.kind == EpubTextKind.Table }
            val lists = book.items.count { it is EpubReaderItem.Text && it.kind == EpubTextKind.ListItem }
            hasChinese = hasChinese || cjkChars >= MIN_LANGUAGE_SAMPLE_CHARS
            hasMixedLanguage = hasMixedLanguage ||
                (cjkChars >= MIN_LANGUAGE_SAMPLE_CHARS && latinLetters >= MIN_LANGUAGE_SAMPLE_CHARS)
            hasImageHeavy = hasImageHeavy || images >= MIN_IMAGE_HEAVY_ITEMS
            hasMultiSpine = hasMultiSpine || book.spinePaths.size >= MIN_MULTI_SPINE_COUNT
            hasTable = hasTable || tables > 0
            hasList = hasList || lists > 0
            hasFixedLayoutFallback = hasFixedLayoutFallback || degradationStats.fixedLayoutFallback
            hasInlineStyles = hasInlineStyles || degradationStats.styleSpans > 0
            hasPackageLocalImages = hasPackageLocalImages || imageStats.packageLocalImages > 0
            hasImageAltText = hasImageAltText || imageStats.altTextImages > 0
            hasLinks = hasLinks || linkStats.links > 0
            hasTargetedInternalLinks = hasTargetedInternalLinks || linkStats.targetedInternalLinks > 0
            hasCharacterProgression = hasCharacterProgression || epubTotalChars(book.paras) > 0
            hasReaderItemLocators = hasReaderItemLocators || book.items.isNotEmpty()
            hasParsedToc = hasParsedToc || book.tableOfContents.isNotEmpty()
            println(
                "EPUB_CORPUS|${file.name}" +
                    "|items=${book.items.size}" +
                    "|paras=${book.paras.size}" +
                    "|spines=${book.spinePaths.size}" +
                    "|toc=${book.tableOfContents.size}" +
                    "|images=$images" +
                    "|packageLocalImages=${imageStats.packageLocalImages}" +
                    "|altImages=${imageStats.altTextImages}" +
                    "|links=$links" +
                    "|targetedInternalLinks=${linkStats.targetedInternalLinks}" +
                    "|styles=${degradationStats.styleSpans}" +
                    "|tables=$tables" +
                    "|lists=$lists" +
                    "|blockquotes=${degradationStats.blockquotes}" +
                    "|pre=${degradationStats.preformatted}" +
                    "|cjkChars=$cjkChars" +
                    "|latinLetters=$latinLetters" +
                    "|fixed=${book.isFixedLayout}" +
                    "|lazyParas=${lazyBook.paras.size}" +
                    "|lazyBlocks=${lazyBook.blockCount}",
            )
            assertTrue(
                book.items.isNotEmpty() || book.paras.isNotEmpty(),
                "${file.name} produced no readable items.",
            )
        }

        assertTrue(hasChinese, "Corpus must include a Chinese EPUB or Chinese-heavy spine.")
        assertTrue(hasMixedLanguage, "Corpus must include mixed Chinese/Latin text.")
        assertTrue(hasImageHeavy, "Corpus must include an image-heavy EPUB.")
        assertTrue(hasMultiSpine, "Corpus must include a multi-spine EPUB.")
        assertTrue(hasTable, "Corpus must include table content.")
        assertTrue(hasList, "Corpus must include list or nested-list content.")
        assertTrue(hasFixedLayoutFallback, "Corpus must include fixed-layout EPUB fallback extraction.")
        assertTrue(hasInlineStyles, "Corpus must exercise inline style spans.")
        assertTrue(hasPackageLocalImages, "Corpus must include package-local image entries.")
        assertTrue(hasImageAltText, "Corpus must include image alt text.")
        assertTrue(hasLinks, "Corpus must include inline links.")
        assertTrue(hasTargetedInternalLinks, "Corpus must include internal links that resolve to reader targets.")
        assertTrue(hasCharacterProgression, "Corpus must exercise character-based progression.")
        assertTrue(hasReaderItemLocators, "Corpus must exercise typed ReaderItem locators.")
        assertTrue(hasParsedToc, "Corpus must include at least one parsed TOC.")
    }

    @Test
    fun `real epub corpus preserves paged layout coverage and locator round trips`() {
        val corpusDir = System.getProperty("readflow.epubCorpusDir")?.let(::File)
        assumeTrue(corpusDir?.isDirectory == true, "Set -Dreadflow.epubCorpusDir=/path/to/epubs to run corpus smoke.")
        val epubs = corpusDir
            ?.listFiles { file -> file.isFile && file.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(epubs.size >= 3, "Need at least 3 EPUB files for corpus smoke.")

        var hasMultiPageBook = false
        var hasImagePages = false
        var hasLinkPages = false
        var hasTargetedInternalLinkPages = false
        var hasStyledPages = false

        epubs.forEach { file ->
            val book = EpubParser().parseBook(file)
            if (book.paras.isEmpty()) return@forEach

            val blocks = epubDisplayBlocks(book.items)
            val imageBlocks = blocks.filterIsInstance<EpubDisplayBlock.Image>()
            val linkedTextBlocks = blocks.filterIsInstance<EpubDisplayBlock.Text>().filter { it.links.isNotEmpty() }
            val styledTextBlocks = blocks.filterIsInstance<EpubDisplayBlock.Text>().filter {
                it.headingLevel != null || it.kind != EpubTextKind.Body || it.indentLevel > 0
            }
            val targetIndexes = epubInternalLinkTargetIndexes(book.spinePaths, book.paras, book.fragmentTargetIndexes)

            val pages = epubPagedLayoutWithBlocks(
                paras = book.paras,
                textProvider = { index -> book.paras[index].text },
                blockProvider = { blocks },
                metrics = pagedCorpusMetrics(),
                lineBreaker = ::corpusPagedLineBreaker,
                measurement = EpubPageMeasurement.ComposeTextLayoutResult,
            )

            assertTrue(pages.isNotEmpty(), "${file.name} produced no paged slices.")

            var previousTextProgression = -1f
            var targetedInternalLinkPages = 0
            pages.forEachIndexed { pageIndex, page ->
                val locator = epubLocatorForPageSlice(book.paras, page)
                val restoredPageIndex = epubPageIndexFromLocator(locator, pages, book.paras)

                assertEquals(pageIndex, restoredPageIndex, "${file.name} page $pageIndex locator did not round-trip.")

                val totalProgression = locator.totalProgression ?: 0f
                assertTrue(
                    totalProgression in 0f..1f,
                    "${file.name} page $pageIndex has invalid totalProgression $totalProgression.",
                )

                when (val kind = page.kind) {
                    EpubPageSliceKind.Text -> {
                        val paragraphText = book.paras[page.paragraphIndex].text
                        assertTrue(
                            totalProgression >= previousTextProgression,
                            "${file.name} text page $pageIndex regressed totalProgression from " +
                                "$previousTextProgression to $totalProgression.",
                        )
                        previousTextProgression = totalProgression
                        assertTrue(
                            page.measurement == EpubPageMeasurement.ComposeTextLayoutResult,
                            "${file.name} text page $pageIndex lost compose measurement source.",
                        )
                        assertTrue(
                            page.startOffset in 0..paragraphText.length,
                            "${file.name} text page $pageIndex has invalid start offset ${page.startOffset}.",
                        )
                        assertTrue(
                            page.endOffset in page.startOffset..paragraphText.length,
                            "${file.name} text page $pageIndex has invalid end offset ${page.endOffset}.",
                        )
                        page.links.forEachIndexed { linkIndex, link ->
                            assertTrue(
                                link.start >= 0 && link.end <= (page.endOffset - page.startOffset),
                                "${file.name} text page $pageIndex link $linkIndex escaped page-local bounds.",
                            )
                            assertTrue(
                                paragraphText.substring(
                                    page.startOffset + link.start,
                                    page.startOffset + link.end,
                                ).isNotBlank(),
                                "${file.name} text page $pageIndex link $linkIndex lost linked text.",
                            )
                            if (!link.isExternal && epubInternalLinkTargetKey(link.href) in targetIndexes) {
                                targetedInternalLinkPages += 1
                            }
                        }
                    }
                    is EpubPageSliceKind.Image -> {
                        assertTrue(kind.href.isNotBlank(), "${file.name} image page $pageIndex lost href.")
                        kind.altText?.let { alt ->
                            assertTrue(alt.isNotBlank(), "${file.name} image page $pageIndex lost alt text.")
                        }
                    }
                }
            }

            imageBlocks.forEachIndexed { blockIndex, block ->
                assertTrue(
                    pages.any { page ->
                        page.paragraphIndex == block.paragraphIndex &&
                            page.kind == EpubPageSliceKind.Image(block.href, block.altText)
                    },
                    "${file.name} image block $blockIndex did not become a paged image slice.",
                )
            }

            linkedTextBlocks.forEachIndexed { blockIndex, block ->
                assertTrue(
                    pages.any { page ->
                        page.kind == EpubPageSliceKind.Text &&
                            page.paragraphIndex == block.paragraphIndex &&
                            page.links.isNotEmpty()
                    },
                    "${file.name} linked text block $blockIndex lost page-local link metadata.",
                )
            }

            styledTextBlocks.forEachIndexed { blockIndex, block ->
                val expectedStyle = EpubPageTextStyle(
                    headingLevel = block.headingLevel,
                    kind = block.kind,
                    indentLevel = block.indentLevel,
                )
                assertTrue(
                    pages.any { page ->
                        page.kind == EpubPageSliceKind.Text &&
                            page.paragraphIndex == block.paragraphIndex &&
                            page.textStyle == expectedStyle
                    },
                    "${file.name} styled text block $blockIndex lost paged style metadata.",
                )
            }

            hasMultiPageBook = hasMultiPageBook || pages.size > 1
            hasImagePages = hasImagePages || imageBlocks.isNotEmpty()
            hasLinkPages = hasLinkPages || linkedTextBlocks.isNotEmpty()
            hasTargetedInternalLinkPages = hasTargetedInternalLinkPages || targetedInternalLinkPages > 0
            hasStyledPages = hasStyledPages || styledTextBlocks.isNotEmpty()

            println(
                "EPUB_PAGED_CORPUS|${file.name}" +
                    "|pages=${pages.size}" +
                    "|imagePages=${pages.count { it.kind is EpubPageSliceKind.Image }}" +
                    "|linkPages=${pages.count { it.links.isNotEmpty() }}" +
                    "|styledPages=${pages.count { page -> page.textStyle != EpubPageTextStyle() }}" +
                    "|targetedInternalLinkPages=$targetedInternalLinkPages",
            )
        }

        assertTrue(hasMultiPageBook, "Corpus must include at least one multi-page paged EPUB.")
        assertTrue(hasImagePages, "Corpus must include at least one paged image slice.")
        assertTrue(hasLinkPages, "Corpus must include at least one paged text slice with page-local links.")
        assertTrue(
            hasTargetedInternalLinkPages,
            "Corpus must include at least one paged text slice with a targetable internal link.",
        )
        assertTrue(hasStyledPages, "Corpus must include at least one paged text slice with style metadata.")
    }

    private fun validateCharacterProgression(file: File, paras: List<EpubPara>) {
        if (paras.isEmpty()) return
        val totalChars = epubTotalChars(paras)
        assertTrue(totalChars > 0, "${file.name} has readable paragraphs but zero total chars.")

        var previousDocumentEnd = 0
        paras.forEachIndexed { index, para ->
            assertEquals(
                para.text.length,
                para.spineCharEnd - para.spineCharStart,
                "${file.name} paragraph $index has wrong spine character range.",
            )
            assertEquals(
                para.text.length,
                para.documentCharEnd - para.documentCharStart,
                "${file.name} paragraph $index has wrong document character range.",
            )
            assertEquals(
                previousDocumentEnd,
                para.documentCharStart,
                "${file.name} paragraph $index document character ranges are not contiguous.",
            )

            val locator = epubLocatorForIndex(paras, index)
            val section = locator.strategy as LocatorStrategy.Section
            assertEquals(para.spineIndex, section.spineIndex, "${file.name} paragraph $index spine locator mismatch.")
            assertEquals(index, section.elementIndex, "${file.name} paragraph $index element locator mismatch.")
            assertEquals(para.spineCharStart, section.charOffset, "${file.name} paragraph $index char locator mismatch.")
            assertEquals(
                para.documentCharStart.toFloat() / totalChars,
                locator.totalProgression,
                "${file.name} paragraph $index totalProgression must be character-based.",
            )
            previousDocumentEnd = para.documentCharEnd
        }
        assertEquals(totalChars, previousDocumentEnd, "${file.name} total char count does not match last paragraph.")
    }

    private fun validateReaderItemLocators(file: File, book: EpubBook) {
        book.items.forEachIndexed { index, item ->
            assertTrue(
                item.locator.spineIndex in book.spinePaths.indices,
                "${file.name} item $index has locator outside spine range.",
            )
            assertTrue(item.locator.elementIndex >= 0, "${file.name} item $index has negative element index.")
            assertTrue(item.locator.charOffset >= 0, "${file.name} item $index has negative char offset.")
        }
    }

    private fun validateTocLocators(file: File, book: EpubBook) {
        book.tableOfContents.forEachIndexed { index, entry ->
            val section = entry.locator.strategy as? LocatorStrategy.Section
            assertTrue(section != null, "${file.name} TOC entry $index must use Section locator.")
            section ?: return@forEachIndexed
            assertTrue(
                section.spineIndex in book.spinePaths.indices,
                "${file.name} TOC entry $index has locator outside spine range.",
            )
            assertTrue(
                section.elementIndex in book.paras.indices,
                "${file.name} TOC entry $index points outside paragraph range.",
            )
            val totalProgression = entry.locator.totalProgression
                ?: error("${file.name} TOC entry $index must include totalProgression.")
            assertTrue(
                totalProgression in 0f..1f,
                "${file.name} TOC entry $index has invalid totalProgression.",
            )
        }
    }

    private fun validateLazyBookIndex(file: File, book: EpubBook, lazyBook: EpubLazyBook) {
        assertEquals(book.paras.size, lazyBook.paras.size, "${file.name} lazy/full paragraph counts diverge.")
        assertEquals(book.spinePaths, lazyBook.spinePaths, "${file.name} lazy/full spine paths diverge.")
        assertEquals(
            epubDisplayBlocks(book.items).size,
            lazyBook.blockCount,
            "${file.name} lazy/full display block counts diverge.",
        )
        assertTrue(lazyBook.cachedSpineIndexes().isEmpty(), "${file.name} lazy book should not warm cache during indexing.")

        lazyBook.paras.forEachIndexed { index, lazyPara ->
            val fullPara = book.paras[index]
            assertTrue(lazyPara.text.isEmpty(), "${file.name} lazy index paragraph $index should not retain text.")
            assertEquals(fullPara.spineIndex, lazyPara.spineIndex, "${file.name} lazy paragraph $index spine mismatch.")
            assertEquals(
                fullPara.spineCharStart,
                lazyPara.spineCharStart,
                "${file.name} lazy paragraph $index spine start mismatch.",
            )
            assertEquals(
                fullPara.spineCharEnd,
                lazyPara.spineCharEnd,
                "${file.name} lazy paragraph $index spine end mismatch.",
            )
            assertEquals(
                fullPara.documentCharStart,
                lazyPara.documentCharStart,
                "${file.name} lazy paragraph $index document start mismatch.",
            )
            assertEquals(
                fullPara.documentCharEnd,
                lazyPara.documentCharEnd,
                "${file.name} lazy paragraph $index document end mismatch.",
            )
        }

        val sampleIndexes = listOf(0, book.paras.lastIndex / 2, book.paras.lastIndex)
            .filter { it in book.paras.indices }
            .distinct()
        sampleIndexes.forEach { index ->
            assertEquals(
                book.paras[index].text,
                lazyBook.paragraphAt(index)?.text,
                "${file.name} lazy paragraphAt($index) loaded wrong text.",
            )
            assertTrue(
                lazyBook.blockIndexForParagraph(index) in 0 until lazyBook.blockCount.coerceAtLeast(1),
                "${file.name} block index for paragraph $index outside block range.",
            )
        }
        if (sampleIndexes.isNotEmpty()) {
            assertTrue(lazyBook.cachedSpineIndexes().isNotEmpty(), "${file.name} lazy paragraphAt did not warm cache.")
        }
        lazyBook.close()
        assertTrue(lazyBook.cachedSpineIndexes().isEmpty(), "${file.name} lazy close did not clear cache.")
    }

    private fun validateImageItems(file: File, book: EpubBook): ImageStats {
        val images = book.items.filterIsInstance<EpubReaderItem.Image>()
        val imageBlocks = epubDisplayBlocks(book.items).filterIsInstance<EpubDisplayBlock.Image>()
        assertEquals(images.size, imageBlocks.size, "${file.name} display blocks must preserve image count.")

        var packageLocalImages = 0
        var altTextImages = 0
        ZipFile(file).use { zip ->
            images.forEachIndexed { index, image ->
                assertTrue(image.href.isNotBlank(), "${file.name} image $index has blank href.")
                image.altText?.let { alt ->
                    assertTrue(alt.isNotBlank(), "${file.name} image $index has blank alt text.")
                    altTextImages += 1
                }
                if (image.href.isExternalOrDataHref()) return@forEachIndexed

                val safePath = epubSafeZipPath(image.href)
                assertTrue(safePath != null, "${file.name} image $index has unsafe href ${image.href}.")
                safePath ?: return@forEachIndexed
                val entry = zip.getEntry(safePath)
                assertTrue(entry != null, "${file.name} image $index missing zip entry $safePath.")
                entry ?: return@forEachIndexed
                assertTrue(!entry.isDirectory, "${file.name} image $index points to directory $safePath.")
                if (entry.size >= 0) {
                    assertTrue(
                        entry.size <= EPUB_MAX_ENCODED_IMAGE_BYTES,
                        "${file.name} image $index exceeds encoded image budget.",
                    )
                }
                packageLocalImages += 1
            }
        }
        return ImageStats(packageLocalImages = packageLocalImages, altTextImages = altTextImages)
    }

    private fun validateLinks(file: File, book: EpubBook): LinkStats {
        val targetIndexes = epubInternalLinkTargetIndexes(book.spinePaths, book.paras, book.fragmentTargetIndexes)
        var links = 0
        var externalLinks = 0
        var targetedInternalLinks = 0
        book.items.forEachIndexed { itemIndex, item ->
            val text = item.linkText() ?: return@forEachIndexed
            item.linkRanges().forEachIndexed { linkIndex, link ->
                links += 1
                assertTrue(link.href.isNotBlank(), "${file.name} item $itemIndex link $linkIndex has blank href.")
                assertTrue(link.start >= 0, "${file.name} item $itemIndex link $linkIndex has negative start.")
                assertTrue(link.end <= text.length, "${file.name} item $itemIndex link $linkIndex exceeds text length.")
                assertTrue(link.start < link.end, "${file.name} item $itemIndex link $linkIndex has empty range.")
                assertTrue(
                    text.substring(link.start, link.end).isNotBlank(),
                    "${file.name} item $itemIndex link $linkIndex has blank linked text.",
                )
                assertEquals(
                    link.href.isExternalHref(),
                    link.isExternal,
                    "${file.name} item $itemIndex link $linkIndex external classification mismatch.",
                )
                if (link.isExternal) {
                    externalLinks += 1
                } else {
                    val key = epubInternalLinkTargetKey(link.href)
                    targetIndexes[key]?.let { targetIndex ->
                        assertTrue(
                            targetIndex.paragraphIndex in book.paras.indices,
                            "${file.name} item $itemIndex link $linkIndex targets paragraph outside range.",
                        )
                        targetedInternalLinks += 1
                    }
                }
            }
        }

        val displayBlockLinks = epubDisplayBlocks(book.items).sumOf { block ->
            when (block) {
                is EpubDisplayBlock.Text -> block.links.size
                is EpubDisplayBlock.Image,
                is EpubDisplayBlock.Break,
                -> 0
            }
        }
        assertEquals(links, displayBlockLinks, "${file.name} display blocks must preserve inline links.")
        return LinkStats(links = links, externalLinks = externalLinks, targetedInternalLinks = targetedInternalLinks)
    }

    private fun validateDegradation(file: File, book: EpubBook, lazyBook: EpubLazyBook): DegradationStats {
        assertEquals(book.isFixedLayout, lazyBook.isFixedLayout, "${file.name} lazy/full fixed-layout flags diverge.")
        assertEquals(book.spinePaths, lazyBook.spinePaths, "${file.name} lazy/full spine paths diverge.")

        var styleSpans = 0
        var tables = 0
        var lists = 0
        var blockquotes = 0
        var preformatted = 0
        book.items.forEachIndexed { itemIndex, item ->
            val text = item.linkText()
            val spans = item.styleRanges()
            styleSpans += spans.size
            spans.forEachIndexed { spanIndex, span ->
                val textLength = text?.length ?: 0
                assertTrue(span.start >= 0, "${file.name} item $itemIndex style $spanIndex has negative start.")
                assertTrue(span.end <= textLength, "${file.name} item $itemIndex style $spanIndex exceeds text length.")
                assertTrue(span.start < span.end, "${file.name} item $itemIndex style $spanIndex has empty range.")
            }
            if (item is EpubReaderItem.Text) {
                assertTrue(item.indentLevel >= 0, "${file.name} item $itemIndex has negative list indent.")
                when (item.kind) {
                    EpubTextKind.Table -> tables += 1
                    EpubTextKind.ListItem -> lists += 1
                    EpubTextKind.Blockquote -> blockquotes += 1
                    EpubTextKind.Preformatted -> preformatted += 1
                    EpubTextKind.Body -> Unit
                }
            }
        }
        if (book.isFixedLayout) {
            assertTrue(book.paras.isNotEmpty(), "${file.name} fixed-layout fallback produced no reflow text.")
        }
        return DegradationStats(
            styleSpans = styleSpans,
            tables = tables,
            lists = lists,
            blockquotes = blockquotes,
            preformatted = preformatted,
            fixedLayoutFallback = book.isFixedLayout && book.paras.isNotEmpty(),
        )
    }

    private fun Char.isCjk(): Boolean =
        this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF'

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    private fun EpubReaderItem.linkText(): String? =
        when (this) {
            is EpubReaderItem.Heading -> text
            is EpubReaderItem.Text -> text
            is EpubReaderItem.Break,
            is EpubReaderItem.Image,
            -> null
        }

    private fun EpubReaderItem.linkRanges(): List<EpubTextLink> =
        when (this) {
            is EpubReaderItem.Heading -> links
            is EpubReaderItem.Text -> links
            is EpubReaderItem.Break,
            is EpubReaderItem.Image,
            -> emptyList()
        }

    private fun EpubReaderItem.styleRanges(): List<EpubTextStyleSpan> =
        when (this) {
            is EpubReaderItem.Heading -> styleSpans
            is EpubReaderItem.Text -> styleSpans
            is EpubReaderItem.Break,
            is EpubReaderItem.Image,
            -> emptyList()
        }

    private fun String.isExternalOrDataHref(): Boolean =
        isExternalHref() || startsWith("data:", ignoreCase = true)

    private fun String.isExternalHref(): Boolean =
        startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("mailto:", ignoreCase = true) ||
            startsWith("tel:", ignoreCase = true)

    private data class ImageStats(
        val packageLocalImages: Int,
        val altTextImages: Int,
    )

    private data class LinkStats(
        val links: Int,
        val externalLinks: Int,
        val targetedInternalLinks: Int,
    )

    private data class DegradationStats(
        val styleSpans: Int,
        val tables: Int,
        val lists: Int,
        val blockquotes: Int,
        val preformatted: Int,
        val fixedLayoutFallback: Boolean,
    )

    private fun pagedCorpusMetrics(): EpubPageMetrics =
        EpubPageMetrics(
            viewportWidthPx = 240,
            viewportHeightPx = 96,
            horizontalPaddingPx = 20,
            verticalPaddingPx = 0,
            averageCharacterWidthPx = 10f,
            lineHeightPx = 24f,
        )

    private fun corpusPagedLineBreaker(
        text: String,
        contentWidthPx: Int,
        textStyle: EpubPageTextStyle,
    ): List<Pair<Int, Int>> {
        val styleCompression = if (textStyle.headingLevel != null) 2 else 0
        val charsPerLine = ((contentWidthPx / 10f).toInt() - styleCompression).coerceAtLeast(1)
        if (text.isEmpty()) return emptyList()

        val lines = mutableListOf<Pair<Int, Int>>()
        var globalOffset = 0
        text.split('\n').forEachIndexed { segmentIndex, segment ->
            if (segment.isEmpty()) {
                lines += globalOffset to globalOffset
                globalOffset += 1
                return@forEachIndexed
            }

            var start = 0
            while (start < segment.length) {
                val rawEnd = (start + charsPerLine).coerceAtMost(segment.length)
                val end = nearestBoundary(segment, start, rawEnd)
                lines += (globalOffset + start) to (globalOffset + end)
                start = end.coerceAtLeast(start + 1)
            }
            if (segmentIndex < text.count { it == '\n' }) {
                globalOffset += segment.length + 1
            }
        }
        return lines
    }

    private fun nearestBoundary(text: String, start: Int, rawEnd: Int): Int {
        if (rawEnd >= text.length) return text.length
        if (rawEnd > start && text[rawEnd - 1].isWhitespace()) return rawEnd

        val minBoundary = (rawEnd - ((rawEnd - start) / 3)).coerceAtLeast(start + 1)
        for (index in rawEnd downTo minBoundary) {
            if (index > start && text[index - 1].isWhitespace()) {
                return index
            }
        }
        return rawEnd
    }

    private companion object {
        const val MIN_LANGUAGE_SAMPLE_CHARS = 20
        const val MIN_IMAGE_HEAVY_ITEMS = 5
        const val MIN_MULTI_SPINE_COUNT = 3
    }
}
