package dev.readflow.render.epub

import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.SelfPagingReaderEngine
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawableScheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

internal enum class EpubImagePlacement {
    FullPage,
    Inline,
}

internal data class EpubComposeSegmentStyleSpan(
    val start: Int,
    val end: Int,
    val style: SpanStyle,
)

internal fun epubHeadingBoost(headingLevel: Int?): Float =
    when (headingLevel) {
        1 -> 5f
        2 -> 3f
        3 -> 1.5f
        else -> 0f
    }

/**
 * EPUB native reflow engine (v4 §12.3 ADR-EPUB-Engine).
 * ZipFile + jsoup → typed ReaderItem model → TextView paragraph stream.
 * No WebView / epubjs / CFI.
 * Locator = Section(spineIndex, elementIndex=flatPos, charOffset=spine char start).
 */
internal sealed interface EpubPageLineMeasurer {
    fun measure(
        text: String,
        contentWidthPx: Int,
        textStyle: EpubPageTextStyle,
    ): List<Pair<Int, Int>>

    val measurement: EpubPageMeasurement

    data class StaticLayout(
        val lineBreaker: (
            text: String,
            contentWidthPx: Int,
            textStyle: EpubPageTextStyle,
        ) -> List<Pair<Int, Int>>,
    ) : EpubPageLineMeasurer {
        override val measurement: EpubPageMeasurement = EpubPageMeasurement.StaticLayout

        override fun measure(
            text: String,
            contentWidthPx: Int,
            textStyle: EpubPageTextStyle,
        ): List<Pair<Int, Int>> = lineBreaker(text, contentWidthPx, textStyle)
    }

    data class ComposeTextLayoutResult(
        val lineBreaker: (
            text: String,
            contentWidthPx: Int,
            textStyle: EpubPageTextStyle,
        ) -> List<EpubTextLayoutLineRange>,
    ) : EpubPageLineMeasurer {
        override val measurement: EpubPageMeasurement = EpubPageMeasurement.ComposeTextLayoutResult

        override fun measure(
            text: String,
            contentWidthPx: Int,
            textStyle: EpubPageTextStyle,
        ): List<Pair<Int, Int>> = lineBreaker(text, contentWidthPx, textStyle)
            .map { it.start to it.end }
    }
}

class EpubReflowEngine private constructor(
    private val context: Context,
    private val pageLineMeasurer: EpubPageLineMeasurer?,
    // Continuous-flow path toggle (方案 C). Defaults to the production const; the internal test
    // constructor can force the legacy slice-pack path for legacy-behavior tests.
    private val flowEngineEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit?,
) : PagedReaderEngine, SelfPagingReaderEngine, TextSelectableReaderEngine, TextAnnotatableReaderEngine {

    constructor(context: Context) : this(context, pageLineMeasurer = null, flowEngineEnabled = EPUB_FLOW_ENGINE_ENABLED, constructorMarker = null)

    internal constructor(
        context: Context,
        pageLineMeasurer: EpubPageLineMeasurer,
        flowEngineEnabled: Boolean = false,
    ) : this(context, pageLineMeasurer = pageLineMeasurer, flowEngineEnabled = flowEngineEnabled, constructorMarker = null)

    /** Test-only: exercise the legacy slice-pack path (or force flow) without a custom measurer. */
    internal constructor(
        context: Context,
        flowEngineEnabled: Boolean,
    ) : this(context, pageLineMeasurer = null, flowEngineEnabled = flowEngineEnabled, constructorMarker = null)

    override val id = "epub-reflow"
    override val format = BookFormat.EPUB
    override val priority = 0
    override val supportsSearch = true
    override val supportedModes: Set<ReadingMode> = setOf(ReadingMode.SCROLL, ReadingMode.PAGED)

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

    private val _currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
    override val currentTextSelection: StateFlow<ReaderTextSelection?> = _currentTextSelection.asStateFlow()

    private var paras: List<EpubPara> = emptyList()
    private var lazyBook: EpubLazyBook? = null
    private var displayBlockCount: Int = 0
    private var epubFile: File? = null
    private var internalLinkTargetIndexes: Map<String, EpubTargetPosition> = emptyMap()
    private var spineCharCounts: List<Int> = emptyList()
    private var pagedSlices: List<EpubPageSlice> = emptyList()
    private var chapterBoundaries: List<ChapterBoundary> = emptyList()
    private var fontSizeSp: Float = 18f
    private var lineSpacingMultiplier: Float = 1.75f
    private var flipStyle: dev.readflow.core.model.PageFlipStyle = dev.readflow.core.model.PageFlipStyle.SLIDE
    private var useSourceHan: Boolean = true
    private var currentFontId: String = "source_han"
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    private var recyclerView: RecyclerView? = null
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null

    // ---- Continuous-flow path (方案 C). Active when EPUB_FLOW_ENGINE_ENABLED; legacy slice-pack
    // path below is retained as a fallback (flip the flag to roll back). ----
    private var flowView: EpubFlowView? = null
    private var flowSpineIndex: Int = -1
    private val flowExecutor: ExecutorService by lazy { Executors.newFixedThreadPool(2) }
    override val selfPagingActive: Boolean get() = flowEngineEnabled
    private var cacheJob: Job? = null
    private var cacheScope: CoroutineScope? = null
    private val activePageContainers = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val activePagedTextPages = WeakHashMap<ComposeView, EpubPagedTextPageState>()
    private val activePagedImagePages = WeakHashMap<View, EpubPagedImagePageState>()
    private val activePagedCompositePages = WeakHashMap<View, EpubPagedImagePageState>()
    private val imageBoundsCache = mutableMapOf<String, EpubImageBoundsCacheEntry>()

    /** Start index (inclusive) and end index (exclusive) of each chapter in paras. */
    private data class ChapterBoundary(
        val spineIndex: Int,
        val startInclusive: Int,
        val endExclusive: Int,
        val title: String,
    )

    private data class EpubPagedTextPageState(
        val slice: EpubPageSlice,
        val pageIndex: Int,
        val selectionHighlightState: MutableState<ReaderTextHighlightRange?>,
    )

    private data class EpubPagedImagePageState(
        val slice: EpubPageSlice,
        val pageIndex: Int,
    )

    private data class PagedTextRenderSegment(
        val source: EpubPageTextSegment,
        val renderStart: Int,
        val renderEnd: Int,
    )

    private data class PagedTextRenderContent(
        val text: String,
        val segments: List<PagedTextRenderSegment>,
    )

    // Caches the intrinsic-bounds decode per image href (value may be null when undecodable, which
    // is itself worth caching to avoid re-opening the zip on every repagination).
    private data class EpubImageBoundsCacheEntry(val value: EpubImageBounds?)

    private data class PagedTextSelectionPart(
        val paragraphIndex: Int,
        val paragraphStart: Int,
        val paragraphEnd: Int,
        val renderStart: Int,
        val renderEnd: Int,
    )

    private data class PagedTextSelectionMapping(
        val selection: ReaderTextSelection,
        val highlightRange: ReaderTextHighlightRange,
    )

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator {
        resetBookStateForOpen()
        return withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "epub_${uri.hashCode()}.epub")
            if (!tmp.exists()) {
                context.contentResolver.openInputStream(uri)?.use { src ->
                    tmp.outputStream().use { dst -> src.copyTo(dst) }
                }
            }
            epubFile = tmp
            imageBoundsCache.clear()
            cacheJob?.cancel()
            cacheJob = SupervisorJob()
            cacheScope = CoroutineScope(cacheJob!! + Dispatchers.IO)
            val book = EpubParser().parseLazyBook(tmp)
            lazyBook = book
            book.prefetchAroundParagraph(0)
            paras = book.paras
            internalLinkTargetIndexes = epubInternalLinkTargetIndexes(book.spinePaths, paras, book.fragmentTargetIndexes)
            spineCharCounts = epubSpineCharCounts(paras)
            pagedSlices = buildPagedSlices()
            chapterBoundaries = buildChapterBoundaries(paras)
            displayBlockCount = book.blockCount
            val initial = epubLocatorForIndex(paras, index = 0)
            withContext(Dispatchers.Main) {
                updatePageCount()
                _chapterInfo.value = ChapterInfo(0, chapterBoundaries.size, chapterBoundaries.firstOrNull()?.title ?: "", 0f)
                _tableOfContents.value = book.tableOfContents.ifEmpty { buildToc(chapterBoundaries, paras.size) }
                _currentLocator.value = initial
            }
            initial
        }
    }

    override fun createView(): View {
        if (flowEngineEnabled) return createFlowView()
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            val palette = paletteFor(themeMode, resources.configuration)
            adapter = EpubParaAdapter(
                blockCount = displayBlockCount,
                blockProvider = { index -> lazyBook?.blockAt(index) },
                imageLoader = { href -> epubFile?.let { decodeEpubImage(it, href) } },
                onLinkClick = ::handleLinkClick,
                onTextSelectionChanged = ::updateTextSelection,
                highlightRangesProvider = ::highlightRangesForParagraph,
                fontSizeSp = fontSizeSp,
                lineSpacingMultiplier = lineSpacingMultiplier,
                inkColor = palette.ink,
                codeBlockBgColor = codeBlockBgFor(themeMode, resources.configuration),
            )
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            clipToPadding = false
            val padV = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = reportProgression(rv)
            })
        }.also { recyclerView = it }
    }

    // ---- Continuous-flow rendering (方案 C) ----------------------------------------------------

    private fun createFlowView(): View {
        val palette = paletteFor(themeMode, context.resources.configuration)
        val view = EpubFlowView(
            context = context,
            onTapZone = ::handleFlowTapZone,
            onTopOffsetChanged = ::handleFlowTopOffsetChanged,
            onSelectionRange = { start, end -> updateFlowSelection(start, end) },
        ).apply {
            mode = if (_pagingKind.value == PagingKind.PAGED) EpubFlowView.Mode.PAGED else EpubFlowView.Mode.SCROLL
            flipStyle = this@EpubReflowEngine.flipStyle
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            textView.setTextColor(palette.ink)
            val padH = (PAGE_HORIZONTAL_PADDING_DP * context.resources.displayMetrics.density).toInt()
            val padV = (PAGE_VERTICAL_PADDING_DP * context.resources.displayMetrics.density).toInt()
            textView.setPadding(padH, padV, padH, padV)
            textView.typeface = dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            applyFlowLineSpacing(textView)
        }
        flowView = view
        val startIdx = epubIndexFromLocator(_currentLocator.value, paras.size)
        loadFlowChapter(spineIndexForParagraph(startIdx), restoreToParagraph = startIdx)
        return view
    }

    private fun spineIndexForParagraph(paragraphIndex: Int): Int =
        paras.getOrNull(paragraphIndex.coerceIn(0, (paras.size - 1).coerceAtLeast(0)))?.spineIndex ?: 0

    private fun flowColumnWidthPx(): Int {
        val metrics = context.resources.displayMetrics
        val padH = (PAGE_HORIZONTAL_PADDING_DP * metrics.density * 2).toInt()
        return (metrics.widthPixels - padH).coerceAtLeast(1)
    }

    private fun flowPageHeightPx(): Int {
        val metrics = context.resources.displayMetrics
        val padV = (PAGE_VERTICAL_PADDING_DP * metrics.density * 2).toInt()
        return (metrics.heightPixels - padV).coerceAtLeast(1)
    }

    /**
     * First-line indent for body paragraphs (Moon+ 首行缩进 = 2 CJK char widths). A CJK glyph's advance
     * ≈ the font size, so 2 chars ≈ 2 × fontSizePx. Scales with the user's font size so the indent stays
     * proportional. Used by the flow Spannable for plain body paragraphs only (see applyTextSpans).
     */
    private fun flowFirstLineIndentPx(density: Float): Int =
        (fontSizeSp * density * 2f).toInt().coerceAtLeast(0)

    /**
     * Applies the flow TextView's line spacing as ADDITIVE leading (spacingAdd, mult = 1) rather than a
     * multiplier. For a text line the resulting height is identical — spacingAdd = (mult − 1) × fontH,
     * so `fontH + spacingAdd == fontH × mult` — leaving pagination geometry and the descender-bleed
     * guard unchanged. But an image line is no longer scaled to `imageH × mult`: a multiplier inflated a
     * full-page image's line box ~1.75×, pushing it past one viewport and (with ALIGN_CENTER) padding
     * ~0.37×imageH of blank above/below the image, so parking at the line top showed blank + the image's
     * top half cut off ("插图一翻就是后半段"). Additive leading makes the box `imageH + spacingAdd`,
     * which fits one page. Must run AFTER textSize + typeface are set (fontMetrics depend on both).
     */
    private fun applyFlowLineSpacing(tv: TextView) {
        val fm = tv.paint.fontMetricsInt
        val fontHeight = (fm.descent - fm.ascent).coerceAtLeast(1)
        val spacingAdd = ((lineSpacingMultiplier - 1f) * fontHeight).coerceAtLeast(0f)
        tv.setLineSpacing(spacingAdd, 1f)
    }

    /** Builds and installs the chapter [spineIndex] into the flow view, optionally restoring a paragraph. */
    private fun loadFlowChapter(spineIndex: Int, restoreToParagraph: Int? = null, landOnLast: Boolean = false) {
        val view = flowView ?: return
        val book = lazyBook ?: return
        book.prefetchAroundParagraph(paras.indexOfFirst { it.spineIndex == spineIndex }.coerceAtLeast(0))
        val blocks = book.layoutBlocks().filter {
            paras.getOrNull(it.paragraphIndex)?.spineIndex == spineIndex
        }
        val flow = epubBuildChapterFlow(spineIndex, blocks)
        flowSpineIndex = spineIndex
        val density = context.resources.displayMetrics.density
        val palette = paletteFor(themeMode, context.resources.configuration)
        val style = EpubFlowStyle(
            fontSizeSp = fontSizeSp,
            lineSpacingMultiplier = lineSpacingMultiplier,
            inkColor = palette.ink,
            typeface = dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId),
            columnWidthPx = flowColumnWidthPx(),
            imageMaxHeightPx = flowPageHeightPx(),
            density = density,
            firstLineIndentPx = flowFirstLineIndentPx(density),
        )
        val theme = MarkwonTheme.create(context)
        val fullPageHrefs = flowFullPageImageHrefs(flow)
        val inlineMaxHeightPx = (INLINE_IMAGE_MAX_HEIGHT_DP * density).toInt()
        // Full-page images must fit one MEASURED page; the view knows its real viewport (screen minus
        // system bars + padding), the engine's screen estimate is ~100px too tall → cover spilled onto
        // a blank 2nd page. Read it lazily at decode time, falling back to the estimate pre-measure.
        val pageHeightProvider = {
            (flowView?.usablePageImageHeightPx() ?: 0).takeIf { it > 0 } ?: flowPageHeightPx()
        }
        val loader = EpubFlowImageLoader(
            epubFileProvider = { epubFile },
            executor = flowExecutor,
            columnWidthPx = flowColumnWidthPx(),
            pageHeightProvider = pageHeightProvider,
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = flowColumnWidthPx(),
            pageHeightProvider = pageHeightProvider,
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
        val spannable = epubBuildFlowSpannable(
            context = context,
            flow = flow,
            style = style,
            markwonTheme = theme,
            imageLoader = loader,
            imageSizeResolver = resolver,
            onLinkClick = ::handleLinkClick,
            highlightRanges = flowHighlightRanges(flow),
        )
        val restoreOffset = restoreToParagraph?.let { flow.offsetForParagraph(it, 0) }
        view.setChapter(flow, spannable, flowPageHeightPx(), restoreOffset = restoreOffset, landOnLast = landOnLast)
        // Schedule async images after the layout pass; positioning is now done inside setChapter's own
        // post (single pre-paint placement — no chapter-top→resume jump on entry).
        view.textView.post {
            AsyncDrawableScheduler.schedule(view.textView)
        }
        flowCurrentFlow = flow
    }

    private var flowCurrentFlow: EpubChapterFlow? = null

    /**
     * Hrefs of the chapter's full-page illustrations (covers/彩插), by the same intrinsic-pixel gate
     * the legacy paged path uses ([FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX]). The flow image loader/resolver
     * fit these to the whole viewport (upscaling allowed) so a cover fills the page; everything else
     * stays inline and column-capped. Bounds come from the cache (decoded once, no pixel data).
     */
    private fun flowFullPageImageHrefs(flow: EpubChapterFlow): Set<String> {
        val result = HashSet<String>()
        flow.segments.forEach { seg ->
            val block = seg.block
            if (block is EpubDisplayBlock.Image) {
                val bounds = epubImageBoundsFor(block.href) ?: return@forEach
                if (maxOf(bounds.width, bounds.height) >= FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX) {
                    result += block.href
                }
            }
        }
        return result
    }

    private fun flowHighlightRanges(flow: EpubChapterFlow): List<ReaderTextHighlightRange> {
        if (textAnnotations.isEmpty()) return emptyList()
        val result = ArrayList<ReaderTextHighlightRange>()
        flow.segments.forEach { seg ->
            if (seg.block !is EpubDisplayBlock.Text) return@forEach
            epubHighlightRanges(paras, seg.paragraphIndex, textAnnotations).forEach { r ->
                result += ReaderTextHighlightRange(seg.layoutStart + r.start, seg.layoutStart + r.end, r.color)
            }
        }
        return result
    }

    private fun handleFlowTapZone(zone: EpubFlowTapZone) {
        when (zone) {
            EpubFlowTapZone.PREV -> advanceFlowPage(-1)
            EpubFlowTapZone.NEXT -> advanceFlowPage(1)
            EpubFlowTapZone.MENU -> pageRequestCallback?.invoke(-1) // -1 = toggle controls (host convention)
        }
    }

    /** The spine indices that actually carry layout content, in reading order. */
    private fun flowSpineOrder(): List<Int> =
        paras.map { it.spineIndex }.distinct().sorted()

    /** Spine adjacent to [spineIndex] in [delta] direction, or null past the book boundary. */
    private fun adjacentSpine(spineIndex: Int, delta: Int): Int? {
        val order = flowSpineOrder()
        val pos = order.indexOf(spineIndex)
        if (pos < 0) return null
        val next = pos + delta
        return order.getOrNull(next)
    }

    private fun firstParagraphOfSpine(spineIndex: Int): Int =
        paras.indexOfFirst { it.spineIndex == spineIndex }.coerceAtLeast(0)

    /**
     * Turns one page in [delta] direction. Within a chapter the flow view scrolls; at a chapter
     * boundary it loads the adjacent spine — forward lands on its first page, backward on its last
     * (Phase 4 cross-chapter continuity).
     */
    private fun advanceFlowPage(delta: Int) {
        val view = flowView ?: return
        if (view.goToAdjacentPage(delta)) return
        val target = adjacentSpine(flowSpineIndex, delta) ?: return
        if (delta > 0) {
            loadFlowChapter(target, restoreToParagraph = firstParagraphOfSpine(target))
        } else {
            loadFlowChapter(target, landOnLast = true)
        }
    }

    private fun handleFlowTopOffsetChanged(layoutOffset: Int) {
        val flow = flowCurrentFlow ?: return
        val (paragraphIndex, paraOffset) = flow.paragraphAtOffset(layoutOffset) ?: return
        warmCacheAround(paragraphIndex)
        _currentLocator.value = epubLocatorForOffset(paras, paragraphIndex, paraOffset)
        updateChapterInfo(paragraphIndex)
        _pageCount.value = flowView?.pageCount() ?: _pageCount.value
    }

    private fun updateFlowSelection(start: Int, end: Int) {
        val flow = flowCurrentFlow ?: return
        if (start == end) { _currentTextSelection.value = null; return }
        val (sPara, sOff) = flow.paragraphAtOffset(minOf(start, end)) ?: return
        val (ePara, eOff) = flow.paragraphAtOffset(maxOf(start, end)) ?: return
        val startLoc = epubLocatorForOffset(paras, sPara, sOff)
        val endLoc = epubLocatorForOffset(paras, ePara, eOff)
        val selectedText = flow.text.substring(
            minOf(start, end).coerceIn(0, flow.text.length),
            maxOf(start, end).coerceIn(0, flow.text.length),
        )
        _currentTextSelection.value = ReaderTextSelection(startLoc, endLoc, selectedText)
    }

    override suspend fun goToAdjacentPage(delta: Int) {
        withContext(Dispatchers.Main) { advanceFlowPage(delta) }
    }

    override fun createPageView(pageIndex: Int): View {
        val pages = pagedSlices.ifEmpty { buildPagedSlices().also { pagedSlices = it } }
        val total = pages.size.coerceAtLeast(1)
        val safePageIndex = pageIndex.coerceIn(0, total - 1)
        val slice = pages.getOrNull(safePageIndex) ?: EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 0)
        if (slice.elements.isNotEmpty()) {
            return createCompositePageView(slice, safePageIndex, total)
        }
        if (slice.kind is EpubPageSliceKind.Image) {
            return createImagePageView(slice, safePageIndex, total)
        }
        val pageText = pageTextFor(slice)
        val highlightRanges = highlightRangesForPageSlice(slice)
        val palette = paletteFor(themeMode, context.resources.configuration)
        val pageProgressDescription = "第 ${safePageIndex + 1} 页，共 $total 页"
        val composeSelectionHighlightState = mutableStateOf<ReaderTextHighlightRange?>(null)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            setBackgroundColor(palette.paper)
            tag = slice
            setTag(R.id.epub_compose_text_surface, pageText)
            setTag(R.id.epub_compose_text_surface_visible, true)
            setTag(R.id.epub_compose_text_highlight_ranges, highlightRanges)
            setTag(R.id.epub_compose_text_links, slice.links)
            setTag(R.id.epub_selection_overlay_view, null)
            setTag(R.id.epub_selection_overlay_visible, false)
            setTag(R.id.epub_selection_overlay_behind_compose_text, false)
            setTag(R.id.epub_selection_bridge_hosted_in_compose_tree, false)
            setTag(R.id.epub_selection_overlay_accessibility_hidden, true)
            setTag(R.id.epub_compose_text_selection_enabled, true)
            setTag(R.id.epub_compose_text_semantics_exposed, true)
            applyComposePageAccessibilityProgress(pageProgressDescription)
            bindEpubComposeTextPage(pageText, slice, highlightRanges, palette, composeSelectionHighlightState)
        }
        return composeView.also { trackPageView(it, safePageIndex, slice, composeSelectionHighlightState) }
    }

    private fun createImagePageView(slice: EpubPageSlice, pageIndex: Int, total: Int): View {
        val image = slice.kind as EpubPageSliceKind.Image
        val palette = paletteFor(themeMode, context.resources.configuration)
        val density = context.resources.displayMetrics.density
        val placement = epubImagePlacementFor(slice, pageIndex)
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                when (placement) {
                    EpubImagePlacement.FullPage -> FrameLayout.LayoutParams.MATCH_PARENT
                    EpubImagePlacement.Inline -> FrameLayout.LayoutParams.WRAP_CONTENT
                },
                when (placement) {
                    EpubImagePlacement.FullPage -> FrameLayout.LayoutParams.MATCH_PARENT
                    EpubImagePlacement.Inline -> FrameLayout.LayoutParams.WRAP_CONTENT
                },
                Gravity.CENTER,
            ).apply {
                // Cap inline images for readability; full-page (cover) fills the page.
                if (placement == EpubImagePlacement.Inline) {
                    maxWidth = (MAX_LINE_WIDTH_DP * density).toInt()
                }
            }
            adjustViewBounds = placement == EpubImagePlacement.Inline
            if (placement == EpubImagePlacement.Inline) {
                maxHeight = (INLINE_IMAGE_MAX_HEIGHT_DP * density).toInt()
            }
            minimumHeight = (48 * density).toInt()
            val edgePadding = when (placement) {
                EpubImagePlacement.FullPage -> 0
                EpubImagePlacement.Inline -> 28
            }
            val verticalPadding = when (placement) {
                EpubImagePlacement.FullPage -> 0
                EpubImagePlacement.Inline -> 24
            }
            setPadding(
                (edgePadding * density).toInt(),
                (verticalPadding * density).toInt(),
                (edgePadding * density).toInt(),
                (verticalPadding * density).toInt(),
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setTag(R.id.epub_image_placement, placement)
            contentDescription = imagePageContentDescription(image, pageIndex, total)
            setImageBitmap(epubFile?.let { decodeEpubImage(it, image.href) })
        }
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(palette.paper)
            tag = slice
            addView(imagePageContent(slice, imageView, palette, density))
        }.also { trackImagePageView(it, pageIndex, slice) }
    }

    // When a heading immediately precedes an image (4-koma section title → panel, colophon →
    // publisher logo) the layout rides the orphan heading onto the image page as a caption
    // (EpubPageMapping Image branch). Render it as a heading line stacked above the image so the
    // heading is never isolated on a single-line page. No caption → return the image view as-is.
    private fun imagePageContent(
        slice: EpubPageSlice,
        imageView: ImageView,
        palette: ReaderPalette,
        density: Float,
    ): View {
        val captionSegment = slice.textSegments.firstOrNull { it.textStyle.headingLevel != null }
            ?: return imageView
        // The caption text is captured at pagination time, when the heading's spine may not yet be
        // in the lazy cache (then textProvider returned ""). Resolve it live here so the heading
        // renders even when it was paginated before its spine loaded; fall back to the captured text.
        val captionText = captionSegment.text.takeIf { it.isNotBlank() }
            ?: lazyBook?.paragraphAt(captionSegment.paragraphIndex)?.text.orEmpty()
        if (captionText.isBlank()) return imageView
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            val paint = currentPageTextPaint(captionSegment.textStyle)
            addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = (16 * density).toInt() }
                    text = captionText
                    setTextColor(palette.ink)
                    textSize = paint.textSize / density
                    typeface = paint.typeface
                    gravity = Gravity.CENTER
                    val sidePadding = (28 * density).toInt()
                    setPadding(sidePadding, 0, sidePadding, 0)
                },
            )
            addView(imageView)
        }
    }

    // "非必要不分页": a COMPOSITE page stacks a packed run of text runs + small inline images in one
    // scrollable column so short text and avatars/logos no longer isolate onto their own pages. Text
    // runs render as selectable TextViews (links/cross-run selection are not wired here — these are
    // sparse illustration/colophon regions; the full Compose selection path stays on pure-text pages).
    private fun createCompositePageView(slice: EpubPageSlice, pageIndex: Int, total: Int): View {
        val palette = paletteFor(themeMode, context.resources.configuration)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        populateCompositeColumn(column, slice, pageIndex, total, palette)
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(palette.paper)
            tag = slice
            addView(column)
        }.also { trackCompositePageView(it, pageIndex, slice) }
    }

    private fun populateCompositeColumn(
        column: LinearLayout,
        slice: EpubPageSlice,
        pageIndex: Int,
        total: Int,
        palette: ReaderPalette,
    ) {
        column.removeAllViews()
        val density = context.resources.displayMetrics.density
        val sidePadding = (PAGE_HORIZONTAL_PADDING_DP * density).toInt()
        slice.elements.forEachIndexed { index, element ->
            when (element) {
                is EpubPageElement.Text -> {
                    val segment = element.segment
                    val fullText = lazyBook?.paragraphAt(segment.paragraphIndex)?.text ?: segment.text
                    val start = segment.startOffset.coerceIn(0, fullText.length)
                    val end = segment.endOffset.coerceIn(0, fullText.length).coerceAtLeast(start)
                    val paint = currentPageTextPaint(segment.textStyle)
                    column.addView(
                        TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { if (index > 0) topMargin = (8 * density).toInt() }
                            text = fullText.substring(start, end)
                            setTextColor(palette.ink)
                            textSize = paint.textSize / density
                            typeface = paint.typeface
                            setLineSpacing(0f, lineSpacingMultiplier)
                            gravity = if (segment.textStyle.headingLevel != null) Gravity.CENTER else Gravity.START
                            setPadding(sidePadding, 0, sidePadding, 0)
                            setTextIsSelectable(true)
                        },
                    )
                }
                is EpubPageElement.Image -> {
                    column.addView(
                        ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.CENTER_HORIZONTAL.toFloat(),
                            ).apply {
                                gravity = Gravity.CENTER_HORIZONTAL
                                topMargin = (INLINE_IMAGE_VERTICAL_PADDING_DP * density).toInt()
                                bottomMargin = (INLINE_IMAGE_VERTICAL_PADDING_DP * density).toInt()
                            }
                            adjustViewBounds = true
                            maxWidth = (MAX_LINE_WIDTH_DP * density).toInt()
                            maxHeight = (INLINE_IMAGE_MAX_HEIGHT_DP * density).toInt()
                            minimumHeight = (48 * density).toInt()
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = element.altText
                                ?.takeIf { it.isNotBlank() }
                                ?: "图片"
                            setImageBitmap(epubFile?.let { decodeEpubImage(it, element.href) })
                        },
                    )
                }
            }
        }
        column.contentDescription = "第 ${pageIndex + 1} 页，共 $total 页"
    }

    private fun trackCompositePageView(container: View, pageIndex: Int, slice: EpubPageSlice) {
        trackPageContainer(container) {
            activePagedCompositePages.remove(container)
        }
        activePagedCompositePages[container] = EpubPagedImagePageState(slice, pageIndex)
    }

    private fun rebindActiveCompositePage(
        container: View,
        palette: ReaderPalette = paletteFor(themeMode, context.resources.configuration),
    ) {
        val pageState = activePagedCompositePages[container] ?: return
        val total = pagedSlices.size.coerceAtLeast(1)
        val pageIndex = pageState.pageIndex.coerceIn(0, total - 1)
        val slice = pagedSlices.getOrNull(pageIndex) ?: pageState.slice
        if (slice.elements.isEmpty()) {
            // Pagination changed under us (font/spacing): this page is no longer composite. Drop it;
            // the host rebinds the holder via createPageView on the next bind.
            activePagedCompositePages.remove(container)
            container.setBackgroundColor(palette.paper)
            return
        }
        if (slice != pageState.slice || pageIndex != pageState.pageIndex) {
            activePagedCompositePages[container] = pageState.copy(slice = slice, pageIndex = pageIndex)
            container.tag = slice
        }
        container.setBackgroundColor(palette.paper)
        val column = (container as? FrameLayout)?.getChildAt(0) as? LinearLayout ?: return
        populateCompositeColumn(column, slice, pageIndex, total, palette)
    }


    // past page 1 to render small). Light-novel EPUBs carry two distinct image classes:
    //   - full-page illustrations / 彩插 / covers — large intrinsic pixels (~800px+ on the long side)
    //   - inline markers: chat avatars (~142px), character icons (~300-400px), footnote glyphs
    // Intrinsic pixels are the only book-agnostic signal: the sizing CSS lives in external
    // stylesheets the parser does not resolve. Large image -> FullPage; small -> Inline.
    // When bounds are undecodable, fall back to the structural rule (an image that is the sole
    // content of its spine is a full-page cover; an image flanked by text is inline).
    private fun epubImagePlacementFor(slice: EpubPageSlice, pageIndex: Int): EpubImagePlacement {
        val href = (slice.kind as? EpubPageSliceKind.Image)?.href
        val bounds = href?.let { epubImageBoundsFor(it) }
        if (bounds != null) {
            val longestSidePx = maxOf(bounds.width, bounds.height)
            return if (longestSidePx >= FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX) {
                EpubImagePlacement.FullPage
            } else {
                EpubImagePlacement.Inline
            }
        }
        // Undecodable bounds: fall back to the original structural rule. Only an image that is the
        // sole content of the first page of its spine is treated as a full-page cover.
        if (pageIndex != 0) return EpubImagePlacement.Inline
        val para = paras.getOrNull(slice.paragraphIndex) ?: return EpubImagePlacement.Inline
        val sameSpineHasTextBeforeOrAtImage = paras
            .take(slice.paragraphIndex + 1)
            .any { it.spineIndex == para.spineIndex && it.text.isNotBlank() }
        return if (sameSpineHasTextBeforeOrAtImage) {
            EpubImagePlacement.Inline
        } else {
            EpubImagePlacement.FullPage
        }
    }

    private fun epubImageBoundsFor(href: String): EpubImageBounds? {
        imageBoundsCache[href]?.let { return it.value }
        val file = epubFile ?: return null
        val bounds = decodeEpubImageBounds(file, href)
        imageBoundsCache[href] = EpubImageBoundsCacheEntry(bounds)
        return bounds
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
    }

    override fun pageIndexForLocator(locator: Locator): Int =
        epubPageIndexFromLocator(locator, pagedSlices, paras)

    private fun reportProgression(rv: RecyclerView) {
        val total = paras.size.takeIf { it > 0 } ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val firstBlock = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val firstParagraph = lazyBook?.blockAt(firstBlock)?.paragraphIndex ?: firstBlock
        val safeIndex = firstParagraph.coerceIn(0, total - 1)
        warmCacheAround(safeIndex)
        _currentLocator.value = epubLocatorForIndex(paras, safeIndex)
        updateChapterInfo(safeIndex)
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
        goTo(epubLocatorForIndex(paras, target.startInclusive))
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

    private suspend fun goToFlow(locator: Locator) {
        val idx = epubIndexFromLocator(locator, paras.size).coerceIn(0, (paras.size - 1).coerceAtLeast(0))
        val paraOffset = when (val s = locator.strategy) {
            is LocatorStrategy.Section -> (s.charOffset - (paras.getOrNull(idx)?.spineCharStart ?: 0)).coerceAtLeast(0)
            else -> 0
        }
        val targetSpine = spineIndexForParagraph(idx)
        withContext(Dispatchers.IO) { lazyBook?.prefetchAroundParagraph(idx) }
        withContext(Dispatchers.Main) {
            val view = flowView
            if (view == null) {
                _currentLocator.value = epubLocatorForOffset(paras, idx, paraOffset)
                updateChapterInfo(idx)
                return@withContext
            }
            if (targetSpine != flowSpineIndex) {
                loadFlowChapter(targetSpine, restoreToParagraph = idx)
            } else {
                val offset = flowCurrentFlow?.offsetForParagraph(idx, paraOffset) ?: 0
                view.goToOffset(offset)
            }
            _currentLocator.value = epubLocatorForOffset(paras, idx, paraOffset)
            updateChapterInfo(idx)
        }
    }

    override suspend fun goTo(locator: Locator) {
        if (flowEngineEnabled) { goToFlow(locator); return }
        val previousPageIndex = if (_pagingKind.value == PagingKind.PAGED) {
            epubPageIndexFromLocator(_currentLocator.value, pagedSlices, paras)
        } else {
            null
        }
        val pageIndex = if (_pagingKind.value == PagingKind.PAGED) {
            epubPageIndexFromLocator(locator, pagedSlices, paras)
        } else {
            null
        }
        val shouldClearPageSelection = previousPageIndex != null &&
            pageIndex != null &&
            pageIndex != previousPageIndex
        val idx = if (_pagingKind.value == PagingKind.PAGED && locator.strategy is LocatorStrategy.Page) {
            pagedSlices.getOrNull(pageIndex ?: 0)?.paragraphIndex ?: epubIndexFromLocator(locator, paras.size)
        } else {
            epubIndexFromLocator(locator, paras.size)
        }
        val target = if (_pagingKind.value == PagingKind.PAGED && pageIndex != null) {
            pagedSlices.getOrNull(pageIndex)?.let { epubLocatorForPageSlice(paras, it) }
                ?: epubLocatorForIndex(paras, idx)
        } else {
            epubLocatorForIndex(paras, idx)
        }
        withContext(Dispatchers.IO) {
            lazyBook?.prefetchAroundParagraph(idx)
        }
        val targetBlock = lazyBook?.blockIndexForParagraph(idx) ?: idx
        withContext(Dispatchers.Main) {
            if (shouldClearPageSelection) {
                clearTextSelection()
            }
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetBlock, 0)
            _currentLocator.value = target
            updateChapterInfo(idx)
            if (_pagingKind.value == PagingKind.PAGED) {
                pageRequestCallback?.invoke(pageIndex ?: epubPageIndexFromLocator(target, pagedSlices, paras))
            }
        }
    }

    override suspend fun search(query: String): List<Locator> = withContext(Dispatchers.IO) {
        epubSearchLocators(paras, query) { index -> lazyBook?.paragraphAt(index) }
    }

    override fun clearTextSelection() {
        _currentTextSelection.value = null
        clearActiveComposeSelectionRanges()
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        textAnnotations = annotations
        // Flow mode owns its own single-Spannable surface; refresh its highlight spans in place
        // (no reload, no repagination) so newly added annotations paint immediately.
        flowView?.let { view -> flowCurrentFlow?.let { flow -> view.refreshHighlights(flowHighlightRanges(flow)) } }
        (recyclerView?.adapter as? EpubParaAdapter)?.updateTextAnnotations()
        activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
    }

    private fun updateTextSelection(paragraphIndex: Int, start: Int, end: Int) {
        _currentTextSelection.value = epubTextSelection(paras, paragraphIndex, start, end) { index ->
            lazyBook?.paragraphAt(index)
        }
    }

    private fun updatePagedTextSelection(
        composeView: ComposeView,
        paragraphIndex: Int,
        slice: EpubPageSlice,
        start: Int,
        end: Int,
        selectionHighlightState: MutableState<ReaderTextHighlightRange?>,
    ) {
        val activePageState = activePagedTextPages[composeView] ?: return
        if (activePageState.slice != slice) return
        if (slice.textSegments.size > 1) {
            updateMultiSegmentPagedTextSelection(composeView, slice, start, end, selectionHighlightState)
            return
        }
        val pageTextLength = (slice.endOffset - slice.startOffset).coerceAtLeast(0)
        val pageText = pageTextFor(slice)
        val (selectionStart, selectionEnd) = epubSelectionRangeOnCodePointBoundaries(
            text = pageText,
            selectionStart = start.coerceIn(0, pageTextLength),
            selectionEnd = end.coerceIn(0, pageTextLength),
        )
        val paragraph = lazyBook?.paragraphAt(paragraphIndex) ?: paras.getOrNull(paragraphIndex)
        val trimmedParagraphRange = paragraph?.text?.let { text ->
            epubNormalizedTextSelectionRange(
                text = text,
                selectionStart = selectionStart + slice.startOffset,
                selectionEnd = selectionEnd + slice.startOffset,
            )
        }
        val textSelection = epubTextSelection(
            indexedParas = paras,
            paragraphIndex = paragraphIndex,
            selectionStart = selectionStart + slice.startOffset,
            selectionEnd = selectionEnd + slice.startOffset,
        ) { index -> lazyBook?.paragraphAt(index) }
        val composeSelectionRange = if (textSelection == null) {
            null
        } else {
            trimmedParagraphRange?.let { (trimmedStart, trimmedEnd) ->
                val localStart = (trimmedStart - slice.startOffset).coerceIn(0, pageTextLength)
                val localEnd = (trimmedEnd - slice.startOffset).coerceIn(0, pageTextLength)
                if (localStart < localEnd) localStart to localEnd else null
            }
        }
        _currentTextSelection.value = textSelection
        val composeSelectionHighlight = composeSelectionRange?.let { (rangeStart, rangeEnd) ->
            ReaderTextHighlightRange(
                start = rangeStart,
                end = rangeEnd,
                color = EpubComposeSelectionHighlightColor,
            )
        }
        composeView.setTag(
            R.id.epub_compose_text_selection_range,
            composeSelectionRange,
        )
        composeView.setTag(
            R.id.epub_compose_text_selection_highlight_range,
            composeSelectionHighlight,
        )
        composeView.setTag(
            R.id.epub_compose_text_annotated_string,
            epubComposeAnnotatedText(
                text = pageText,
                highlightRanges = highlightRangesForPageSlice(slice),
                links = slice.links,
                selectionHighlightRange = composeSelectionHighlight,
                linkClickHandler = composeLinkClickHandler(composeView, slice),
            ),
        )
        selectionHighlightState.value = composeSelectionHighlight
    }

    private fun updateMultiSegmentPagedTextSelection(
        composeView: ComposeView,
        slice: EpubPageSlice,
        start: Int,
        end: Int,
        selectionHighlightState: MutableState<ReaderTextHighlightRange?>,
    ) {
        val content = pageTextRenderContent(slice)
        val pageText = content.text
        val pageTextLength = pageText.length
        val (selectionStart, selectionEnd) = epubSelectionRangeOnCodePointBoundaries(
            text = pageText,
            selectionStart = start.coerceIn(0, pageTextLength),
            selectionEnd = end.coerceIn(0, pageTextLength),
        )
        val mappedSelection = multiSegmentTextSelection(
            content = content,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
        val textSelection = mappedSelection?.selection
        _currentTextSelection.value = textSelection
        val composeSelectionHighlight = mappedSelection?.highlightRange
        composeView.setTag(R.id.epub_compose_text_selection_range, composeSelectionHighlight?.let { it.start to it.end })
        composeView.setTag(R.id.epub_compose_text_selection_highlight_range, composeSelectionHighlight)
        composeView.setTag(
            R.id.epub_compose_text_annotated_string,
            epubComposeAnnotatedText(
                text = pageText,
                highlightRanges = highlightRangesForPageSlice(slice),
                links = slice.links,
                selectionHighlightRange = composeSelectionHighlight,
                linkClickHandler = composeLinkClickHandler(composeView, slice),
            ),
        )
        selectionHighlightState.value = composeSelectionHighlight
    }

    private fun multiSegmentTextSelection(
        content: PagedTextRenderContent,
        selectionStart: Int,
        selectionEnd: Int,
    ): PagedTextSelectionMapping? {
        val selectedParts = content.segments.mapNotNull { renderSegment ->
            val segmentLength = renderSegment.renderEnd - renderSegment.renderStart
            val localStart = (selectionStart - renderSegment.renderStart).coerceIn(0, segmentLength)
            val localEnd = (selectionEnd - renderSegment.renderStart).coerceIn(0, segmentLength)
            if (localStart >= localEnd) return@mapNotNull null
            val paragraphIndex = renderSegment.source.paragraphIndex
            val fullText = lazyBook?.paragraphAt(paragraphIndex)?.text ?: renderSegment.source.text
            val paragraphStart = renderSegment.source.startOffset + localStart
            val paragraphEnd = renderSegment.source.startOffset + localEnd
            val (trimmedStart, trimmedEnd) = epubNormalizedTextSelectionRange(
                text = fullText,
                selectionStart = paragraphStart,
                selectionEnd = paragraphEnd,
            ) ?: return@mapNotNull null
            PagedTextSelectionPart(
                paragraphIndex = paragraphIndex,
                paragraphStart = trimmedStart,
                paragraphEnd = trimmedEnd,
                renderStart = renderSegment.renderStart + trimmedStart - renderSegment.source.startOffset,
                renderEnd = renderSegment.renderStart + trimmedEnd - renderSegment.source.startOffset,
            )
        }
        val firstPart = selectedParts.firstOrNull() ?: return null
        val lastPart = selectedParts.last()
        val startLocator = locatorForTextSelectionOffset(firstPart.paragraphIndex, firstPart.paragraphStart) ?: return null
        val endLocator = locatorForTextSelectionOffset(lastPart.paragraphIndex, lastPart.paragraphEnd) ?: return null
        return PagedTextSelectionMapping(
            selection = ReaderTextSelection(
                start = startLocator,
                end = endLocator,
                selectedText = content.text.substring(firstPart.renderStart, lastPart.renderEnd),
            ),
            highlightRange = ReaderTextHighlightRange(
                start = firstPart.renderStart,
                end = lastPart.renderEnd,
                color = EpubComposeSelectionHighlightColor,
            ),
        )
    }

    private fun locatorForTextSelectionOffset(paragraphIndex: Int, paragraphOffset: Int): Locator? {
        val para = paras.getOrNull(paragraphIndex) ?: return null
        val totalChars = epubTotalChars(paras).coerceAtLeast(1).toFloat()
        val documentOffset = para.documentCharStart + paragraphOffset
        val totalProgression = (documentOffset.toFloat() / totalChars).coerceIn(0f, 1f)
        return Locator(
            strategy = LocatorStrategy.Section(
                spineIndex = para.spineIndex,
                elementIndex = paragraphIndex,
                charOffset = para.spineCharStart + paragraphOffset,
            ),
            progression = totalProgression,
            totalProgression = totalProgression,
        )
    }

    private fun resetBookStateForOpen() {
        cacheJob?.cancel()
        cacheJob = null
        cacheScope = null
        flowView?.textView?.let { runCatching { AsyncDrawableScheduler.unschedule(it) } }
        flowView = null
        flowCurrentFlow = null
        flowSpineIndex = -1
        activePagedTextPages.keys.toList().forEach { composeView ->
            composeView.clearEpubComposeTextPageForBookReset()
        }
        activePageContainers.toList().forEach { container ->
            container.clearEpubImagePageForBookReset()
        }
        activePageContainers.clear()
        activePagedTextPages.clear()
        activePagedImagePages.clear()
        activePagedCompositePages.clear()
        lazyBook?.close()
        lazyBook = null
        recyclerView = null
        paras = emptyList()
        displayBlockCount = 0
        epubFile = null
        internalLinkTargetIndexes = emptyMap()
        spineCharCounts = emptyList()
        pagedSlices = emptyList()
        chapterBoundaries = emptyList()
        textAnnotations = emptyList()
        _currentTextSelection.value = null
        _currentLocator.value = Locator(LocatorStrategy.Unknown)
        _chapterInfo.value = ChapterInfo(0, 1, "", 0f)
        _tableOfContents.value = emptyList()
        _pageCount.value = 0
    }

    private fun highlightRangesForParagraph(paragraphIndex: Int) =
        epubHighlightRanges(paras, paragraphIndex, textAnnotations)

    override suspend fun close() {
        recyclerView = null
        pageRequestCallback = null
        flowView?.textView?.let { runCatching { AsyncDrawableScheduler.unschedule(it) } }
        flowView = null
        flowCurrentFlow = null
        flowSpineIndex = -1
        cacheJob?.cancel()
        cacheJob = null
        cacheScope = null
        activePagedTextPages.keys.toList().forEach { composeView ->
            composeView.clearEpubComposeTextPageForBookReset()
        }
        activePageContainers.toList().forEach { container ->
            container.clearEpubImagePageForBookReset()
        }
        activePageContainers.clear()
        activePagedTextPages.clear()
        activePagedImagePages.clear()
        activePagedCompositePages.clear()
        lazyBook?.close()
        lazyBook = null
        paras = emptyList()
        displayBlockCount = 0
        epubFile = null
        internalLinkTargetIndexes = emptyMap()
        spineCharCounts = emptyList()
        pagedSlices = emptyList()
        chapterBoundaries = emptyList()
        _currentTextSelection.value = null
        textAnnotations = emptyList()
        _currentLocator.value = Locator(LocatorStrategy.Unknown)
        _chapterInfo.value = ChapterInfo(0, 1, "", 0f)
        _tableOfContents.value = emptyList()
        _pageCount.value = 0
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        if (flowEngineEnabled) { rebuildFlowChapter(); return }
        rebuildPagedSlices()
        clearTextSelection()
        (recyclerView?.adapter as? EpubParaAdapter)?.updateFontSize(sp)
        activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
        activePagedImagePages.keys.toList().forEach(::rebindActiveImagePage)
        activePagedCompositePages.keys.toList().forEach(::rebindActiveCompositePage)
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        if (flowEngineEnabled) { rebuildFlowChapter(); return }
        rebuildPagedSlices()
        clearTextSelection()
        (recyclerView?.adapter as? EpubParaAdapter)?.updateLineSpacing(multiplier)
        activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
        activePagedImagePages.keys.toList().forEach(::rebindActiveImagePage)
        activePagedCompositePages.keys.toList().forEach(::rebindActiveCompositePage)
    }

    override suspend fun setPageFlipStyle(style: dev.readflow.core.model.PageFlipStyle) {
        flipStyle = style
        flowView?.flipStyle = style
    }

    override suspend fun setSerifFont(useSourceHan: Boolean) {
        this.useSourceHan = useSourceHan
        currentFontId = if (useSourceHan) "source_han" else "system"
        if (flowEngineEnabled) { rebuildFlowChapter(); return }
        // Rebind active Compose text pages to pick up new fontFamily
        withContext(Dispatchers.Main) {
            activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
            activePagedCompositePages.keys.toList().forEach(::rebindActiveCompositePage)
        }
        (recyclerView?.adapter as? EpubParaAdapter)?.notifyDataSetChanged()
    }

    override suspend fun setFont(fontId: String) {
        currentFontId = fontId
        useSourceHan = fontId == "source_han"
        if (flowEngineEnabled) { rebuildFlowChapter(); return }
        withContext(Dispatchers.Main) {
            activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
            activePagedCompositePages.keys.toList().forEach(::rebindActiveCompositePage)
        }
        (recyclerView?.adapter as? EpubParaAdapter)?.notifyDataSetChanged()
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val palette = paletteFor(mode, context.resources.configuration)
        if (flowEngineEnabled) {
            withContext(Dispatchers.Main) {
                flowView?.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
                flowView?.textView?.setTextColor(palette.ink)
                rebuildFlowChapter()
            }
            return
        }
        recyclerView?.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        (recyclerView?.adapter as? EpubParaAdapter)?.updateInkColor(palette.ink)
        (recyclerView?.adapter as? EpubParaAdapter)?.updateCodeBlockBgColor(codeBlockBgFor(mode, context.resources.configuration))
        activePageContainers.forEach { it.setBackgroundColor(palette.paper) }
        activePagedTextPages.keys.toList().forEach { rebindActiveComposeTextPage(it, palette) }
        activePagedCompositePages.keys.toList().forEach { rebindActiveCompositePage(it, palette) }
    }

    /** Rebuilds the current flow chapter's Spannable in place, preserving reading position. */
    private suspend fun rebuildFlowChapter() {
        withContext(Dispatchers.Main) {
            val view = flowView ?: return@withContext
            val anchorParagraph = epubIndexFromLocator(_currentLocator.value, paras.size)
            view.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            view.textView.typeface = dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)
            applyFlowLineSpacing(view.textView)
            if (flowSpineIndex >= 0) loadFlowChapter(flowSpineIndex, restoreToParagraph = anchorParagraph)
        }
    }

    override suspend fun setMode(mode: ReadingMode) {
        val targetKind = when (mode) {
            ReadingMode.SCROLL -> PagingKind.CONTINUOUS
            ReadingMode.PAGED -> PagingKind.PAGED
        }
        if (flowEngineEnabled) {
            withContext(Dispatchers.Main) {
                _pagingKind.value = targetKind
                flowView?.let { view ->
                    val anchor = epubIndexFromLocator(_currentLocator.value, paras.size)
                    view.mode = if (targetKind == PagingKind.PAGED) EpubFlowView.Mode.PAGED else EpubFlowView.Mode.SCROLL
                    val offset = flowCurrentFlow?.offsetForParagraph(anchor, 0) ?: 0
                    view.post { view.goToOffset(offset) }
                    _pageCount.value = view.pageCount()
                }
            }
            return
        }
        withContext(Dispatchers.Main) {
            val paragraphIndex = currentParagraphIndex()
            val shouldClearModeSelection = targetKind != _pagingKind.value
            if (shouldClearModeSelection) {
                clearTextSelection()
                if (targetKind == PagingKind.CONTINUOUS) {
                    activePagedTextPages.keys.toList().forEach { composeView ->
                        composeView.clearEpubComposeTextPageForBookReset()
                    }
                    activePageContainers.toList().forEach { container ->
                        container.clearEpubImagePageForBookReset()
                    }
                    activePagedTextPages.clear()
                    activePageContainers.clear()
                    activePagedImagePages.clear()
                    activePagedCompositePages.clear()
                }
            }
            _pagingKind.value = targetKind
            _currentLocator.value = epubLocatorForIndex(paras, paragraphIndex)
            updatePageCount()
            if (targetKind == PagingKind.PAGED) {
                pageRequestCallback?.invoke(epubPageIndexFromLocator(_currentLocator.value, pagedSlices, paras))
            }
        }
    }

    private fun currentParagraphIndex(): Int =
        if (_pagingKind.value == PagingKind.PAGED && _currentLocator.value.strategy is LocatorStrategy.Page) {
            pagedSlices.getOrNull(epubPageIndexFromLocator(_currentLocator.value, pagedSlices, paras))?.paragraphIndex ?: 0
        } else {
            epubIndexFromLocator(_currentLocator.value, paras.size)
        }

    private fun handleLinkClick(link: EpubTextLink) {
        if (link.isExternal) {
            clearTextSelection()
            openExternalLink(link.href)
        } else {
            goToInternalLink(link.href)
        }
    }

    private fun openExternalLink(href: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun goToInternalLink(href: String) {
        val key = epubInternalLinkTargetKey(href)
        if (key.isEmpty()) return
        val targetPosition = internalLinkTargetIndexes[key] ?: return
        val target = epubLocatorForTarget(paras, targetPosition)
        if (_pagingKind.value == PagingKind.PAGED) {
            clearTextSelection()
            _currentLocator.value = target
            updateChapterInfo(targetPosition.paragraphIndex)
            pageRequestCallback?.invoke(epubPageIndexFromLocator(target, pagedSlices, paras))
            return
        }
        recyclerView?.post {
            val targetBlock = lazyBook?.blockIndexForParagraph(targetPosition.paragraphIndex)
                ?: targetPosition.paragraphIndex
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetBlock, 0)
            _currentLocator.value = target
            updateChapterInfo(targetPosition.paragraphIndex)
        }
    }

    private fun buildPagedSlices(): List<EpubPageSlice> =
        currentPageLineMeasurer().let { measurer ->
            val metrics = currentPageMetrics()
            epubPagedLayoutWithBlocks(
                paras = paras,
                textProvider = { index -> lazyBook?.cachedParagraphAt(index)?.text.orEmpty() },
                blockProvider = { lazyBook?.layoutBlocks().orEmpty() },
                metrics = metrics,
                lineBreaker = { text, contentWidth, textStyle ->
                    measurer.measure(text, contentWidth, textStyle)
                },
                measurement = measurer.measurement,
                inlineImageLineCost = { href -> inlineImageLineCost(href, metrics) },
            )
        }

    // "非必要不分页": small (inline-class) images flow with text on a shared page. Return the image's
    // estimated rendered height as a count of text-line units so the packer can budget it; return
    // null for large (full-page) images, which keep their own standalone page. Undecodable bounds →
    // null (treated full-page) to preserve the safe legacy behavior.
    private fun inlineImageLineCost(href: String, metrics: EpubPageMetrics): Int? {
        val bounds = epubImageBoundsFor(href) ?: return null
        if (maxOf(bounds.width, bounds.height) >= FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX) return null
        val density = context.resources.displayMetrics.density
        val contentWidthPx = (metrics.viewportWidthPx - metrics.horizontalPaddingPx).coerceAtLeast(1)
        // Mirror the inline ImageView constraints: width capped at MAX_LINE_WIDTH_DP and the content
        // width, height capped at INLINE_IMAGE_MAX_HEIGHT_DP, FIT_CENTER preserving aspect ratio.
        val maxWidthPx = minOf((MAX_LINE_WIDTH_DP * density), contentWidthPx.toFloat()).coerceAtLeast(1f)
        val maxHeightPx = (INLINE_IMAGE_MAX_HEIGHT_DP * density).coerceAtLeast(1f)
        val aspect = bounds.height.toFloat() / bounds.width.toFloat().coerceAtLeast(1f)
        val drawnHeightPx = minOf(maxWidthPx * aspect, maxHeightPx)
        // Inline image vertical padding (24dp top + 24dp bottom in createImagePageView).
        val totalHeightPx = drawnHeightPx + (2 * INLINE_IMAGE_VERTICAL_PADDING_DP * density)
        val lineHeightPx = metrics.lineHeightPx.coerceAtLeast(1f)
        return (totalHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
    }

    private fun currentPageLineMeasurer(): EpubPageLineMeasurer =
        pageLineMeasurer ?: EpubPageLineMeasurer.ComposeTextLayoutResult { text, contentWidth, textStyle ->
            currentComposeTextLayoutLines(text, contentWidth, textStyle)
        }

    private fun currentComposeTextLayoutLines(
        text: String,
        contentWidthPx: Int,
        textStyle: EpubPageTextStyle,
    ): List<EpubTextLayoutLineRange> {
        val measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(
                density = context.resources.displayMetrics.density,
                fontScale = context.resources.configuration.fontScale,
            ),
            defaultLayoutDirection = LayoutDirection.Ltr,
            cacheSize = 8,
        )
        val layout = measurer.measure(
            text = text,
            style = currentComposeTextStyle(textStyle),
            constraints = Constraints(maxWidth = contentWidthPx.coerceAtLeast(1)),
        )
        return (0 until layout.lineCount).map { line ->
            EpubTextLayoutLineRange(
                start = layout.getLineStart(line).coerceIn(0, text.length),
                end = layout.getLineEnd(line, visibleEnd = true).coerceIn(0, text.length),
            )
        }
    }

    private fun currentComposeTextStyle(style: EpubPageTextStyle): TextStyle {
        val headingBoost = epubHeadingBoost(style.headingLevel)
        return TextStyle(
            fontSize = (fontSizeSp + headingBoost).sp,
            lineHeight = (fontSizeSp * lineSpacingMultiplier).sp,
            fontFamily = when {
                style.kind == EpubTextKind.Preformatted || style.kind == EpubTextKind.Table -> FontFamily.Monospace
                else -> dev.readflow.core.ui.FontProvider.fontFamilyFor(context, currentFontId)
            },
            fontWeight = if (style.headingLevel != null) FontWeight.Bold else FontWeight.Normal,
        )
    }

    // Base style for the whole compose page surface. A packed page may mix a heading segment
    // with following body segments; we render the page at body size and restore the heading's
    // larger/bold appearance per-segment (see segmentStyleSpansFor), so body text packed under
    // a heading is not blown up to heading size.
    private fun composePageBaseStyle(slice: EpubPageSlice): EpubPageTextStyle =
        if (slice.textSegments.size > 1) {
            slice.textStyle.copy(headingLevel = null)
        } else {
            slice.textStyle
        }

    // Per-segment heading overrides applied over composePageBaseStyle. Only heading segments on a
    // multi-segment (packed) page need a span; single-segment pages already carry the right base.
    private fun segmentStyleSpansFor(slice: EpubPageSlice): List<EpubComposeSegmentStyleSpan> {
        if (slice.textSegments.size <= 1) return emptyList()
        val content = pageTextRenderContent(slice)
        return content.segments.mapNotNull { segment ->
            val level = segment.source.textStyle.headingLevel ?: return@mapNotNull null
            EpubComposeSegmentStyleSpan(
                start = segment.renderStart,
                end = segment.renderEnd,
                style = SpanStyle(
                    fontSize = (fontSizeSp + epubHeadingBoost(level)).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }

    private fun currentComposeTextLayout(style: EpubPageTextStyle): EpubComposeTextLayout {
        val leadingPadding = PAGE_HORIZONTAL_PADDING_DP + (style.indentLevel * 24) +
            if (style.kind == EpubTextKind.Blockquote) 18 else 0
        return EpubComposeTextLayout(
            paddingStartDp = leadingPadding,
            paddingEndDp = PAGE_HORIZONTAL_PADDING_DP,
            paddingTopDp = PAGE_VERTICAL_PADDING_DP,
            paddingBottomDp = PAGE_VERTICAL_PADDING_DP,
            horizontalScroll = style.kind == EpubTextKind.Preformatted,
        )
    }

    private fun rebuildPagedSlices() {
        pagedSlices = buildPagedSlices()
        updatePageCount()
        if (_pagingKind.value == PagingKind.PAGED) {
            pageRequestCallback?.invoke(epubPageIndexFromLocator(_currentLocator.value, pagedSlices, paras))
        }
    }

    private fun currentPageMetrics(): EpubPageMetrics {
        val metrics = context.resources.displayMetrics
        val horizontalPaddingPx = (PAGE_HORIZONTAL_PADDING_DP * metrics.density * 2).toInt()
        val verticalPaddingPx = (PAGE_VERTICAL_PADDING_DP * metrics.density * 2).toInt()
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, metrics)
        val averageCharacterWidthPx = currentPageTextPaint()
            .measureText(AVERAGE_PAGE_CHARACTER_SAMPLE)
            .div(AVERAGE_PAGE_CHARACTER_SAMPLE.length)
            .coerceAtLeast(1f)
        return EpubPageMetrics(
            viewportWidthPx = metrics.widthPixels.coerceAtLeast(1),
            viewportHeightPx = metrics.heightPixels.coerceAtLeast(1),
            horizontalPaddingPx = horizontalPaddingPx,
            verticalPaddingPx = verticalPaddingPx,
            averageCharacterWidthPx = averageCharacterWidthPx,
            lineHeightPx = (textSizePx * lineSpacingMultiplier).coerceAtLeast(1f),
        )
    }

    private fun currentPageTextPaint(style: EpubPageTextStyle = EpubPageTextStyle()): TextPaint {
        val metrics = context.resources.displayMetrics
        val headingBoost = epubHeadingBoost(style.headingLevel)
        return TextPaint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp + headingBoost, metrics)
            typeface = when {
                style.kind == EpubTextKind.Preformatted || style.kind == EpubTextKind.Table -> Typeface.MONOSPACE
                style.headingLevel != null -> Typeface.DEFAULT_BOLD
                else -> dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)
            }
        }
    }

    private fun updatePageCount() {
        _pageCount.value = when (_pagingKind.value) {
            PagingKind.CONTINUOUS -> paras.size
            PagingKind.PAGED -> pagedSlices.size
        }
    }

    private fun pageTextFor(slice: EpubPageSlice): String {
        if (slice.textSegments.isNotEmpty()) {
            return pageTextRenderContent(slice).text
        }
        val fullText = lazyBook?.paragraphAt(slice.paragraphIndex)?.text.orEmpty()
        val start = slice.startOffset.coerceIn(0, fullText.length)
        val end = slice.endOffset.coerceIn(0, fullText.length).coerceAtLeast(start)
        return fullText.substring(start, end)
    }

    private fun pageTextRenderContent(slice: EpubPageSlice): PagedTextRenderContent {
        val text = StringBuilder()
        val segments = mutableListOf<PagedTextRenderSegment>()
        slice.textSegments.forEachIndexed { index, segment ->
            if (index > 0) {
                text.append("\n\n")
            }
            val fullText = lazyBook?.paragraphAt(segment.paragraphIndex)?.text ?: segment.text
            val start = segment.startOffset.coerceIn(0, fullText.length)
            val end = segment.endOffset.coerceIn(0, fullText.length).coerceAtLeast(start)
            val renderStart = text.length
            text.append(fullText.substring(start, end))
            segments += PagedTextRenderSegment(
                source = segment,
                renderStart = renderStart,
                renderEnd = text.length,
            )
        }
        return PagedTextRenderContent(text = text.toString(), segments = segments)
    }

    private fun highlightRangesForPageSlice(slice: EpubPageSlice) =
        if (slice.textSegments.isNotEmpty()) {
            val content = pageTextRenderContent(slice)
            content.segments.flatMap { segment ->
                highlightRangesForParagraph(segment.source.paragraphIndex).mapNotNull { range ->
                    val start = maxOf(range.start, segment.source.startOffset)
                    val end = minOf(range.end, segment.source.endOffset)
                    if (start >= end) return@mapNotNull null
                    range.copy(
                        start = segment.renderStart + start - segment.source.startOffset,
                        end = segment.renderStart + end - segment.source.startOffset,
                    )
                }
            }
        } else {
            highlightRangesForParagraph(slice.paragraphIndex).mapNotNull { range ->
                val start = maxOf(range.start, slice.startOffset)
                val end = minOf(range.end, slice.endOffset)
                if (start >= end) return@mapNotNull null
                range.copy(start = start - slice.startOffset, end = end - slice.startOffset)
            }
        }

    private fun warmCacheAround(paragraphIndex: Int) {
        val book = lazyBook ?: return
        cacheScope?.launch {
            book.prefetchAroundParagraph(paragraphIndex)
        }
    }

    private fun trackPageView(
        container: ComposeView,
        pageIndex: Int,
        slice: EpubPageSlice,
        selectionHighlightState: MutableState<ReaderTextHighlightRange?>,
    ) {
        trackPageContainer(container) {
            activePagedTextPages.remove(container)
        }
        activePagedTextPages[container] = EpubPagedTextPageState(slice, pageIndex, selectionHighlightState)
    }

    private fun trackImagePageView(
        container: View,
        pageIndex: Int,
        slice: EpubPageSlice,
    ) {
        trackPageContainer(container) {
            activePagedImagePages.remove(container)
        }
        activePagedImagePages[container] = EpubPagedImagePageState(slice, pageIndex)
    }

    private fun rebindActiveComposeTextPage(
        composeView: ComposeView,
        palette: ReaderPalette = paletteFor(themeMode, context.resources.configuration),
    ) {
        val pageState = activePagedTextPages[composeView] ?: return
        val total = pagedSlices.size.coerceAtLeast(1)
        val pageIndex = pageState.pageIndex.coerceIn(0, total - 1)
        val slice = pagedSlices.getOrNull(pageIndex) ?: pageState.slice
        if (slice.kind is EpubPageSliceKind.Image) {
            activePagedTextPages.remove(composeView)
            composeView.retireEpubComposeTextPage(
                slice = slice,
                palette = palette,
                selectionHighlightState = pageState.selectionHighlightState,
            )
            return
        }
        if (slice != pageState.slice || pageIndex != pageState.pageIndex) {
            activePagedTextPages[composeView] = pageState.copy(slice = slice, pageIndex = pageIndex)
            composeView.tag = slice
        }
        composeView.applyComposePageAccessibilityProgress("第 ${pageIndex + 1} 页，共 $total 页")
        val pageText = pageTextFor(slice)
        val highlightRanges = highlightRangesForPageSlice(slice)
        composeView.bindEpubComposeTextPage(
            pageText = pageText,
            slice = slice,
            highlightRanges = highlightRanges,
            palette = palette,
            selectionHighlightState = pageState.selectionHighlightState,
        )
    }

    private fun rebindActiveImagePage(
        container: View,
        palette: ReaderPalette = paletteFor(themeMode, context.resources.configuration),
    ) {
        val pageState = activePagedImagePages[container] ?: return
        val total = pagedSlices.size.coerceAtLeast(1)
        val pageIndex = pageState.pageIndex.coerceIn(0, total - 1)
        val slice = pagedSlices.getOrNull(pageIndex) ?: pageState.slice
        if (slice.kind !is EpubPageSliceKind.Image) {
            activePagedImagePages.remove(container)
            container.tag = slice
            container.setBackgroundColor(palette.paper)
            container.clearEpubImagePageForBookReset()
            return
        }
        if (slice != pageState.slice || pageIndex != pageState.pageIndex) {
            activePagedImagePages[container] = pageState.copy(slice = slice, pageIndex = pageIndex)
        }
        container.tag = slice
        container.setBackgroundColor(palette.paper)
        val imageView = container.findEpubImageView() ?: return
        val placement = epubImagePlacementFor(slice, pageIndex)
        imageView.setTag(R.id.epub_image_placement, placement)
        imageView.contentDescription = imagePageContentDescription(slice.kind, pageIndex, total)
        imageView.setImageBitmap(epubFile?.let { decodeEpubImage(it, slice.kind.href) })
    }

    private fun clearActiveComposeSelectionRanges() {
        activePagedTextPages.forEach { (composeView, pageState) ->
            val pageText = pageTextFor(pageState.slice)
            composeView.setTag(R.id.epub_compose_text_selection_range, null)
            composeView.setTag(R.id.epub_compose_text_selection_highlight_range, null)
            composeView.setTag(
                R.id.epub_compose_text_annotated_string,
                epubComposeAnnotatedText(
                    text = pageText,
                    highlightRanges = highlightRangesForPageSlice(pageState.slice),
                    links = pageState.slice.links,
                    linkClickHandler = composeLinkClickHandler(composeView, pageState.slice),
                ),
            )
            pageState.selectionHighlightState.value = null
        }
    }

    private fun ComposeView.retireEpubComposeTextPage(
        slice: EpubPageSlice,
        palette: ReaderPalette,
        selectionHighlightState: MutableState<ReaderTextHighlightRange?>,
    ) {
        _currentTextSelection.value = null
        selectionHighlightState.value = null
        tag = slice
        setBackgroundColor(palette.paper)
        contentDescription = null
        setTag(R.id.epub_compose_page_progress_description, null)
        setTag(R.id.epub_compose_page_root_delegates_accessibility_to_text, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = null
        }
        setTag(R.id.epub_compose_text_surface, null)
        setTag(R.id.epub_compose_text_annotated_string, null)
        setTag(R.id.epub_compose_text_surface_visible, false)
        setTag(R.id.epub_compose_text_highlight_ranges, emptyList<ReaderTextHighlightRange>())
        setTag(R.id.epub_compose_text_links, emptyList<EpubTextLink>())
        setTag(R.id.epub_compose_text_link_callback, null)
        setTag(R.id.epub_compose_text_link_tap_wired, false)
        setTag(R.id.epub_compose_text_style, null)
        setTag(R.id.epub_compose_text_layout, null)
        setTag(R.id.epub_compose_text_selection_range, null)
        setTag(R.id.epub_compose_text_selection_highlight_range, null)
        setTag(R.id.epub_compose_text_selection_enabled, false)
        setTag(R.id.epub_compose_text_selection_callback, null)
        setTag(R.id.epub_compose_text_gesture_selection_wired, false)
        setTag(R.id.epub_compose_text_long_press_selection_wired, false)
        setTag(R.id.epub_compose_text_semantics_exposed, false)
        setTag(R.id.epub_selection_overlay_view, null)
        setTag(R.id.epub_selection_overlay_visible, false)
        setTag(R.id.epub_selection_overlay_behind_compose_text, false)
        setTag(R.id.epub_selection_bridge_hosted_in_compose_tree, false)
        setTag(R.id.epub_selection_overlay_accessibility_hidden, true)
        setContent {}
    }

    private fun ComposeView.clearEpubComposeTextPageForBookReset() {
        contentDescription = null
        setTag(R.id.epub_compose_page_progress_description, null)
        setTag(R.id.epub_compose_page_root_delegates_accessibility_to_text, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = null
        }
        setTag(R.id.epub_compose_text_surface, null)
        setTag(R.id.epub_compose_text_annotated_string, null)
        setTag(R.id.epub_compose_text_surface_visible, false)
        setTag(R.id.epub_compose_text_highlight_ranges, emptyList<ReaderTextHighlightRange>())
        setTag(R.id.epub_compose_text_links, emptyList<EpubTextLink>())
        setTag(R.id.epub_compose_text_link_callback, null)
        setTag(R.id.epub_compose_text_link_tap_wired, false)
        setTag(R.id.epub_compose_text_style, null)
        setTag(R.id.epub_compose_text_layout, null)
        setTag(R.id.epub_compose_text_selection_range, null)
        setTag(R.id.epub_compose_text_selection_highlight_range, null)
        setTag(R.id.epub_compose_text_selection_enabled, false)
        setTag(R.id.epub_compose_text_selection_callback, null)
        setTag(R.id.epub_compose_text_gesture_selection_wired, false)
        setTag(R.id.epub_compose_text_long_press_selection_wired, false)
        setTag(R.id.epub_compose_text_semantics_exposed, false)
        setTag(R.id.epub_selection_overlay_view, null)
        setTag(R.id.epub_selection_overlay_visible, false)
        setTag(R.id.epub_selection_overlay_behind_compose_text, false)
        setTag(R.id.epub_selection_bridge_hosted_in_compose_tree, false)
        setTag(R.id.epub_selection_overlay_accessibility_hidden, true)
        setContent {}
    }

    private fun View.clearEpubImagePageForBookReset() {
        if (this is ImageView) {
            contentDescription = null
            setImageDrawable(null)
        }
        val group = this as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            group.getChildAt(index).clearEpubImagePageForBookReset()
        }
    }

    private fun View.findEpubImageView(): ImageView? {
        if (this is ImageView) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findEpubImageView()?.let { return it }
        }
        return null
    }

    private fun imagePageContentDescription(
        image: EpubPageSliceKind.Image,
        pageIndex: Int,
        total: Int,
    ): String =
        image.altText
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it，第 ${pageIndex + 1} 页，共 $total 页" }
            ?: "图片，第 ${pageIndex + 1} 页，共 $total 页"

    private fun ComposeView.bindEpubComposeTextPage(
        pageText: String,
        slice: EpubPageSlice,
        highlightRanges: List<ReaderTextHighlightRange>,
        palette: ReaderPalette,
        selectionHighlightState: MutableState<ReaderTextHighlightRange?>,
    ) {
        val textLayout = currentComposeTextLayout(slice.textStyle)
        val pageLinks = pageLinksFor(slice)
        val baseStyle = composePageBaseStyle(slice)
        val segmentStyleSpans = segmentStyleSpansFor(slice)
        val annotatedText = epubComposeAnnotatedText(
            text = pageText,
            highlightRanges = highlightRanges,
            links = pageLinks,
            selectionHighlightRange = selectionHighlightState.value,
            linkClickHandler = composeLinkClickHandler(this, slice),
            segmentStyleSpans = segmentStyleSpans,
        )
        setTag(R.id.epub_compose_text_surface, pageText)
        setTag(R.id.epub_compose_text_annotated_string, annotatedText)
        setTag(R.id.epub_compose_text_surface_visible, true)
        setTag(R.id.epub_compose_text_highlight_ranges, highlightRanges)
        setTag(R.id.epub_compose_text_links, pageLinks)
        val composeLinkClickCallback = composeLinkClickHandler(this, slice)
        setTag(R.id.epub_compose_text_link_callback, composeLinkClickCallback)
        setTag(R.id.epub_compose_text_link_tap_wired, true)
        setTag(R.id.epub_compose_text_style, slice.textStyle)
        setTag(R.id.epub_compose_text_layout, textLayout)
        setTag(R.id.epub_selection_overlay_view, null)
        setTag(R.id.epub_selection_overlay_visible, false)
        setTag(R.id.epub_selection_overlay_behind_compose_text, false)
        setTag(R.id.epub_selection_bridge_hosted_in_compose_tree, false)
        setTag(R.id.epub_selection_overlay_accessibility_hidden, true)
        setTag(R.id.epub_compose_text_selection_enabled, true)
        setTag(R.id.epub_compose_text_semantics_exposed, true)
        val composeSelectionCallback: (Int, Int) -> Unit = { start, end ->
            updatePagedTextSelection(
                composeView = this@bindEpubComposeTextPage,
                paragraphIndex = slice.paragraphIndex,
                slice = slice,
                start = start,
                end = end,
                selectionHighlightState = selectionHighlightState,
            )
        }
        setTag(R.id.epub_compose_text_selection_callback, composeSelectionCallback)
        setTag(R.id.epub_compose_text_gesture_selection_wired, true)
        setTag(R.id.epub_compose_text_long_press_selection_wired, true)
        setContent {
            EpubComposeTextPage(
                text = pageText,
                textStyle = currentComposeTextStyle(baseStyle).copy(color = ComposeColor(palette.ink)),
                textLayout = textLayout,
                highlightRanges = highlightRanges,
                links = pageLinks,
                selectionHighlightRange = selectionHighlightState.value,
                onComposeSelectionRange = composeSelectionCallback,
                onComposeLinkClick = composeLinkClickCallback,
                segmentStyleSpans = segmentStyleSpans,
            )
        }
    }

    private fun pageLinksFor(slice: EpubPageSlice): List<EpubTextLink> {
        if (slice.textSegments.size <= 1) return slice.links
        val content = pageTextRenderContent(slice)
        return content.segments.flatMap { segment ->
            segment.source.links.map { link ->
                link.copy(
                    start = segment.renderStart + link.start,
                    end = segment.renderStart + link.end,
                )
            }
        }
    }

    private fun composeLinkClickHandler(
        composeView: ComposeView,
        slice: EpubPageSlice,
    ): (EpubTextLink) -> Unit = { link ->
        val activePageState = activePagedTextPages[composeView]
        if (activePageState?.slice == slice) {
            handleLinkClick(link)
        }
    }

    private fun ComposeView.applyComposePageAccessibilityProgress(description: String) {
        contentDescription = null
        setTag(R.id.epub_compose_page_progress_description, description)
        setTag(R.id.epub_compose_page_root_delegates_accessibility_to_text, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = description
        }
    }

    private fun trackPageContainer(container: View, onDetached: () -> Unit = {}) {
        activePageContainers.add(container)
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                activePageContainers.remove(container)
                onDetached()
            }
        })
    }

    private companion object {
        // 方案 C continuous-flow engine. Flip to false to roll back to the legacy slice-pack PAGED
        // path (both code paths are retained). The host honors this via SelfPagingReaderEngine.
        const val EPUB_FLOW_ENGINE_ENABLED = true
        const val MAX_LINE_WIDTH_DP = 680
        const val PAGE_HORIZONTAL_PADDING_DP = 28
        const val PAGE_VERTICAL_PADDING_DP = 24
        const val INLINE_IMAGE_MAX_HEIGHT_DP = 360
        const val INLINE_IMAGE_VERTICAL_PADDING_DP = 24
        // Intrinsic-pixel gate for full-page placement. Light-novel illustrations/彩插/covers run
        // ~800px+ on the long side; inline avatars (~142px) and icons (~300-400px) sit well below.
        const val FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX = 600
        const val AVERAGE_PAGE_CHARACTER_SAMPLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz一二三四五六七八九十"

        private fun paletteFor(mode: ThemeMode, configuration: Configuration): ReaderPalette {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val p = readerPaletteFor(mode, systemNight)
            return ReaderPalette(p.paper, p.ink, p.isNight)
        }

        private fun codeBlockBgFor(mode: ThemeMode, configuration: Configuration): Int {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (readerPaletteFor(mode, systemNight).isNight) {
                EpubParaAdapter.CODE_BLOCK_BG_NIGHT
            } else {
                EpubParaAdapter.CODE_BLOCK_BG_DAY
            }
        }
    }
}

private data class ReaderPalette(val paper: Int, val ink: Int, val isNight: Boolean)

internal const val EpubComposeSelectionHighlightColor: Int = 0x663B82F6

internal data class EpubComposeTextLayout(
    val paddingStartDp: Int,
    val paddingEndDp: Int,
    val paddingTopDp: Int,
    val paddingBottomDp: Int,
    val horizontalScroll: Boolean,
)

@Composable
private fun EpubComposeTextPage(
    text: String,
    textStyle: TextStyle,
    textLayout: EpubComposeTextLayout,
    highlightRanges: List<ReaderTextHighlightRange>,
    links: List<EpubTextLink>,
    selectionHighlightRange: ReaderTextHighlightRange?,
    onComposeSelectionRange: (start: Int, end: Int) -> Unit,
    onComposeLinkClick: (EpubTextLink) -> Unit,
    segmentStyleSpans: List<EpubComposeSegmentStyleSpan> = emptyList(),
) {
    val horizontalScrollState = rememberScrollState()
    val scrollModifier = if (textLayout.horizontalScroll) {
        Modifier.horizontalScroll(horizontalScrollState)
    } else {
        Modifier
    }
    val textLayoutResultState = remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val selectionStartState = remember(text) { mutableStateOf<EpubComposeInitialSelection?>(null) }
    fun textOffsetAt(position: Offset): Int? =
        textLayoutResultState.value
            ?.getOffsetForPosition(position)
            ?.coerceIn(0, text.length)
    fun linkAt(position: Offset): EpubTextLink? {
        val offset = textOffsetAt(position) ?: return null
        return links.firstOrNull { link ->
            offset >= link.start && offset < link.end
        }
    }
    val composeLinkTapModifier = Modifier.pointerInput(text, links, onComposeLinkClick) {
        detectTapGestures(
            onTap = { position ->
                linkAt(position)?.let(onComposeLinkClick)
            },
        )
    }
    val composeSelectionGestureModifier = Modifier.pointerInput(text, onComposeSelectionRange) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val initialSelection = textOffsetAt(position)?.let { offset ->
                    epubComposeInitialSelectionAt(text, offset)
                }
                selectionStartState.value = initialSelection
                if (initialSelection != null) {
                    onComposeSelectionRange(initialSelection.range.first, initialSelection.range.second)
                }
            },
            onDragEnd = {
                selectionStartState.value = null
            },
            onDragCancel = {
                selectionStartState.value = null
            },
            onDrag = { change, _ ->
                val focus = textOffsetAt(change.position)
                val selectionRange = focus?.let { offset ->
                    epubComposeDragSelectionRange(selectionStartState.value, offset)
                }
                if (selectionRange != null) {
                    onComposeSelectionRange(selectionRange.first, selectionRange.second)
                }
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            BasicText(
                text = epubComposeAnnotatedText(
                    text,
                    highlightRanges,
                    links,
                    selectionHighlightRange,
                    linkClickHandler = onComposeLinkClick,
                    segmentStyleSpans = segmentStyleSpans,
                ),
                style = textStyle,
                onTextLayout = { layoutResult ->
                    textLayoutResultState.value = layoutResult
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = textLayout.paddingStartDp.dp,
                        end = textLayout.paddingEndDp.dp,
                        top = textLayout.paddingTopDp.dp,
                        bottom = textLayout.paddingBottomDp.dp,
                    )
                    .then(scrollModifier)
                    .then(composeLinkTapModifier)
                    .then(composeSelectionGestureModifier),
            )
        }
    }
}

private fun epubComposeAnnotatedText(
    text: String,
    highlightRanges: List<ReaderTextHighlightRange>,
    links: List<EpubTextLink>,
    selectionHighlightRange: ReaderTextHighlightRange? = null,
    linkClickHandler: ((EpubTextLink) -> Unit)? = null,
    segmentStyleSpans: List<EpubComposeSegmentStyleSpan> = emptyList(),
): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    segmentStyleSpans.forEach { span ->
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(0, text.length)
        if (start < end) {
            builder.addStyle(style = span.style, start = start, end = end)
        }
    }
    links.forEach { link ->
        val start = link.start.coerceIn(0, text.length)
        val end = link.end.coerceIn(0, text.length)
        if (start < end && link.href.isNotBlank()) {
            val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline)
            builder.addStringAnnotation(
                tag = "URL",
                annotation = link.href,
                start = start,
                end = end,
            )
            builder.addLink(
                LinkAnnotation.Url(
                    url = link.href,
                    styles = TextLinkStyles(style = linkStyle),
                    linkInteractionListener = linkClickHandler?.let { handler ->
                        object : LinkInteractionListener {
                            override fun onClick(linkAnnotation: LinkAnnotation) {
                                handler(link)
                            }
                        }
                    },
                ),
                start = start,
                end = end,
            )
            builder.addStyle(
                style = linkStyle,
                start = start,
                end = end,
            )
        }
    }
    val ranges = if (selectionHighlightRange == null) {
        highlightRanges
    } else {
        highlightRanges + selectionHighlightRange
    }
    ranges.forEach { range ->
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(0, text.length)
        if (start < end) {
            builder.addStyle(
                style = SpanStyle(background = ComposeColor(range.color)),
                start = start,
                end = end,
            )
        }
    }
    return builder.toAnnotatedString()
}
