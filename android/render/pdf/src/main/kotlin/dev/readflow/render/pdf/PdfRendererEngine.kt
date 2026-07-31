package dev.readflow.render.pdf

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.fixedPageIndex
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.SearchHighlightableReaderEngine
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.ZoomableReaderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * PDF engine using system [PdfRenderer] (v4 lite §5.5).
 * Paged reading; one bitmap per page, lazy-rendered and cached.
 * Progress identity = bare [LocatorStrategy.Page]. Annotation anchors use [LocatorStrategy.PageText].
 *
 * Navigation chrome ([chapterInfo]) uses **real** PDF outline entries only.
 * [buildPdfFallbackToc] page-list rows stay available for the TOC panel but are never
 * treated as a chapter outline (PAGE chrome instead).
 *
 * Text search / selection use the framework text-layer surface when reflection binding
 * succeeds for an open book. Capability is API availability + open session only — never
 * early-page content probing. Search work is serialized on [renderDispatcher] with
 * [PdfRenderer] open/close so it cannot race page renders.
 * Search highlights are transient; annotations are persistent paint only (Room owns storage).
 */
class PdfRendererEngine(private val context: Context) :
    PagedReaderEngine,
    ZoomableReaderEngine,
    SearchHighlightableReaderEngine,
    TextSelectableReaderEngine,
    TextAnnotatableReaderEngine {

    override val id = "pdf-renderer"
    override val format = BookFormat.PDF
    override val priority = 0

    /**
     * Atomic open-document text session (API binding + search/selection capability).
     * Single [Volatile] publish so search/selection/annotation never split pair state.
     */
    @Volatile
    private var openTextSession: PdfOpenTextSession = PdfOpenTextSessionUnavailable

    override val supportsSearch: Boolean
        get() = openTextSession.searchCapable

    override val supportsTextAnnotationCreation: Boolean
        get() = openTextSession.selectionCapable

    private val _pagingKind = MutableStateFlow(PagingKind.PAGED)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _chapterInfo = MutableStateFlow(pdfClosedOrEmptyDocumentChapterInfo())
    override val chapterInfo: StateFlow<ChapterInfo> = _chapterInfo.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    /** Real parsed outline only — never the fallback page list. */
    private var realOutlineEntries: List<TocEntry> = emptyList()

    private val _zoomScale = MutableStateFlow(1f)
    override val zoomScale: StateFlow<Float> = _zoomScale.asStateFlow()

    private val _currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
    override val currentTextSelection: StateFlow<ReaderTextSelection?> =
        _currentTextSelection.asStateFlow()

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var recyclerView: RecyclerView? = null
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null
    private val activePageViews = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    private val activePageHosts = Collections.newSetFromMap(WeakHashMap<PdfSearchPageHost, Boolean>())
    private val trackedPageHosts = WeakHashMap<PdfSearchPageHost, PageHostRegistration>()
    private var pageHostSession = 0
    private var pageHostSessionActive = false
    private var pageSurfaceMode = PageSurfaceMode.UNDECIDED
    private var renderScope = pdfRenderScope()
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val pageBitmapAttachments = PdfBitmapAttachmentRegistry<Bitmap, ImageView>(
        attachedValue = { imageView -> (imageView.drawable as? BitmapDrawable)?.bitmap },
        clearAttachment = { imageView -> imageView.setImageDrawable(null) },
    )
    private var pageCachePolicy = pdfPageCachePolicy(
        pageWidthPx = context.resources.displayMetrics.widthPixels,
        pageHeightPx = context.resources.displayMetrics.heightPixels,
    )
    private var pageStore = createPageStore(renderScope, pageCachePolicy)

    /** Bumped on close/open so in-flight search/selection results are discarded. */
    private val searchGeneration = AtomicInteger(0)

    /**
     * Monotonic id for selection gesture events (long-press / move / finish).
     * Each new event invalidates prior async [selectContent] work so only the latest
     * event under the current [searchGeneration] can publish selection.
     */
    private val selectionGestureId = AtomicInteger(0)

    /** Cancellable in-flight selection computation for the latest gesture event. */
    private var selectionJob: Job? = null

    /** Last completed search for the current generation (page-point bounds retained). */
    private var lastSearchMatches: List<PdfSearchMatch> = emptyList()
    private var lastSearchGeneration: Int = -1
    private var selectedSearchHit: ReaderSearchHit? = null
    /** Bitmap-space rectangles for the selected page, recomputed on bind/highlight. */
    private var selectedHighlightBitmapRects: List<PdfRect> = emptyList()
    private var selectedHighlightPageIndex: Int = -1

    /** Live selection paint (bitmap space) + page index. */
    private var selectionBitmapRects: List<PdfRect> = emptyList()
    private var selectionPageIndex: Int = -1
    private var selectionPagePointBounds: List<PdfRect> = emptyList()
    private var selectionPageWidthPt: Float = 0f
    private var selectionPageHeightPt: Float = 0f

    /** Persisted annotations from ViewModel; paint only — no Room writes here. */
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    private var textAnnotationsPaintKey: String = ""
    /**
     * Bumped when the annotation paint key actually changes (or annotations clear).
     * Async geometry resolution captures this; stale completions must not put bounds.
     */
    private val annotationListGeneration = AtomicInteger(0)
    /** Cancellable in-flight geometry resolution for the latest annotation list. */
    private var annotationGeometryJob: Job? = null
    /** Cached page-point bounds for annotation ids resolved via framework selectContent. */
    private val annotationPagePointBounds = HashMap<String, AnnotationGeometry>()

    private data class AnnotationGeometry(
        val pageIndex: Int,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val pagePointBounds: List<PdfRect>,
    )

    private data class PageMetrics(
        val widthPt: Float,
        val heightPt: Float,
    )

    private data class PageHostRegistration(
        val session: Int,
        val attachListener: View.OnAttachStateChangeListener,
        val layoutListener: View.OnLayoutChangeListener,
    )

    private enum class PageSurfaceMode {
        UNDECIDED,
        LEGACY,
        PAGED,
    }

    private val pageMetricsCache = HashMap<Int, PageMetrics>()

    private val pageSelectionListener = object : PdfPageSelectionListener {
        override fun onSelectionGesture(
            pageIndex: Int,
            startPagePoint: Pair<Float, Float>,
            stopPagePoint: Pair<Float, Float>,
            finished: Boolean,
        ) {
            handleSelectionGesture(pageIndex, startPagePoint, stopPagePoint, finished)
        }

        override fun onSelectionCancelled(pageIndex: Int) {
            invalidateSelectionGestureWork()
            clearTextSelection()
        }
    }

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        invalidatePageHostsOnMain()
        recyclePageCacheOnMain()
        invalidateSearchState()
        clearTextSelectionInternal()
        clearAnnotationState()
        closeRendererResources()
        val tmp = File(context.cacheDir, "pdf_${uri.hashCode()}.pdf")
        if (!tmp.exists()) {
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmp.outputStream().use { dst -> src.copyTo(dst) }
            }
        }
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        parcelFd = pfd
        val renderer = PdfRenderer(pfd)
        pdfRenderer = renderer
        val total = renderer.pageCount
        // Parse real outline once; keep it separate from TOC-panel fallback page list.
        val outline = PdfOutlineParser.parse(tmp, total)
        val toc = outline.ifEmpty { buildPdfFallbackToc(total) }
        val initialBitmap = if (total > 0) renderFirstPage() else null
        // Resolve framework text API on the serialized render dispatcher — no page opens.
        val resolvedApi = withContext(renderDispatcher) { resolveFrameworkTextApi() }
        // One immutable session: capability from API binding + open book only (no content probe).
        val session = resolvePdfOpenTextSession(
            resolvedApi = resolvedApi,
            bookOpen = pdfRenderer != null,
        )
        val initial = if (total > 0) {
            Locator(LocatorStrategy.Page(0, total), 0f, 0f)
        } else {
            Locator(LocatorStrategy.Unknown, 0f, 0f)
        }
        withContext(Dispatchers.Main) {
            ensureRenderScope()
            pageCachePolicy = initialBitmap?.let { bitmap ->
                pdfPageCachePolicy(pageWidthPx = bitmap.width, pageHeightPx = bitmap.height)
            } ?: pdfPageCachePolicy(
                pageWidthPx = context.resources.displayMetrics.widthPixels,
                pageHeightPx = context.resources.displayMetrics.heightPixels,
            )
            pageStore = createPageStore(renderScope, pageCachePolicy)
            initialBitmap?.let { pageStore.put(0, it) }
            if (total > 0) {
                pageStore.retainAround(0, pageCachePolicy.radius)
            }
            realOutlineEntries = outline
            openTextSession = session
            _pageCount.value = total
            _tableOfContents.value = toc
            publishLocator(initial)
            updateZoom(1f)
            pageHostSessionActive = true
        }
        initial
    }

    override fun createView(): View {
        val renderer = pdfRenderer ?: error("openBook not called")
        selectPageSurface(PageSurfaceMode.LEGACY)
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = PdfPageAdapter(
                renderer = renderer,
                context = context,
                highlightProvider = { pageIndex -> searchBitmapRectsForPage(pageIndex) },
                annotationProvider = { pageIndex -> annotationBitmapRectsForPage(pageIndex) },
                selectionProvider = { pageIndex -> selectionBitmapRectsForPage(pageIndex) },
                selectionListener = pageSelectionListener,
                contentDescriptionProvider = { pageIndex -> contentDescriptionForPage(pageIndex) },
            )
            background = paperBackground(context, themeMode, resources.configuration)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = reportProgression(rv)
            })
        }.also { recyclerView = it }
    }

    override fun createPageView(pageIndex: Int): View {
        selectPageSurface(PageSurfaceMode.PAGED)
        val total = _pageCount.value.coerceAtLeast(1)
        val safeIndex = pageIndex.coerceIn(0, total - 1)
        return PdfSearchPageHost(context).apply {
            tag = safeIndex
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = paperBackground(context, themeMode, resources.configuration)
        }.also(::trackPageHost)
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
    }

    private fun reportProgression(rv: RecyclerView) {
        val total = _pageCount.value.takeIf { it > 0 } ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val ratio = first.toFloat() / total
        publishLocator(
            Locator(
                strategy = LocatorStrategy.Page(first, total),
                progression = ratio,
                totalProgression = ratio,
            ),
        )
    }

    /** Publish locator and keep chapter chrome in sync (real outline vs PAGE vs DOCUMENT). */
    private fun publishLocator(locator: Locator) {
        _currentLocator.value = locator
        publishChapterInfo(locator)
    }

    private fun publishChapterInfo(locator: Locator = _currentLocator.value) {
        // Accept Page or PageText for chrome; progress identity remains bare Page on publish.
        val pageIndex = fixedPageIndex(locator)
            ?: locator.totalProgression
                ?.takeIf { it.isFinite() }
                ?.let { progression ->
                    val total = _pageCount.value
                    if (total > 0) (progression.coerceIn(0f, 1f) * total).toInt() else 0
                }
            ?: 0
        _chapterInfo.value = pdfNavigationChapterInfo(
            realOutlineEntries = realOutlineEntries,
            currentPageIndex = pageIndex,
            pageCount = _pageCount.value,
        )
    }

    override fun pageIndexForLocator(locator: Locator): Int {
        val total = _pageCount.value.coerceAtLeast(1)
        // PageText annotation jump shares the fixed page index with bare Page.
        val index = fixedPageIndex(locator)
            ?: locator.totalProgression?.let { (it * total).toInt() }
            ?: 0
        return index.coerceIn(0, total - 1)
    }

    override suspend fun goTo(locator: Locator) {
        val total = _pageCount.value
        if (total <= 0) {
            withContext(Dispatchers.Main) {
                publishLocator(Locator(LocatorStrategy.Unknown, 0f, 0f))
            }
            return
        }
        // PageText annotation jump uses page index; always republish bare Page (progress identity).
        val idx = pdfTargetPageIndex(locator, total)
        val target = Locator(
            strategy = LocatorStrategy.Page(idx, total),
            progression = idx.toFloat() / total,
            totalProgression = idx.toFloat() / total,
        )
        withContext(Dispatchers.Main) {
            val currentIdx = fixedPageIndex(_currentLocator.value)
            if (currentIdx != idx) {
                updateZoom(1f)
            }
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
            publishLocator(target)
            pageStore.retainAround(idx, pageCachePolicy.radius)
            refreshRetainedPageHosts(idx)
            if (pageSurfaceMode == PageSurfaceMode.PAGED) {
                pageStore.prefetchAround(idx, pageCachePolicy.radius, 0 until total)
            }
            pageRequestCallback?.invoke(idx)
        }
    }

    override suspend fun seekToProgress(fraction: Float) {
        val total = _pageCount.value
        if (total <= 0) return
        val idx = (fraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total - 1)
        goTo(Locator(strategy = LocatorStrategy.Page(idx, total)))
    }

    override suspend fun goToAdjacentChapter(delta: Int) {
        val pageIndex = fixedPageIndex(_currentLocator.value) ?: 0
        val target = pdfAdjacentNavigationLocator(
            realOutlineEntries = realOutlineEntries,
            currentPageIndex = pageIndex,
            pageCount = _pageCount.value,
            delta = delta,
        ) ?: return
        goTo(target)
    }

    override suspend fun close() {
        invalidatePageHostsOnMain()
        withContext(Dispatchers.Main) {
            (recyclerView?.adapter as? PdfPageAdapter)?.recycle()
        }
        recyclePageCacheOnMain()
        invalidateSearchState()
        clearTextSelectionInternal()
        clearAnnotationState()
        closeRendererResources()
        recyclerView = null
        pageRequestCallback = null
        activePageViews.clear()
        activePageHosts.clear()
        pageMetricsCache.clear()
        _zoomScale.value = 1f
        realOutlineEntries = emptyList()
        _tableOfContents.value = emptyList()
        _pageCount.value = 0
        // Locator reset + DOCUMENT chapterInfo — no stale page/chapter chrome after close.
        publishLocator(Locator(LocatorStrategy.Unknown, 0f, 0f))
    }

    override fun clearTextSelection() {
        invalidateSelectionGestureWork()
        clearTextSelectionInternal()
        refreshSelectionAndAnnotationPaint()
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        val key = annotationPaintKey(annotations)
        // Idempotent: equivalent paint input must not bump generation or re-resolve.
        if (key == textAnnotationsPaintKey) return
        // Invalidate prior geometry work before publishing the new list.
        // Clear all cached page-point bounds so same-id range updates cannot keep
        // painting geometry that belonged to a previous PageText window.
        invalidateAnnotationGeometryWork()
        textAnnotations = annotations
        textAnnotationsPaintKey = key
        annotationPagePointBounds.clear()
        refreshSelectionAndAnnotationPaint()
        // Resolve geometry for the new list asynchronously on the render dispatcher.
        requestAnnotationGeometryResolution()
    }

    override suspend fun search(query: String): List<ReaderSearchHit> {
        val needle = query.trim()
        val session = openTextSession
        if (needle.isEmpty() || !session.searchCapable) return emptyList()
        val generation = searchGeneration.get()
        val api = session.api
        if (!api.isAvailable) return emptyList()

        val matches = withContext(renderDispatcher) {
            if (generation != searchGeneration.get()) return@withContext emptyList()
            val renderer = pdfRenderer ?: return@withContext emptyList()
            val total = renderer.pageCount
            if (total <= 0) return@withContext emptyList()
            val out = ArrayList<PdfSearchMatch>()
            for (pageIndex in 0 until total) {
                if (generation != searchGeneration.get()) return@withContext emptyList()
                val page = runCatching { renderer.openPage(pageIndex) }.getOrNull() ?: continue
                try {
                    val pageMatches = api.searchPage(page, needle)
                    if (pageMatches.isEmpty()) continue
                    val pageText = api.pageText(page)
                    out += mapPdfFrameworkMatches(
                        pageIndex = pageIndex,
                        pageCount = total,
                        query = needle,
                        pageText = pageText,
                        matches = pageMatches,
                        pageWidthPt = page.width.toFloat(),
                        pageHeightPt = page.height.toFloat(),
                    )
                } finally {
                    runCatching { page.close() }
                }
            }
            out
        }

        if (generation != searchGeneration.get()) return emptyList()
        lastSearchMatches = matches
        lastSearchGeneration = generation
        return matches.map { it.hit }
    }

    override fun setSearchHighlight(hit: ReaderSearchHit?) {
        selectedSearchHit = hit
        if (hit == null) {
            selectedHighlightBitmapRects = emptyList()
            selectedHighlightPageIndex = -1
            refreshSearchHighlights()
            return
        }
        val pageIndex = (hit.locator.strategy as? LocatorStrategy.Page)?.index
        if (pageIndex == null || lastSearchGeneration != searchGeneration.get()) {
            selectedHighlightBitmapRects = emptyList()
            selectedHighlightPageIndex = -1
            refreshSearchHighlights()
            return
        }
        val match = if (hit.matchStart != null) {
            lastSearchMatches.firstOrNull { candidate ->
                candidate.pageIndex == pageIndex &&
                    candidate.hit.matchLength == hit.matchLength &&
                    candidate.hit.matchStart == hit.matchStart
            }
        } else {
            lastSearchMatches.firstOrNull { candidate ->
                candidate.pageIndex == pageIndex &&
                    candidate.hit.matchLength == hit.matchLength &&
                    candidate.hit.locator.strategy == hit.locator.strategy &&
                    candidate.hit.snippet == hit.snippet
            } ?: lastSearchMatches.firstOrNull {
                it.pageIndex == pageIndex && it.hit.matchLength == hit.matchLength
            }
        }
        if (match == null) {
            selectedHighlightBitmapRects = emptyList()
            selectedHighlightPageIndex = -1
            refreshSearchHighlights()
            return
        }
        selectedHighlightPageIndex = match.pageIndex
        selectedHighlightBitmapRects = bitmapRectsForMatch(match)
        refreshSearchHighlights()
    }

    override suspend fun setFontSize(sp: Float) { /* not applicable for PDF */ }
    override suspend fun setZoom(scale: Float) {
        withContext(Dispatchers.Main) {
            updateZoom(scale.coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE))
        }
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        recyclerView?.background = paperBackground(context, mode, context.resources.configuration)
        activePageViews.forEach {
            it.background = paperBackground(context, mode, context.resources.configuration)
        }
        activePageHosts.forEach {
            it.background = paperBackground(context, mode, context.resources.configuration)
        }
    }
    override suspend fun setMode(mode: ReadingMode) {
        _pagingKind.value = PagingKind.PAGED
    }

    private fun handleSelectionGesture(
        pageIndex: Int,
        startBitmapPt: Pair<Float, Float>,
        stopBitmapPt: Pair<Float, Float>,
        finished: Boolean,
    ) {
        val openBookGeneration = searchGeneration.get()
        val session = openTextSession
        val api = session.api
        if (!session.selectionCapable) {
            invalidateSelectionGestureWork()
            clearTextSelectionInternal()
            refreshSelectionAndAnnotationPaint()
            return
        }
        // Cancel prior gesture work; only the latest event id may publish selection.
        selectionJob?.cancel()
        val eventId = selectionGestureId.incrementAndGet()
        selectionJob = renderScope.launch {
            val result = withContext(renderDispatcher) {
                if (!isSelectionEventCurrent(openBookGeneration, eventId)) {
                    return@withContext null
                }
                val renderer = pdfRenderer ?: return@withContext null
                val page = runCatching { renderer.openPage(pageIndex) }.getOrNull()
                    ?: return@withContext null
                try {
                    if (!isSelectionEventCurrent(openBookGeneration, eventId)) {
                        return@withContext null
                    }
                    val metrics = PageMetrics(page.width.toFloat(), page.height.toFloat())
                    pageMetricsCache[pageIndex] = metrics
                    val bitmap = pageStore.cached(pageIndex)
                    val bw = bitmap?.width ?: context.resources.displayMetrics.widthPixels
                    val bh = bitmap?.height
                        ?: (bw * metrics.heightPt / metrics.widthPt.coerceAtLeast(1f))
                            .toInt().coerceAtLeast(1)
                    val startPt = bitmapPixelToPagePoint(
                        bitmapX = startBitmapPt.first,
                        bitmapY = startBitmapPt.second,
                        pageWidthPt = metrics.widthPt,
                        pageHeightPt = metrics.heightPt,
                        bitmapWidthPx = bw,
                        bitmapHeightPx = bh,
                    )
                    val stopPt = bitmapPixelToPagePoint(
                        bitmapX = stopBitmapPt.first,
                        bitmapY = stopBitmapPt.second,
                        pageWidthPt = metrics.widthPt,
                        pageHeightPt = metrics.heightPt,
                        bitmapWidthPx = bw,
                        bitmapHeightPx = bh,
                    )
                    val frameworkSelection = api.selectContent(
                        page = page,
                        start = PdfSelectionBoundarySpec.Point(
                            x = startPt.first.toInt(),
                            y = startPt.second.toInt(),
                        ),
                        stop = PdfSelectionBoundarySpec.Point(
                            x = stopPt.first.toInt(),
                            y = stopPt.second.toInt(),
                        ),
                    ) ?: return@withContext null
                    // Cross-page fabrication is out of scope: fail closed if page differs.
                    if (frameworkSelection.page != pageIndex) return@withContext null
                    val pageText = api.pageText(page)
                    val readerSelection = mapPageSelectionToReaderTextSelection(
                        pageIndex = pageIndex,
                        pageCount = renderer.pageCount,
                        pageText = pageText,
                        selection = frameworkSelection,
                    )
                    SelectionComputeResult(
                        readerSelection = readerSelection,
                        pagePointBounds = selectionPagePointBounds(frameworkSelection),
                        pageWidthPt = metrics.widthPt,
                        pageHeightPt = metrics.heightPt,
                        pageIndex = pageIndex,
                    )
                } finally {
                    runCatching { page.close() }
                }
            }
            if (!isSelectionEventCurrent(openBookGeneration, eventId)) return@launch
            withContext(Dispatchers.Main) {
                if (!isSelectionEventCurrent(openBookGeneration, eventId)) return@withContext
                if (result == null || result.readerSelection == null) {
                    if (finished) {
                        // Only the latest finished event may clear; still gated by event id.
                        clearTextSelectionInternal()
                        refreshSelectionAndAnnotationPaint()
                    }
                    return@withContext
                }
                applyLiveSelection(result)
            }
        }
    }

    private fun isSelectionEventCurrent(openBookGeneration: Int, eventId: Int): Boolean =
        PdfSelectionGestureLifecycle.isEventFresh(
            openBookGeneration = searchGeneration.get(),
            eventOpenBookGeneration = openBookGeneration,
            currentEventId = selectionGestureId.get(),
            resultEventId = eventId,
        )

    /**
     * Cancel in-flight selection work and bump the gesture id so any still-running
     * completion cannot publish. Does not clear published selection state by itself.
     */
    private fun invalidateSelectionGestureWork() {
        selectionJob?.cancel()
        selectionJob = null
        selectionGestureId.incrementAndGet()
    }

    private data class SelectionComputeResult(
        val readerSelection: ReaderTextSelection?,
        val pagePointBounds: List<PdfRect>,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val pageIndex: Int,
    )

    private fun applyLiveSelection(result: SelectionComputeResult) {
        val selection = result.readerSelection ?: run {
            clearTextSelectionInternal()
            refreshSelectionAndAnnotationPaint()
            return
        }
        selectionPageIndex = result.pageIndex
        selectionPagePointBounds = result.pagePointBounds
        selectionPageWidthPt = result.pageWidthPt
        selectionPageHeightPt = result.pageHeightPt
        selectionBitmapRects = normalizePdfPageRectsToBitmap(
            pagePointBounds = result.pagePointBounds,
            pageWidthPt = result.pageWidthPt,
            pageHeightPt = result.pageHeightPt,
            bitmapWidthPx = pageStore.cached(result.pageIndex)?.width
                ?: context.resources.displayMetrics.widthPixels,
            bitmapHeightPx = pageStore.cached(result.pageIndex)?.height
                ?: (context.resources.displayMetrics.widthPixels *
                    result.pageHeightPt / result.pageWidthPt.coerceAtLeast(1f))
                    .toInt().coerceAtLeast(1),
        )
        _currentTextSelection.value = selection
        refreshSelectionAndAnnotationPaint()
    }

    private suspend fun renderFirstPage(): Bitmap? =
        withContext(renderDispatcher) { renderPageBlocking(0) }

    private fun renderPageBlocking(idx: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        val page = renderer.openPage(idx)
        return try {
            pageMetricsCache[idx] = PageMetrics(page.width.toFloat(), page.height.toFloat())
            val width = context.resources.displayMetrics.widthPixels
            val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
            // PdfRenderer on Android 16 rejects RGB_565 with "Unsupported pixel format".
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    private suspend fun closeRendererResources() {
        withContext(renderDispatcher) {
            pdfRenderer?.close()
            parcelFd?.close()
            pdfRenderer = null
            parcelFd = null
        }
    }

    private suspend fun recyclePageCacheOnMain() {
        withContext(Dispatchers.Main) {
            pageStore.clear()
        }
    }

    /**
     * Resolve reflection binding only. Does not open PDF pages or inspect document content.
     * Must run on [renderDispatcher] while the renderer session is open for thread affinity.
     */
    private fun resolveFrameworkTextApi(): PdfFrameworkTextApi = PdfFrameworkTextApi.resolve()

    private fun invalidateSearchState() {
        searchGeneration.incrementAndGet()
        // Open-book generation change also invalidates any in-flight selection gesture.
        invalidateSelectionGestureWork()
        // Production teardown: single unavailable session (not content-driven).
        openTextSession = PdfOpenTextSessionUnavailable
        lastSearchMatches = emptyList()
        lastSearchGeneration = -1
        selectedSearchHit = null
        selectedHighlightBitmapRects = emptyList()
        selectedHighlightPageIndex = -1
    }

    private fun clearTextSelectionInternal() {
        // Drop stale async completions before clearing published state.
        // Callers that already invalidated (clearTextSelection) are idempotent here:
        // a second bump only further invalidates; no publish path runs without a new event.
        selectionJob?.cancel()
        selectionJob = null
        _currentTextSelection.value = null
        selectionBitmapRects = emptyList()
        selectionPageIndex = -1
        selectionPagePointBounds = emptyList()
        selectionPageWidthPt = 0f
        selectionPageHeightPt = 0f
    }

    private fun clearAnnotationState() {
        invalidateAnnotationGeometryWork()
        textAnnotations = emptyList()
        textAnnotationsPaintKey = ""
        annotationPagePointBounds.clear()
    }

    /**
     * Cancel in-flight geometry resolution and bump the annotation-list generation so
     * any still-running completion cannot put bounds for a previous list.
     */
    private fun invalidateAnnotationGeometryWork() {
        annotationGeometryJob?.cancel()
        annotationGeometryJob = null
        annotationListGeneration.incrementAndGet()
    }

    private fun isAnnotationResolutionCurrent(
        openBookGeneration: Int,
        listGeneration: Int,
    ): Boolean =
        PdfAnnotationGeometryLifecycle.isResolutionFresh(
            openBookGeneration = searchGeneration.get(),
            resolutionOpenBookGeneration = openBookGeneration,
            annotationListGeneration = annotationListGeneration.get(),
            resolutionAnnotationListGeneration = listGeneration,
        )

    private fun bitmapRectsForMatch(match: PdfSearchMatch): List<PdfRect> {
        val bitmap = pageStore.cached(match.pageIndex)
        val bw = bitmap?.width ?: context.resources.displayMetrics.widthPixels
        val bh = bitmap?.height
            ?: (bw * match.pageHeightPt / match.pageWidthPt.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        return normalizePdfPageRectsToBitmap(
            pagePointBounds = match.pagePointBounds,
            pageWidthPt = match.pageWidthPt,
            pageHeightPt = match.pageHeightPt,
            bitmapWidthPx = bw,
            bitmapHeightPx = bh,
        )
    }

    private fun searchBitmapRectsForPage(pageIndex: Int): List<PdfRect> {
        if (pageIndex != selectedHighlightPageIndex) return emptyList()
        return selectedHighlightBitmapRects
    }

    private fun selectionBitmapRectsForPage(pageIndex: Int): List<PdfRect> {
        if (pageIndex != selectionPageIndex) return emptyList()
        // Recompute from page points if bitmap size changed after load.
        if (selectionPagePointBounds.isNotEmpty() &&
            selectionPageWidthPt > 0f &&
            selectionPageHeightPt > 0f
        ) {
            val bitmap = pageStore.cached(pageIndex)
            val bw = bitmap?.width ?: context.resources.displayMetrics.widthPixels
            val bh = bitmap?.height
                ?: (bw * selectionPageHeightPt / selectionPageWidthPt.coerceAtLeast(1f))
                    .toInt().coerceAtLeast(1)
            return normalizePdfPageRectsToBitmap(
                pagePointBounds = selectionPagePointBounds,
                pageWidthPt = selectionPageWidthPt,
                pageHeightPt = selectionPageHeightPt,
                bitmapWidthPx = bw,
                bitmapHeightPx = bh,
            )
        }
        return selectionBitmapRects
    }

    private fun annotationBitmapRectsForPage(pageIndex: Int): List<PdfColoredRect> {
        val pageAnns = pageTextAnnotationsForPage(textAnnotations, pageIndex)
        if (pageAnns.isEmpty()) return emptyList()
        val out = ArrayList<PdfColoredRect>()
        for (annotation in pageAnns) {
            val geometry = annotationPagePointBounds[annotation.id] ?: continue
            if (geometry.pageIndex != pageIndex) continue
            val bitmap = pageStore.cached(pageIndex)
            val bw = bitmap?.width ?: context.resources.displayMetrics.widthPixels
            val bh = bitmap?.height
                ?: (bw * geometry.pageHeightPt / geometry.pageWidthPt.coerceAtLeast(1f))
                    .toInt().coerceAtLeast(1)
            val rects = normalizePdfPageRectsToBitmap(
                pagePointBounds = geometry.pagePointBounds,
                pageWidthPt = geometry.pageWidthPt,
                pageHeightPt = geometry.pageHeightPt,
                bitmapWidthPx = bw,
                bitmapHeightPx = bh,
            )
            for (rect in rects) {
                out += PdfColoredRect(rect = rect, color = annotation.color)
            }
        }
        return out
    }

    private fun contentDescriptionForPage(pageIndex: Int): String {
        val total = _pageCount.value.coerceAtLeast(1)
        val selected = _currentTextSelection.value
            ?.takeIf {
                val start = it.start.strategy as? LocatorStrategy.PageText
                start?.index == pageIndex
            }
            ?.selectedText
        return pdfPageContentDescription(pageIndex, total, selected)
    }

    private fun applyAllPaintForHost(host: PdfSearchPageHost, pageIndex: Int) {
        host.setSearchRects(searchBitmapRectsForPage(pageIndex))
        host.setAnnotationRects(annotationBitmapRectsForPage(pageIndex))
        host.setSelectionRects(selectionBitmapRectsForPage(pageIndex))
        host.contentDescription = contentDescriptionForPage(pageIndex)
        host.imageView.contentDescription = host.contentDescription
        host.rebindHighlightPaint()
    }

    private fun refreshSearchHighlights() {
        activePageHosts.forEach { host ->
            val pageIndex = host.tag as? Int ?: return@forEach
            host.setSearchRects(searchBitmapRectsForPage(pageIndex))
            host.rebindHighlightPaint()
        }
        (recyclerView?.adapter as? PdfPageAdapter)?.notifySearchHighlightChanged()
    }

    private fun refreshSelectionAndAnnotationPaint() {
        activePageHosts.forEach { host ->
            val pageIndex = host.tag as? Int ?: return@forEach
            applyAllPaintForHost(host, pageIndex)
        }
        (recyclerView?.adapter as? PdfPageAdapter)?.notifyAllOverlaysChanged()
    }

    private fun requestAnnotationGeometryResolution() {
        val openBookGeneration = searchGeneration.get()
        val listGeneration = annotationListGeneration.get()
        val session = openTextSession
        val api = session.api
        if (!session.selectionCapable) return
        // Snapshot the list for this generation; completion must still match both gens.
        val pending = textAnnotations.filter { ann ->
            pageTextAnnotationCharRange(ann) != null && !annotationPagePointBounds.containsKey(ann.id)
        }
        if (pending.isEmpty()) return
        annotationGeometryJob?.cancel()
        annotationGeometryJob = renderScope.launch {
            val resolved = withContext(renderDispatcher) {
                if (!isAnnotationResolutionCurrent(openBookGeneration, listGeneration)) {
                    return@withContext emptyMap()
                }
                val renderer = pdfRenderer ?: return@withContext emptyMap()
                val out = HashMap<String, AnnotationGeometry>()
                for (annotation in pending) {
                    if (!isAnnotationResolutionCurrent(openBookGeneration, listGeneration)) break
                    val range = pageTextAnnotationCharRange(annotation) ?: continue
                    val pageIndex = fixedPageIndex(annotation.start) ?: continue
                    val page = runCatching { renderer.openPage(pageIndex) }.getOrNull() ?: continue
                    try {
                        val frameworkSelection = api.selectContent(
                            page = page,
                            start = PdfSelectionBoundarySpec.CharIndex(range.first),
                            stop = PdfSelectionBoundarySpec.CharIndex(range.second),
                        ) ?: continue
                        if (frameworkSelection.page != pageIndex) continue
                        val bounds = selectionPagePointBounds(frameworkSelection)
                        if (bounds.isEmpty()) continue
                        out[annotation.id] = AnnotationGeometry(
                            pageIndex = pageIndex,
                            pageWidthPt = page.width.toFloat(),
                            pageHeightPt = page.height.toFloat(),
                            pagePointBounds = bounds,
                        )
                    } finally {
                        runCatching { page.close() }
                    }
                }
                out
            }
            if (!isAnnotationResolutionCurrent(openBookGeneration, listGeneration) ||
                resolved.isEmpty()
            ) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!isAnnotationResolutionCurrent(openBookGeneration, listGeneration)) {
                    return@withContext
                }
                annotationPagePointBounds.putAll(resolved)
                refreshSelectionAndAnnotationPaint()
            }
        }
    }

    private fun trackPageHost(host: PdfSearchPageHost) {
        val registrationSession = pageHostSession
        val imageView = host.imageView
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val attached = view as? PdfSearchPageHost ?: return
                if (!pageHostSessionActive || registrationSession != pageHostSession) return
                if (attached !in activePageHosts) activatePageHost(attached)
            }

            override fun onViewDetachedFromWindow(view: View) {
                val detached = view as? PdfSearchPageHost ?: return
                deactivatePageHost(detached)
            }
        }
        val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (pageHostSessionActive && registrationSession == pageHostSession && view is ImageView) {
                (view.parent as? PdfSearchPageHost)?.reapplyPdfTransform()
            }
        }
        trackedPageHosts[host] = PageHostRegistration(
            session = registrationSession,
            attachListener = attachListener,
            layoutListener = layoutListener,
        )
        host.addOnAttachStateChangeListener(attachListener)
        imageView.addOnLayoutChangeListener(layoutListener)
    }

    private fun activatePageHost(host: PdfSearchPageHost) {
        val registration = trackedPageHosts[host] ?: return
        if (!pageHostSessionActive || registration.session != pageHostSession || pdfRenderer == null) {
            return
        }
        val pageIndex = host.tag as? Int ?: return
        val total = _pageCount.value
        if (total <= 0) return
        if (pageIndex !in 0 until total) return
        val imageView = host.imageView
        activePageHosts.add(host)
        activePageViews.add(imageView)
        pageBitmapAttachments.track(imageView)
        host.selectionListener = pageSelectionListener
        host.boundaryPageTurnListener = { pageDelta ->
            val target = (pageIndex + pageDelta).coerceIn(0, total - 1)
            if (target != pageIndex) pageRequestCallback?.invoke(target)
        }
        host.setZoomScale(_zoomScale.value)
        applyAllPaintForHost(host, pageIndex)
        if (!pageStore.isRetained(pageIndex)) return
        pageStore.load(pageIndex) { bitmap ->
            if (bitmap == null ||
                host.tag != pageIndex ||
                host !in activePageHosts ||
                !pageStore.isRetained(pageIndex)
            ) {
                return@load
            }
            imageView.setImageBitmap(bitmap)
            host.setZoomScale(_zoomScale.value)
            applyAllPaintForHost(host, pageIndex)
        }
    }

    private fun deactivatePageHost(host: PdfSearchPageHost) {
        activePageHosts.remove(host)
        activePageViews.remove(host.imageView)
        pageBitmapAttachments.untrack(host.imageView)
        host.imageView.setImageDrawable(null)
        host.clearAllPaint()
        host.selectionListener = null
        host.boundaryPageTurnListener = null
        host.contentDescription = null
        host.imageView.contentDescription = null
    }

    private fun selectPageSurface(requested: PageSurfaceMode) {
        if (pageSurfaceMode == PageSurfaceMode.UNDECIDED || pageSurfaceMode == requested) {
            pageSurfaceMode = requested
            return
        }
        // Confirmed mixed-surface remount: reset the previous surface session so the engine can
        // serve the requested surface instead of hard-crashing. Single-surface sessions (the
        // normal paged host) keep the exact current behavior.
        resetPageSurfaceSession()
        pageSurfaceMode = requested
    }

    /**
     * Tear down the previous surface's state before serving a differently shaped surface:
     * deactivate and untrack paged page hosts (bumping the session invalidates their
     * registrations) and drop the legacy recycler reference. Runs on the caller (main) thread
     * like [createView]/[createPageView]; no raw PdfRenderer access happens during the reset.
     */
    private fun resetPageSurfaceSession() {
        pageHostSession += 1
        trackedPageHosts.toMap().forEach { (host, registration) ->
            host.removeOnAttachStateChangeListener(registration.attachListener)
            host.imageView.removeOnLayoutChangeListener(registration.layoutListener)
            deactivatePageHost(host)
        }
        trackedPageHosts.clear()
        activePageViews.clear()
        activePageHosts.clear()
        recyclerView = null
    }

    private suspend fun invalidatePageHostsOnMain() {
        withContext(Dispatchers.Main) {
            pageHostSessionActive = false
            pageHostSession += 1
            pageSurfaceMode = PageSurfaceMode.UNDECIDED
            trackedPageHosts.toMap().forEach { (host, registration) ->
                host.removeOnAttachStateChangeListener(registration.attachListener)
                host.imageView.removeOnLayoutChangeListener(registration.layoutListener)
                deactivatePageHost(host)
            }
            trackedPageHosts.clear()
            activePageViews.clear()
            activePageHosts.clear()
        }
    }

    private fun refreshRetainedPageHosts(currentPageIndex: Int) {
        activePageHosts
            .toList()
            .sortedBy { host ->
                (host.tag as? Int)?.let { pageIndex ->
                    kotlin.math.abs(pageIndex - currentPageIndex)
                } ?: Int.MAX_VALUE
            }
            .forEach { host ->
                val pageIndex = host.tag as? Int ?: return@forEach
                if (pageStore.isRetained(pageIndex)) activatePageHost(host)
            }
    }

    private fun updateZoom(scale: Float) {
        _zoomScale.value = scale
        activePageHosts.forEach { it.setZoomScale(scale) }
    }

    private fun ensureRenderScope() {
        if (renderScope.isActive) return
        renderScope = pdfRenderScope()
        pageStore = createPageStore(renderScope, pageCachePolicy)
    }

    private fun createPageStore(
        scope: CoroutineScope,
        policy: PdfPageCachePolicy,
    ): PdfPageBitmapStore<Bitmap> =
        PdfPageBitmapStore(
            scope = scope,
            renderDispatcher = renderDispatcher,
            maxEntries = policy.maxPages,
            release = { bitmap -> pageBitmapAttachments.release(bitmap) { it.recycle() } },
            render = { pageIndex -> renderPageBlocking(pageIndex) },
        )

    private companion object {
        const val MIN_ZOOM_SCALE = 1f
        const val MAX_ZOOM_SCALE = 4f

        /** A fresh paper-texture background drawable (审计: drawables can't be shared across views). */
        fun paperBackground(context: Context, mode: ThemeMode, configuration: Configuration) =
            run {
                val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                val p = readerPaletteFor(mode, systemNight)
                readerPaperBackground(context, p.paper, p.ink, p.isNight)
            }
    }
}

/**
 * Convert bitmap-pixel coordinates to PDF page-point space (top-left origin, same as render).
 */
internal fun bitmapPixelToPagePoint(
    bitmapX: Float,
    bitmapY: Float,
    pageWidthPt: Float,
    pageHeightPt: Float,
    bitmapWidthPx: Int,
    bitmapHeightPx: Int,
): Pair<Float, Float> {
    if (bitmapWidthPx <= 0 || bitmapHeightPx <= 0 || pageWidthPt <= 0f || pageHeightPt <= 0f) {
        return 0f to 0f
    }
    val x = (bitmapX / bitmapWidthPx) * pageWidthPt
    val y = (bitmapY / bitmapHeightPx) * pageHeightPt
    return x.coerceIn(0f, pageWidthPt) to y.coerceIn(0f, pageHeightPt)
}

private fun pdfRenderScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
