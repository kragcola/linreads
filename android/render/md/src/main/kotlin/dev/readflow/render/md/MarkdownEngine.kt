package dev.readflow.render.md

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReaderTypographyRange
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.adjacentTocEntry
import dev.readflow.core.model.chapterInfoFromOrderedToc
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SearchHighlightableReaderEngine
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.withTextHighlightSpans
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
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
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Markdown engine (Markwon 4.6.2).
 *
 * SCROLL: single [ScrollView] + [TextView] (existing behaviour).
 * PAGED: [PagedReaderEngine] slots over cached rendered [Spanned]; page boundaries use
 * [StaticLayout] line geometry so bottoms never clip mid-line. Markwon parse runs only on
 * open / content change; page-turn binds cached slices.
 *
 * Viewport: after the host reports [setViewportSize], pagination uses that size (not
 * displayMetrics). Active page views are keyed by stable page index with a mutable binding
 * so equal-pageCount typography rebuilds refresh text/selection base correctly.
 */
class MarkdownEngine(
    private val context: Context,
    private val paginationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) :
    ReaderEngine,
    PagedReaderEngine,
    TextSelectableReaderEngine,
    TextAnnotatableReaderEngine,
    SearchHighlightableReaderEngine {

    override val id: String = "md-markwon"
    override val format: BookFormat = BookFormat.MD
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

    private val _pageCount = MutableStateFlow(1)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private val _currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
    override val currentTextSelection: StateFlow<ReaderTextSelection?> = _currentTextSelection.asStateFlow()

    private var document: MarkdownDocument = MarkdownDocument.parse("")
    private var fontSizeSp: Float = ReaderTypographyRange.DEFAULT_FONT_SIZE.toFloat()
    private var lineSpacingMultiplier: Float = ReaderTypographyRange.DEFAULT_LINE_SPACING
    private var currentFontId: String = "system_serif"
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    /** Transient selected search hit; independent of view instances for mode remount repaint. */
    private var searchHighlightHit: ReaderSearchHit? = null
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null
    private var suppressLocatorUpdates = false
    /**
     * Bumps on open/close/createView remount so deferred ScrollView.post {} highlight refresh
     * callbacks and the SCROLL onScrollChange listener cannot mutate a newer view tree or
     * republish an old book's locator.
     */
    private var highlightRefreshGeneration: Long = 0L
    private var pendingScrollTypographyAnchor: Locator? = null
    private var scrollTypographyRestoreGeneration: Long = 0L
    /**
     * Latest-operation-wins token for [scheduleScrollRestore]: every new scheduled restore bumps
     * it, orphaning all earlier scroll/content listeners and delayed retry posts so an older
     * restore can never scroll or republish after a newer goTo/typography/mode action.
     */
    private var scrollRestoreTransaction: Long = 0L
    /** ScrollView identity that owns the active SCROLL highlight surface (null when detached). */
    private var highlightRefreshScrollView: ScrollView? = null
    private var highlightRefreshTextView: TextView? = null

    /** Full Markwon-rendered Spanned for the open document (no highlights). Rebuilt on open. */
    private var cachedRendered: Spanned = SpannableStringBuilder("")
    /** Page windows over [cachedRendered] line geometry; empty in SCROLL. */
    private var pageWindows: List<MarkdownPageWindow> = emptyList()
    private val paginationGeneration = AtomicLong()
    private val paginationMutex = Mutex()
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null

    /**
     * Host-reported ViewPager viewport. Zero means "not yet laid out" — fall back to
     * displayMetrics until the first real layout arrives.
     */
    private var viewportWidthPx: Int = 0
    private var viewportHeightPx: Int = 0

    /**
     * Full-document annotation highlight ranges in absolute rendered offsets.
     * Recomputed on open / setTextAnnotations; page bind only filters by window.
     */
    private var cachedHighlightRanges: List<ReaderTextHighlightRange> = emptyList()
    /** Full-document transient search highlight (absolute rendered offsets), separate from annotations. */
    private var cachedSearchHighlightRanges: List<ReaderTextHighlightRange> = emptyList()

    /** Weak tracking of active PAGED views for selection clear / annotation refresh / typography. */
    private val activePageContainers = Collections.newSetFromMap(WeakHashMap<FrameLayout, Boolean>())
    private val activePageBindings = Collections.newSetFromMap(WeakHashMap<PageViewBinding, Boolean>())

    private val markwon by lazy {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    private val textPaddingPx: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            TEXT_PADDING_DP,
            context.resources.displayMetrics,
        ).toInt().coerceAtLeast(1)

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        // Fence the old surface before any background parse or document replacement. The old
        // ScrollView may still receive main-thread callbacks while this suspend function is on IO.
        withContext(Dispatchers.Main) {
            invalidateHighlightRefreshCallbacks()
        }
        paginationGeneration.incrementAndGet()
        val markdown = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
        val parsed = MarkdownDocument.parse(markdown)
        val initial = parsed.locatorForOffset(0)
        // Markwon.toMarkdown is pure text→Spanned (no TextView); safe off-main.
        val rendered = markwon.toMarkdown(markdown)
        withContext(Dispatchers.Main) {
            // Install the new document only after the previous surface has been fenced on Main;
            // no callback can observe a mixed old-view/new-document pair.
            document = parsed
            cachedRendered = rendered
            // Preserve the last valid window set until the rebuilt set is installed: a host
            // observing PAGED must never see a published pageCount backed by empty windows.
            document.clearMappingCache()
            searchHighlightHit = null
            cachedSearchHighlightRanges = emptyList()
            recomputeCachedHighlightRanges()
            _tableOfContents.value = parsed.tableOfContents
            publishLocator(initial)
            if (_pagingKind.value == PagingKind.PAGED) {
                if (rebuildPageWindows(requestPageForAnchor = false)) {
                    refreshActivePageContents()
                }
            } else {
                _pageCount.value = 1
            }
        }
        initial
    }

    /** Publish locator and keep chapter chrome in sync with TOC + progression. */
    private fun publishLocator(locator: Locator) {
        _currentLocator.value = locator
        _chapterInfo.value = chapterInfoFromOrderedToc(
            tocEntries = _tableOfContents.value,
            totalProgression = locator.totalProgression,
            documentTitleFallback = DOCUMENT_TITLE_FALLBACK,
        )
    }

    override fun createView(): View {
        val palette = paletteFor(themeMode, context.resources.configuration)
        val padding = textPaddingPx
        // Remount: drop ownership of any prior ScrollView/TextView so stale post{} cannot write them.
        invalidateHighlightRefreshCallbacks()
        // Capture the generation AFTER invalidation: this onScrollChange listener may only
        // publish progress while this exact surface is the engine's current SCROLL pair.
        // openBook/close/another createView bump the generation and/or swap the fields, so a
        // callback from this (now detached) surface must never republish an old locator.
        val generation = highlightRefreshGeneration
        val tv = SelectionAwareTextView(context).apply {
            textSize = fontSizeSp
            setLineSpacing(0f, lineSpacingMultiplier)
            typeface = resolveTypeface()
            setPadding(padding, padding, padding, padding)
            setTextColor(palette.ink)
            setTextIsSelectable(true)
            onSelectionRangeChanged = ::updateScrollTextSelection
        }
        // Prefer cached Spanned so mode remount does not re-parse Markwon.
        val base = ensureCachedRendered()
        markwon.setParsedMarkdown(tv, base)
        applyTextAnnotations(tv)
        textView = tv

        val sv = ScrollView(context).apply {
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            // WRAP_CONTENT height is required so content can exceed the viewport and scroll.
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (suppressLocatorUpdates) return@setOnScrollChangeListener
                // Reject stale callbacks: after remount/open/close the generation is bumped or
                // this surface is no longer the active pair — publishing here would mix a newer
                // document with this surface's old rendered text.
                if (generation != highlightRefreshGeneration) return@setOnScrollChangeListener
                if (_pagingKind.value != PagingKind.CONTINUOUS) return@setOnScrollChangeListener
                if (scrollView !== this@apply || textView !== tv) return@setOnScrollChangeListener
                publishLocator(
                    document.locatorForRenderedOffset(
                        renderedOffset = tv.characterOffsetForScrollY(scrollY),
                        renderedText = tv.text,
                    ),
                )
            }
        }
        scrollView = sv
        highlightRefreshScrollView = sv
        highlightRefreshTextView = tv
        // Restore source anchor after mode remount (host calls createView for SCROLL).
        // Must wait for a real layout pass — posting while unattached burns retries at width=0.
        scheduleScrollRestore(_currentLocator.value)
        return sv
    }

    override fun createPageView(pageIndex: Int): View {
        val windows = pageWindows.ifEmpty {
            listOf(MarkdownPageWindow(0, 0, 0, 0))
        }
        val pageCount = windows.size.coerceAtLeast(1)
        val safeIndex = pageIndex.coerceIn(0, pageCount - 1)
        val window = windows[safeIndex]
        val base = ensureCachedRendered()
        val slice = pageSlice(base, window)
        val highlighted = slice.withTextHighlightSpans(
            ranges = filterHighlightRangesForWindow(window, slice.length),
            searchRanges = filterSearchHighlightRangesForWindow(window, slice.length),
        )
        val palette = paletteFor(themeMode, context.resources.configuration)
        val padding = textPaddingPx
        val binding = PageViewBinding(
            pageIndex = safeIndex,
            startOffset = window.startOffset,
            endOffset = window.endOffset,
        )
        val pageTextView = SelectionAwareTextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            textSize = fontSizeSp
            setLineSpacing(0f, lineSpacingMultiplier)
            typeface = resolveTypeface()
            setPadding(padding, padding, padding, padding)
            setTextColor(palette.ink)
            setTextIsSelectable(true)
            tag = binding
            text = highlighted
            // Selection must read the *current* binding, not a captured window.startOffset.
            onSelectionRangeChanged = { start, end ->
                updatePagedTextSelection(binding.startOffset, start, end)
            }
        }
        binding.textView = pageTextView
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            contentDescription = "第 ${safeIndex + 1} 页，共 $pageCount 页"
            addView(pageTextView)
        }
        trackPageView(container, binding)
        return container
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
    }

    override suspend fun setViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val changed = widthPx != viewportWidthPx || heightPx != viewportHeightPx
        if (!changed) return
        if (_pagingKind.value != PagingKind.PAGED) {
            viewportWidthPx = widthPx
            viewportHeightPx = heightPx
            return
        }
        // Preserve source Section anchor across rotation / real size change.
        val anchor = normalizeToSourceSection(_currentLocator.value)
        publishLocator(anchor)
        if (
            rebuildPageWindows(
                requestPageForAnchor = true,
                requestedViewportWidthPx = widthPx,
                requestedViewportHeightPx = heightPx,
                commitViewport = true,
            )
        ) {
            refreshActivePageContents()
        }
    }

    override fun pageIndexForLocator(locator: Locator): Int {
        if (_pagingKind.value != PagingKind.PAGED) {
            return super.pageIndexForLocator(locator)
        }
        val windows = pageWindows
        if (windows.isEmpty()) return 0
        when (val strategy = locator.strategy) {
            // Host ViewPager settles with bare Page only; PageText is not a MD page slot.
            is LocatorStrategy.Page ->
                return strategy.index.coerceIn(0, windows.lastIndex)
            is LocatorStrategy.PageText,
            is LocatorStrategy.Section,
            is LocatorStrategy.ByteOffset,
            LocatorStrategy.Unknown,
            -> Unit
        }
        val renderedOffset = document.renderedOffsetFor(locator, ensureCachedRendered())
        return pageIndexForRenderedOffset(windows, renderedOffset)
    }

    override suspend fun goTo(locator: Locator) {
        when (_pagingKind.value) {
            PagingKind.CONTINUOUS -> goToScroll(locator)
            PagingKind.PAGED -> goToPaged(locator)
        }
    }

    private suspend fun goToScroll(locator: Locator) {
        val offset = document.offsetFor(locator)
        val target = document.locatorForOffset(offset)
        // A pending typography restore is stale once the user navigates: drop its anchor and
        // generation so its callback cannot republish an old locator or scroll the viewport back.
        // scheduleScrollRestore below also bumps the restore transaction to orphan its listeners.
        pendingScrollTypographyAnchor = null
        scrollTypographyRestoreGeneration += 1L
        publishLocator(target)
        if (scrollView == null || textView == null) return
        scheduleScrollRestore(target)
    }

    private fun goToPaged(locator: Locator) {
        val sourceLocator = when (locator.strategy) {
            is LocatorStrategy.Page -> {
                // Host ViewPager emits Page locators on settle; normalize to source Section
                // at the page start so bookmarks/progress stay typography-stable.
                val pageIndex = pageIndexForLocator(locator)
                stableSourceLocatorForPage(pageIndex)
            }
            // PageText is foreign PDF identity — resolve via totalProgression (offsetFor else).
            is LocatorStrategy.PageText,
            is LocatorStrategy.Section,
            is LocatorStrategy.ByteOffset,
            LocatorStrategy.Unknown,
            -> document.locatorForOffset(document.offsetFor(locator))
        }
        publishLocator(sourceLocator)
        val pageIndex = pageIndexForLocator(sourceLocator)
        pageRequestCallback?.invoke(pageIndex)
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

    /**
     * Source [Locator] for a page start that round-trips through
     * [pageIndexForLocator] back to the same page.
     *
     * Scans only within the page window using the cached rendered→source mapping.
     * No fixed 16-char guard: advances through the window until the mapped page index
     * is stable, or falls back to the last in-window offset.
     */
    private fun stableSourceLocatorForPage(pageIndex: Int): Locator {
        val windows = pageWindows
        if (windows.isEmpty()) return document.locatorForOffset(0)
        val safePage = pageIndex.coerceIn(0, windows.lastIndex)
        val window = windows[safePage]
        val rendered = ensureCachedRendered()
        if (window.startOffset >= window.endOffset || rendered.isEmpty()) {
            return document.locatorForRenderedOffset(window.startOffset.coerceAtLeast(0), rendered)
        }
        val endExclusive = window.endOffset.coerceIn(window.startOffset + 1, rendered.length + 1)
        var offset = window.startOffset.coerceIn(0, rendered.length)
        var locator = document.locatorForRenderedOffset(offset, rendered)
        // Monotonic scan within the window only — no arbitrary 16-char cap.
        while (
            offset + 1 < endExclusive &&
            offset + 1 <= rendered.length &&
            pageIndexForRenderedOffset(windows, document.renderedOffsetFor(locator, rendered)) < safePage
        ) {
            offset++
            locator = document.locatorForRenderedOffset(offset, rendered)
        }
        // Ensure round-trip: if still early (rare mapping pin), keep last candidate in window.
        val mappedPage = pageIndexForRenderedOffset(
            windows,
            document.renderedOffsetFor(locator, rendered),
        )
        if (mappedPage == safePage) return locator
        // Prefer first offset in window that maps back to this page.
        var probe = window.startOffset
        while (probe < endExclusive && probe <= rendered.length) {
            val candidate = document.locatorForRenderedOffset(probe, rendered)
            if (pageIndexForRenderedOffset(windows, document.renderedOffsetFor(candidate, rendered)) == safePage) {
                return candidate
            }
            probe++
        }
        return locator
    }

    /**
     * Restore SCROLL position after the ScrollView has a non-zero size.
     * Re-applies across layout passes (Robolectric and real devices remeasure WRAP_CONTENT
     * children and can clamp scrollY to 0 until content height is committed).
     */
    private fun scheduleScrollRestore(locator: Locator, onApplied: (() -> Unit)? = null) {
        val sv = scrollView ?: return
        // Capture generation so open/close/createView invalidation drops deferred restores even
        // when the same ScrollView instance is reused (openBook without remount).
        val generation = highlightRefreshGeneration
        // Latest-operation-wins: each new scheduled restore orphans every earlier listener and
        // delayed post, so an older restore can never scroll or republish after a newer action.
        val transaction = ++scrollRestoreTransaction
        var completionReported = false
        fun reportCompletion() {
            if (completionReported) return
            completionReported = true
            onApplied?.invoke()
        }
        lateinit var scrollListener: View.OnLayoutChangeListener
        lateinit var contentListener: View.OnLayoutChangeListener
        var observedTextView: TextView? = null
        var attemptInProgress = false
        var terminated = false
        var retryPosted = false
        var retryBudget = SCROLL_RESTORE_RETRY_BUDGET
        var layoutObserved = false
        lateinit var postRetry: () -> Unit

        fun detach() {
            terminated = true
            sv.removeOnLayoutChangeListener(scrollListener)
            observedTextView?.removeOnLayoutChangeListener(contentListener)
            observedTextView = null
        }

        fun attemptRestore() {
            // Already-completed or detached restores are no-ops; a posted retry runnable may
            // still be queued after the attempt that completed/terminated the transaction.
            if (completionReported || terminated) return
            // Robolectric and some Android layout paths deliver TextView layout callbacks
            // synchronously from ensureScrollTextViewMeasured(). Do not re-enter the same
            // restore transaction while it is measuring/layouting the content.
            if (attemptInProgress) return
            attemptInProgress = true
            try {
                if (
                    generation != highlightRefreshGeneration ||
                    transaction != scrollRestoreTransaction ||
                    scrollView !== sv
                ) {
                    detach()
                    return
                }
                // Retry on TextView content layout too: WRAP_CONTENT reflow commits its height on a
                // later pass, and the ScrollView's own outer bounds may not change while content does.
                val tv = textView
                if (tv != null && tv !== observedTextView) {
                    observedTextView?.removeOnLayoutChangeListener(contentListener)
                    observedTextView = tv
                    tv.addOnLayoutChangeListener(contentListener)
                }
                if (
                    sv.width > 0 &&
                    sv.height > 0 &&
                    restoreScrollToLocator(
                        locator = locator,
                        generation = generation,
                        transaction = transaction,
                        onRetry = postRetry,
                        allowRequestedLayout = layoutObserved,
                    )
                ) {
                    detach()
                    reportCompletion()
                }
            } finally {
                attemptInProgress = false
            }
        }

        /**
         * Bounded next-frame retry used only by the distance-not-converged branch. The budget is
         * replenished solely by a real layout listener pass (see below), never by this retry
         * itself, so a layout that never commits or an unreachable Y cannot self-repost forever.
         */
        postRetry = {
            if (!completionReported && !terminated && !retryPosted && retryBudget > 0) {
                retryPosted = true
                retryBudget -= 1
                sv.postOnAnimation {
                    retryPosted = false
                    if (completionReported || terminated) return@postOnAnimation
                    attemptRestore()
                }
            }
        }
        scrollListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                // A real layout pass replenishes the retry budget so the next attempt may use one
                // next-frame retry. Never replenish while an attempt is measuring/layouting: the
                // synchronous TextView.layout reentry below cannot feed an infinite retry loop.
                if (!attemptInProgress) {
                    layoutObserved = true
                    retryBudget = SCROLL_RESTORE_RETRY_BUDGET
                }
                attemptRestore()
            }
        }
        contentListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (!attemptInProgress) {
                    layoutObserved = true
                    retryBudget = SCROLL_RESTORE_RETRY_BUDGET
                }
                attemptRestore()
            }
        }
        sv.addOnLayoutChangeListener(scrollListener)
        attemptRestore()
        // Immediate attempts for already-laid-out hosts and post-attach frames.
        sv.post { attemptRestore() }
    }

    /**
     * @return true when scroll position was applied (or no scroll needed); false if still waiting
     * for measurable content/viewport.
     */
    private fun restoreScrollToLocator(
        locator: Locator,
        generation: Long = highlightRefreshGeneration,
        transaction: Long = scrollRestoreTransaction,
        onRetry: () -> Unit,
        allowRequestedLayout: Boolean = false,
    ): Boolean {
        if (generation != highlightRefreshGeneration || transaction != scrollRestoreTransaction) return true
        val sv = scrollView ?: return true
        val tv = textView ?: return true
        if (sv.width <= 0 || sv.height <= 0) return false
        // Prefer Layout metrics over View height — WRAP_CONTENT often under-reports until
        // an explicit UNSPECIFIED-height measure (esp. under Robolectric).
        ensureScrollTextViewMeasured(sv, tv)
        val layout = tv.layout ?: return false
        val renderedOffset = document.renderedOffsetFor(locator, tv.text)
        val contentHeight = layout.height + tv.totalPaddingTop + tv.totalPaddingBottom
        val maxScroll = (contentHeight - sv.height).coerceAtLeast(0)
        val visualLine = layout.getLineForOffset(renderedOffset.coerceIn(0, tv.text.length))
        val y = (layout.getLineTop(visualLine) + tv.totalPaddingTop).coerceIn(0, maxScroll)
        suppressLocatorUpdates = true
        try {
            // Commit content height into the ScrollView hierarchy before scrolling.
            if (tv.height < contentHeight || tv.layoutParams?.height != contentHeight) {
                val lp = tv.layoutParams ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    contentHeight,
                )
                lp.height = contentHeight
                tv.layoutParams = lp
                sv.requestLayout()
            }
            sv.scrollTo(0, y)
            // The first attempt after a typography change may see a positive but still-clamped
            // scrollY while the WRAP_CONTENT height request is pending. Require one real layout
            // listener pass before accepting that value; later passes may converge immediately.
            if (sv.isLayoutRequested && !allowRequestedLayout) {
                return false
            }
            // Completion is real only when the viewport actually reaches the computed target line.
            // A positive-but-still-clamped scrollY (content height under-committed) must keep
            // retrying instead of reporting success.
            if (abs(sv.scrollY - y) > SCROLL_RESTORE_TOLERANCE_PX) {
                onRetry()
                return false
            }
        } finally {
            suppressLocatorUpdates = false
        }
        if (
            generation != highlightRefreshGeneration ||
            transaction != scrollRestoreTransaction
        ) return true
        publishLocator(document.locatorForOffset(document.offsetFor(locator)))
        return true
    }

    private fun ensureScrollTextViewMeasured(sv: ScrollView, tv: TextView) {
        val width = (sv.width - sv.paddingLeft - sv.paddingRight).coerceAtLeast(0)
        if (width <= 0) return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tv.measure(widthSpec, heightSpec)
        val measuredH = tv.measuredHeight.coerceAtLeast(1)
        val measuredW = tv.measuredWidth.coerceAtLeast(width)
        val lp = tv.layoutParams
        if (lp != null && lp.height != measuredH) {
            lp.height = measuredH
            tv.layoutParams = lp
        }
        tv.layout(0, 0, measuredW, measuredH)
    }

    override suspend fun search(query: String): List<ReaderSearchHit> = withContext(Dispatchers.Default) {
        document.search(query)
    }

    override fun clearTextSelection() {
        _currentTextSelection.value = null
        if (_pagingKind.value == PagingKind.PAGED) {
            activePageBindings.forEach { binding ->
                (binding.textView as? SelectionAwareTextView)?.clearNativeTextSelection()
            }
            return
        }
        val selectionAwareTextView = textView as? SelectionAwareTextView ?: return
        val sv = scrollView ?: return
        suppressLocatorUpdates = true
        try {
            selectionAwareTextView.clearNativeTextSelection()
        } finally {
            suppressLocatorUpdates = false
        }
        publishLocator(
            document.locatorForRenderedOffset(
                renderedOffset = selectionAwareTextView.characterOffsetForScrollY(sv.scrollY),
                renderedText = selectionAwareTextView.text,
            ),
        )
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        textAnnotations = annotations
        recomputeCachedHighlightRanges()
        refreshBoundHighlightSurfaces()
    }

    override fun setSearchHighlight(hit: ReaderSearchHit?) {
        searchHighlightHit = hit
        recomputeCachedSearchHighlightRanges()
        refreshBoundHighlightSurfaces()
    }

    private fun refreshBoundHighlightSurfaces() {
        if (_pagingKind.value == PagingKind.PAGED) {
            refreshActivePageContents()
            return
        }
        textView?.let(::applyTextAnnotations)
    }

    private fun recomputeCachedHighlightRanges() {
        val base = ensureCachedRendered()
        cachedHighlightRanges = document.highlightRanges(textAnnotations, base)
    }

    private fun recomputeCachedSearchHighlightRanges() {
        val hit = searchHighlightHit
        if (hit == null) {
            cachedSearchHighlightRanges = emptyList()
            return
        }
        val base = ensureCachedRendered()
        cachedSearchHighlightRanges = listOfNotNull(document.searchHighlightRange(hit, base))
    }

    private fun filterHighlightRangesForWindow(
        window: MarkdownPageWindow,
        sliceLength: Int,
    ): List<ReaderTextHighlightRange> =
        filterAbsoluteRangesForWindow(cachedHighlightRanges, window, sliceLength)

    private fun filterSearchHighlightRangesForWindow(
        window: MarkdownPageWindow,
        sliceLength: Int,
    ): List<ReaderTextHighlightRange> =
        filterAbsoluteRangesForWindow(cachedSearchHighlightRanges, window, sliceLength)

    private fun filterAbsoluteRangesForWindow(
        ranges: List<ReaderTextHighlightRange>,
        window: MarkdownPageWindow,
        sliceLength: Int,
    ): List<ReaderTextHighlightRange> =
        ranges.mapNotNull { range ->
            val localStart = (range.start - window.startOffset).coerceAtLeast(0)
            val localEnd = (range.end - window.startOffset).coerceAtMost(sliceLength)
            if (localStart >= localEnd) null
            else range.copy(start = localStart, end = localEnd)
        }

    private fun applyTextAnnotations(view: TextView) {
        val base = ensureCachedRendered()
        if (cachedHighlightRanges.isEmpty() && textAnnotations.isNotEmpty()) {
            recomputeCachedHighlightRanges()
        }
        if (cachedSearchHighlightRanges.isEmpty() && searchHighlightHit != null) {
            recomputeCachedSearchHighlightRanges()
        }
        val highlightedText = base.withTextHighlightSpans(
            ranges = cachedHighlightRanges,
            searchRanges = cachedSearchHighlightRanges,
        )
        val sv = scrollView
        if (sv == null) {
            view.text = highlightedText
            return
        }

        val previousScrollY = sv.scrollY
        val previousLocator = _currentLocator.value
        val generation = highlightRefreshGeneration
        val expectedScrollView = sv
        val expectedTextView = view
        highlightRefreshScrollView = sv
        highlightRefreshTextView = view
        suppressLocatorUpdates = true
        try {
            view.text = highlightedText
            val immediateMaxScroll = (view.height - sv.height).coerceAtLeast(0)
            val immediateRestoreScrollY = previousScrollY.coerceIn(0, immediateMaxScroll)
            if (immediateRestoreScrollY != sv.scrollY) {
                sv.scrollTo(0, immediateRestoreScrollY)
            }
        } finally {
            suppressLocatorUpdates = false
        }
        sv.post {
            // Stale after remount/open/close: do not touch current surfaces or locator.
            if (generation != highlightRefreshGeneration) return@post
            if (highlightRefreshScrollView !== expectedScrollView) return@post
            if (highlightRefreshTextView !== expectedTextView) return@post
            if (scrollView !== expectedScrollView || textView !== expectedTextView) return@post
            val activeScrollView = expectedScrollView
            val activeTextView = expectedTextView
            val maxScroll = (activeTextView.height - activeScrollView.height).coerceAtLeast(0)
            val locatorScrollY = activeTextView.scrollYForCharacterOffset(
                document.renderedOffsetFor(previousLocator, activeTextView.text),
            ).coerceIn(0, maxScroll)
            val restoreScrollY = when {
                previousScrollY > 0 -> previousScrollY.coerceIn(0, maxScroll)
                else -> locatorScrollY
            }
            suppressLocatorUpdates = true
            try {
                activeScrollView.scrollTo(0, restoreScrollY)
            } finally {
                suppressLocatorUpdates = false
            }
            publishLocator(
                document.locatorForRenderedOffset(
                    renderedOffset = activeTextView.characterOffsetForScrollY(restoreScrollY),
                    renderedText = activeTextView.text,
                ),
            )
        }
    }

    private fun invalidateHighlightRefreshCallbacks() {
        highlightRefreshGeneration += 1L
        pendingScrollTypographyAnchor = null
        scrollTypographyRestoreGeneration += 1L
        scrollRestoreTransaction += 1L
        highlightRefreshScrollView = null
        highlightRefreshTextView = null
    }

    private fun updateScrollTextSelection(start: Int, end: Int) {
        val displayedText = textView?.text ?: ensureCachedRendered()
        _currentTextSelection.value = document.selectionForRenderedOffsets(start, end, displayedText)
    }

    private fun updatePagedTextSelection(baseOffset: Int, localStart: Int, localEnd: Int) {
        val absoluteStart = baseOffset + localStart
        val absoluteEnd = baseOffset + localEnd
        val rendered = ensureCachedRendered()
        _currentTextSelection.value =
            document.selectionForRenderedOffsets(absoluteStart, absoluteEnd, rendered)
    }

    override suspend fun close() {
        paginationGeneration.incrementAndGet()
        invalidateHighlightRefreshCallbacks()
        scrollView = null
        textView = null
        pageRequestCallback = null
        pageWindows = emptyList()
        cachedRendered = SpannableStringBuilder("")
        cachedHighlightRanges = emptyList()
        viewportWidthPx = 0
        viewportHeightPx = 0
        activePageBindings.clear()
        activePageContainers.clear()
        document.clearMappingCache()
        document = MarkdownDocument.parse("")
        _currentTextSelection.value = null
        textAnnotations = emptyList()
        searchHighlightHit = null
        cachedSearchHighlightRanges = emptyList()
        _tableOfContents.value = emptyList()
        _pageCount.value = 1
        _pagingKind.value = PagingKind.CONTINUOUS
        publishLocator(
            Locator(strategy = LocatorStrategy.ByteOffset(0L, 0), progression = 0f, totalProgression = 0f),
        )
    }

    override suspend fun setFontSize(sp: Float) {
        if (fontSizeSp == sp) return
        val scrollAnchor = captureScrollTypographyAnchor()
        fontSizeSp = sp
        textView?.textSize = sp
        activePageBindings.forEach { it.textView?.textSize = sp }
        rebuildAfterTypographyChange(scrollAnchor)
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        if (lineSpacingMultiplier == multiplier) return
        val scrollAnchor = captureScrollTypographyAnchor()
        lineSpacingMultiplier = multiplier
        textView?.setLineSpacing(0f, multiplier)
        activePageBindings.forEach { it.textView?.setLineSpacing(0f, multiplier) }
        rebuildAfterTypographyChange(scrollAnchor)
    }

    override suspend fun setSerifFont(useSourceHan: Boolean) {
        setFont(if (useSourceHan) "source_han" else "system_serif")
    }

    override suspend fun setFont(fontId: String) {
        if (currentFontId == fontId) return
        val scrollAnchor = captureScrollTypographyAnchor()
        currentFontId = fontId
        val face = resolveTypeface()
        textView?.typeface = face
        activePageBindings.forEach { it.textView?.typeface = face }
        rebuildAfterTypographyChange(scrollAnchor)
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val palette = paletteFor(mode, context.resources.configuration)
        scrollView?.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        textView?.setTextColor(palette.ink)
        activePageContainers.forEach {
            it.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        }
        activePageBindings.forEach { it.textView?.setTextColor(palette.ink) }
    }

    override suspend fun setMode(mode: ReadingMode) {
        val targetKind = when (mode) {
            ReadingMode.SCROLL -> PagingKind.CONTINUOUS
            ReadingMode.PAGED -> PagingKind.PAGED
        }
        val startingKind = _pagingKind.value
        withContext(Dispatchers.Main) {
            // Capture stable source anchor before mode switch.
            val anchor = when (_pagingKind.value) {
                PagingKind.CONTINUOUS -> {
                    val sv = scrollView
                    val tv = textView
                    if (sv != null && tv != null && tv.layout != null) {
                        document.locatorForRenderedOffset(
                            renderedOffset = tv.characterOffsetForScrollY(sv.scrollY),
                            renderedText = tv.text,
                        )
                    } else {
                        normalizeToSourceSection(_currentLocator.value)
                    }
                }
                PagingKind.PAGED -> normalizeToSourceSection(_currentLocator.value)
            }
            // A mode switch away from CONTINUOUS invalidates every active scroll restore before
            // the paged anchor is finalized; otherwise a stale typography/goTo callback could
            // republish an older anchor or yank the new surface back after the switch.
            pendingScrollTypographyAnchor = null
            scrollTypographyRestoreGeneration += 1L
            scrollRestoreTransaction += 1L
            publishLocator(anchor)
            if (targetKind != PagingKind.PAGED) {
                _pagingKind.value = targetKind
                paginationGeneration.incrementAndGet()
                pageWindows = emptyList()
                _pageCount.value = 1
            }
        }
        if (targetKind == PagingKind.PAGED) {
            withContext(Dispatchers.Main) {
                val rebuilt = rebuildPageWindows(
                    requestPageForAnchor = true,
                    expectedPagingKind = startingKind,
                )
                if (rebuilt) {
                    // Publish PAGED only after the new windows are installed so a host can
                    // never observe PAGED backed by empty/uninstalled windows during the switch.
                    _pagingKind.value = PagingKind.PAGED
                    refreshActivePageContents()
                }
            }
        }
    }

    /**
     * Rebuild page ranges after font/line-spacing/viewport changes.
     * Always requests the page for the current source anchor and refreshes active page
     * views even when [pageCount] stays equal (avoids stale slices).
     */
    private suspend fun rebuildAfterTypographyChange(scrollAnchor: Locator? = null) {
        if (_pagingKind.value != PagingKind.PAGED) {
            // TextView reflow changes line heights in place. Restore by source offset after the
            // new layout is measured so a typography change cannot leave the reader at the old
            // pixel Y (which is a different passage after reflow).
            scrollAnchor?.let(::restoreScrollTypographyAnchor)
            return
        }
        if (rebuildPageWindows(requestPageForAnchor = true)) {
            refreshActivePageContents()
        }
    }

    /** Captures the first visible rendered line as a stable source anchor before TextView reflow. */
    private fun captureScrollTypographyAnchor(): Locator? {
        if (_pagingKind.value != PagingKind.CONTINUOUS) return null
        pendingScrollTypographyAnchor?.let { return it }
        // Keep an exact source locator already owned by the engine (for example a goTo target).
        // Re-deriving from the viewport only gives the visual line start and silently discards an
        // inner-character anchor during a typography rebuild.
        val current = normalizeToSourceSection(_currentLocator.value)
        if ((current.strategy as? LocatorStrategy.Section)?.charOffset ?: 0 > 0) {
            return current
        }
        val sv = scrollView ?: return normalizeToSourceSection(_currentLocator.value)
        val tv = textView ?: return normalizeToSourceSection(_currentLocator.value)
        if (tv.layout == null) return normalizeToSourceSection(_currentLocator.value)
        return document.locatorForRenderedOffset(
            renderedOffset = tv.characterOffsetForScrollY(sv.scrollY),
            renderedText = tv.text,
        )
    }

    private fun restoreScrollTypographyAnchor(anchor: Locator) {
        val normalized = normalizeToSourceSection(anchor)
        pendingScrollTypographyAnchor = normalized
        val generation = ++scrollTypographyRestoreGeneration
        publishLocator(normalized)
        if (scrollView == null || textView == null) {
            pendingScrollTypographyAnchor = null
            return
        }
        scheduleScrollRestore(normalized) {
            if (generation == scrollTypographyRestoreGeneration) {
                pendingScrollTypographyAnchor = null
            }
        }
    }

    private suspend fun rebuildPageWindows(
        requestPageForAnchor: Boolean,
        requestedViewportWidthPx: Int = viewportWidthPx,
        requestedViewportHeightPx: Int = viewportHeightPx,
        commitViewport: Boolean = false,
        expectedPagingKind: PagingKind = PagingKind.PAGED,
    ): Boolean {
        val snapshot = paginationSnapshot(requestedViewportWidthPx, requestedViewportHeightPx)
        val windows = withContext(paginationDispatcher) {
            paginationMutex.withLock {
                if (snapshot.generation != paginationGeneration.get()) return@withLock null
                measurePageWindows(snapshot)
            }
        } ?: return false
        if (
            !snapshot.isCurrent(requestedViewportWidthPx, requestedViewportHeightPx) ||
            _pagingKind.value != expectedPagingKind
        ) {
            return false
        }
        if (commitViewport) {
            viewportWidthPx = requestedViewportWidthPx
            viewportHeightPx = requestedViewportHeightPx
        }
        pageWindows = windows
        _pageCount.value = pageWindows.size.coerceAtLeast(1)
        if (requestPageForAnchor) {
            val anchor = normalizeToSourceSection(_currentLocator.value)
            publishLocator(anchor)
            val renderedOffset = document.renderedOffsetFor(anchor, ensureCachedRendered())
            val pageIndex = pageIndexForRenderedOffset(pageWindows, renderedOffset)
            pageRequestCallback?.invoke(pageIndex)
        }
        return true
    }

    private fun ensureCachedRendered(): Spanned {
        if (cachedRendered.isEmpty() && document.markdown.isNotEmpty()) {
            cachedRendered = markwon.toMarkdown(document.markdown)
            recomputeCachedHighlightRanges()
        }
        return cachedRendered
    }

    /**
     * Measure complete-line page windows using StaticLayout with the same paint/padding
     * as displayed TextViews so bottoms never clip half-lines.
     *
     * Prefer host-reported viewport; fall back to displayMetrics only before first layout.
     */
    private fun paginationSnapshot(
        requestedViewportWidthPx: Int,
        requestedViewportHeightPx: Int,
    ): MarkdownPaginationSnapshot {
        val rendered = ensureCachedRendered()
        val metrics = context.resources.displayMetrics
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
        val padding = textPaddingPx
        val contentWidth = (widthPx - padding * 2).coerceAtLeast(1)
        val contentHeight = (heightPx - padding * 2).coerceAtLeast(1)
        return MarkdownPaginationSnapshot(
            document = document,
            rendered = rendered,
            contentWidthPx = contentWidth,
            contentHeightPx = contentHeight,
            density = metrics.density,
            textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                metrics,
            ),
            lineSpacingMultiplier = lineSpacingMultiplier.coerceAtLeast(0.1f),
            typeface = resolveTypeface(),
            fontId = currentFontId,
            viewportWidthPx = requestedViewportWidthPx,
            viewportHeightPx = requestedViewportHeightPx,
            generation = paginationGeneration.incrementAndGet(),
        )
    }

    private suspend fun measurePageWindows(
        snapshot: MarkdownPaginationSnapshot,
    ): List<MarkdownPageWindow>? {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            density = snapshot.density
            textSize = snapshot.textSizePx
            typeface = snapshot.typeface
        }
        val layoutBuilder = StaticLayout.Builder
            .obtain(
                snapshot.rendered,
                0,
                snapshot.rendered.length,
                paint,
                snapshot.contentWidthPx,
            )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, snapshot.lineSpacingMultiplier)
            .setIncludePad(true)
        currentCoroutineContext().ensureActive()
        if (snapshot.generation != paginationGeneration.get()) return null
        val layout = layoutBuilder.build()
        currentCoroutineContext().ensureActive()
        if (snapshot.generation != paginationGeneration.get()) return null
        return markdownPaginate(StaticLayoutMarkdownGeometry(layout), snapshot.contentHeightPx)
    }

    private fun MarkdownPaginationSnapshot.isCurrent(
        expectedViewportWidthPx: Int,
        expectedViewportHeightPx: Int,
    ): Boolean =
        document === this@MarkdownEngine.document &&
            rendered === cachedRendered &&
            fontId == currentFontId &&
            viewportWidthPx == expectedViewportWidthPx &&
            viewportHeightPx == expectedViewportHeightPx &&
            textSizePx == TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                context.resources.displayMetrics,
            ) &&
            lineSpacingMultiplier == this@MarkdownEngine.lineSpacingMultiplier.coerceAtLeast(0.1f) &&
            generation == paginationGeneration.get()

    private fun pageSlice(base: Spanned, window: MarkdownPageWindow): CharSequence {
        val length = base.length
        if (length == 0 || window.startOffset >= length) {
            return SpannableStringBuilder("")
        }
        val start = window.startOffset.coerceIn(0, length)
        val end = window.endOffset.coerceIn(start, length)
        return base.subSequence(start, end)
    }

    /**
     * Rebind every active page view by **page index** (not old startOffset).
     * Updates binding.startOffset/endOffset, tag, text, and highlights for the new window
     * even when pageCount is unchanged after typography reflow.
     */
    private fun refreshActivePageContents() {
        if (pageWindows.isEmpty()) return
        val base = ensureCachedRendered()
        activePageBindings.forEach { binding ->
            val tv = binding.textView ?: return@forEach
            val pageIndex = binding.pageIndex
            if (pageIndex !in pageWindows.indices) return@forEach
            val window = pageWindows[pageIndex]
            binding.startOffset = window.startOffset
            binding.endOffset = window.endOffset
            tv.tag = binding
            val slice = pageSlice(base, window)
            tv.text = slice.withTextHighlightSpans(
                ranges = filterHighlightRangesForWindow(window, slice.length),
                searchRanges = filterSearchHighlightRangesForWindow(window, slice.length),
            )
            tv.textSize = fontSizeSp
            tv.setLineSpacing(0f, lineSpacingMultiplier)
            tv.typeface = resolveTypeface()
            val padding = textPaddingPx
            tv.setPadding(padding, padding, padding, padding)
        }
    }

    private fun trackPageView(container: FrameLayout, binding: PageViewBinding) {
        activePageContainers.add(container)
        activePageBindings.add(binding)
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                activePageContainers.remove(container)
                activePageBindings.remove(binding)
            }
        })
    }

    private fun normalizeToSourceSection(locator: Locator): Locator {
        val offset = document.offsetFor(locator)
        return document.locatorForOffset(offset)
    }

    private fun resolveTypeface(): Typeface =
        dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)

    private companion object {
        /** Density-aware padding shared by measured StaticLayout and displayed TextViews. */
        const val TEXT_PADDING_DP: Float = 16f
        /** Max pixel distance between scrollY and the computed target that counts as restored. */
        private const val SCROLL_RESTORE_TOLERANCE_PX = 2f
        /**
         * Next-frame retries allowed per real layout pass. Replenished only by layout listeners
         * outside an in-flight attempt so a stuck layout cannot self-repost forever.
         */
        private const val SCROLL_RESTORE_RETRY_BUDGET = 1
        private const val DOCUMENT_TITLE_FALLBACK = "正文"

        private fun paletteFor(mode: ThemeMode, configuration: Configuration): ReaderPalette {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val p = readerPaletteFor(mode, systemNight)
            return ReaderPalette(p.paper, p.ink, p.isNight)
        }
    }
}

/**
 * Mutable binding for an active PAGED [TextView]. Keyed by stable [pageIndex];
 * [startOffset]/[endOffset] update on typography/viewport rebuild so selection
 * callbacks always use the current window base.
 */
internal class PageViewBinding(
    var pageIndex: Int,
    var startOffset: Int,
    var endOffset: Int,
    var textView: TextView? = null,
)

private data class MarkdownPaginationSnapshot(
    val document: MarkdownDocument,
    val rendered: Spanned,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val density: Float,
    val textSizePx: Float,
    val lineSpacingMultiplier: Float,
    val typeface: Typeface,
    val fontId: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val generation: Long,
)

/** Adapts [StaticLayout] / [Layout] to [MarkdownLineGeometry]. */
internal class StaticLayoutMarkdownGeometry(
    private val layout: Layout,
) : MarkdownLineGeometry {
    override val lineCount: Int get() = layout.lineCount
    override fun getLineTop(line: Int): Int = layout.getLineTop(line)
    override fun getLineBottom(line: Int): Int = layout.getLineBottom(line)
    override fun getLineStart(line: Int): Int = layout.getLineStart(line)
    override fun getLineEnd(line: Int): Int = layout.getLineEnd(line)
    override fun getLineForVertical(y: Int): Int = layout.getLineForVertical(y)
}

private fun TextView.characterOffsetForScrollY(scrollY: Int): Int {
    val layout = layout ?: return 0
    val vertical = (scrollY - totalPaddingTop).coerceAtLeast(0)
    val visualLine = layout.getLineForVertical(vertical)
    return layout.getLineStart(visualLine).coerceIn(0, text.length)
}

private fun TextView.scrollYForCharacterOffset(offset: Int): Int {
    val layout = layout ?: return 0
    val safeOffset = offset.coerceIn(0, text.length)
    val visualLine = layout.getLineForOffset(safeOffset)
    return layout.getLineTop(visualLine) + totalPaddingTop
}

private data class ReaderPalette(val paper: Int, val ink: Int, val isNight: Boolean)
