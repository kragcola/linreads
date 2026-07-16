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
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.withTextHighlightSpans
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

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
class MarkdownEngine(private val context: Context) :
    ReaderEngine,
    PagedReaderEngine,
    TextSelectableReaderEngine,
    TextAnnotatableReaderEngine {

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

    private val _pageCount = MutableStateFlow(1)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private val _currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
    override val currentTextSelection: StateFlow<ReaderTextSelection?> = _currentTextSelection.asStateFlow()

    private var document: MarkdownDocument = MarkdownDocument.parse("")
    private var fontSizeSp: Float = 18f
    private var lineSpacingMultiplier: Float = 1.3f
    private var currentFontId: String = "system_serif"
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null
    private var suppressLocatorUpdates = false

    /** Full Markwon-rendered Spanned for the open document (no highlights). Rebuilt on open. */
    private var cachedRendered: Spanned = SpannableStringBuilder("")
    /** Page windows over [cachedRendered] line geometry; empty in SCROLL. */
    private var pageWindows: List<MarkdownPageWindow> = emptyList()
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
        val markdown = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
        val parsed = MarkdownDocument.parse(markdown)
        val initial = parsed.locatorForOffset(0)
        // Markwon.toMarkdown is pure text→Spanned (no TextView); safe off-main.
        val rendered = markwon.toMarkdown(markdown)
        document = parsed
        withContext(Dispatchers.Main) {
            cachedRendered = rendered
            pageWindows = emptyList()
            document.clearMappingCache()
            recomputeCachedHighlightRanges()
            _tableOfContents.value = parsed.tableOfContents
            _currentLocator.value = initial
            if (_pagingKind.value == PagingKind.PAGED) {
                rebuildPageWindows(requestPageForAnchor = false)
            } else {
                _pageCount.value = 1
            }
        }
        initial
    }

    override fun createView(): View {
        val palette = paletteFor(themeMode, context.resources.configuration)
        val padding = textPaddingPx
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
                _currentLocator.value = document.locatorForRenderedOffset(
                    renderedOffset = tv.characterOffsetForScrollY(scrollY),
                    renderedText = tv.text,
                )
            }
        }
        scrollView = sv
        // Restore source anchor after mode remount (host calls createView for SCROLL).
        // Must wait for a real layout pass — posting while unattached burns retries at width=0.
        scheduleScrollRestore(_currentLocator.value)
        return sv
    }

    override fun createPageView(pageIndex: Int): View {
        ensurePageWindows()
        val windows = pageWindows.ifEmpty {
            listOf(MarkdownPageWindow(0, 0, 0, cachedRendered.length.coerceAtLeast(0)))
        }
        val pageCount = windows.size.coerceAtLeast(1)
        val safeIndex = pageIndex.coerceIn(0, pageCount - 1)
        val window = windows[safeIndex]
        val base = ensureCachedRendered()
        val slice = pageSlice(base, window)
        val highlighted = slice.withTextHighlightSpans(
            filterHighlightRangesForWindow(window, slice.length),
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

    override fun setViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val changed = widthPx != viewportWidthPx || heightPx != viewportHeightPx
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        if (!changed) return
        if (_pagingKind.value != PagingKind.PAGED) return
        // Preserve source Section anchor across rotation / real size change.
        val anchor = normalizeToSourceSection(_currentLocator.value)
        _currentLocator.value = anchor
        rebuildPageWindows(requestPageForAnchor = true)
        refreshActivePageContents()
    }

    override fun pageIndexForLocator(locator: Locator): Int {
        if (_pagingKind.value != PagingKind.PAGED) {
            return super.pageIndexForLocator(locator)
        }
        ensurePageWindows()
        val windows = pageWindows
        if (windows.isEmpty()) return 0
        when (val strategy = locator.strategy) {
            is LocatorStrategy.Page ->
                return strategy.index.coerceIn(0, windows.lastIndex)
            else -> Unit
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
        _currentLocator.value = target
        if (scrollView == null || textView == null) return
        scheduleScrollRestore(target)
    }

    private fun goToPaged(locator: Locator) {
        ensurePageWindows()
        val sourceLocator = when (locator.strategy) {
            is LocatorStrategy.Page -> {
                // Host ViewPager emits Page locators on settle; normalize to source Section
                // at the page start so bookmarks/progress stay typography-stable.
                val pageIndex = pageIndexForLocator(locator)
                stableSourceLocatorForPage(pageIndex)
            }
            else -> document.locatorForOffset(document.offsetFor(locator))
        }
        _currentLocator.value = sourceLocator
        val pageIndex = pageIndexForLocator(sourceLocator)
        pageRequestCallback?.invoke(pageIndex)
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
    private fun scheduleScrollRestore(locator: Locator) {
        val sv = scrollView ?: return
        val listener = object : View.OnLayoutChangeListener {
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
                if (scrollView !== sv) {
                    sv.removeOnLayoutChangeListener(this)
                    return
                }
                if (sv.width <= 0 || sv.height <= 0) return
                val applied = restoreScrollToLocator(locator)
                if (applied) {
                    sv.removeOnLayoutChangeListener(this)
                }
            }
        }
        sv.addOnLayoutChangeListener(listener)
        // Immediate attempts for already-laid-out hosts and post-attach frames.
        sv.post {
            if (scrollView !== sv) {
                sv.removeOnLayoutChangeListener(listener)
                return@post
            }
            if (sv.width > 0 && sv.height > 0 && restoreScrollToLocator(locator)) {
                sv.removeOnLayoutChangeListener(listener)
            }
        }
    }

    /**
     * @return true when scroll position was applied (or no scroll needed); false if still waiting
     * for measurable content/viewport.
     */
    private fun restoreScrollToLocator(locator: Locator): Boolean {
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
            // Some measure passes clamp once; retry on next frame if target not reached.
            if (y > 0 && sv.scrollY == 0) {
                sv.post {
                    if (scrollView === sv && textView === tv) {
                        ensureScrollTextViewMeasured(sv, tv)
                        sv.scrollTo(0, y)
                    }
                }
                return false
            }
        } finally {
            suppressLocatorUpdates = false
        }
        _currentLocator.value = document.locatorForOffset(document.offsetFor(locator))
        // Success when we needed no scroll, or scroll moved (or content fits).
        return y == 0 || sv.scrollY > 0 || maxScroll == 0
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

    override suspend fun search(query: String): List<Locator> = withContext(Dispatchers.Default) {
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
        _currentLocator.value = document.locatorForRenderedOffset(
            renderedOffset = selectionAwareTextView.characterOffsetForScrollY(sv.scrollY),
            renderedText = selectionAwareTextView.text,
        )
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        textAnnotations = annotations
        recomputeCachedHighlightRanges()
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

    private fun filterHighlightRangesForWindow(
        window: MarkdownPageWindow,
        sliceLength: Int,
    ): List<ReaderTextHighlightRange> =
        cachedHighlightRanges.mapNotNull { range ->
            val localStart = (range.start - window.startOffset).coerceAtLeast(0)
            val localEnd = (range.end - window.startOffset).coerceAtMost(sliceLength)
            if (localStart >= localEnd) null
            else range.copy(start = localStart, end = localEnd)
        }

    private fun applyTextAnnotations(view: TextView) {
        val base = ensureCachedRendered()
        val ranges = if (cachedHighlightRanges.isEmpty() && textAnnotations.isNotEmpty()) {
            recomputeCachedHighlightRanges()
            cachedHighlightRanges
        } else {
            cachedHighlightRanges
        }
        val highlightedText = base.withTextHighlightSpans(ranges)
        val sv = scrollView
        if (sv == null) {
            view.text = highlightedText
            return
        }

        val previousScrollY = sv.scrollY
        val previousLocator = _currentLocator.value
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
            val activeScrollView = scrollView ?: return@post
            val activeTextView = textView ?: return@post
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
            _currentLocator.value = document.locatorForRenderedOffset(
                renderedOffset = activeTextView.characterOffsetForScrollY(restoreScrollY),
                renderedText = activeTextView.text,
            )
        }
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
        _tableOfContents.value = emptyList()
        _pageCount.value = 1
        _pagingKind.value = PagingKind.CONTINUOUS
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        textView?.textSize = sp
        activePageBindings.forEach { it.textView?.textSize = sp }
        rebuildAfterTypographyChange()
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        textView?.setLineSpacing(0f, multiplier)
        activePageBindings.forEach { it.textView?.setLineSpacing(0f, multiplier) }
        rebuildAfterTypographyChange()
    }

    override suspend fun setSerifFont(useSourceHan: Boolean) {
        setFont(if (useSourceHan) "source_han" else "system_serif")
    }

    override suspend fun setFont(fontId: String) {
        currentFontId = fontId
        val face = resolveTypeface()
        textView?.typeface = face
        activePageBindings.forEach { it.textView?.typeface = face }
        rebuildAfterTypographyChange()
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
            _currentLocator.value = anchor
            _pagingKind.value = targetKind
            if (targetKind == PagingKind.PAGED) {
                rebuildPageWindows(requestPageForAnchor = true)
            } else {
                pageWindows = emptyList()
                _pageCount.value = 1
            }
        }
    }

    /**
     * Rebuild page ranges after font/line-spacing/viewport changes.
     * Always requests the page for the current source anchor and refreshes active page
     * views even when [pageCount] stays equal (avoids stale slices).
     */
    private fun rebuildAfterTypographyChange() {
        if (_pagingKind.value != PagingKind.PAGED) return
        rebuildPageWindows(requestPageForAnchor = true)
        refreshActivePageContents()
    }

    private fun rebuildPageWindows(requestPageForAnchor: Boolean) {
        val windows = measurePageWindows()
        pageWindows = windows
        _pageCount.value = windows.size.coerceAtLeast(1)
        if (requestPageForAnchor) {
            val pageIndex = pageIndexForLocator(_currentLocator.value)
            pageRequestCallback?.invoke(pageIndex)
        }
    }

    private fun ensurePageWindows() {
        if (_pagingKind.value == PagingKind.PAGED && pageWindows.isEmpty()) {
            rebuildPageWindows(requestPageForAnchor = false)
        }
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
    private fun measurePageWindows(): List<MarkdownPageWindow> {
        val rendered = ensureCachedRendered()
        val metrics = context.resources.displayMetrics
        val widthPx = if (viewportWidthPx > 0) viewportWidthPx else metrics.widthPixels.coerceAtLeast(1)
        val heightPx = if (viewportHeightPx > 0) viewportHeightPx else metrics.heightPixels.coerceAtLeast(1)
        val padding = textPaddingPx
        val contentWidth = (widthPx - padding * 2).coerceAtLeast(1)
        val contentHeight = (heightPx - padding * 2).coerceAtLeast(1)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            density = metrics.density
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                metrics,
            )
            typeface = resolveTypeface()
        }
        val layout = StaticLayout.Builder
            .obtain(rendered, 0, rendered.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacingMultiplier.coerceAtLeast(0.1f))
            .setIncludePad(true)
            .build()
        return markdownPaginate(StaticLayoutMarkdownGeometry(layout), contentHeight)
    }

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
                filterHighlightRangesForWindow(window, slice.length),
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
