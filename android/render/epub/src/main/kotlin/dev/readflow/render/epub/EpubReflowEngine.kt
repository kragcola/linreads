package dev.readflow.render.epub

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EPUB native reflow engine (v4 §12.3 ADR-EPUB-Engine).
 * ZipFile + jsoup → flat paragraph list → RecyclerView continuous scroll.
 * No WebView / epubjs / CFI.
 * Locator = Section(spineIndex, elementIndex=flatPos, charOffset=0).
 */
class EpubReflowEngine(private val context: Context) : ReaderEngine {

    override val id = "epub-reflow"
    override val format = BookFormat.EPUB
    override val priority = 0
    override val supportsSearch = false

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _chapterInfo = MutableStateFlow(ChapterInfo(0, 1, "", 0f))
    override val chapterInfo: StateFlow<ChapterInfo> = _chapterInfo.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private var paras: List<EpubPara> = emptyList()
    private var chapterBoundaries: List<ChapterBoundary> = emptyList()
    private var fontSizeSp: Float = 18f
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var recyclerView: RecyclerView? = null

    /** Start index (inclusive) and end index (exclusive) of each chapter in paras. */
    private data class ChapterBoundary(
        val spineIndex: Int,
        val startInclusive: Int,
        val endExclusive: Int,
        val title: String,
    )

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "epub_${uri.hashCode()}.epub")
        if (!tmp.exists()) {
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmp.outputStream().use { dst -> src.copyTo(dst) }
            }
        }
        paras = EpubParser().parse(tmp)
        chapterBoundaries = buildChapterBoundaries(paras)
        withContext(Dispatchers.Main) {
            _pageCount.value = paras.size
            _chapterInfo.value = ChapterInfo(0, chapterBoundaries.size, chapterBoundaries.firstOrNull()?.title ?: "", 0f)
            _tableOfContents.value = buildToc(chapterBoundaries, paras.size)
        }
        _currentLocator.value
    }

    override fun createView(): View {
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            val palette = paletteFor(themeMode, resources.configuration)
            adapter = EpubParaAdapter(paras, fontSizeSp, palette.ink)
            setBackgroundColor(palette.paper)
            clipToPadding = false
            val padV = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = reportProgression(rv)
            })
        }.also { recyclerView = it }
    }

    private fun reportProgression(rv: RecyclerView) {
        val total = paras.size.takeIf { it > 0 } ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val para = paras.getOrNull(first) ?: return
        val ratio = first.toFloat() / total
        _currentLocator.value = Locator(
            strategy = LocatorStrategy.Section(para.spineIndex, first, 0),
            progression = ratio,
            totalProgression = ratio,
        )
        // Update chapter info
        updateChapterInfo(first)
    }

    private fun updateChapterInfo(paraIndex: Int) {
        val chapter = chapterBoundaries.firstOrNull { paraIndex in it.startInclusive until it.endExclusive }
            ?: chapterBoundaries.firstOrNull() ?: return
        val chapterIdx = chapterBoundaries.indexOf(chapter)
        val chapterSize = chapter.endExclusive - chapter.startInclusive
        val positionInChapter = (paraIndex - chapter.startInclusive).coerceAtLeast(0)
        val progressInChapter = if (chapterSize > 0) positionInChapter.toFloat() / chapterSize else 0f
        _chapterInfo.value = ChapterInfo(
            currentIndex = chapterIdx,
            totalChapters = chapterBoundaries.size,
            currentTitle = chapter.title,
            progressInChapter = progressInChapter,
        )
    }

    override suspend fun goToAdjacentChapter(delta: Int) {
        val info = _chapterInfo.value
        val targetIdx = (info.currentIndex + delta).coerceIn(0, chapterBoundaries.lastIndex)
        if (targetIdx == info.currentIndex) return
        val target = chapterBoundaries[targetIdx]
        withContext(Dispatchers.Main) {
            (recyclerView?.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(target.startInclusive, 0)
        }
    }

    private fun buildChapterBoundaries(paras: List<EpubPara>): List<ChapterBoundary> {
        if (paras.isEmpty()) return listOf(ChapterBoundary(0, 0, 0, ""))
        val boundaries = mutableListOf<ChapterBoundary>()
        var start = 0
        var currentSpine = paras.first().spineIndex
        for (i in paras.indices) {
            if (paras[i].spineIndex != currentSpine) {
                boundaries += ChapterBoundary(currentSpine, start, i, "第${currentSpine + 1}章")
                start = i
                currentSpine = paras[i].spineIndex
            }
        }
        boundaries += ChapterBoundary(currentSpine, start, paras.size, if (boundaries.isEmpty()) "正文" else "第${currentSpine + 1}章")
        return boundaries
    }

    private fun buildToc(boundaries: List<ChapterBoundary>, total: Int): List<TocEntry> =
        boundaries
            .filter { it.endExclusive > it.startInclusive }
            .map { boundary ->
                TocEntry(
                    title = boundary.title,
                    locator = Locator(
                        strategy = LocatorStrategy.Section(boundary.spineIndex, boundary.startInclusive, 0),
                        totalProgression = if (total > 0) boundary.startInclusive.toFloat() / total else 0f,
                    ),
                )
            }

    override suspend fun goTo(locator: Locator) {
        val idx = (locator.strategy as? LocatorStrategy.Section)?.elementIndex ?: 0
        withContext(Dispatchers.Main) {
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
        }
    }

    override suspend fun close() {
        recyclerView = null
        paras = emptyList()
        chapterBoundaries = emptyList()
        _tableOfContents.value = emptyList()
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        (recyclerView?.adapter as? EpubParaAdapter)?.updateFontSize(sp)
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val palette = paletteFor(mode, context.resources.configuration)
        recyclerView?.setBackgroundColor(palette.paper)
        (recyclerView?.adapter as? EpubParaAdapter)?.updateInkColor(palette.ink)
    }

    override suspend fun setMode(mode: ReadingMode) { /* CONTINUOUS only in v4 lite */ }

    private companion object {
        val PAPER_DAY = Color.rgb(0xED, 0xE6, 0xD6)
        val PAPER_NIGHT = Color.rgb(0x2A, 0x26, 0x20)
        val PAPER_LIGHT = Color.rgb(0xFA, 0xFA, 0xF8)
        val PAPER_SEPIA = Color.rgb(0xF5, 0xF0, 0xE8)

        private fun paletteFor(mode: ThemeMode, configuration: Configuration): ReaderPalette {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return when (mode) {
                ThemeMode.LIGHT -> ReaderPalette(PAPER_LIGHT, EpubParaAdapter.INK_DAY)
                ThemeMode.DARK -> ReaderPalette(PAPER_NIGHT, EpubParaAdapter.INK_NIGHT)
                ThemeMode.SEPIA -> ReaderPalette(PAPER_SEPIA, EpubParaAdapter.INK_DAY)
                ThemeMode.SYSTEM -> if (systemNight) {
                    ReaderPalette(PAPER_NIGHT, EpubParaAdapter.INK_NIGHT)
                } else {
                    ReaderPalette(PAPER_DAY, EpubParaAdapter.INK_DAY)
                }
            }
        }
    }
}

private data class ReaderPalette(val paper: Int, val ink: Int)
