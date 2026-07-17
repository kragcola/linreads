package dev.readflow.render.txt

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
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
import dev.readflow.core.model.adjacentTocEntry
import dev.readflow.core.model.chapterInfoFromOrderedToc
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.withTextHighlightSpans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * Minimal TXT engine (v4 §5.3/§5.4). CONTINUOUS scroll via RecyclerView.
 * TXT content is copied from the incoming Uri into a private temp file, indexed
 * with 64 KiB FileChannel blocks, and paragraph text is read on demand.
 * Charset detection uses juniversalchardet with BOM priority and UTF-8 fallback.
 */
class TxtVirtualPagerEngine(
    private val context: Context,
) : PagedReaderEngine, TextSelectableReaderEngine, TextAnnotatableReaderEngine {

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
    private var fontSizeSp: Float = 18f
    private var lineSpacingMultiplier: Float = 1.3f
    private var useSourceHan: Boolean = true
    private var currentFontId: String = "source_han"
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var encodingOverride: String? = null
    private var currentUri: Uri? = null
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    private var recyclerView: RecyclerView? = null
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null
    private var pendingProgrammaticScroll: PendingProgrammaticScroll? = null
    /**
     * PAGED 模式下的页→段落区间映射（贪心装箱：把多段落填进一页直到页高用尽，非必要不分页）。
     * 每个元素是该页起始段落下标（含），区间到下一元素前一段（含）。空表示尚未构建/SCROLL 模式。
     */
    private var pagedParagraphStarts: List<Int> = emptyList()
    private val activePageTextViews = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
    private val activePageContainers = Collections.newSetFromMap(WeakHashMap<FrameLayout, Boolean>())

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        currentUri = uri
        txtDocument?.close()
        pendingProgrammaticScroll = null
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
        pagedParagraphStarts = emptyList()
        _pageCount.value = document.paragraphCount
        _tableOfContents.value = buildToc(document)
        publishLocator(locatorForIndex(0, document.paragraphCount))
        _currentLocator.value
    }

    override fun createView(): View {
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            val palette = paletteFor(themeMode, resources.configuration)
            adapter = TxtParagraphAdapter(
                paragraphCount = paragraphCount(),
                paragraphProvider = ::paragraphAt,
                fontSizeSp = fontSizeSp,
                lineSpacingMultiplier = lineSpacingMultiplier,
                inkColor = palette.ink,
                highlightRangesProvider = ::highlightRangesForParagraph,
                onSelectionChanged = ::updateTextSelection,
                typeface = resolveTypeface(),
            )
            background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
            clipToPadding = false
            val padV = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    reportProgression(view)
                }
            })
            (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentParagraphIndex(), 0)
        }
        recyclerView = rv
        return rv
    }

    override fun createPageView(pageIndex: Int): View {
        val total = paragraphCount().coerceAtLeast(1)
        val starts = pagedParagraphStarts.ifEmpty { buildPagedParagraphStarts().also { pagedParagraphStarts = it } }
        val pageCount = starts.size.coerceAtLeast(1)
        val safePageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val startParagraph = starts.getOrNull(safePageIndex) ?: 0
        val endParagraphExclusive = starts.getOrNull(safePageIndex + 1) ?: total
        val palette = paletteFor(themeMode, context.resources.configuration)
        val density = context.resources.displayMetrics.density
        val maxLineWidthPx = (TxtParagraphAdapter.MAX_LINE_WIDTH_DP * density).toInt()
        val pageTextViews = mutableListOf<TextView>()

        // 一页内按段落顺序竖直堆叠（顶对齐），每个 TextView 仍对应单一段落 →
        // 选择/高亮/标注沿用既有“按段落下标”逻辑，无需偏移重映射。
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }
        for (paragraphIndex in startParagraph until endParagraphExclusive.coerceAtMost(total)) {
            val textView = SelectionAwareTextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL.toFloat(),
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                maxWidth = maxLineWidthPx
                setPadding((28 * density).toInt(), (10 * density).toInt(), (28 * density).toInt(), (10 * density).toInt())
                gravity = Gravity.START
                typeface = resolveTypeface()
                tag = paragraphIndex
                text = paragraphAt(paragraphIndex).withTextHighlightSpans(highlightRangesForParagraph(paragraphIndex))
                setTextIsSelectable(true)
                onSelectionRangeChanged = { start, end -> updateTextSelection(paragraphIndex, start, end) }
                applyTextStyle(palette.ink)
            }
            column.addView(textView)
            pageTextViews += textView
        }
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
        return container.also { trackPageView(it, pageTextViews) }
    }

    /**
     * 贪心把段落装进页：逐段测量视觉行数，累加到接近页可容纳行数时换页。
     * 段落本身超过一页则独占（不会无限循环）。返回各页起始段落下标。
     */
    private fun buildPagedParagraphStarts(): List<Int> {
        val total = paragraphCount()
        if (total <= 0) return listOf(0)
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val contentWidthPx = ((TxtParagraphAdapter.MAX_LINE_WIDTH_DP * density)
            .coerceAtMost((metrics.widthPixels - 56 * density)))
            .toInt().coerceAtLeast(1)
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, metrics)
        val lineHeightPx = (textSizePx * lineSpacingMultiplier).coerceAtLeast(1f)
        // 页内容高度：视口高 - 上下纸边(24dp×2) - 每段竖直 padding(10dp×2) 的安全余量。
        val contentHeightPx = (metrics.heightPixels - 48 * density).coerceAtLeast(1f)
        val linesPerPage = (contentHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
        val paint = TextPaint().apply {
            textSize = textSizePx
            typeface = resolveTypeface()
        }
        val starts = mutableListOf(0)
        var usedLines = 0
        for (index in 0 until total) {
            val lines = paragraphLineCount(paragraphAt(index), contentWidthPx, paint)
            val paragraphGap = if (index == starts.last()) 0 else 1 // 段间一行视觉间隔
            if (usedLines > 0 && usedLines + paragraphGap + lines > linesPerPage) {
                starts += index
                usedLines = lines
            } else {
                usedLines += paragraphGap + lines
            }
        }
        return starts
    }

    private fun paragraphLineCount(text: String, contentWidthPx: Int, paint: TextPaint): Int {
        if (text.isEmpty()) return 1
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidthPx)
            .setLineSpacing(0f, lineSpacingMultiplier.coerceAtLeast(0.1f))
            .setIncludePad(false)
            .build()
            .lineCount
            .coerceAtLeast(1)
    }

    /** 段落下标 → 所在页下标（PAGED 装箱映射）。 */
    private fun pageForParagraph(paragraphIndex: Int): Int {
        val starts = pagedParagraphStarts
        if (starts.isEmpty()) return paragraphIndex
        // starts 升序，找最后一个 <= paragraphIndex 的页。
        var page = 0
        for (i in starts.indices) {
            if (starts[i] <= paragraphIndex) page = i else break
        }
        return page
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
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
        val total = paragraphCount()
        if (total == 0) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val pending = pendingProgrammaticScroll
        if (pending != null) {
            if (pending.isStaleReport(first)) {
                return
            }
            pendingProgrammaticScroll = null
        }
        publishLocator(locatorForIndex(first, total))
    }

    override suspend fun goTo(locator: Locator) {
        val total = paragraphCount().coerceAtLeast(1)
        val index = when (val s = locator.strategy) {
            is LocatorStrategy.Section -> s.elementIndex
            is LocatorStrategy.Page -> s.index
            is LocatorStrategy.ByteOffset -> txtDocument?.indexForOffset(s.offset)
                ?: locator.totalProgression?.let { (it * total).toInt() }
                ?: 0
            LocatorStrategy.Unknown -> locator.totalProgression?.let { (it * total).toInt() } ?: 0
        }.coerceIn(0, total - 1)
        val target = locatorForIndex(index, total)
        recyclerView?.let { rv ->
            pendingProgrammaticScroll = PendingProgrammaticScroll(
                fromIndex = currentVisibleParagraphIndex() ?: currentParagraphIndex(),
                targetIndex = index,
            )
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        }
        publishLocator(target)
        pageRequestCallback?.invoke(index)
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

    override suspend fun search(query: String): List<Locator> = withContext(Dispatchers.IO) {
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
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTextAnnotations()
        activePageTextViews.forEach { textView ->
            val index = textView.tag as? Int ?: return@forEach
            textView.text = paragraphAt(index).withTextHighlightSpans(highlightRangesForParagraph(index))
        }
    }

    private fun updateTextSelection(paragraphIndex: Int, start: Int, end: Int) {
        _currentTextSelection.value = txtDocument?.selectionForParagraphRange(paragraphIndex, start, end)
    }

    private fun highlightRangesForParagraph(paragraphIndex: Int) =
        txtDocument?.highlightRangesForParagraph(paragraphIndex, textAnnotations).orEmpty()

    private fun locatorForIndex(index: Int, totalItems: Int = paragraphCount().coerceAtLeast(1)): Locator {
        val total = totalItems.coerceAtLeast(1)
        val safeIndex = index.coerceIn(0, total - 1)
        val range = txtDocument?.rangeAt(safeIndex)
        return Locator(
            strategy = LocatorStrategy.ByteOffset(
                offset = range?.startByte ?: 0L,
                length = range?.length?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            ),
            progression = safeIndex.toFloat() / total,
            totalProgression = safeIndex.toFloat() / total,
        )
    }

    private fun currentParagraphIndex(): Int {
        val total = paragraphCount().coerceAtLeast(1)
        return when (val strategy = _currentLocator.value.strategy) {
            is LocatorStrategy.Section -> strategy.elementIndex
            is LocatorStrategy.Page -> strategy.index
            is LocatorStrategy.ByteOffset -> txtDocument?.indexForOffset(strategy.offset) ?: 0
            else -> 0
        }.coerceIn(0, total - 1)
    }

    override suspend fun close() {
        recyclerView = null
        pageRequestCallback = null
        pendingProgrammaticScroll = null
        activePageTextViews.clear()
        activePageContainers.clear()
        _currentTextSelection.value = null
        textAnnotations = emptyList()
        txtDocument?.close()
        txtDocument = null
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
        fontSizeSp = sp
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateFontSize(sp)
        activePageTextViews.forEach { it.applyTextStyle() }
        rebuildPagedRangesAfterTypographyChange()
    }

    override suspend fun setSerifFont(useSourceHan: Boolean) {
        this.useSourceHan = useSourceHan
        currentFontId = if (useSourceHan) "source_han" else "system"
        withContext(Dispatchers.Main) {
            (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTypeface(resolveTypeface())
            rebuildPagedRangesAfterTypographyChange()
        }
    }

    override suspend fun setFont(fontId: String) {
        currentFontId = fontId
        useSourceHan = fontId == "source_han"
        withContext(Dispatchers.Main) {
            (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTypeface(resolveTypeface())
            rebuildPagedRangesAfterTypographyChange()
        }
    }

    private fun resolveTypeface(): Typeface =
        dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)

    /** PAGED 模式下排版参数变化后重算装箱，并把当前段落对应的新页号回调给宿主。 */
    private fun rebuildPagedRangesAfterTypographyChange() {
        if (_pagingKind.value != PagingKind.PAGED) return
        val paragraphIndex = currentParagraphIndex()
        pagedParagraphStarts = buildPagedParagraphStarts()
        _pageCount.value = pagedParagraphStarts.size
        pageRequestCallback?.invoke(pageForParagraph(paragraphIndex))
    }

    override suspend fun setTxtEncodingOverride(charsetName: String?) {
        encodingOverride = charsetName
        val uri = currentUri ?: return
        val savedProgression = _currentLocator.value.totalProgression
        openBook(uri)
        savedProgression?.let { p ->
            val total = _pageCount.value.coerceAtLeast(1)
            val targetIndex = (p * total).toInt().coerceIn(0, total - 1)
            goTo(locatorForIndex(targetIndex, total))
        }
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateLineSpacing(multiplier)
        activePageTextViews.forEach { it.applyTextStyle() }
        rebuildPagedRangesAfterTypographyChange()
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
        withContext(Dispatchers.Main) {
            val paragraphIndex = if (_pagingKind.value == PagingKind.CONTINUOUS) {
                currentVisibleParagraphIndex() ?: currentParagraphIndex()
            } else {
                currentParagraphIndex()
            }
            publishLocator(locatorForIndex(paragraphIndex))
            _pagingKind.value = targetKind
            if (targetKind == PagingKind.PAGED) {
                pagedParagraphStarts = buildPagedParagraphStarts()
                _pageCount.value = pagedParagraphStarts.size
                pageRequestCallback?.invoke(pageForParagraph(paragraphIndex))
            } else {
                pagedParagraphStarts = emptyList()
                _pageCount.value = paragraphCount()
            }
        }
    }

    override fun pageIndexForLocator(locator: Locator): Int {
        if (_pagingKind.value != PagingKind.PAGED || pagedParagraphStarts.isEmpty()) {
            return super.pageIndexForLocator(locator)
        }
        val total = paragraphCount().coerceAtLeast(1)
        val paragraphIndex = when (val s = locator.strategy) {
            is LocatorStrategy.Section -> s.elementIndex
            is LocatorStrategy.Page -> return s.index.coerceIn(0, pagedParagraphStarts.lastIndex)
            is LocatorStrategy.ByteOffset -> txtDocument?.indexForOffset(s.offset) ?: 0
            LocatorStrategy.Unknown -> locator.totalProgression?.let { (it * total).toInt() } ?: 0
        }.coerceIn(0, total - 1)
        return pageForParagraph(paragraphIndex)
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

    private fun trackPageView(container: FrameLayout, textViews: List<TextView>) {
        activePageContainers.add(container)
        activePageTextViews.addAll(textViews)
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                activePageContainers.remove(container)
                activePageTextViews.removeAll(textViews.toSet())
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

    private companion object {
        private const val DOCUMENT_TITLE_FALLBACK = "正文"
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

    private fun paragraphAt(index: Int): String = txtDocument?.readParagraph(index).orEmpty()

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
