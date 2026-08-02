package dev.readflow.render.txt

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReaderTypographyRange
import dev.readflow.core.model.adjacentTocEntry
import dev.readflow.core.model.chapterInfoFromOrderedToc
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.SearchHighlightableReaderEngine
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.withTextHighlightSpans
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Minimal TXT engine (v4 §5.3/§5.4). CONTINUOUS scroll via RecyclerView.
 * TXT content is copied from the incoming Uri into a private temp file, indexed
 * with 64 KiB FileChannel blocks, and paragraph text is read on demand.
 * Charset detection uses juniversalchardet with BOM priority and UTF-8 fallback.
 */
class TxtVirtualPagerEngine(
    private val context: Context,
    private val paginationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PagedReaderEngine,
    InitialLocatorAwareReaderEngine,
    TextSelectableReaderEngine,
    TextAnnotatableReaderEngine,
    SearchHighlightableReaderEngine {

    override val id: String = "txt-virtual-pager"
    override val format: BookFormat = BookFormat.TXT
    override val priority: Int = 0
    override val supportsSearch: Boolean = true
    override val supportedModes: Set<ReadingMode> = setOf(ReadingMode.SCROLL, ReadingMode.PAGED)

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(
        Locator(strategy = LocatorStrategy.ByteOffset(0L, 0), progression = 0f, totalProgression = 0f),
    )
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _chapterInfo = MutableStateFlow(
        chapterInfoFromOrderedToc(
            tocEntries = emptyList(),
            totalProgression = 0f,
            documentTitleFallback = DOCUMENT_TITLE_FALLBACK,
        ),
    )
    override val chapterInfo: StateFlow<ChapterInfo> = _chapterInfo.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private val _currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
    override val currentTextSelection: StateFlow<ReaderTextSelection?> = _currentTextSelection.asStateFlow()

    private var txtDocument: TxtDocument? = null
    private var txtFingerprint: TxtDocumentFingerprint? = null
    private var pendingEngineState: ByteArray? = null
    private var pendingInitialLocator: Locator? = null
    private var fontSizeSp: Float = ReaderTypographyRange.DEFAULT_FONT_SIZE.toFloat()
    private var lineSpacingMultiplier: Float = ReaderTypographyRange.DEFAULT_LINE_SPACING
    private var useSourceHan: Boolean = true
    private var currentFontId: String = "source_han"
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var encodingOverride: String? = null
    private var currentUri: Uri? = null
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    /** Transient selected search hit; independent of view instances for mode remount repaint. */
    private var searchHighlightHit: ReaderSearchHit? = null
    private var recyclerView: RecyclerView? = null
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null
    private var pendingProgrammaticScroll: PendingProgrammaticScroll? = null
    private var pendingContinuousTypographyAnchor: TxtParagraphPosition? = null
    private var continuousTypographyRestoreGeneration = 0L
    /**
     * Surface-scoped scroll-report token. Bumped on createView remount and document lifecycle
     * changes, while typography and navigation bumps can never disable the current surface's
     * normal scroll reporting. Each RecyclerView's scroll listener captures its own surface's
     * generation, and reportProgression refuses any callback whose captured generation is no
     * longer current or whose view is not the live surface. openBook/close also bump it so a reused
     * RecyclerView cannot publish offsets from the previous document; the host must remount before
     * reporting the new document.
     */
    private val scrollReportGeneration = AtomicLong()
    /**
     * Host-reported ViewPager viewport. Zero means "not yet laid out" — fall back to
     * displayMetrics until the first real layout arrives (Markdown parity).
     */
    private var viewportWidthPx: Int = 0
    private var viewportHeightPx: Int = 0
    /** Compatibility projection retained for diagnostics/tests; duplicate indexes mean one
     * paragraph spans multiple visual pages. Rendering and navigation use [pagedPageWindows]. */
    private var pagedParagraphStarts: List<Int> = emptyList()
    private var pagedPageWindows: List<TxtPageWindow> = emptyList()
    private val paginationGeneration = AtomicLong()
    private val paginationMutex = Mutex()
    private val activePageTextViews = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
    private val pagedPageTexts = WeakHashMap<TextView, TxtPagedText>()
    private val activePageContainers = Collections.newSetFromMap(WeakHashMap<FrameLayout, Boolean>())
    /** Weak tracking of active PAGED page bindings for same-count viewport rebind. */
    private val activePageBindings = Collections.newSetFromMap(WeakHashMap<TxtPageViewBinding, Boolean>())

    override suspend fun supports(uri: Uri): Boolean = true

    override fun setInitialLocator(locator: Locator?) {
        pendingInitialLocator = locator
    }

    override suspend fun openBook(uri: Uri): Locator {
        val requestedInitialLocator = pendingInitialLocator
        pendingInitialLocator = null
        return withContext(Dispatchers.IO) {
            paginationGeneration.incrementAndGet()
            currentUri = uri
            val previousDocument = txtDocument
            txtDocument = null
            paginationMutex.withLock {
                previousDocument?.close()
            }
            pendingProgrammaticScroll = null
            pendingContinuousTypographyAnchor = null
            continuousTypographyRestoreGeneration += 1L
            scrollReportGeneration.incrementAndGet()
            // Engine instance may be reused for a different book; drop transient search paint state.
            searchHighlightHit = null
            val requiresFingerprint = pendingEngineState != null
            val copied = resolveReadableFile(uri, requiresFingerprint)
            val overrideDetection = encodingOverride?.let { name ->
                runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
            }?.let { cs ->
                TxtCharsetDetection(
                    charset = cs,
                    source = TxtCharsetDetectionSource.Fallback,
                    fallbackReason = "user-override"
                )
            }
            val document = TxtDocument.index(
                file = copied.file,
                deleteOnClose = copied.deleteOnClose,
                fingerprint = copied.fingerprint,
                cachedEngineState = pendingEngineState,
                charsetDetection = overrideDetection,
            )
            pendingEngineState = null
            txtDocument = document
            txtFingerprint = copied.fingerprint
            _tableOfContents.value = buildToc(document)

            // Preserve the last valid window set until the new windows are installed: a host
            // observing PAGED must never see a published pageCount backed by empty windows.
            var installedFreshWindows = false
            if (_pagingKind.value == PagingKind.PAGED) {
                val result = calculatePagedPageWindows()
                if (result != null && result.isCurrent()) {
                    installPagedPageWindows(result.windows)
                    installedFreshWindows = true
                }
            }
            val initialPosition = requestedInitialLocator
                ?.let(::paragraphPositionForLocator)
                ?: TxtParagraphPosition(0, 0)
            if (_pagingKind.value == PagingKind.PAGED && installedFreshWindows) {
                installPagedPageWindows(pagedPageWindows.rebasedAt(initialPosition))
                _pageCount.value = pagedPageWindows.size.coerceAtLeast(1)
            } else if (_pagingKind.value == PagingKind.PAGED) {
                // No freshly installed window set: never advertise a paragraph-based count the
                // host would bind into blank page slots. Keep the last published pageCount
                // (0 for a fresh session) and the preserved windows until repagination lands.
            } else {
                _pageCount.value = document.paragraphCount
            }
            val resolvedInitialLocator = locatorForPosition(initialPosition, document.paragraphCount)
            val initialLocator = requestedInitialLocator?.takeIf { requested ->
                val requestedOffset = (requested.strategy as? LocatorStrategy.ByteOffset)?.offset
                val resolvedOffset =
                    (resolvedInitialLocator.strategy as? LocatorStrategy.ByteOffset)?.offset
                requestedOffset != null && requestedOffset == resolvedOffset
            } ?: resolvedInitialLocator
            publishLocator(initialLocator)
            if (_pagingKind.value == PagingKind.PAGED && installedFreshWindows) {
                val initialPage = pageForPosition(initialPosition.paragraphIndex, initialPosition.charOffset)
                withContext(Dispatchers.Main) {
                    pageRequestCallback?.invoke(initialPage)
                }
            }
            initialLocator
        }
    }

    override fun createView(): View {
        // Remount: a pending continuous typography anchor belongs to the previous surface. Drop
        // its state and generation so a later typography edit captures the new surface's actual
        // viewport instead of reusing the stale anchor after the user scrolls the new surface.
        pendingContinuousTypographyAnchor = null
        continuousTypographyRestoreGeneration += 1L
        // Assign a fresh surface/report generation: only this RecyclerView's scroll callbacks may
        // publish into the engine, so a stale previous surface's callback is refused by both the
        // captured-generation check and the exact RecyclerView identity check.
        val surfaceReportGeneration = scrollReportGeneration.incrementAndGet()
        val initialPosition = currentParagraphPosition()
        val rv = RecyclerView(context).apply {
            // A paragraph is one adapter row, but it can be taller than the viewport. The stock
            // LinearLayoutManager measures WRAP_CONTENT children with an AT_MOST viewport height,
            // which truncates a single long paragraph and makes inner-character scrolling a no-op.
            // Measure paragraph rows with an unspecified height so RecyclerView can scroll through
            // their full TextView layout while retaining LinearLayoutManager semantics.
            layoutManager = TxtParagraphLayoutManager(context)
            val palette = paletteFor(themeMode, resources.configuration)
            adapter = TxtParagraphAdapter(
                paragraphCount = paragraphCount(),
                paragraphProvider = ::paragraphAt,
                fontSizeSp = fontSizeSp,
                lineSpacingMultiplier = lineSpacingMultiplier,
                inkColor = palette.ink,
                highlightRangesProvider = { index ->
                    highlightRangesForParagraph(index) to searchHighlightRangesForParagraph(index)
                },
                onSelectionChanged = ::updateTextSelection,
                typeface = resolveTypeface(),
            )
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            clipToPadding = false
            val padV = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    reportProgression(view, surfaceReportGeneration)
                }
            })
            (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(initialPosition.paragraphIndex, 0)
        }
        recyclerView = rv
        if (initialPosition.charOffset > 0) {
            scheduleContinuousInnerOffsetRestore(
                rv = rv,
                position = initialPosition,
                navigationGeneration = continuousTypographyRestoreGeneration,
            )
        }
        return rv
    }

    override fun createPageView(pageIndex: Int): View {
        val pageCount = pagedPageWindows.size.coerceAtLeast(1)
        val safePageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val segments = pagedPageWindows.getOrNull(safePageIndex)?.segments.orEmpty()
        val palette = paletteFor(themeMode, context.resources.configuration)
        val density = context.resources.displayMetrics.density
        val maxLineWidthPx = (TxtParagraphAdapter.MAX_LINE_WIDTH_DP * density).toInt()
        val pageTextViews = mutableListOf<TextView>()
        val binding = TxtPageViewBinding(
            pageIndex = safePageIndex,
            segments = segments,
        )

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }
        if (segments.isNotEmpty()) {
            val textView = createPagedTextView(segments, maxLineWidthPx, density, palette.ink)
            column.addView(textView)
            pageTextViews += textView
        }
        binding.column = column
        binding.textViews = pageTextViews.toMutableList()
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            val padV = (24 * density).toInt()
            setPadding(0, padV, 0, padV)
            contentDescription = "第 ${safePageIndex + 1} 页，共 $pageCount 页"
            addView(column)
        }
        binding.container = container
        return container.also { trackPageView(it, binding) }
    }

    private suspend fun calculatePagedPageWindows(
        requestedViewportWidthPx: Int = viewportWidthPx,
        requestedViewportHeightPx: Int = viewportHeightPx,
    ): TxtPaginationResult? {
        val snapshot = paginationSnapshot(requestedViewportWidthPx, requestedViewportHeightPx) ?: return null
        val windows = withContext(paginationDispatcher) {
            paginationMutex.withLock {
                if (snapshot.generation != paginationGeneration.get()) return@withLock emptyList()
                buildPagedPageWindows(snapshot)
            }
        }
        return TxtPaginationResult(snapshot, windows)
    }

    private fun paginationSnapshot(
        requestedViewportWidthPx: Int,
        requestedViewportHeightPx: Int,
    ): TxtPaginationSnapshot? {
        val document = txtDocument ?: return null
        val total = document.paragraphCount
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val widthPx = if (requestedViewportWidthPx > 0) {
            requestedViewportWidthPx
        } else {
            metrics.widthPixels.coerceAtLeast(1)
        }
        val heightPx = if (requestedViewportHeightPx > 0) {
            requestedViewportHeightPx
        } else {
            metrics.heightPixels.coerceAtLeast(1)
        }
        val contentWidthPx = ((TxtParagraphAdapter.MAX_LINE_WIDTH_DP * density)
            .coerceAtMost((widthPx - 56 * density)))
            .toInt().coerceAtLeast(1)
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, metrics)
        val contentHeightPx = (heightPx - 48 * density).toInt().coerceAtLeast(1)
        val rowVerticalPaddingPx = (PAGED_PARAGRAPH_GAP_DP * density).toInt()
        return TxtPaginationSnapshot(
            document = document,
            totalParagraphs = total,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
            textSizePx = textSizePx,
            rowVerticalPaddingPx = rowVerticalPaddingPx,
            lineSpacingMultiplier = lineSpacingMultiplier.coerceAtLeast(0.1f),
            typeface = resolveTypeface(),
            fontId = currentFontId,
            viewportWidthPx = requestedViewportWidthPx,
            viewportHeightPx = requestedViewportHeightPx,
            generation = paginationGeneration.incrementAndGet(),
        )
    }

    private suspend fun buildPagedPageWindows(snapshot: TxtPaginationSnapshot): List<TxtPageWindow> {
        val paint = TextPaint().apply {
            textSize = snapshot.textSizePx
            typeface = snapshot.typeface
        }
        val pages = mutableListOf<TxtPageWindow>()
        val pageSegments = mutableListOf<TxtPageSegment>()
        var usedHeightPx = 0

        fun flushPage() {
            if (pageSegments.isEmpty()) return
            pages += TxtPageWindow(pageSegments.toList())
            pageSegments.clear()
            usedHeightPx = 0
        }

        for (index in 0 until snapshot.totalParagraphs) {
            currentCoroutineContext().ensureActive()
            if (snapshot.generation != paginationGeneration.get()) return emptyList()
            val paragraph = snapshot.document.readParagraph(index)
            val layout = StaticLayout.Builder
                .obtain(paragraph, 0, paragraph.length, paint, snapshot.contentWidthPx)
                .setLineSpacing(0f, snapshot.lineSpacingMultiplier)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            val measuredLineStarts = fallbackMeasuredLineStarts(
                paragraph,
                paint,
                snapshot.contentWidthPx,
                layout.lineCount,
            )
            val lineCount = measuredLineStarts?.lastIndex ?: layout.lineCount.coerceAtLeast(1)
            val measuredLineHeight = maxOf(
                ceil(
                    (paint.fontMetrics.descent - paint.fontMetrics.ascent) * snapshot.lineSpacingMultiplier,
                ).toInt(),
                ceil(snapshot.textSizePx * snapshot.lineSpacingMultiplier).toInt(),
                1,
            )

            fun lineStart(line: Int): Int = measuredLineStarts?.get(line) ?: layout.getLineStart(line)

            fun linesHeight(startLine: Int, endLineExclusive: Int): Int =
                if (measuredLineStarts != null) {
                    (endLineExclusive - startLine).coerceAtLeast(1) * measuredLineHeight
                } else {
                    layout.getLineBottom(endLineExclusive - 1) - layout.getLineTop(startLine)
                }

            var startLine = 0
            while (startLine < lineCount) {
                val availableHeight = snapshot.contentHeightPx - usedHeightPx
                var endLine = startLine
                while (endLine < lineCount) {
                    val candidateHeight =
                        linesHeight(startLine, endLine + 1) + snapshot.rowVerticalPaddingPx
                    if (candidateHeight > availableHeight) break
                    endLine++
                }
                if (endLine == startLine && pageSegments.isNotEmpty()) {
                    flushPage()
                    continue
                }
                if (endLine == startLine) {
                    endLine = (startLine + 1).coerceAtMost(lineCount)
                }
                val startOffset = lineStart(startLine).coerceIn(0, paragraph.length)
                val endOffset = if (endLine < lineCount) {
                    lineStart(endLine).coerceIn(startOffset, paragraph.length)
                } else {
                    paragraph.length
                }
                val segmentHeight = linesHeight(startLine, endLine) + snapshot.rowVerticalPaddingPx
                pageSegments += TxtPageSegment(index, startOffset, endOffset)
                usedHeightPx += segmentHeight
                startLine = endLine
                if (startLine < lineCount) flushPage()
            }
        }
        flushPage()
        return pages
    }

    private fun TxtPaginationResult.isCurrent(
        expectedViewportWidthPx: Int = viewportWidthPx,
        expectedViewportHeightPx: Int = viewportHeightPx,
    ): Boolean =
        snapshot.document === txtDocument &&
            snapshot.fontId == currentFontId &&
            snapshot.viewportWidthPx == expectedViewportWidthPx &&
            snapshot.viewportHeightPx == expectedViewportHeightPx &&
            snapshot.textSizePx == TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                context.resources.displayMetrics,
            ) &&
            snapshot.lineSpacingMultiplier == lineSpacingMultiplier.coerceAtLeast(0.1f) &&
            snapshot.generation == paginationGeneration.get()

    private fun installPagedPageWindows(windows: List<TxtPageWindow>) {
        pagedPageWindows = windows
        pagedParagraphStarts = windows.map { it.anchor.paragraphIndex }
    }

    /** Robolectric and a few vendor shapers may report one unbreakable line for long CJK/token
     * runs. Use a linear code-point estimate only for that inconsistent geometry. */
    private fun fallbackMeasuredLineStarts(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        staticLineCount: Int,
    ): IntArray? {
        if (text.isEmpty() || staticLineCount > 1 || fallbackMeasuredWidth(text, paint) <= widthPx) return null
        val starts = mutableListOf(0)
        var lineStart = 0
        var lineWidth = 0f
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val advance = fallbackEstimatedAdvance(codePoint, paint)
            if (index > lineStart && lineWidth + advance > widthPx) {
                starts += index
                lineStart = index
                lineWidth = 0f
            }
            lineWidth += advance
            index += Character.charCount(codePoint)
        }
        if (starts.last() != text.length) starts += text.length
        return starts.toIntArray()
    }

    private fun fallbackMeasuredWidth(text: String, paint: TextPaint): Float {
        var estimated = 0f
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            estimated += fallbackEstimatedAdvance(codePoint, paint)
            index += Character.charCount(codePoint)
        }
        return maxOf(paint.measureText(text), estimated)
    }

    private fun fallbackEstimatedAdvance(codePoint: Int, paint: TextPaint): Float =
        paint.textSize * when {
            Character.isWhitespace(codePoint) -> 0.33f
            Character.isIdeographic(codePoint) || codePoint >= 0x1F000 -> 1f
            else -> 0.55f
        }

    private fun pageForParagraph(paragraphIndex: Int): Int = pageForPosition(paragraphIndex, 0)

    private fun pageForPosition(paragraphIndex: Int, charOffset: Int): Int {
        if (paragraphCount() == 0) return 0
        if (pagedPageWindows.isEmpty()) return paragraphIndex
        val paragraphLength = paragraphAt(paragraphIndex.coerceIn(0, paragraphCount() - 1)).length
        val offset = charOffset.coerceIn(0, paragraphLength)
        val exact = pagedPageWindows.indexOfFirst { page ->
            page.segments.any { segment ->
                segment.paragraphIndex == paragraphIndex &&
                    offset >= segment.startOffset &&
                    (offset < segment.endOffset ||
                        offset == segment.endOffset && segment.endOffset == paragraphLength)
            }
        }
        if (exact >= 0) return exact
        return pagedPageWindows.indexOfFirst { page ->
            page.segments.any { it.paragraphIndex == paragraphIndex }
        }.takeIf { it >= 0 } ?: paragraphIndex.coerceIn(0, pagedPageWindows.lastIndex)
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
    }

    /**
     * Host ViewPager reports real layout size (rotation / multi-window / insets).
     * Positive sizes are stored; invalid/non-changing sizes are ignored. When PAGED,
     * repacks with the host viewport (not displayMetrics alone), preserves the
     * paragraph/ByteOffset anchor, requests the containing page, and rebinds active pages.
     */
    override suspend fun setViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val changed = widthPx != viewportWidthPx || heightPx != viewportHeightPx
        if (!changed) return
        if (_pagingKind.value != PagingKind.PAGED) {
            viewportWidthPx = widthPx
            viewportHeightPx = heightPx
            return
        }
        // Preserve paragraph/source anchor across rotation — never publish bare Page.
        val position = currentParagraphPosition()
        publishLocator(locatorForPosition(position))
        rebuildPagedRangesAfterTypographyChange(
            requestedViewportWidthPx = widthPx,
            requestedViewportHeightPx = heightPx,
            commitViewport = true,
            preservedPosition = position,
        )
    }

    private fun buildToc(document: TxtDocument): List<TocEntry> {
        if (document.paragraphCount == 0) return emptyList()
        return (0 until document.paragraphCount).mapNotNull { index ->
            val paragraph = document.readParagraph(index)
            val heading = paragraph.lineSequence().firstOrNull()?.trim().orEmpty()
            if (!isTxtHeading(heading)) return@mapNotNull null
            TocEntry(
                title = heading.take(48),
                locator = locatorForIndex(index, document.paragraphCount),
            )
        }
    }

    /** Publish locator and keep chapter chrome in sync with TOC + progression. */
    private fun publishLocator(locator: Locator) {
        _currentLocator.value = locator
        publishChapterInfo(locator)
    }

    private fun publishChapterInfo(locator: Locator = _currentLocator.value) {
        _chapterInfo.value = chapterInfoFromOrderedToc(
            tocEntries = _tableOfContents.value,
            totalProgression = locator.totalProgression,
            documentTitleFallback = DOCUMENT_TITLE_FALLBACK,
        )
    }

    private fun reportProgression(rv: RecyclerView) {
        reportProgression(rv, scrollReportGeneration.get())
    }

    private fun reportProgression(rv: RecyclerView, surfaceGeneration: Long) {
        if (
            surfaceGeneration != scrollReportGeneration.get() ||
            recyclerView !== rv ||
            _pagingKind.value != PagingKind.CONTINUOUS
        ) {
            return
        }
        val total = paragraphCount()
        if (total == 0) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION } ?: return
        val pending = pendingProgrammaticScroll
        if (pending != null) {
            if (pending.isStaleReport(first)) {
                return
            }
            pendingProgrammaticScroll = null
        }
        val position = paragraphPositionAtViewportY(rv, first, rv.paddingTop)
        publishLocator(locatorForPosition(position, total))
    }

    private fun restoreContinuousPosition(
        rv: RecyclerView,
        position: TxtParagraphPosition,
    ): Boolean {
        val holder = rv.findViewHolderForAdapterPosition(position.paragraphIndex)
            as? TxtParagraphAdapter.ParagraphHolder ?: return false
        val textView = holder.textView
        val layout = textView.layout ?: return false
        if (layout.lineCount == 0) return false
        val safeOffset = position.charOffset.coerceIn(0, textView.text.length)
        val line = layout.getLineForOffset(safeOffset)
        val lineTopInRecycler = holder.itemView.top + textView.top + textView.totalPaddingTop +
            layout.getLineTop(line)
        val distanceToAnchor = lineTopInRecycler - rv.paddingTop
        if (
            abs(distanceToAnchor) <= CONTINUOUS_ANCHOR_TOLERANCE_PX ||
            isAtContinuousScrollBoundary(rv, distanceToAnchor)
        ) {
            // The document boundary can leave a final/initial line visible without enough content
            // on the other side to place it exactly at the viewport top. It is already stable.
            return true
        }

        // goTo already positioned the paragraph at offset zero. LinearLayoutManager corrects a
        // negative offset for the first/only oversized row back to its start gap, so apply the
        // in-row correction only after that paragraph has a stable layout.
        rv.scrollBy(0, distanceToAnchor)
        return false
    }

    private fun isAtContinuousScrollBoundary(rv: RecyclerView, distanceToAnchor: Int): Boolean {
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return false
        val itemCount = rv.adapter?.itemCount ?: return false
        if (itemCount <= 0) return true
        return when {
            distanceToAnchor > 0 -> {
                val lastItem = layoutManager.findViewByPosition(itemCount - 1) ?: return false
                layoutManager.getDecoratedBottom(lastItem) <=
                    rv.height - rv.paddingBottom + CONTINUOUS_ANCHOR_TOLERANCE_PX
            }
            distanceToAnchor < 0 -> {
                val firstItem = layoutManager.findViewByPosition(0) ?: return false
                layoutManager.getDecoratedTop(firstItem) >=
                    rv.paddingTop - CONTINUOUS_ANCHOR_TOLERANCE_PX
            }
            else -> true
        }
    }

    private fun captureContinuousTypographyAnchor(): TxtParagraphPosition? {
        if (_pagingKind.value != PagingKind.CONTINUOUS) return null
        if (paragraphCount() == 0) return null
        pendingContinuousTypographyAnchor?.let { return it }
        val rv = recyclerView ?: return currentParagraphPosition()
        val lm = rv.layoutManager as? LinearLayoutManager ?: return currentParagraphPosition()
        val first = lm.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: return currentParagraphPosition()
        return paragraphPositionAtViewportY(rv, first, rv.paddingTop)
    }

    private fun scheduleContinuousTypographyRestore(position: TxtParagraphPosition?) {
        position ?: return
        if (_pagingKind.value != PagingKind.CONTINUOUS) return
        val anchor = position.clamp(paragraphCount().coerceAtLeast(1), ::paragraphAt)
        pendingContinuousTypographyAnchor = anchor
        val generation = ++continuousTypographyRestoreGeneration
        publishLocator(locatorForPosition(anchor))
        val rv = recyclerView ?: run {
            pendingContinuousTypographyAnchor = null
            return
        }
        // The holder's layout at schedule time is still the pre-reflow geometry: notifyDataSetChanged
        // only rebinds during the next layout pass. Completing against that stale layout would leave
        // the surface at the old pixel position, so completion must wait for a re-created layout.
        val staleLayout = (rv.findViewHolderForAdapterPosition(anchor.paragraphIndex)
            as? TxtParagraphAdapter.ParagraphHolder)?.textView?.layout
        retryContinuousHolderRestore(
            rv = rv,
            paragraphIndex = anchor.paragraphIndex,
            isCurrent = {
                generation == continuousTypographyRestoreGeneration &&
                    recyclerView === rv &&
                    _pagingKind.value == PagingKind.CONTINUOUS
            },
            attempt = {
                val layout = (rv.findViewHolderForAdapterPosition(anchor.paragraphIndex)
                    as? TxtParagraphAdapter.ParagraphHolder)?.textView?.layout
                if (layout == null || layout === staleLayout) {
                    // Child content has not reflowed yet; keep waiting for the holder layout.
                    false
                } else if (restoreContinuousPosition(rv, anchor)) {
                    pendingContinuousTypographyAnchor = null
                    publishLocator(locatorForPosition(anchor))
                    true
                } else {
                    false
                }
            },
        )
    }

    /**
     * Retries [attempt] until it returns true, driven by the target holder's content layout
     * (child layout changes + child attach/detach) instead of the RecyclerView's own outer
     * bounds. notifyDataSetChanged reflows/rebinds children without necessarily changing the
     * RecyclerView bounds, and a one-shot rv.post can run before the holder has its new layout.
     * [isCurrent] gates every callback (generation + exact RecyclerView identity + CONTINUOUS
     * mode); once stale, callbacks detach themselves without touching newer restore state.
     */
    private fun retryContinuousHolderRestore(
        rv: RecyclerView,
        paragraphIndex: Int,
        isCurrent: () -> Boolean,
        attempt: () -> Boolean,
    ) {
        lateinit var childLayoutListener: View.OnLayoutChangeListener
        lateinit var childAttachListener: RecyclerView.OnChildAttachStateChangeListener
        var observedChild: View? = null
        var completed = false
        var retryPosted = false
        var geometryCheckPosted = false
        var layoutCompletionRetryScheduled = false

        fun detach() {
            observedChild?.removeOnLayoutChangeListener(childLayoutListener)
            observedChild = null
            rv.removeOnChildAttachStateChangeListener(childAttachListener)
        }

        fun tryRestore() {
            if (completed) return
            if (!isCurrent()) {
                detach()
                return
            }
            if (rv.isComputingLayout) {
                if (!retryPosted) {
                    retryPosted = true
                    rv.post {
                        retryPosted = false
                        tryRestore()
                    }
                }
                return
            }
            if (rv.isLayoutRequested) {
                val layoutManager = rv.layoutManager as? TxtParagraphLayoutManager
                if (layoutManager != null && !layoutCompletionRetryScheduled) {
                    layoutCompletionRetryScheduled = true
                    layoutManager.doAfterNextLayout {
                        layoutCompletionRetryScheduled = false
                        if (!retryPosted) {
                            retryPosted = true
                            rv.post {
                                retryPosted = false
                                tryRestore()
                            }
                        }
                    }
                }
                return
            }
            val holder = rv.findViewHolderForAdapterPosition(paragraphIndex)
                as? TxtParagraphAdapter.ParagraphHolder
            val child = holder?.itemView
            if (child !== observedChild) {
                observedChild?.removeOnLayoutChangeListener(childLayoutListener)
                observedChild = child
                child?.addOnLayoutChangeListener(childLayoutListener)
                geometryCheckPosted = false
            }
            if (attempt()) {
                completed = true
                detach()
            } else if (!completed && !geometryCheckPosted) {
                // scrollBy is synchronous, but child bounds do not promise an OnLayoutChange
                // callback. One posted geometry check completes the second restore phase.
                geometryCheckPosted = true
                if (!retryPosted) {
                    retryPosted = true
                    rv.post {
                        retryPosted = false
                        tryRestore()
                    }
                }
            }
        }

        childLayoutListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                geometryCheckPosted = false
                tryRestore()
            }
        }
        childAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                geometryCheckPosted = false
                tryRestore()
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                if (view === observedChild) {
                    observedChild?.removeOnLayoutChangeListener(childLayoutListener)
                    observedChild = null
                }
            }
        }
        rv.addOnChildAttachStateChangeListener(childAttachListener)
        tryRestore()
        if (!retryPosted) {
            retryPosted = true
            rv.post {
                retryPosted = false
                tryRestore()
            }
        }
    }

    /**
     * After scrollToPositionWithOffset lands, aligns [position.charOffset]'s line at the viewport
     * top once the target holder/content layout exists (continuous goTo inner-offset semantics).
     * [navigationGeneration] is captured after this goTo invalidated older restores; every
     * callback is gated on it so a later goTo/typography/remount/mode change self-detaches this
     * restore instead of pulling the viewport back to the previous navigation target.
     */
    private fun scheduleContinuousInnerOffsetRestore(
        rv: RecyclerView,
        position: TxtParagraphPosition,
        navigationGeneration: Long,
    ) {
        if (position.charOffset <= 0) return
        retryContinuousHolderRestore(
            rv = rv,
            paragraphIndex = position.paragraphIndex,
            isCurrent = {
                navigationGeneration == continuousTypographyRestoreGeneration &&
                    recyclerView === rv &&
                    _pagingKind.value == PagingKind.CONTINUOUS
            },
            attempt = { restoreContinuousPosition(rv, position) },
        )
    }

    private fun paragraphPositionAtViewportY(
        rv: RecyclerView,
        paragraphIndex: Int,
        viewportY: Int,
    ): TxtParagraphPosition {
        val holder = rv.findViewHolderForAdapterPosition(paragraphIndex)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: return TxtParagraphPosition(paragraphIndex, 0)
        val textView = holder.textView
        val layout = textView.layout ?: return TxtParagraphPosition(paragraphIndex, 0)
        if (layout.lineCount == 0) return TxtParagraphPosition(paragraphIndex, 0)
        val textTopInRecycler = holder.itemView.top + textView.top + textView.totalPaddingTop
        val localY = (viewportY - textTopInRecycler).coerceIn(0, (layout.height - 1).coerceAtLeast(0))
        val line = layout.getLineForVertical(localY)
        return TxtParagraphPosition(paragraphIndex, layout.getLineStart(line))
    }

    override suspend fun goTo(locator: Locator) {
        val total = paragraphCount().coerceAtLeast(1)
        // PAGED packing: ViewPager slots are pages, not paragraphs (see pageIndexForLocator /
        // setMode). pageRequestCallback must receive a page index; LocatorStrategy.Page is a
        // page slot from the host settle path, not a paragraph index.
        val paged = _pagingKind.value == PagingKind.PAGED && pagedPageWindows.isNotEmpty()
        val position = paragraphPositionForLocator(locator)
        val target = locatorForPosition(position, total)
        recyclerView?.let { rv ->
            // A pending continuous typography restore is stale once the user navigates: drop its
            // anchor/generation so the old callback cannot scroll the surface back after goTo lands.
            pendingContinuousTypographyAnchor = null
            continuousTypographyRestoreGeneration += 1L
            // This exact generation owns this navigation's inner-offset restore; any later goTo,
            // typography change, remount, or mode switch bumps it and orphans this callback.
            val navigationGeneration = continuousTypographyRestoreGeneration
            pendingProgrammaticScroll = PendingProgrammaticScroll(
                fromIndex = currentVisibleParagraphIndex() ?: currentParagraphIndex(),
                targetIndex = position.paragraphIndex,
            )
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position.paragraphIndex, 0)
            if (_pagingKind.value == PagingKind.CONTINUOUS) {
                scheduleContinuousInnerOffsetRestore(rv, position, navigationGeneration)
            }
        }
        publishLocator(target)
        pageRequestCallback?.invoke(
            if (paged) pageForPosition(position.paragraphIndex, position.charOffset)
            else position.paragraphIndex,
        )
    }

    private fun paragraphPositionForLocator(locator: Locator): TxtParagraphPosition {
        val total = paragraphCount().coerceAtLeast(1)
        val paged = _pagingKind.value == PagingKind.PAGED && pagedPageWindows.isNotEmpty()
        return when (val s = locator.strategy) {
            is LocatorStrategy.Section -> TxtParagraphPosition(s.elementIndex, s.charOffset)
            is LocatorStrategy.Page -> {
                if (paged) {
                    val page = s.index.coerceIn(0, pagedPageWindows.lastIndex)
                    pagedPageWindows[page].anchor.toParagraphPosition()
                } else {
                    TxtParagraphPosition(s.index, 0)
                }
            }
            // PageText is PDF text-point identity — never treat index as TXT paragraph.
            is LocatorStrategy.PageText,
            LocatorStrategy.Unknown,
            -> TxtParagraphPosition(locator.totalProgression?.let { (it * total).toInt() } ?: 0, 0)
            is LocatorStrategy.ByteOffset -> paragraphPositionForByteOffset(s.offset)
                ?: TxtParagraphPosition(
                    locator.totalProgression?.let { (it * total).toInt() } ?: 0,
                    0,
                )
        }.clamp(total, ::paragraphAt)
    }

    override suspend fun goToAdjacentChapter(delta: Int) {
        val toc = _tableOfContents.value
        if (toc.isEmpty()) return
        val current = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = _currentLocator.value.totalProgression,
            documentTitleFallback = DOCUMENT_TITLE_FALLBACK,
        )
        if (current.kind != ChapterInfo.Kind.CHAPTER) return
        val target = adjacentTocEntry(toc, current.currentIndex, delta) ?: return
        goTo(target.locator)
    }

    override suspend fun search(query: String): List<ReaderSearchHit> = withContext(Dispatchers.IO) {
        txtDocument?.search(query).orEmpty()
    }

    override fun clearTextSelection() {
        _currentTextSelection.value = null
        recyclerView?.clearVisibleNativeSelections()
        activePageTextViews.forEach { textView ->
            (textView as? SelectionAwareTextView)?.clearNativeTextSelection()
        }
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        textAnnotations = annotations
        refreshBoundHighlightSurfaces()
    }

    override fun setSearchHighlight(hit: ReaderSearchHit?) {
        searchHighlightHit = hit
        refreshBoundHighlightSurfaces()
    }

    private fun refreshBoundHighlightSurfaces() {
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTextAnnotations()
        activePageTextViews.forEach { textView ->
            val pageText = pagedPageTexts[textView]
            if (pageText != null) {
                val refreshed = buildPagedText(pageText.segments)
                pagedPageTexts[textView] = refreshed
                textView.text = refreshed.text
            } else {
                val index = textView.tag as? Int ?: return@forEach
                textView.text = paragraphAt(index).withTextHighlightSpans(
                    ranges = highlightRangesForParagraph(index),
                    searchRanges = searchHighlightRangesForParagraph(index),
                )
            }
        }
    }

    private fun createPagedTextView(
        segments: List<TxtPageSegment>,
        maxLineWidthPx: Int,
        density: Float,
        inkColor: Int,
    ): SelectionAwareTextView = SelectionAwareTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        maxWidth = maxLineWidthPx
        setPadding(
            (28 * density).toInt(),
            (10 * density).toInt(),
            (28 * density).toInt(),
            (10 * density).toInt(),
        )
        gravity = Gravity.START
        typeface = resolveTypeface()
        includeFontPadding = false
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        val pageText = buildPagedText(segments)
        tag = pageText.anchorParagraphIndex
        text = pageText.text
        setTextIsSelectable(true)
        onSelectionRangeChanged = { start, end ->
            _currentTextSelection.value = txtDocument?.selectionForPagedText(pageText, start, end)
        }
        applyTextStyle(inkColor)
        pagedPageTexts[this] = pageText
    }

    private fun buildPagedText(segments: List<TxtPageSegment>): TxtPagedText {
        val paragraphs = txtDocument?.readParagraphs(segments.map(TxtPageSegment::paragraphIndex).distinct()).orEmpty()
        return buildTxtPagedText(
            segments = segments,
            paragraphProvider = { index -> paragraphs[index].orEmpty() },
            highlightRangesProvider = ::highlightRangesForParagraph,
            searchHighlightRangesProvider = ::searchHighlightRangesForParagraph,
            paragraphGapPx = (PAGED_PARAGRAPH_GAP_DP * context.resources.displayMetrics.density).toInt(),
        )
    }

    private fun updateTextSelection(paragraphIndex: Int, start: Int, end: Int) {
        _currentTextSelection.value = txtDocument?.selectionForParagraphRange(paragraphIndex, start, end)
    }

    private fun highlightRangesForParagraph(paragraphIndex: Int) =
        txtDocument?.highlightRangesForParagraph(paragraphIndex, textAnnotations).orEmpty()

    private fun searchHighlightRangesForParagraph(paragraphIndex: Int): List<ReaderTextHighlightRange> {
        val hit = searchHighlightHit ?: return emptyList()
        val range = txtDocument?.searchHighlightRangeForParagraph(paragraphIndex, hit) ?: return emptyList()
        return listOf(range)
    }

    private fun locatorForIndex(index: Int, totalItems: Int = paragraphCount().coerceAtLeast(1)): Locator =
        locatorForPosition(TxtParagraphPosition(index, 0), totalItems)

    private fun locatorForPosition(
        position: TxtParagraphPosition,
        totalItems: Int = paragraphCount().coerceAtLeast(1),
    ): Locator {
        if (paragraphCount() == 0 || totalItems <= 0) {
            return Locator(
                strategy = LocatorStrategy.ByteOffset(offset = 0L, length = 0),
                progression = 0f,
                totalProgression = 0f,
            )
        }
        val total = totalItems.coerceAtLeast(1)
        val safeIndex = position.paragraphIndex.coerceIn(0, total - 1)
        val range = txtDocument?.rangeAt(safeIndex)
        val paragraph = txtDocument?.readParagraph(safeIndex).orEmpty()
        val charOffset = position.charOffset.coerceIn(0, paragraph.length)
        val prefixBytes = paragraph.substring(0, charOffset)
            .toByteArray(txtDocument?.charsetDetection?.charset ?: Charsets.UTF_8)
            .size
            .toLong()
        val absoluteOffset = (range?.startByte ?: 0L) + prefixBytes
        val remainingLength = ((range?.length ?: 0L) - prefixBytes).coerceAtLeast(0L)
        val withinParagraph = if (paragraph.isEmpty()) 0f else charOffset.toFloat() / paragraph.length
        return Locator(
            strategy = LocatorStrategy.ByteOffset(
                offset = absoluteOffset,
                length = remainingLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
            progression = safeIndex.toFloat() / total,
            totalProgression = ((safeIndex + withinParagraph) / total).coerceIn(0f, 1f),
        )
    }

    private fun currentParagraphPosition(): TxtParagraphPosition {
        if (paragraphCount() == 0) return TxtParagraphPosition(0, 0)
        val total = paragraphCount().coerceAtLeast(1)
        return when (val strategy = _currentLocator.value.strategy) {
            is LocatorStrategy.Section -> TxtParagraphPosition(strategy.elementIndex, strategy.charOffset)
            is LocatorStrategy.Page -> if (pagedPageWindows.isNotEmpty()) {
                pagedPageWindows[strategy.index.coerceIn(0, pagedPageWindows.lastIndex)].anchor.toParagraphPosition()
            } else {
                TxtParagraphPosition(strategy.index, 0)
            }
            is LocatorStrategy.ByteOffset -> paragraphPositionForByteOffset(strategy.offset)
                ?: TxtParagraphPosition(0, 0)
            is LocatorStrategy.PageText,
            LocatorStrategy.Unknown,
            -> TxtParagraphPosition(0, 0)
        }.clamp(total, ::paragraphAt)
    }

    private fun currentParagraphIndex(): Int = currentParagraphPosition().paragraphIndex

    private fun paragraphPositionForByteOffset(offset: Long): TxtParagraphPosition? {
        val document = txtDocument ?: return null
        if (document.paragraphCount <= 0) return null
        val paragraphIndex = document.indexForOffset(offset).coerceIn(0, document.paragraphCount - 1)
        val range = document.rangeAt(paragraphIndex) ?: return TxtParagraphPosition(paragraphIndex, 0)
        val paragraph = document.readParagraph(paragraphIndex)
        val localByteOffset = (offset - range.startByte).coerceAtLeast(0L)
        return TxtParagraphPosition(
            paragraphIndex,
            paragraph.characterIndexForByteOffset(localByteOffset, document.charsetDetection.charset),
        )
    }

    override suspend fun close() {
        paginationGeneration.incrementAndGet()
        val closingDocument = txtDocument
        txtDocument = null
        withContext(paginationDispatcher) {
            paginationMutex.withLock {
                closingDocument?.close()
            }
        }
        recyclerView = null
        scrollReportGeneration.incrementAndGet()
        pageRequestCallback = null
        pendingProgrammaticScroll = null
        pendingContinuousTypographyAnchor = null
        continuousTypographyRestoreGeneration += 1L
        activePageTextViews.clear()
        activePageContainers.clear()
        activePageBindings.clear()
        viewportWidthPx = 0
        viewportHeightPx = 0
        pagedParagraphStarts = emptyList()
        pagedPageWindows = emptyList()
        pagedPageTexts.clear()
        _currentTextSelection.value = null
        textAnnotations = emptyList()
        searchHighlightHit = null
        txtFingerprint = null
        pendingEngineState = null
        _tableOfContents.value = emptyList()
        publishLocator(
            Locator(strategy = LocatorStrategy.ByteOffset(0L, 0), progression = 0f, totalProgression = 0f),
        )
    }

    override suspend fun saveState(): ByteArray = withContext(Dispatchers.IO) {
        val document = txtDocument ?: return@withContext ByteArray(0)
        val fingerprint = txtFingerprint ?: document.fingerprint().also { txtFingerprint = it }
        document.engineState(fingerprint)
    }

    override suspend fun restoreState(state: ByteArray) {
        pendingEngineState = state.takeIf { it.isNotEmpty() }
    }

    override suspend fun setFontSize(sp: Float) {
        if (fontSizeSp == sp) return
        val continuousAnchor = captureContinuousTypographyAnchor()
        fontSizeSp = sp
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateFontSize(sp)
        activePageTextViews.forEach { it.applyTextStyle() }
        if (!rebuildPagedRangesAfterTypographyChange()) {
            scheduleContinuousTypographyRestore(continuousAnchor)
        }
    }

    override suspend fun setSerifFont(useSourceHan: Boolean) {
        val continuousAnchor = captureContinuousTypographyAnchor()
        this.useSourceHan = useSourceHan
        currentFontId = if (useSourceHan) "source_han" else "system"
        withContext(Dispatchers.Main) {
            (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTypeface(resolveTypeface())
            if (!rebuildPagedRangesAfterTypographyChange()) {
                scheduleContinuousTypographyRestore(continuousAnchor)
            }
        }
    }

    override suspend fun setFont(fontId: String) {
        if (currentFontId == fontId) return
        val continuousAnchor = captureContinuousTypographyAnchor()
        currentFontId = fontId
        useSourceHan = fontId == "source_han"
        withContext(Dispatchers.Main) {
            (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTypeface(resolveTypeface())
            if (!rebuildPagedRangesAfterTypographyChange()) {
                scheduleContinuousTypographyRestore(continuousAnchor)
            }
        }
    }

    private fun resolveTypeface(): Typeface =
        dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)

    /**
     * PAGED 模式下排版/视口变化后重算装箱，回调新页号，并刷新已挂载页（pageCount 不变也要 rebind）。
     */
    private suspend fun rebuildPagedRangesAfterTypographyChange(
        requestedViewportWidthPx: Int = viewportWidthPx,
        requestedViewportHeightPx: Int = viewportHeightPx,
        commitViewport: Boolean = false,
        preservedPosition: TxtParagraphPosition = currentPagedPageStartPosition(),
    ): Boolean {
        if (_pagingKind.value != PagingKind.PAGED) return false
        val result = calculatePagedPageWindows(
            requestedViewportWidthPx = requestedViewportWidthPx,
            requestedViewportHeightPx = requestedViewportHeightPx,
        ) ?: return false
        if (
            !result.isCurrent(requestedViewportWidthPx, requestedViewportHeightPx) ||
            _pagingKind.value != PagingKind.PAGED
        ) {
            return false
        }
        if (commitViewport) {
            viewportWidthPx = requestedViewportWidthPx
            viewportHeightPx = requestedViewportHeightPx
        }
        installPagedPageWindows(result.windows.rebasedAt(preservedPosition))
        _pageCount.value = pagedPageWindows.size.coerceAtLeast(1)
        publishLocator(locatorForPosition(preservedPosition))
        pageRequestCallback?.invoke(
            pageForPosition(preservedPosition.paragraphIndex, preservedPosition.charOffset),
        )
        refreshActivePageContents()
        return true
    }

    /**
     * Typography changes are anchored to the first source character on the visible page. Search,
     * selection, and annotation navigation can leave [currentLocator] inside that page; promoting
     * that inner locator to the next layout's page start makes the text jump under the reader.
     */
    private fun currentPagedPageStartPosition(): TxtParagraphPosition {
        val current = currentParagraphPosition()
        if (pagedPageWindows.isEmpty()) return current
        return pagedPageWindows[
            pageForPosition(current.paragraphIndex, current.charOffset)
        ].anchor.toParagraphPosition()
    }

    private fun TxtPageSegment.contains(position: TxtParagraphPosition): Boolean =
        paragraphIndex == position.paragraphIndex &&
            position.charOffset >= startOffset &&
            (
                position.charOffset < endOffset ||
                    position.charOffset == endOffset && endOffset == paragraphAt(paragraphIndex).length
                )

    /**
     * Keep the saved viewport start as an actual page boundary after reflow. A global repack may
     * otherwise place a few earlier lines above the anchor even when the host opens the containing
     * page. Splitting only that page preserves every source character and leaves all other windows
     * unchanged.
     */
    private fun List<TxtPageWindow>.rebasedAt(
        position: TxtParagraphPosition,
    ): List<TxtPageWindow> {
        val pageIndex = indexOfFirst { page ->
            page.segments.any { segment ->
                segment.contains(position)
            }
        }
        if (pageIndex < 0) return this
        val page = this[pageIndex]
        if (page.anchor.toParagraphPosition() == position) return this
        val segmentIndex = page.segments.indexOfFirst { it.contains(position) }
        if (segmentIndex < 0) return this
        val segment = page.segments[segmentIndex]
        val before = buildList {
            addAll(page.segments.take(segmentIndex))
            if (position.charOffset > segment.startOffset) {
                add(segment.copy(endOffset = position.charOffset))
            }
        }
        val after = buildList {
            if (position.charOffset < segment.endOffset) {
                add(segment.copy(startOffset = position.charOffset))
            }
            addAll(page.segments.drop(segmentIndex + 1))
        }
        if (after.isEmpty()) return this
        return buildList(size + if (before.isEmpty()) 0 else 1) {
            addAll(this@rebasedAt.take(pageIndex))
            if (before.isNotEmpty()) add(TxtPageWindow(before))
            add(TxtPageWindow(after))
            addAll(this@rebasedAt.drop(pageIndex + 1))
        }
    }

    /**
     * Rebind every active PAGED page by stable [TxtPageViewBinding.pageIndex].
     * Rebuilds paragraph grouping/text even when packed pageCount is unchanged.
     */
    private fun refreshActivePageContents() {
        if (pagedPageWindows.isEmpty()) return
        val pageCount = pagedPageWindows.size.coerceAtLeast(1)
        val density = context.resources.displayMetrics.density
        val maxLineWidthPx = (TxtParagraphAdapter.MAX_LINE_WIDTH_DP * density).toInt()
        val palette = paletteFor(themeMode, context.resources.configuration)
        val detachedTextViews = mutableSetOf<TextView>()
        val attachedTextViews = mutableListOf<TextView>()

        activePageBindings.forEach { binding ->
            val column = binding.column ?: return@forEach
            val container = binding.container ?: return@forEach
            val pageIndex = binding.pageIndex
            val segments = pagedPageWindows.getOrNull(pageIndex)?.segments ?: return@forEach
            binding.segments = segments

            detachedTextViews.addAll(binding.textViews)
            binding.textViews.forEach(pagedPageTexts::remove)
            column.removeAllViews()
            val pageTextViews = mutableListOf<TextView>()
            if (segments.isNotEmpty()) {
                val textView = createPagedTextView(segments, maxLineWidthPx, density, palette.ink)
                column.addView(textView)
                pageTextViews += textView
                attachedTextViews += textView
            }
            binding.textViews = pageTextViews
            container.contentDescription = "第 ${pageIndex + 1} 页，共 $pageCount 页"
        }

        if (detachedTextViews.isNotEmpty() || attachedTextViews.isNotEmpty()) {
            activePageTextViews.removeAll(detachedTextViews)
            activePageTextViews.addAll(attachedTextViews)
        }
    }

    override suspend fun setTxtEncodingOverride(charsetName: String?) {
        encodingOverride = charsetName
        val uri = currentUri ?: return
        // openBook clears packing and sets pageCount = paragraphCount; restore PAGED pack after.
        val wasPaged = _pagingKind.value == PagingKind.PAGED
        // Capture anchors before reopen: source bytes are unchanged, so ByteOffset is strongest.
        val savedStrategy = _currentLocator.value.strategy
        val savedParagraphCount = paragraphCount()
        val savedParagraphIndex = currentParagraphIndex()
        val savedProgression = _currentLocator.value.totalProgression
        openBook(uri)
        if (wasPaged) {
            // openBook does not reset pagingKind; rebuild packing with stored host viewport.
            val result = calculatePagedPageWindows()
            if (result != null && result.isCurrent()) {
                installPagedPageWindows(result.windows)
            }
            if (pagedPageWindows.isNotEmpty()) {
                _pageCount.value = pagedPageWindows.size.coerceAtLeast(1)
            }
            val totalParas = paragraphCount().coerceAtLeast(1)
            val targetIndex = resolveEncodingReopenParagraph(
                savedStrategy = savedStrategy,
                savedParagraphCount = savedParagraphCount,
                savedParagraphIndex = savedParagraphIndex,
                savedProgression = savedProgression,
                totalParas = totalParas,
            )
            goTo(locatorForIndex(targetIndex, totalParas))
            refreshActivePageContents()
        } else {
            val totalParas = paragraphCount().coerceAtLeast(1)
            val targetIndex = resolveEncodingReopenParagraph(
                savedStrategy = savedStrategy,
                savedParagraphCount = savedParagraphCount,
                savedParagraphIndex = savedParagraphIndex,
                savedProgression = savedProgression,
                totalParas = totalParas,
            )
            goTo(locatorForIndex(targetIndex, totalParas))
        }
    }

    /**
     * Encoding reopen restore order:
     * 1. ByteOffset via [TxtDocument.indexForOffset] (source file bytes did not change)
     * 2. Equal paragraph count → exact saved index
     * 3. Progression approximate structure-change fallback, then saved index
     */
    private fun resolveEncodingReopenParagraph(
        savedStrategy: LocatorStrategy,
        savedParagraphCount: Int,
        savedParagraphIndex: Int,
        savedProgression: Float?,
        totalParas: Int,
    ): Int {
        val total = totalParas.coerceAtLeast(1)
        if (savedStrategy is LocatorStrategy.ByteOffset) {
            val fromOffset = txtDocument?.indexForOffset(savedStrategy.offset)
            if (fromOffset != null) {
                return fromOffset.coerceIn(0, total - 1)
            }
        }
        if (total == savedParagraphCount.coerceAtLeast(1)) {
            return savedParagraphIndex.coerceIn(0, total - 1)
        }
        return savedProgression?.let { p ->
            (p * total).toInt().coerceIn(0, total - 1)
        } ?: savedParagraphIndex.coerceIn(0, total - 1)
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        if (lineSpacingMultiplier == multiplier) return
        val continuousAnchor = captureContinuousTypographyAnchor()
        lineSpacingMultiplier = multiplier
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateLineSpacing(multiplier)
        activePageTextViews.forEach { it.applyTextStyle() }
        if (!rebuildPagedRangesAfterTypographyChange()) {
            scheduleContinuousTypographyRestore(continuousAnchor)
        }
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val palette = paletteFor(mode, context.resources.configuration)
        recyclerView?.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateInkColor(palette.ink)
        activePageContainers.forEach {
            it.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        }
        activePageTextViews.forEach { it.applyTextStyle(palette.ink) }
    }

    override suspend fun setMode(mode: ReadingMode) {
        val targetKind = when (mode) {
            ReadingMode.SCROLL -> PagingKind.CONTINUOUS
            ReadingMode.PAGED -> PagingKind.PAGED
        }
        val startingKind = _pagingKind.value
        val position = withContext(Dispatchers.Main) {
            // A mode switch away from CONTINUOUS must preserve an already-captured typography
            // anchor: the pending source position predates any reflow and is canonical, so use it
            // instead of recomputing from the current (possibly reflowed) pixel geometry.
            val anchor = if (_pagingKind.value == PagingKind.CONTINUOUS) {
                pendingContinuousTypographyAnchor ?: currentVisibleParagraphPosition()
            } else {
                currentParagraphPosition()
            }
            // Invalidate every restore armed on the previous surface so no stale callback can
            // republish or scroll the new mode's surface; the preserved anchor is captured above.
            scrollReportGeneration.incrementAndGet()
            pendingContinuousTypographyAnchor = null
            continuousTypographyRestoreGeneration += 1L
            publishLocator(locatorForPosition(anchor))
            if (targetKind != PagingKind.PAGED) {
                _pagingKind.value = targetKind
                paginationGeneration.incrementAndGet()
                pagedParagraphStarts = emptyList()
                pagedPageWindows = emptyList()
                _pageCount.value = paragraphCount()
            }
            anchor
        }
        if (targetKind == PagingKind.PAGED) {
            val result = calculatePagedPageWindows()
            if (result == null) {
                // Mode is applied before openBook (cold open); there is no document to pack yet.
                // Publish PAGED without advertising a page count — openBook installs the windows
                // and publishes the count before the host can bind any page slot.
                withContext(Dispatchers.Main) {
                    if (_pagingKind.value == startingKind) _pagingKind.value = PagingKind.PAGED
                }
                return
            }
            withContext(Dispatchers.Main) {
                if (!result.isCurrent() || _pagingKind.value != startingKind) return@withContext
                installPagedPageWindows(result.windows)
                // Publish PAGED only after the new windows are installed so a host can never
                // observe PAGED backed by empty/uninstalled windows during the switch.
                _pagingKind.value = PagingKind.PAGED
                _pageCount.value = pagedPageWindows.size.coerceAtLeast(1)
                refreshActivePageContents()
                pageRequestCallback?.invoke(pageForPosition(position.paragraphIndex, position.charOffset))
            }
        }
    }

    override fun pageIndexForLocator(locator: Locator): Int {
        if (_pagingKind.value != PagingKind.PAGED || pagedPageWindows.isEmpty()) {
            return super.pageIndexForLocator(locator)
        }
        val total = paragraphCount().coerceAtLeast(1)
        val position = when (val s = locator.strategy) {
            is LocatorStrategy.Section -> TxtParagraphPosition(s.elementIndex, s.charOffset)
            is LocatorStrategy.Page -> return s.index.coerceIn(0, pagedPageWindows.lastIndex)
            // PageText must not map to a paged paragraph slot — fall back like Unknown.
            is LocatorStrategy.PageText,
            LocatorStrategy.Unknown,
            -> TxtParagraphPosition(locator.totalProgression?.let { (it * total).toInt() } ?: 0, 0)
            is LocatorStrategy.ByteOffset -> paragraphPositionForByteOffset(s.offset)
                ?: TxtParagraphPosition(0, 0)
        }.clamp(total, ::paragraphAt)
        return pageForPosition(position.paragraphIndex, position.charOffset)
    }

    private fun currentVisibleParagraphIndex(): Int? {
        val rv = recyclerView ?: return null
        val total = paragraphCount().coerceAtLeast(1)
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val viewportCenter = rv.paddingTop + (rv.height - rv.paddingTop - rv.paddingBottom) / 2
        val centered = (0 until rv.childCount).mapNotNull { index ->
            val child = rv.getChildAt(index) ?: return@mapNotNull null
            val position = lm.getPosition(child).takeIf { it != RecyclerView.NO_POSITION } ?: return@mapNotNull null
            val childCenter = (child.top + child.bottom) / 2
            position to abs(childCenter - viewportCenter)
        }.minByOrNull { it.second }?.first
        return (centered ?: lm.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION })
            ?.coerceIn(0, total - 1)
    }

    /**
     * Paragraph + line-start charOffset at the viewport center of the SCROLL surface. Unlike
     * [currentVisibleParagraphIndex], a mid-paragraph viewport keeps its actual line-start
     * charOffset instead of collapsing to paragraph start (mode-switch anchor preservation).
     */
    private fun currentVisibleParagraphPosition(): TxtParagraphPosition {
        val rv = recyclerView ?: return currentParagraphPosition()
        val paragraphIndex = currentVisibleParagraphIndex() ?: return currentParagraphPosition()
        val holder = rv.findViewHolderForAdapterPosition(paragraphIndex)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: return TxtParagraphPosition(paragraphIndex, 0)
        val textView = holder.textView
        val layout = textView.layout ?: return TxtParagraphPosition(paragraphIndex, 0)
        if (layout.lineCount == 0) return TxtParagraphPosition(paragraphIndex, 0)
        val viewportCenter = rv.paddingTop + (rv.height - rv.paddingTop - rv.paddingBottom) / 2
        val textTopInRecycler = holder.itemView.top + textView.top + textView.totalPaddingTop
        val localY = (viewportCenter - textTopInRecycler).coerceIn(0, (layout.height - 1).coerceAtLeast(0))
        val line = layout.getLineForVertical(localY)
        return TxtParagraphPosition(paragraphIndex, layout.getLineStart(line))
    }

    private fun trackPageView(container: FrameLayout, binding: TxtPageViewBinding) {
        activePageContainers.add(container)
        activePageBindings.add(binding)
        activePageTextViews.addAll(binding.textViews)
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                activePageContainers.remove(container)
                activePageBindings.remove(binding)
                activePageTextViews.removeAll(binding.textViews.toSet())
                binding.textViews.forEach(pagedPageTexts::remove)
            }
        })
    }

    private fun TextView.applyTextStyle(color: Int = paletteFor(themeMode, context.resources.configuration).ink) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        setLineSpacing(0f, lineSpacingMultiplier)
        setTextColor(color)
    }

    private fun RecyclerView.clearVisibleNativeSelections() {
        for (index in 0 until childCount) {
            val container = getChildAt(index) as? ViewGroup ?: continue
            container.clearNativeSelectionsRecursively()
        }
    }

    private fun View.clearNativeSelectionsRecursively() {
        when (this) {
            is SelectionAwareTextView -> clearNativeTextSelection()
            is ViewGroup -> {
                for (index in 0 until childCount) {
                    getChildAt(index).clearNativeSelectionsRecursively()
                }
            }
        }
    }

    /**
     * Lets a long single paragraph retain its complete measured height inside the scroll surface.
     * RecyclerView's default child spec caps WRAP_CONTENT rows at the viewport height, preventing
     * inner-line scrolling when a TXT source has no paragraph breaks.
     */
    private class TxtParagraphLayoutManager(context: Context) : LinearLayoutManager(context) {
        private val afterLayoutCallbacks = mutableListOf<() -> Unit>()

        fun doAfterNextLayout(callback: () -> Unit) {
            afterLayoutCallbacks += callback
        }

        override fun onLayoutCompleted(state: RecyclerView.State?) {
            super.onLayoutCompleted(state)
            if (afterLayoutCallbacks.isEmpty()) return
            val callbacks = afterLayoutCallbacks.toList()
            afterLayoutCallbacks.clear()
            callbacks.forEach { callback -> callback() }
        }

        override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) {
            val lp = child.layoutParams as? RecyclerView.LayoutParams
            if (lp == null || lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                super.measureChildWithMargins(child, widthUsed, heightUsed)
                return
            }
            val availableWidth = (
                width - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin - widthUsed
            ).coerceAtLeast(0)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                availableWidth,
                View.MeasureSpec.EXACTLY,
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            child.measure(widthSpec, heightSpec)
        }
    }

    private companion object {
        private const val DOCUMENT_TITLE_FALLBACK = "正文"
        private const val PAGED_PARAGRAPH_GAP_DP = 20
        private const val CONTINUOUS_ANCHOR_TOLERANCE_PX = 2
        private val TXT_HEADING = Regex("""^(第.{1,12}[章节回卷部篇集].*|Chapter\s+\d+.*|CHAPTER\s+\d+.*)$""")

        private fun isTxtHeading(value: String): Boolean =
            value.length in 2..48 && TXT_HEADING.matches(value)

        private fun paletteFor(mode: ThemeMode, configuration: Configuration): ReaderPalette {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val p = readerPaletteFor(mode, systemNight)
            return ReaderPalette(p.paper, p.ink, p.isNight)
        }
    }

    private fun paragraphCount(): Int = txtDocument?.paragraphCount ?: 0

    private fun paragraphAt(index: Int): String {
        val document = txtDocument ?: return ""
        if (index !in 0 until document.paragraphCount) return ""
        return document.readParagraph(index)
    }

    private fun resolveReadableFile(uri: Uri, requiresFingerprint: Boolean): CopiedTxtFile {
        resolveAppPrivateFile(uri)?.let { file ->
            return CopiedTxtFile(
                file = file,
                fingerprint = if (requiresFingerprint) TxtDocumentFingerprint.fromFile(file) else null,
                deleteOnClose = false,
            )
        }
        val temp = File.createTempFile("readflow-txt-", ".txt", context.cacheDir)
        try {
            val crc = if (requiresFingerprint) CRC32() else null
            var byteLength = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(TxtDocument.BLOCK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        crc?.update(buffer, 0, read)
                        if (crc != null) {
                            byteLength += read.toLong()
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return CopiedTxtFile(
                file = temp,
                fingerprint = if (crc != null) {
                    TxtDocumentFingerprint(byteLength, crc.value)
                } else {
                    null
                },
                deleteOnClose = true,
            )
        } catch (throwable: Throwable) {
            temp.delete()
            throw throwable
        }
    }

    private fun resolveAppPrivateFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val candidate = uri.path?.let(::File)?.takeIf { it.exists() } ?: return null
        val appRoots = listOf(context.filesDir, context.cacheDir).map { it.canonicalFile }
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { file ->
            appRoots.any { root -> file.path == root.path || file.path.startsWith("${root.path}${File.separator}") }
        }
    }
}

private data class ReaderPalette(val paper: Int, val ink: Int, val isNight: Boolean)
private data class CopiedTxtFile(
    val file: File,
    val fingerprint: TxtDocumentFingerprint?,
    val deleteOnClose: Boolean,
)

/**
 * Mutable binding for an active PAGED page. Keyed by stable [pageIndex];
 * paragraph range and the composited page TextView update on typography/viewport rebuild so
 * selection/highlight mappings stay current without relying on host page destruction.
 */
internal class TxtPageViewBinding(
    var pageIndex: Int,
    var segments: List<TxtPageSegment>,
    var container: FrameLayout? = null,
    var column: LinearLayout? = null,
    var textViews: MutableList<TextView> = mutableListOf(),
)

internal data class TxtPageSegment(
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class TxtPageWindow(
    val segments: List<TxtPageSegment>,
) {
    init {
        require(segments.isNotEmpty())
    }

    val anchor: TxtPageSegment
        get() = segments.first()
}

private data class TxtPaginationSnapshot(
    val document: TxtDocument,
    val totalParagraphs: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val textSizePx: Float,
    val rowVerticalPaddingPx: Int,
    val lineSpacingMultiplier: Float,
    val typeface: Typeface,
    val fontId: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val generation: Long,
)

private data class TxtPaginationResult(
    val snapshot: TxtPaginationSnapshot,
    val windows: List<TxtPageWindow>,
)

private data class TxtParagraphPosition(
    val paragraphIndex: Int,
    val charOffset: Int,
) {
    fun clamp(total: Int, paragraphProvider: (Int) -> String): TxtParagraphPosition {
        if (total <= 0) return TxtParagraphPosition(0, 0)
        val index = paragraphIndex.coerceIn(0, total.coerceAtLeast(1) - 1)
        return TxtParagraphPosition(index, charOffset.coerceIn(0, paragraphProvider(index).length))
    }
}

private fun TxtPageSegment.toParagraphPosition(): TxtParagraphPosition =
    TxtParagraphPosition(paragraphIndex, startOffset)

private data class PendingProgrammaticScroll(
    val fromIndex: Int,
    val targetIndex: Int,
) {
    fun isStaleReport(reportedIndex: Int): Boolean =
        when {
            fromIndex < targetIndex -> reportedIndex < targetIndex
            fromIndex > targetIndex -> reportedIndex > targetIndex
            else -> false
        }
}
