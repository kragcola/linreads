package dev.readflow.render.api

import android.net.Uri
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadingMode { SCROLL, PAGED }

enum class PagingKind { PAGED, CONTINUOUS }

fun PagingKind.toReadingMode(): ReadingMode = when (this) {
    PagingKind.PAGED -> ReadingMode.PAGED
    PagingKind.CONTINUOUS -> ReadingMode.SCROLL
}

/** Stable default for engines that do not publish chapter chrome state. */
private val DefaultReaderChapterInfo = ChapterInfo(
    currentIndex = 0,
    totalChapters = 0,
    currentTitle = "正文",
    progressInChapter = 0f,
    kind = ChapterInfo.Kind.DOCUMENT,
)

private val DefaultReaderChapterInfoFlow: StateFlow<ChapterInfo> =
    MutableStateFlow(DefaultReaderChapterInfo).asStateFlow()

private val DefaultReaderTableOfContentsFlow: StateFlow<List<TocEntry>> =
    MutableStateFlow(emptyList<TocEntry>()).asStateFlow()

/**
 * THREADING CONTRACT (v4 §5.1, P0-C):
 *   - All methods are CALLED on Dispatchers.Main; engines assume the main thread on entry.
 *   - Heavy work (parsing, FileChannel scan, JNI) MUST move off-main via withContext,
 *     then switch back to Main before touching the View.
 */
interface ReaderEngine {
    // Identity
    val id: String
    val format: BookFormat
    val priority: Int
    val pagingKind: StateFlow<PagingKind>
    val supportedModes: Set<ReadingMode>
        get() = setOf(pagingKind.value.toReadingMode())
    val supportsSearch: Boolean
    suspend fun supports(uri: Uri): Boolean

    // Lifecycle
    suspend fun openBook(uri: Uri): Locator   // heavy work here, NOT in constructor
    fun createView(): View                    // called after openBook(); engine owns the View
    suspend fun close()

    // Navigation
    suspend fun goTo(locator: Locator)

    /**
     * Seek to a whole-book progress fraction [0,1] (draggable progress bar).
     * Default builds a progression-only [Locator] that reflow/scroll engines resolve
     * via totalProgression. Fixed-page engines (PDF) override to map onto a page index.
     */
    suspend fun seekToProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        goTo(Locator(strategy = LocatorStrategy.Unknown, totalProgression = clamped))
    }

    val currentLocator: StateFlow<Locator>
    val pageCount: StateFlow<Int>

    /** Chapter info for the bottom chrome progress bar. Emits on each position change. */
    val chapterInfo: StateFlow<ChapterInfo>
        get() = DefaultReaderChapterInfoFlow

    /** Jump to next (+1) or previous (-1) chapter. No-op if at boundary. */
    suspend fun goToAdjacentChapter(delta: Int) {}

    /** Navigable document outline for the reader TOC panel. */
    val tableOfContents: StateFlow<List<TocEntry>>
        get() = DefaultReaderTableOfContentsFlow

    /** Jump to a table-of-contents entry. Default delegates to [goTo]. */
    suspend fun goToTocEntry(entry: TocEntry) = goTo(entry.locator)

    suspend fun search(query: String): List<ReaderSearchHit> = emptyList()

    // Layout control (reflow formats)
    suspend fun setFontSize(sp: Float)
    suspend fun setLineSpacing(multiplier: Float) {}
    /** 切换正文字体：true=内置思源宋体，false=系统 Serif。默认空实现。 */
    suspend fun setSerifFont(useSourceHan: Boolean) {}
    /** 按 fontId 切换正文字体：system/source_han/custom:<fileName>。默认空实现。 */
    suspend fun setFont(fontId: String) {}
    /** EPUB CSS family -> reader font id replacements. Other formats ignore this setting. */
    suspend fun setEpubFontReplacements(replacements: Map<String, String>) {}
    /** TXT 用户编码覆盖：传 charset 名强制重解码，null=沿用自动检测。默认空实现，仅 TXT 引擎实现。 */
    suspend fun setTxtEncodingOverride(charsetName: String?) {}
    suspend fun setTheme(mode: ThemeMode) {}
    suspend fun setMode(mode: ReadingMode)
    /** Page-turn animation style for PAGED mode (滑动/仿真/无). No-op for engines without self-paging. */
    suspend fun setPageFlipStyle(style: PageFlipStyle) {}

    // View lifecycle / acceleration cache (semantic position lives in ReaderState.currentLocator)
    fun onViewAttached(view: View) {}
    fun onViewDetached(view: View) {}
    suspend fun saveState(): ByteArray = ByteArray(0)
    suspend fun restoreState(state: ByteArray) {}
}

/**
 * Optional capability for engines that can make [openBook] publish the restored display locator as
 * their first resolved position. This keeps cold-open restore transactional without forcing every
 * engine to grow a broader openBook signature.
 */
interface InitialLocatorAwareReaderEngine : ReaderEngine {
    fun setInitialLocator(locator: Locator?)
}

/**
 * Optional capability for fixed-page engines that can provide one independent
 * page view at a time. ViewPager2 hosts use this without forcing reflow engines
 * into page rendering before they are ready.
 */
interface PagedReaderEngine : ReaderEngine {
    fun createPageView(pageIndex: Int): View
    fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?)

    /**
     * Report the host viewport size in pixels (typically the ViewPager content size after
     * layout / rotation). Default is a no-op so TXT/PDF/EPUB engines keep existing behaviour.
     * Markdown uses this for complete-line StaticLayout pagination instead of displayMetrics.
     */
    fun setViewportSize(widthPx: Int, heightPx: Int) {}

    fun pageIndexForLocator(locator: Locator): Int {
        val total = pageCount.value.coerceAtLeast(1)
        // Bare Page only: progress / host settle identity. PageText is a text point and must
        // not be treated as page/paragraph index here — PDF goTo uses fixedPageIndex instead.
        val index = when (val strategy = locator.strategy) {
            is LocatorStrategy.Page -> strategy.index
            is LocatorStrategy.Section -> strategy.elementIndex
            is LocatorStrategy.PageText,
            is LocatorStrategy.ByteOffset,
            LocatorStrategy.Unknown,
            -> locator.totalProgression?.let { (it * total).toInt() } ?: 0
        }
        return index.coerceIn(0, total - 1)
    }
}

/** Optional capability for fixed-layout engines that support transient matrix zoom. */
interface ZoomableReaderEngine : ReaderEngine {
    val zoomScale: StateFlow<Float>
    suspend fun setZoom(scale: Float)
}

/**
 * Optional capability for engines that paginate INTERNALLY inside a single self-managed scroll/flow
 * view (continuous-flow EPUB, Moon+ Reader style). The host attaches the engine's [createView] once
 * (no per-page ViewPager2 slots) and delegates page turns to [goToAdjacentPage], because the engine
 * owns its own touch gestures (free middle-zone scroll, slide flip) which a pager would otherwise
 * intercept. Page count / current position are still reported via [pageCount] / [currentLocator].
 *
 * NOTE (render-api-default-method-stale-build): adding this interface requires a clean full rebuild
 * of dependents, otherwise the engine crashes at runtime with AbstractMethodError.
 */
interface SelfPagingReaderEngine : ReaderEngine {
    /**
     * Whether self-paging is currently active. When false, a host should fall back to this engine's
     * other capabilities (e.g. [PagedReaderEngine]) — letting an engine ship both a new self-paging
     * path and a legacy paged path behind a flag for safe rollback.
     */
    val selfPagingActive: Boolean

    /** Turn [delta] pages within the current self-managed view (+1 next, -1 previous). */
    suspend fun goToAdjacentPage(delta: Int)
}

/**
 * Optional capability for engines that can paint a **transient** in-page search selection highlight.
 *
 * Search hits must never be stored as persistent [ReaderTextAnnotation] records. The engine keeps the
 * selected [ReaderSearchHit] independently of view instances so [createView] / mode remount can
 * re-apply paint. [setSearchHighlight] is called on Main and should stay cheap at the call site
 * (null clears).
 *
 * Shared paint color: [READER_SEARCH_HIGHLIGHT_COLOR] — semi-transparent blue, distinct from saved
 * annotation yellow.
 */
interface SearchHighlightableReaderEngine : ReaderEngine {
    /**
     * Apply or clear the selected search hit highlight. Pass null to clear.
     * Uses [ReaderSearchHit.locator] + [ReaderSearchHit.matchLength] as authoritative; do not
     * re-search snippets or infer length from collapsed snippet text.
     */
    fun setSearchHighlight(hit: ReaderSearchHit?)
}

/**
 * Shared semi-transparent blue for the selected in-page search result.
 * Distinct from ordinary saved annotation yellow (~0x66FFE082).
 */
const val READER_SEARCH_HIGHLIGHT_COLOR: Int = 0x664A90E2
