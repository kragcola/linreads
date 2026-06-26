package dev.readflow.render.epub

import dev.readflow.core.model.TocEntry
import java.io.File
import java.util.LinkedHashMap
import java.util.zip.ZipFile

internal data class EpubSpineRef(
    val spineIndex: Int,
    val path: String,
    val firstParagraphIndex: Int,
    val paragraphCount: Int,
    val firstBlockIndex: Int,
    val blockCount: Int,
    val documentCharStart: Int,
)

internal class EpubLazyBook(
    private val file: File,
    private val spineRefs: List<EpubSpineRef>,
    val paras: List<EpubPara>,
    val tableOfContents: List<TocEntry>,
    val spinePaths: List<String>,
    val fragmentTargetIndexes: Map<String, EpubTargetPosition>,
    val isFixedLayout: Boolean,
    val blockCount: Int,
    private val paragraphBlockIndexes: List<Int>,
    private val layoutBlocks: List<EpubDisplayBlock>,
    maxCachedSpines: Int,
) {
    private val cacheLimit = maxCachedSpines.coerceAtLeast(1)
    private val loadCounts = mutableMapOf<Int, Int>()
    private val cache = object : LinkedHashMap<Int, EpubSpineContent>(cacheLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, EpubSpineContent>): Boolean =
            size > cacheLimit
    }

    @Synchronized
    fun blockAt(globalBlockIndex: Int): EpubDisplayBlock? {
        val ref = spineForBlock(globalBlockIndex) ?: return null
        return loadSpine(ref).blocks.getOrNull(globalBlockIndex - ref.firstBlockIndex)
    }

    @Synchronized
    fun paragraphAt(globalParagraphIndex: Int): EpubPara? {
        val ref = spineForParagraph(globalParagraphIndex) ?: return null
        return loadSpine(ref).paras.getOrNull(globalParagraphIndex - ref.firstParagraphIndex)
    }

    @Synchronized
    fun cachedParagraphAt(globalParagraphIndex: Int): EpubPara? {
        val ref = spineForParagraph(globalParagraphIndex) ?: return null
        return cache[ref.spineIndex]?.paras?.getOrNull(globalParagraphIndex - ref.firstParagraphIndex)
    }

    @Synchronized
    fun cachedBlocks(): List<EpubDisplayBlock> =
        spineRefs.flatMap { ref ->
            cache[ref.spineIndex]?.blocks.orEmpty()
        }

    @Synchronized
    fun layoutBlocks(): List<EpubDisplayBlock> = layoutBlocks

    @Synchronized
    fun blockIndexForParagraph(globalParagraphIndex: Int): Int {
        if (paragraphBlockIndexes.isEmpty()) return 0
        return paragraphBlockIndexes[globalParagraphIndex.coerceIn(paragraphBlockIndexes.indices)]
            .coerceIn(0, blockCount.coerceAtLeast(1) - 1)
    }

    @Synchronized
    fun prefetchAroundParagraph(globalParagraphIndex: Int) {
        val ref = spineForParagraph(globalParagraphIndex) ?: return
        loadSpine(ref)
        spineRefs.getOrNull(ref.spineIndex + 1)?.let(::loadSpine)
        spineRefs.getOrNull(ref.spineIndex - 1)?.let(::loadSpine)
    }

    @Synchronized
    fun cachedSpineIndexes(): Set<Int> = cache.keys.toSet()

    @Synchronized
    fun loadCount(spineIndex: Int): Int = loadCounts[spineIndex] ?: 0

    @Synchronized
    fun close() {
        cache.clear()
        loadCounts.clear()
    }

    private fun spineForBlock(globalBlockIndex: Int): EpubSpineRef? =
        spineRefs.firstOrNull { ref ->
            globalBlockIndex >= ref.firstBlockIndex &&
                globalBlockIndex < ref.firstBlockIndex + ref.blockCount
        }

    private fun spineForParagraph(globalParagraphIndex: Int): EpubSpineRef? =
        spineRefs.firstOrNull { ref ->
            globalParagraphIndex >= ref.firstParagraphIndex &&
                globalParagraphIndex < ref.firstParagraphIndex + ref.paragraphCount
        }

    private fun loadSpine(ref: EpubSpineRef): EpubSpineContent {
        cache[ref.spineIndex]?.let { return it }
        loadCounts[ref.spineIndex] = loadCount(ref.spineIndex) + 1
        val items = readSpineItems(ref)
        val paras = epubParasFromReaderItems(items).map { para ->
            para.copy(
                documentCharStart = ref.documentCharStart + para.documentCharStart,
                documentCharEnd = ref.documentCharStart + para.documentCharEnd,
            )
        }
        val blocks = epubDisplayBlocks(items).map { it.withParagraphOffset(ref.firstParagraphIndex) }
        return EpubSpineContent(paras = paras, blocks = blocks)
            .also { cache[ref.spineIndex] = it }
    }

    private fun readSpineItems(ref: EpubSpineRef): List<EpubReaderItem> =
        ZipFile(file).use { zip ->
            val html = readEpubZipText(zip, ref.path, EPUB_MAX_SPINE_ENTRY_BYTES) ?: return emptyList()
            epubParserGuard(emptyList()) {
                parseReaderItemsFromHtml(
                    spineIndex = ref.spineIndex,
                    html = html,
                    resourceBaseDir = epubParentDir(ref.path),
                    documentPath = ref.path,
                )
            }
        }

    private fun EpubDisplayBlock.withParagraphOffset(offset: Int): EpubDisplayBlock =
        when (this) {
            is EpubDisplayBlock.Text -> copy(paragraphIndex = paragraphIndex + offset)
            is EpubDisplayBlock.Image -> copy(paragraphIndex = paragraphIndex + offset)
            is EpubDisplayBlock.Break -> copy(paragraphIndex = paragraphIndex + offset)
        }

    private data class EpubSpineContent(
        val paras: List<EpubPara>,
        val blocks: List<EpubDisplayBlock>,
    )

    companion object {
        const val DEFAULT_MAX_CACHED_SPINES = 3

        fun empty(file: File, maxCachedSpines: Int): EpubLazyBook =
            EpubLazyBook(
                file = file,
                spineRefs = emptyList(),
                paras = emptyList(),
                tableOfContents = emptyList(),
                spinePaths = emptyList(),
                fragmentTargetIndexes = emptyMap(),
                isFixedLayout = false,
                blockCount = 0,
                paragraphBlockIndexes = emptyList(),
                layoutBlocks = emptyList(),
                maxCachedSpines = maxCachedSpines,
            )
    }
}
