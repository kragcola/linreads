package dev.readflow.render.epub

import dev.readflow.core.model.TocEntry
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

internal data class EpubBook(
    val items: List<EpubReaderItem>,
    val paras: List<EpubPara>,
    val tableOfContents: List<TocEntry>,
    val spinePaths: List<String>,
    val fragmentTargetIndexes: Map<String, EpubTargetPosition> = emptyMap(),
    val isFixedLayout: Boolean = false,
)

/**
 * Parses an EPUB file into typed reader items, then derives the current
 * TextView paragraph stream used by the v4-lite renderer.
 * Reads container.xml → OPF → spine → per-item HTML → jsoup extraction.
 * No CFI / WebView dependency.
 */
internal class EpubParser {

    fun parse(file: File): List<EpubPara> = parseBook(file).paras

    fun parseBook(file: File): EpubBook = epubParserGuard(emptyBook()) {
        ZipFile(file).use { zip ->
            if (zip.size() > EPUB_MAX_ZIP_ENTRIES) return@use emptyBook()
            val opfPath = findOpfPath(zip)
            val pkg = parsePackage(zip, opfPath)
            val items = parseItems(zip, pkg)
            val paras = epubParasFromReaderItems(items)
            val spinePaths = pkg.spineItems.map { it.path }
            val fragmentTargets = epubFragmentTargetIndexes(spinePaths, items)
            EpubBook(
                items = items,
                paras = paras,
                tableOfContents = parseTableOfContents(zip, pkg, paras, fragmentTargets),
                spinePaths = spinePaths,
                fragmentTargetIndexes = fragmentTargets,
                isFixedLayout = pkg.isFixedLayout,
            )
        }
    }

    fun parseLazyBook(file: File, maxCachedSpines: Int = EpubLazyBook.DEFAULT_MAX_CACHED_SPINES): EpubLazyBook =
        epubParserGuard(EpubLazyBook.empty(file, maxCachedSpines)) {
            ZipFile(file).use { zip ->
                if (zip.size() > EPUB_MAX_ZIP_ENTRIES) return@use EpubLazyBook.empty(file, maxCachedSpines)
                val opfPath = findOpfPath(zip)
                val pkg = parsePackage(zip, opfPath)
                val index = buildLazyBookIndex(zip, pkg)
                EpubLazyBook(
                    file = file,
                    spineRefs = index.spineRefs,
                    paras = index.paras,
                    tableOfContents = parseTableOfContents(zip, pkg, index.paras, index.fragmentTargetIndexes),
                    spinePaths = pkg.spineItems.map { it.path },
                    fragmentTargetIndexes = index.fragmentTargetIndexes,
                    isFixedLayout = pkg.isFixedLayout,
                    blockCount = index.blockCount,
                    paragraphBlockIndexes = index.paragraphBlockIndexes,
                    layoutBlocks = index.layoutBlocks,
                    maxCachedSpines = maxCachedSpines,
                )
            }
        }

    fun parseItems(file: File): List<EpubReaderItem> = parseBook(file).items

    private fun emptyBook(): EpubBook =
        EpubBook(
            items = emptyList(),
            paras = emptyList(),
            tableOfContents = emptyList(),
            spinePaths = emptyList(),
            fragmentTargetIndexes = emptyMap(),
        )

    private data class EpubLazyBookIndex(
        val spineRefs: List<EpubSpineRef>,
        val paras: List<EpubPara>,
        val blockCount: Int,
        val paragraphBlockIndexes: List<Int>,
        val layoutBlocks: List<EpubDisplayBlock>,
        val fragmentTargetIndexes: Map<String, EpubTargetPosition>,
    )

    private data class EpubPackage(
        val spineItems: List<ManifestItem>,
        val navItem: ManifestItem?,
        val ncxItem: ManifestItem?,
        val isFixedLayout: Boolean,
    )

    private data class ManifestItem(
        val id: String,
        val path: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    private fun parseItems(zip: ZipFile, pkg: EpubPackage): List<EpubReaderItem> =
        pkg.spineItems.flatMapIndexed { spineIndex, item -> parseSpineItems(zip, spineIndex, item) }

    private fun parseSpineItems(zip: ZipFile, spineIndex: Int, item: ManifestItem): List<EpubReaderItem> {
        val html = readEpubZipText(zip, item.path, EPUB_MAX_SPINE_ENTRY_BYTES) ?: return emptyList()
        return epubParserGuard(emptyList()) {
            parseReaderItemsFromHtml(
                spineIndex = spineIndex,
                html = html,
                resourceBaseDir = epubParentDir(item.path),
                documentPath = item.path,
            )
        }
    }

    private fun buildLazyBookIndex(zip: ZipFile, pkg: EpubPackage): EpubLazyBookIndex {
        val spineRefs = mutableListOf<EpubSpineRef>()
        val paras = mutableListOf<EpubPara>()
        val paragraphBlockIndexes = mutableListOf<Int>()
        val layoutBlocks = mutableListOf<EpubDisplayBlock>()
        var firstParagraphIndex = 0
        var firstBlockIndex = 0
        var documentOffset = 0
        val fragmentTargetIndexes = mutableMapOf<String, EpubTargetPosition>()

        pkg.spineItems.forEachIndexed { spineIndex, item ->
            val items = parseSpineItems(zip, spineIndex, item)
            val localParas = epubParasFromReaderItems(items)
            val blocks = epubDisplayBlocks(items)
            val localParagraphBlockIndexes = firstBlockIndexesByParagraph(blocks, localParas.size)
            val localFragmentTargets = epubFragmentTargetIndexes(pkg.spineItems.map { it.path }, items)
            layoutBlocks += blocks.map { block -> block.withParagraphOffset(firstParagraphIndex) }

            localParas.forEachIndexed { localParagraphIndex, para ->
                paras += para.copy(
                    text = "",
                    documentCharStart = documentOffset + para.documentCharStart,
                    documentCharEnd = documentOffset + para.documentCharEnd,
                )
                paragraphBlockIndexes += firstBlockIndex + localParagraphBlockIndexes[localParagraphIndex]
            }
            localFragmentTargets.forEach { (href, localTarget) ->
                fragmentTargetIndexes[href] = localTarget.copy(
                    paragraphIndex = firstParagraphIndex + localTarget.paragraphIndex,
                )
            }
            spineRefs += EpubSpineRef(
                spineIndex = spineIndex,
                path = item.path,
                firstParagraphIndex = firstParagraphIndex,
                paragraphCount = localParas.size,
                firstBlockIndex = firstBlockIndex,
                blockCount = blocks.size,
                documentCharStart = documentOffset,
            )
            firstParagraphIndex += localParas.size
            firstBlockIndex += blocks.size
            documentOffset += localParas.maxOfOrNull { it.documentCharEnd } ?: 0
        }

        return EpubLazyBookIndex(
            spineRefs = spineRefs,
            paras = paras,
            blockCount = firstBlockIndex,
            paragraphBlockIndexes = paragraphBlockIndexes,
            layoutBlocks = layoutBlocks,
            fragmentTargetIndexes = fragmentTargetIndexes,
        )
    }

    private fun EpubDisplayBlock.withParagraphOffset(offset: Int): EpubDisplayBlock =
        when (this) {
            is EpubDisplayBlock.Text -> copy(paragraphIndex = paragraphIndex + offset)
            is EpubDisplayBlock.Image -> copy(paragraphIndex = paragraphIndex + offset)
            is EpubDisplayBlock.Break -> copy(paragraphIndex = paragraphIndex + offset)
        }

    private fun firstBlockIndexesByParagraph(blocks: List<EpubDisplayBlock>, paragraphCount: Int): List<Int> {
        val indexes = MutableList(paragraphCount) { -1 }
        blocks.forEachIndexed { blockIndex, block ->
            if (block.paragraphIndex in indexes.indices && indexes[block.paragraphIndex] < 0) {
                indexes[block.paragraphIndex] = blockIndex
            }
        }
        return indexes.map { index -> if (index >= 0) index else 0 }
    }

    private fun findOpfPath(zip: ZipFile): String {
        val xml = readEpubZipText(zip, "META-INF/container.xml", EPUB_MAX_PACKAGE_ENTRY_BYTES)
            ?: return "OEBPS/content.opf"
        val doc = epubParserGuard(null) { Jsoup.parse(xml, "", Parser.xmlParser()) }
            ?: return "OEBPS/content.opf"
        return doc.selectFirst("rootfile[media-type=application/oebps-package+xml]")
            ?.attr("full-path")
            ?.let(::epubSafeZipPath)
            ?: "OEBPS/content.opf"
    }

    private fun parsePackage(zip: ZipFile, opfPath: String): EpubPackage {
        val opfText = readEpubZipText(zip, opfPath, EPUB_MAX_PACKAGE_ENTRY_BYTES)
            ?: return emptyPackage()
        val baseDir = epubParentDir(opfPath)
        val opf = epubParserGuard(null) { Jsoup.parse(opfText, "", Parser.xmlParser()) }
            ?: return emptyPackage()
        val manifestItems = opf.select("manifest item").mapNotNull { item ->
            val id = item.attr("id").trim()
            val href = item.attr("href").trim()
            if (id.isEmpty() || href.isEmpty()) return@mapNotNull null
            val path = epubSafeResolvePath(baseDir, href) ?: return@mapNotNull null
            ManifestItem(
                id = id,
                path = path,
                mediaType = item.attr("media-type").trim(),
                properties = item.attr("properties")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { it.lowercase() }
                    .toSet(),
            )
        }
        val manifestById = manifestItems.associateBy { it.id }
        val spine = opf.selectFirst("spine")
        val spineItems = spine
            ?.select("itemref")
            ?.mapNotNull { manifestById[it.attr("idref")] }
            .orEmpty()
        val spineTocId = spine?.attr("toc")?.trim()?.takeIf { it.isNotEmpty() }
        return EpubPackage(
            spineItems = spineItems,
            navItem = manifestItems.firstOrNull { "nav" in it.properties },
            ncxItem = spineTocId?.let { manifestById[it] }
                ?: manifestItems.firstOrNull {
                    it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) ||
                        it.path.endsWith(".ncx", ignoreCase = true)
                },
            isFixedLayout = hasFixedLayoutMetadata(opf) ||
                manifestItems.any { "rendition:layout-pre-paginated" in it.properties } ||
                opf.select("spine itemref").any { itemRef ->
                    itemRef.attr("properties")
                        .split(Regex("\\s+"))
                        .any { it.equals("rendition:layout-pre-paginated", ignoreCase = true) }
                },
        )
    }

    private fun emptyPackage(): EpubPackage =
        EpubPackage(
            spineItems = emptyList(),
            navItem = null,
            ncxItem = null,
            isFixedLayout = false,
        )

    private fun hasFixedLayoutMetadata(opf: org.jsoup.nodes.Document): Boolean =
        opf.select("metadata meta").any { meta ->
            val key = meta.attr("property").ifBlank { meta.attr("name") }
            val value = meta.text().ifBlank { meta.attr("content") }.trim()
            key.equals("rendition:layout", ignoreCase = true) &&
                value.equals("pre-paginated", ignoreCase = true)
        }

    private fun parseTableOfContents(
        zip: ZipFile,
        pkg: EpubPackage,
        paras: List<EpubPara>,
        fragmentTargets: Map<String, EpubTargetPosition>,
    ): List<TocEntry> {
        val spinePaths = pkg.spineItems.map { it.path }
        val navToc = pkg.navItem?.let { item ->
            val html = readEpubZipText(zip, item.path, EPUB_MAX_TOC_ENTRY_BYTES) ?: return@let emptyList()
            buildEpubToc(parseNavTocEntries(html), item.path, spinePaths, paras, fragmentTargets)
        }.orEmpty()
        if (navToc.isNotEmpty()) return navToc

        return pkg.ncxItem?.let { item ->
            val xml = readEpubZipText(zip, item.path, EPUB_MAX_TOC_ENTRY_BYTES) ?: return@let emptyList()
            buildEpubToc(parseNcxTocEntries(xml), item.path, spinePaths, paras, fragmentTargets)
        }.orEmpty()
    }
}
