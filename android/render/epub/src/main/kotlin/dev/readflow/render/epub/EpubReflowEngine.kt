package dev.readflow.render.epub

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
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
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val epubParserFactory: () -> EpubParser,
    private val fullIndexDispatcher: CoroutineDispatcher,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit?,
) : PagedReaderEngine,
    SelfPagingReaderEngine,
    TextSelectableReaderEngine,
    TextAnnotatableReaderEngine,
    InitialLocatorAwareReaderEngine {

    constructor(context: Context) : this(
        context = context,
        pageLineMeasurer = null,
        flowEngineEnabled = EPUB_FLOW_ENGINE_ENABLED,
        epubParserFactory = { EpubParser() },
        fullIndexDispatcher = Dispatchers.IO,
        constructorMarker = null,
    )

    internal constructor(
        context: Context,
        pageLineMeasurer: EpubPageLineMeasurer,
        flowEngineEnabled: Boolean = false,
    ) : this(
        context = context,
        pageLineMeasurer = pageLineMeasurer,
        flowEngineEnabled = flowEngineEnabled,
        epubParserFactory = { EpubParser() },
        fullIndexDispatcher = Dispatchers.IO,
        constructorMarker = null,
    )

    /** Test-only: exercise the legacy slice-pack path (or force flow) without a custom measurer. */
    internal constructor(
        context: Context,
        flowEngineEnabled: Boolean,
    ) : this(
        context = context,
        pageLineMeasurer = null,
        flowEngineEnabled = flowEngineEnabled,
        epubParserFactory = { EpubParser() },
        fullIndexDispatcher = Dispatchers.IO,
        constructorMarker = null,
    )

    internal constructor(
        context: Context,
        flowEngineEnabled: Boolean,
        epubParserFactory: () -> EpubParser,
        fullIndexDispatcher: CoroutineDispatcher,
    ) : this(
        context = context,
        pageLineMeasurer = null,
        flowEngineEnabled = flowEngineEnabled,
        epubParserFactory = epubParserFactory,
        fullIndexDispatcher = fullIndexDispatcher,
        constructorMarker = null,
    )

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
    @Volatile
    private var lazyBook: EpubLazyBook? = null
    @Volatile
    private var startupSession: EpubStartupSession? = null
    private var fullIndexDeferred: Deferred<EpubLazyBook>? = null
    private var displayBlockCount: Int = 0
    private var epubFile: File? = null
    private var internalLinkTargetIndexes: Map<String, EpubTargetPosition> = emptyMap()
    private var spineCharCounts: List<Int> = emptyList()
    private var pagedSlices: List<EpubPageSlice> = emptyList()
    private val legacyPagedSlicesLock = Any()
    private val bookGeneration = AtomicLong(0L)
    private var chapterBoundaries: List<ChapterBoundary> = emptyList()
    private var fontSizeSp: Float = 18f
    private var lineSpacingMultiplier: Float = 1.0f
    private var flipStyle: dev.readflow.core.model.PageFlipStyle = dev.readflow.core.model.PageFlipStyle.SLIDE
    private var useSourceHan: Boolean = false
    private var currentFontId: String = "system_serif"
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    private var recyclerView: RecyclerView? = null
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null

    // ---- Continuous-flow path (方案 C). Active when EPUB_FLOW_ENGINE_ENABLED; legacy slice-pack
    // path below is retained as a fallback (flip the flag to roll back). ----
    private var flowView: EpubFlowView? = null
    private var flowHost: android.widget.FrameLayout? = null
    private var flowSpineIndex: Int = -1
    private var flowAppliedPalette: ReaderPalette? = null
    private val flowExecutor: ExecutorService by lazy { Executors.newFixedThreadPool(2) }
    private val flowMainHandler = Handler(Looper.getMainLooper())
    private val pageShotApplicationContext = context.applicationContext
    private val pageShotBudget = run {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        PageShotBudget(
            pageShotBudgetCapacityBytes(
                memoryClassMiB = activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MIB,
                isLowRamDevice = activityManager?.isLowRamDevice == true,
            ),
        )
    }
    private var pageShotMemoryCallbacksRegistered = false
    private var pageShotSpeculationPaused = false
    private var pageShotBackgroundPaused = false
    private var pageShotSevereMemoryBackoff = false
    private val pageShotMemoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flowMainHandler.post(::handleSeverePageShotMemoryPressure)
            }
        }

        override fun onTrimMemory(level: Int) {
            val runningPressure =
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            if (
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            ) {
                flowMainHandler.post(::handleBackgroundPageShotTrim)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && runningPressure) {
                flowMainHandler.post(::handleSeverePageShotMemoryPressure)
            }
        }
    }
    private var liveFlowImageLoader: EpubFlowImageLoader? = null
    private var boundaryPreviewGeneration = 0L
    private var nextBoundaryPreviewToken = 0L
    private var nextBoundaryPreviewRequestId = 0L
    private val boundaryPreviewJobs = mutableMapOf<Boolean, Future<*>>()
    private val boundaryPreviewJobTokens = mutableMapOf<Boolean, Long>()
    private val boundaryPreviewSessions = mutableMapOf<Boolean, BoundaryPreviewRenderSession>()
    private val boundaryPreviewTargets = mutableMapOf<Long, BoundaryPreviewTarget>()
    override val selfPagingActive: Boolean get() = flowEngineEnabled
    private var cacheJob: Job? = null
    private var cacheScope: CoroutineScope? = null
    private val activePageContainers = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val activePagedTextPages = WeakHashMap<ComposeView, EpubPagedTextPageState>()
    private val activePagedImagePages = WeakHashMap<View, EpubPagedImagePageState>()
    private val activePagedCompositePages = WeakHashMap<View, EpubPagedImagePageState>()
    private val imageBoundsCache = mutableMapOf<String, EpubImageBoundsCacheEntry>()
    private var pendingInitialLocator: Locator? = null

    private data class BoundaryPreviewRenderSession(
        val generation: Long,
        val forward: Boolean,
        val sourceSpine: Int,
        val sourceChapterGeneration: Long,
        val targetSpine: Int,
        val targetFlow: EpubChapterFlow,
        val view: EpubFlowView,
        val loader: EpubFlowImageLoader,
        var timeoutRunnable: Runnable? = null,
    )

    private data class BoundaryPreviewTarget(
        val generation: Long,
        val sourceSpine: Int,
        val sourcePreviewGeneration: Long,
        val targetSpine: Int,
        val forward: Boolean,
        val targetFlow: EpubChapterFlow,
    )

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

    override fun setInitialLocator(locator: Locator?) {
        pendingInitialLocator = locator
    }

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator {
        val requestedInitialLocator = pendingInitialLocator
        pendingInitialLocator = null
        resetBookStateForOpen()
        val openGeneration = bookGeneration.get()
        return withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "epub_${uri.hashCode()}.epub")
            if (!tmp.exists()) {
                context.contentResolver.openInputStream(uri)?.use { src ->
                    tmp.outputStream().use { dst -> src.copyTo(dst) }
                }
            }
            if (openGeneration != bookGeneration.get()) {
                throw CancellationException("EPUB open was superseded")
            }
            val parser = epubParserFactory()
            val startup = if (
                flowEngineEnabled &&
                (requestedInitialLocator == null || requestedInitialLocator.strategy is LocatorStrategy.Section)
            ) {
                val packageIndex = parser.parsePackageIndex(tmp)
                buildStartupOpenState(tmp, packageIndex, parser, requestedInitialLocator)
            } else {
                null
            }
            if (startup != null) {
                withContext(Dispatchers.Main) {
                    if (openGeneration != bookGeneration.get()) {
                        startup.session.close()
                        throw CancellationException("EPUB open was superseded")
                    }
                    installBookRuntime(tmp)
                    installStartupOpenState(startup)
                    launchFullIndex(tmp, openGeneration)
                    updatePageCount()
                    updateChapterInfo(startup.initialParagraphIndex)
                    _tableOfContents.value = emptyList()
                    _currentLocator.value = startup.initialLocator
                }
                return@withContext startup.initialLocator
            }

            val book = parser.parseLazyBook(tmp)
            if (openGeneration != bookGeneration.get()) {
                book.close()
                throw CancellationException("EPUB open was superseded")
            }
            val indexedParas = book.paras
            val indexedLinkTargets = epubInternalLinkTargetIndexes(
                book.spinePaths,
                indexedParas,
                book.fragmentTargetIndexes,
            )
            val indexedSpineCharCounts = epubSpineCharCounts(indexedParas)
            val initialGuessIndex = initialPrefetchIndex(requestedInitialLocator, indexedParas)
            book.prefetchAroundParagraph(initialGuessIndex)
            // The production chapter-flow view paginates only the active spine. Building the retired
            // whole-book page table here blocks first paint on every paragraph and image merely to keep
            // old Page locators compatible. Pay that cost only when an old Page locator actually needs it.
            val indexedPages = if (
                !flowEngineEnabled || requestedInitialLocator?.strategy is LocatorStrategy.Page
            ) {
                buildPagedSlices(indexedParas, book.layoutBlocks(), tmp)
            } else {
                emptyList()
            }
            val indexedChapterBoundaries = buildChapterBoundaries(indexedParas)
            val initial = resolveInitialOpenLocator(requestedInitialLocator, indexedParas, indexedPages)
            val initialIndex = epubIndexFromLocator(initial, indexedParas.size)
            book.prefetchAroundParagraph(initialIndex)
            withContext(Dispatchers.Main) {
                if (openGeneration != bookGeneration.get()) {
                    book.close()
                    throw CancellationException("EPUB open was superseded")
                }
                installBookRuntime(tmp)
                lazyBook = book
                paras = indexedParas
                internalLinkTargetIndexes = indexedLinkTargets
                spineCharCounts = indexedSpineCharCounts
                pagedSlices = indexedPages
                chapterBoundaries = indexedChapterBoundaries
                displayBlockCount = book.blockCount
                updatePageCount()
                updateChapterInfo(initialIndex)
                _tableOfContents.value = book.tableOfContents.ifEmpty { buildToc(chapterBoundaries, paras.size) }
                _currentLocator.value = initial
            }
            initial
        }
    }

    private fun installBookRuntime(file: File) {
        epubFile = file
        imageBoundsCache.clear()
        cacheJob?.cancel()
        cacheJob = SupervisorJob()
        cacheScope = CoroutineScope(cacheJob!! + Dispatchers.IO)
    }

    private data class StartupOpenState(
        val session: EpubStartupSession,
        val initialLocator: Locator,
        val initialParagraphIndex: Int,
        val initialSpine: EpubParsedSpine,
    )

    private fun buildStartupOpenState(
        file: File,
        packageIndex: EpubPackageIndex,
        parser: EpubParser,
        requested: Locator?,
    ): StartupOpenState? {
        if (packageIndex.spineItems.isEmpty()) return null
        val section = when (val strategy = requested?.strategy) {
            null -> LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 0)
            is LocatorStrategy.Section -> strategy
            else -> return null
        }
        if (section.spineIndex !in packageIndex.spineItems.indices) return null
        if (startupZeroOffsetIsAmbiguous(section, requested, packageIndex)) return null
        val parsed = parser.parseSpine(file, packageIndex, section.spineIndex)
        if (parsed.paras.isEmpty()) return null
        val resolved = resolveProvisionalSection(section, parsed.paras) ?: return null
        val session = EpubStartupSession(packageIndex) { spineIndex ->
            epubParserFactory().parseSpine(file, packageIndex, spineIndex)
        }
        if (!session.installInitial(parsed, resolved.globalParagraphBase)) return null
        val globalIndex = resolved.globalParagraphBase + resolved.anchor.localParagraphIndex
        val para = parsed.paras[resolved.anchor.localParagraphIndex]
        val charOffset = para.spineCharStart + resolved.anchor.paragraphOffset
        val spineFraction = if (parsed.charCount > 0) {
            charOffset.toFloat() / parsed.charCount
        } else {
            0f
        }
        val approximateProgression = requested?.totalProgression
            ?: requested?.progression
            ?: session.approximateProgression(section.spineIndex, spineFraction)
        val initial = Locator(
            strategy = LocatorStrategy.Section(
                spineIndex = section.spineIndex,
                elementIndex = globalIndex,
                charOffset = charOffset,
            ),
            progression = approximateProgression,
            totalProgression = approximateProgression,
        )
        return StartupOpenState(
            session = session,
            initialLocator = initial,
            initialParagraphIndex = globalIndex,
            initialSpine = parsed,
        )
    }

    private fun startupZeroOffsetIsAmbiguous(
        section: LocatorStrategy.Section,
        requested: Locator?,
        packageIndex: EpubPackageIndex,
    ): Boolean {
        if (section.charOffset != 0 || section.elementIndex == 0) return false
        if (section.elementIndex == section.spineIndex) return false
        if (section.spineIndex == 0) return true
        val savedProgression = requested?.totalProgression ?: requested?.progression ?: return true
        val weights = packageIndex.spineEntryWeights
        val totalWeight = weights.sum().coerceAtLeast(1L)
        val spineStart = weights.take(section.spineIndex).sum().toFloat() / totalWeight
        return kotlin.math.abs(savedProgression - spineStart) > STARTUP_SPINE_START_TOLERANCE
    }

    private fun installStartupOpenState(state: StartupOpenState) {
        startupSession = state.session
        lazyBook = null
        paras = state.session.parasSnapshot()
        val initialSpineIndex = state.initialSpine.spineIndex
        val initialBase = state.session.globalBase(initialSpineIndex) ?: state.initialParagraphIndex
        internalLinkTargetIndexes = buildMap {
            state.session.packageIndex.spinePaths.getOrNull(initialSpineIndex)?.let { path ->
                put(epubNormalizePath(path), EpubTargetPosition(initialBase))
            }
            putAll(state.session.fragmentTargetsForSpine(initialSpineIndex))
        }
        spineCharCounts = MutableList(state.session.packageIndex.spineItems.size) { 0 }.also { counts ->
            counts[initialSpineIndex] = state.initialSpine.charCount
        }
        pagedSlices = emptyList()
        chapterBoundaries = emptyList()
        displayBlockCount = state.initialSpine.blocks.size
    }

    private fun launchFullIndex(file: File, generation: Long) {
        val scope = cacheScope ?: return
        val deferred = scope.async(fullIndexDispatcher) {
            epubParserFactory().parseLazyBook(file)
        }
        fullIndexDeferred = deferred
        scope.launch {
            val book = deferred.await()
            withContext(Dispatchers.Main) {
                promoteFullIndex(generation, book)
            }
        }
    }

    private fun promoteFullIndex(generation: Long, book: EpubLazyBook) {
        if (generation != bookGeneration.get() || startupSession == null || book.paras.isEmpty()) return
        val currentSection = _currentLocator.value.strategy as? LocatorStrategy.Section
        val canonicalIndex = currentSection?.let { canonicalParagraphIndex(it, book.paras) } ?: 0
        val canonicalPara = book.paras.getOrNull(canonicalIndex)
        val paragraphOffset = if (currentSection != null && canonicalPara != null) {
            (currentSection.charOffset - canonicalPara.spineCharStart)
                .coerceIn(0, (canonicalPara.spineCharEnd - canonicalPara.spineCharStart).coerceAtLeast(0))
        } else {
            0
        }
        lazyBook = book
        paras = book.paras
        internalLinkTargetIndexes = epubInternalLinkTargetIndexes(
            book.spinePaths,
            paras,
            book.fragmentTargetIndexes,
        )
        spineCharCounts = epubSpineCharCounts(paras)
        chapterBoundaries = buildChapterBoundaries(paras)
        displayBlockCount = book.blockCount
        pagedSlices = emptyList()
        startupSession?.close()
        startupSession = null
        fullIndexDeferred = null
        val canonical = epubLocatorForOffset(paras, canonicalIndex, paragraphOffset)
        _currentLocator.value = canonical
        updateChapterInfo(canonicalIndex)
        _tableOfContents.value = book.tableOfContents.ifEmpty { buildToc(chapterBoundaries, paras.size) }
        _pageCount.value = if (flowEngineEnabled) flowView?.pageCount() ?: 0 else when (_pagingKind.value) {
            PagingKind.CONTINUOUS -> paras.size
            PagingKind.PAGED -> pagedSlices.size
        }
        prewarmBoundaryPreviews()
    }

    private fun canonicalParagraphIndex(
        section: LocatorStrategy.Section,
        indexedParas: List<EpubPara>,
    ): Int {
        val sameSpine = indexedParas.indices.filter { indexedParas[it].spineIndex == section.spineIndex }
        val containing = sameSpine.firstOrNull { index ->
            val para = indexedParas[index]
            section.charOffset in para.spineCharStart until para.spineCharEnd
        }
        if (containing != null) return containing
        sameSpine.lastOrNull { indexedParas[it].spineCharEnd == section.charOffset }?.let { return it }
        indexedParas.getOrNull(section.elementIndex)?.let { para ->
            if (para.spineIndex == section.spineIndex) return section.elementIndex
        }
        return sameSpine.firstOrNull() ?: 0
    }

    private fun resolveInitialOpenLocator(
        locator: Locator?,
        indexedParas: List<EpubPara> = paras,
        indexedPages: List<EpubPageSlice>? = null,
    ): Locator {
        if (indexedParas.isEmpty()) return Locator(LocatorStrategy.Unknown)
        return when (val strategy = locator?.strategy) {
            is LocatorStrategy.Page -> resolveLegacyPageLocator(locator, indexedParas, indexedPages)
            is LocatorStrategy.Section -> {
                val paragraphIndex = epubIndexFromLocator(locator, indexedParas.size)
                val paragraphOffset =
                    (strategy.charOffset - (indexedParas.getOrNull(paragraphIndex)?.spineCharStart ?: 0))
                        .coerceAtLeast(0)
                epubLocatorForOffset(indexedParas, paragraphIndex, paragraphOffset)
            }
            null -> epubLocatorForIndex(indexedParas, 0)
            else -> epubLocatorForIndex(indexedParas, epubIndexFromLocator(locator, indexedParas.size))
        }
    }

    private fun resolveLegacyPageLocator(
        locator: Locator,
        indexedParas: List<EpubPara> = paras,
        indexedPages: List<EpubPageSlice>? = null,
    ): Locator {
        val page = locator.strategy as? LocatorStrategy.Page ?: return locator
        val pages = indexedPages ?: ensureLegacyPagedSlices()
        legacyPageIndexFromLocator(locator, pages)?.let { index ->
            pages.getOrNull(index)?.let { return epubLocatorForPageSlice(indexedParas, it) }
        }
        return pages
            .getOrNull(page.index.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
            ?.let { epubLocatorForPageSlice(indexedParas, it) }
            ?: epubLocatorForIndex(indexedParas, epubIndexFromLocator(locator, indexedParas.size))
    }

    private fun initialPrefetchIndex(
        locator: Locator?,
        indexedParas: List<EpubPara> = paras,
    ): Int {
        if (indexedParas.isEmpty()) return 0
        val page = locator?.strategy as? LocatorStrategy.Page
        if (page != null) {
            val total = page.total.coerceAtLeast(1)
            val progression = locator.totalProgression
                ?: locator.progression
                ?: (page.index.toFloat() / total)
            val targetDocumentOffset = (progression.coerceIn(0f, 1f) * epubTotalChars(indexedParas)).toInt()
            return indexedParas.indexOfLast { para -> para.documentCharStart <= targetDocumentOffset }
                .takeIf { it >= 0 }
                ?: 0
        }
        return locator?.let { epubIndexFromLocator(it, indexedParas.size) } ?: 0
    }

    private fun legacyPageIndexFromLocator(
        locator: Locator,
        pages: List<EpubPageSlice> = pagedSlices,
    ): Int? {
        val page = locator.strategy as? LocatorStrategy.Page ?: return null
        val progression = locator.totalProgression ?: locator.progression ?: return null
        val estimatedIndex = (progression.coerceIn(0f, 1f) * pages.size).toInt()
        return estimatedIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
            .takeIf { pages.isNotEmpty() }
            ?: page.index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
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

    private fun ensurePageShotMemoryCallbacksRegistered() {
        if (pageShotMemoryCallbacksRegistered) return
        pageShotApplicationContext.registerComponentCallbacks(pageShotMemoryCallbacks)
        pageShotMemoryCallbacksRegistered = true
    }

    private fun unregisterPageShotMemoryCallbacks() {
        if (!pageShotMemoryCallbacksRegistered) return
        pageShotApplicationContext.unregisterComponentCallbacks(pageShotMemoryCallbacks)
        pageShotMemoryCallbacksRegistered = false
    }

    private fun onPageShotOutOfMemory() {
        flowMainHandler.post(::handleSeverePageShotMemoryPressure)
    }

    private fun handleBackgroundPageShotTrim() {
        pageShotBackgroundPaused = true
        pauseAndTrimPageShots()
    }

    private fun handleSeverePageShotMemoryPressure() {
        pageShotSevereMemoryBackoff = true
        pauseAndTrimPageShots()
    }

    private fun pauseAndTrimPageShots() {
        pageShotSpeculationPaused = true
        pageShotBudget.pauseSpeculativeAdmission()
        trimBoundaryPreviewStatePreservingActiveTurn()
        flowView?.pausePageShotSpeculationAndTrim()
        pageShotBudget.evictEvictable().forEach { identity ->
            (identity as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun resumePageShotsAfterForeground() {
        if (!pageShotBackgroundPaused || pageShotSevereMemoryBackoff) return
        pageShotBackgroundPaused = false
        pageShotSpeculationPaused = false
        pageShotBudget.resumeSpeculativeAdmission()
        flowView?.resumePageShotSpeculation()
        prewarmBoundaryPreviews()
    }

    private fun createFlowView(): View {
        ensurePageShotMemoryCallbacksRegistered()
        val palette = paletteFor(themeMode, context.resources.configuration)
        val view = EpubFlowView(
            context = context,
            onTapZone = ::handleFlowTapZone,
            onTopOffsetChanged = ::handleFlowTopOffsetChanged,
            onSelectionRange = { start, end -> updateFlowSelection(start, end) },
            pageShotBudget = pageShotBudget,
            onPageShotOutOfMemory = ::onPageShotOutOfMemory,
        ).also { configureFlowView(it, palette) }
        view.onBoundaryPreviewNeeded = ::requestBoundaryPreview
        view.onBoundaryPreviewConfigurationChanged = {
            invalidateBoundaryPreviewState(clearViewSlots = false)
        }
        view.canCommitBoundaryTurn = ::canCommitBoundaryPreview
        view.onBoundaryTurnCommitted = ::commitBoundaryPreview
        view.onBoundaryTurnDiscarded = ::discardBoundaryPreview
        view.onBoundaryPreviewEvicted = { preview -> boundaryPreviewTargets.remove(preview.token) }
        view.onBoundaryPreviewRequestCancelled = ::cancelBoundaryPreviewRequest
        view.onPageShotForeground = ::resumePageShotsAfterForeground
        view.onPageSettled = ::prewarmBoundaryPreviews
        view.onChapterStable = {
            if (flowView === view && flowSpineIndex >= 0) prewarmBoundaryPreviews()
        }
        flowAppliedPalette = palette
        flowView = view
        val startAnchor = flowAnchorFromLocator(_currentLocator.value)
        loadFlowChapter(
            spineIndexForParagraph(startAnchor.first),
            restoreToParagraph = startAnchor.first,
            restoreToParagraphOffset = startAnchor.second,
        )
        // Keep a host container for the reader and the existing hidden adjacent-chapter preview views.
        // SIMULATION now stays entirely in EpubFlowView's local PAPER drawable; no GL child is created.
        val host = android.widget.FrameLayout(context)
        flowHost = host
        host.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return host
    }

    private fun configureFlowView(view: EpubFlowView, palette: ReaderPalette) {
        view.mode = if (_pagingKind.value == PagingKind.PAGED) EpubFlowView.Mode.PAGED else EpubFlowView.Mode.SCROLL
        view.flipStyle = flipStyle
        view.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        view.textView.setTextColor(palette.ink)
        val padH = (PAGE_HORIZONTAL_PADDING_DP * context.resources.displayMetrics.density).toInt()
        val padV = (PAGE_VERTICAL_PADDING_DP * context.resources.displayMetrics.density).toInt()
        view.textView.setPadding(padH, padV, padH, padV)
        view.textView.typeface = dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)
        view.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        applyFlowLineSpacing(view.textView)
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
    private fun loadFlowChapter(
        spineIndex: Int,
        restoreToParagraph: Int? = null,
        restoreToParagraphOffset: Int = 0,
        landOnLast: Boolean = false,
        reportPositionAfterStableReveal: Boolean = false,
        preparedFlow: EpubChapterFlow? = null,
    ) {
        val view = flowView ?: return
        val flow = preparedFlow?.takeIf { it.spineIndex == spineIndex } ?: run {
            val blocks = blocksForSpine(spineIndex)
            if (blocks.isEmpty()) return
            epubBuildChapterFlow(spineIndex, blocks)
        }
        liveFlowImageLoader?.releaseAll()
        liveFlowImageLoader = installFlowChapter(
            view = view,
            flow = flow,
            restoreToParagraph = restoreToParagraph,
            restoreToParagraphOffset = restoreToParagraphOffset,
            landOnLast = landOnLast,
            reportPositionAfterStableReveal = reportPositionAfterStableReveal,
            isCurrent = { flowCurrentFlow === flow },
            onLinkClick = ::handleLinkClick,
        ) ?: return
        flowSpineIndex = spineIndex
        flowCurrentFlow = flow
    }

    private fun blocksForSpine(spineIndex: Int): List<EpubDisplayBlock> =
        startupSession?.blocksForSpine(spineIndex)?.takeIf { it.isNotEmpty() }
            ?: lazyBook?.layoutBlocks()?.filter { block ->
                paras.getOrNull(block.paragraphIndex)?.spineIndex == spineIndex
            }.orEmpty()

    private fun installFlowChapter(
        view: EpubFlowView,
        flow: EpubChapterFlow,
        restoreToParagraph: Int? = null,
        restoreToParagraphOffset: Int = 0,
        landOnLast: Boolean = false,
        reportPositionAfterStableReveal: Boolean = false,
        isCurrent: () -> Boolean,
        onLinkClick: (EpubTextLink) -> Unit,
    ): EpubFlowImageLoader? {
        val density = context.resources.displayMetrics.density
        val palette = paletteFor(themeMode, context.resources.configuration)
        val initialColumnWidthPx = (view.width - view.textView.paddingLeft - view.textView.paddingRight)
            .takeIf { it > 0 }
            ?: flowColumnWidthPx()
        val style = EpubFlowStyle(
            fontSizeSp = fontSizeSp,
            lineSpacingMultiplier = lineSpacingMultiplier,
            inkColor = palette.ink,
            typeface = dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId),
            columnWidthPx = initialColumnWidthPx,
            imageMaxHeightPx = view.usablePageImageHeightPx().takeIf { it > 0 } ?: flowPageHeightPx(),
            density = density,
            firstLineIndentPx = flowFirstLineIndentPx(density),
        )
        val theme = MarkwonTheme.create(context)
        val fullPageImageOffsets = flowFullPageImageOffsets(flow)
        val fullPageHrefs = flow.segments.mapNotNullTo(HashSet()) { segment ->
            (segment.block as? EpubDisplayBlock.Image)
                ?.takeIf { segment.layoutStart in fullPageImageOffsets }
                ?.href
        }
        val inlineMaxHeightPx = (INLINE_IMAGE_MAX_HEIGHT_DP * density).toInt()
        // Full-page images must fit the current MEASURED viewport. Read both dimensions lazily so
        // rotation/split-screen changes do not reuse install-time geometry; fall back before first measure.
        val pageHeightProvider = {
            view.usablePageImageHeightPx().takeIf { it > 0 } ?: flowPageHeightPx()
        }
        val columnWidthProvider = {
            (view.width - view.textView.paddingLeft - view.textView.paddingRight)
                .takeIf { it > 0 }
                ?: initialColumnWidthPx
        }
        lateinit var loader: EpubFlowImageLoader
        loader = EpubFlowImageLoader(
            epubFileProvider = { epubFile },
            executor = flowExecutor,
            columnWidthPx = initialColumnWidthPx,
            columnWidthProvider = columnWidthProvider,
            pageHeightProvider = pageHeightProvider,
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
            imageBoundsProvider = ::epubImageBoundsFor,
            onImageResultChanged = {
                if (isCurrent()) view.refreshAfterAsyncImageResult()
            },
            onDecodeFinished = {
                if (isCurrent()) view.onAsyncImageDecodeFinished()
            },
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = initialColumnWidthPx,
            columnWidthProvider = columnWidthProvider,
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
            onLinkClick = onLinkClick,
            highlightRanges = flowHighlightRanges(flow),
            fullPageHrefs = fullPageHrefs,
            fullPageImageOffsets = fullPageImageOffsets,
            pageHeightProvider = pageHeightProvider,
            columnWidthProvider = columnWidthProvider,
        )
        val restoreOffset = restoreToParagraph?.let { flow.offsetForParagraph(it, restoreToParagraphOffset) }
        // setChapter posts its first settle before the Markwon scheduler attaches AsyncDrawables. Treat
        // that not-yet-scheduled window as pending too, or the stability gate can reveal/pre-cache the
        // transparent placeholders before loader.inFlight has a chance to become non-empty.
        var imageSchedulingPending = true
        view.pendingDecodesProvider = { imageSchedulingPending || loader.hasPendingDecodes() }
        view.setChapter(
            flow,
            spannable,
            flowPageHeightPx(),
            restoreOffset = restoreOffset,
            landOnLast = landOnLast,
            reportPositionAfterStableReveal = reportPositionAfterStableReveal,
        )
        // Schedule async images after the layout pass; positioning is now done inside setChapter's own
        // post (single pre-paint placement — no chapter-top→resume jump on entry).
        view.textView.post {
            try {
                AsyncDrawableScheduler.schedule(view.textView)
            } finally {
                imageSchedulingPending = false
                view.tryRevealWhenStable()
            }
        }
        return loader
    }

    private var flowCurrentFlow: EpubChapterFlow? = null

    private fun prewarmBoundaryPreviews() {
        val view = flowView ?: return
        if (pageShotSpeculationPaused || pageShotBudget.isSpeculativeAdmissionPaused) return
        if (_pagingKind.value != PagingKind.PAGED || flowSpineIndex < 0) return
        val sourceGeneration = view.boundaryPreviewGenerationToken()
        if (view.shouldPrewarmBoundaryPreview(forward = true)) {
            requestBoundaryPreview(forward = true, sourceChapterGeneration = sourceGeneration)
        }
        if (view.shouldPrewarmBoundaryPreview(forward = false)) {
            requestBoundaryPreview(forward = false, sourceChapterGeneration = sourceGeneration)
        }
    }

    private fun requestBoundaryPreview(forward: Boolean, sourceChapterGeneration: Long) {
        val view = flowView ?: return
        val required = view.boundaryPreviewIsRequired(forward)
        if ((pageShotSpeculationPaused || pageShotBudget.isSpeculativeAdmissionPaused) && !required) return
        val book = lazyBook
        val session = startupSession
        if (book == null && session == null) {
            view.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        val host = flowHost ?: return view.rejectBoundaryPreview(forward, sourceChapterGeneration)
        if (
            _pagingKind.value != PagingKind.PAGED ||
            view.boundaryPreviewGenerationToken() != sourceChapterGeneration
        ) {
            view.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        if (boundaryPreviewJobs.containsKey(forward) || boundaryPreviewSessions.containsKey(forward)) return
        val sourceSpine = flowSpineIndex
        val targetSpine = adjacentSpine(sourceSpine, if (forward) 1 else -1) ?: run {
            view.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        if (!view.preparePageShotBudgetForBoundaryPreview(forward, required)) {
            view.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        val generation = boundaryPreviewGeneration
        val requestId = ++nextBoundaryPreviewRequestId
        boundaryPreviewJobTokens[forward] = requestId
        boundaryPreviewJobs[forward] = flowExecutor.submit {
            var startupSpine: EpubStartupSpine? = null
            val targetFlow = runCatching {
                fun blocksFrom(indexedBook: EpubLazyBook): List<EpubDisplayBlock> =
                    indexedBook.layoutBlocks().filter { block ->
                        indexedBook.paras.getOrNull(block.paragraphIndex)?.spineIndex == targetSpine
                    }
                var blocks = when {
                    book != null -> blocksFrom(book)
                    session != null -> session.ensureAdjacent(sourceSpine, targetSpine)
                        ?.also { startupSpine = it }
                        ?.globalBlocks
                        .orEmpty()
                    else -> emptyList()
                }
                if (session != null && startupSession !== session) {
                    lazyBook?.let { promotedBook ->
                        startupSpine = null
                        blocks = blocksFrom(promotedBook)
                    }
                }
                epubBuildChapterFlow(targetSpine, blocks).takeIf { blocks.isNotEmpty() }
            }.getOrNull()
            flowMainHandler.post {
                if (boundaryPreviewJobTokens[forward] != requestId) return@post
                boundaryPreviewJobs.remove(forward)
                boundaryPreviewJobTokens.remove(forward)
                if (targetFlow == null) {
                    view.rejectBoundaryPreview(forward, sourceChapterGeneration)
                    return@post
                }
                if (
                    generation != boundaryPreviewGeneration ||
                    flowView !== view ||
                    flowHost !== host ||
                    flowSpineIndex != sourceSpine ||
                    view.boundaryPreviewGenerationToken() != sourceChapterGeneration ||
                    !view.boundaryPreviewBudgetAllows(forward) ||
                    (
                        (pageShotSpeculationPaused || pageShotBudget.isSpeculativeAdmissionPaused) &&
                            !view.boundaryPreviewIsRequired(forward)
                        )
                ) {
                    view.rejectBoundaryPreview(forward, sourceChapterGeneration)
                    return@post
                }
                val parsedStartupSpine = startupSpine
                if (parsedStartupSpine != null && session != null && startupSession === session) {
                    refreshStartupSessionSpine(session, parsedStartupSpine)
                }
                installBoundaryPreviewRenderer(
                    generation = generation,
                    forward = forward,
                    sourceSpine = sourceSpine,
                    sourceChapterGeneration = sourceChapterGeneration,
                    targetSpine = targetSpine,
                    targetFlow = targetFlow,
                )
            }
        }
    }

    private fun refreshStartupSessionSpine(
        session: EpubStartupSession,
        startupSpine: EpubStartupSpine,
    ) {
        if (startupSession !== session) return
        paras = session.parasSnapshot()
        val spineIndex = startupSpine.parsed.spineIndex
        spineCharCounts = spineCharCounts.toMutableList().also { counts ->
            if (spineIndex in counts.indices) counts[spineIndex] = startupSpine.parsed.charCount
        }
        internalLinkTargetIndexes = internalLinkTargetIndexes.toMutableMap().also { targets ->
            session.packageIndex.spinePaths.getOrNull(spineIndex)?.let { path ->
                targets[epubNormalizePath(path)] = EpubTargetPosition(startupSpine.globalParagraphBase)
            }
            targets.putAll(session.fragmentTargetsForSpine(spineIndex))
        }
        displayBlockCount = session.packageIndex.spineItems.indices.sumOf { index ->
            session.blocksForSpine(index).size
        }
    }

    private fun installBoundaryPreviewRenderer(
        generation: Long,
        forward: Boolean,
        sourceSpine: Int,
        sourceChapterGeneration: Long,
        targetSpine: Int,
        targetFlow: EpubChapterFlow,
    ) {
        val liveView = flowView ?: return
        val host = flowHost ?: run {
            liveView.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        if (
            !liveView.boundaryPreviewBudgetAllows(forward) ||
            (pageShotSpeculationPaused || pageShotBudget.isSpeculativeAdmissionPaused) &&
            !liveView.boundaryPreviewIsRequired(forward)
        ) {
            liveView.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        if (liveView.width <= 0 || liveView.height <= 0) {
            liveView.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        boundaryPreviewSessions.remove(forward)?.let(::disposeBoundaryPreviewSession)
        val palette = paletteFor(themeMode, context.resources.configuration)
        val previewView = EpubFlowView(
            context = context,
            onTapZone = {},
            onTopOffsetChanged = {},
            onSelectionRange = { _, _ -> },
            pageShotBudget = pageShotBudget,
            onPageShotOutOfMemory = ::onPageShotOutOfMemory,
            onPinnedPageShotAdmissionNeeded = {
                flowView?.evictSpeculativePageShotsForPinnedAllocation(forward)
            },
        ).also {
            configureFlowView(it, palette)
            it.animateChapterReveal = false
            it.pageTexturePrecacheEnabled = false
            // Kept behind the opaque live reader. A zero-alpha view may be skipped by ViewRoot and
            // never receive the pre-draw that closes the async-image stability gate.
            it.alpha = 1f
            it.isEnabled = false
            it.isClickable = false
            it.isFocusable = false
            it.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            it.textView.setTextIsSelectable(false)
            it.textView.isEnabled = false
            it.textView.isFocusable = false
            it.textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        host.addView(
            previewView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val widthSpec = View.MeasureSpec.makeMeasureSpec(liveView.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(liveView.height, View.MeasureSpec.EXACTLY)
        previewView.measure(widthSpec, heightSpec)
        previewView.layout(0, 0, liveView.width, liveView.height)
        previewView.onChapterStable = {
            completeBoundaryPreviewRenderer(forward, generation, previewView)
        }
        val loader = installFlowChapter(
            view = previewView,
            flow = targetFlow,
            landOnLast = !forward,
            isCurrent = {
                val session = boundaryPreviewSessions[forward]
                session?.generation == generation && session.view === previewView
            },
            onLinkClick = {},
        ) ?: run {
            host.removeView(previewView)
            previewView.dispose()
            liveView.rejectBoundaryPreview(forward, sourceChapterGeneration)
            return
        }
        val session = BoundaryPreviewRenderSession(
            generation = generation,
            forward = forward,
            sourceSpine = sourceSpine,
            sourceChapterGeneration = sourceChapterGeneration,
            targetSpine = targetSpine,
            targetFlow = targetFlow,
            view = previewView,
            loader = loader,
        )
        boundaryPreviewSessions[forward] = session
        val timeout = Runnable {
                val stale = boundaryPreviewSessions[forward]
                if (stale?.view === previewView && stale.generation == generation) {
                    boundaryPreviewSessions.remove(forward)
                    disposeBoundaryPreviewSession(stale)
                    flowView?.rejectBoundaryPreview(forward, stale.sourceChapterGeneration)
                }
            }
        session.timeoutRunnable = timeout
        previewView.postDelayed(timeout, BOUNDARY_PREVIEW_TIMEOUT_MS)
    }

    private fun completeBoundaryPreviewRenderer(
        forward: Boolean,
        generation: Long,
        previewView: EpubFlowView,
    ) {
        val session = boundaryPreviewSessions[forward] ?: return
        if (session.generation != generation || session.view !== previewView) return
        val bitmap = previewView.snapshotBoundaryLandingPage(
            landOnLast = !forward,
            // A speculative renderer may become the user's active wait while its chapter/image is
            // still settling. Admission priority must follow the live transaction at capture time;
            // otherwise a stale EVICTABLE snapshot can be rejected at the three-shot ceiling without
            // invoking owner-aware eviction, leaving the finger/tap waiting until timeout.
            required = flowView?.boundaryPreviewIsRequired(forward) == true,
        )
        boundaryPreviewSessions.remove(forward)
        disposeBoundaryPreviewSession(session)
        if (bitmap == null) {
            flowView?.rejectBoundaryPreview(forward, session.sourceChapterGeneration)
            return
        }
        val liveView = flowView
        if (
            generation != boundaryPreviewGeneration ||
            liveView == null ||
            flowSpineIndex != session.sourceSpine ||
            liveView.boundaryPreviewGenerationToken() != session.sourceChapterGeneration ||
            !liveView.boundaryPreviewBudgetAllows(forward) ||
            (
                (pageShotSpeculationPaused || pageShotBudget.isSpeculativeAdmissionPaused) &&
                    !liveView.boundaryPreviewIsRequired(forward)
                )
        ) {
            pageShotBudget.release(bitmap)
            bitmap.recycle()
            liveView?.rejectBoundaryPreview(forward, session.sourceChapterGeneration)
            return
        }
        val token = ++nextBoundaryPreviewToken
        boundaryPreviewTargets[token] = BoundaryPreviewTarget(
            generation = generation,
            sourceSpine = session.sourceSpine,
            sourcePreviewGeneration = session.sourceChapterGeneration,
            targetSpine = session.targetSpine,
            forward = forward,
            targetFlow = session.targetFlow,
        )
        val accepted = liveView.offerBoundaryPreview(
            BoundaryPagePreview(
                token = token,
                forward = forward,
                sourceChapterGeneration = session.sourceChapterGeneration,
                bitmap = bitmap,
            ),
        )
        if (!accepted) {
            boundaryPreviewTargets.remove(token)
            liveView.rejectBoundaryPreview(forward, session.sourceChapterGeneration)
        }
    }

    private fun commitBoundaryPreview(preview: BoundaryPagePreview) {
        if (!canCommitBoundaryPreview(preview)) return
        val target = boundaryPreviewTargets.remove(preview.token) ?: return
        invalidateBoundaryPreviewState(clearViewSlots = true)
        loadFlowChapter(
            spineIndex = target.targetSpine,
            restoreToParagraph = if (target.forward) firstParagraphOfSpine(target.targetSpine) else null,
            landOnLast = !target.forward,
            reportPositionAfterStableReveal = true,
            preparedFlow = target.targetFlow,
        )
    }

    private fun canCommitBoundaryPreview(preview: BoundaryPagePreview): Boolean {
        val target = boundaryPreviewTargets[preview.token] ?: return false
        return target.generation == boundaryPreviewGeneration &&
            target.sourceSpine == flowSpineIndex &&
            target.sourcePreviewGeneration == preview.sourceChapterGeneration &&
            target.sourcePreviewGeneration == flowView?.boundaryPreviewGenerationToken() &&
            target.forward == preview.forward
    }

    private fun discardBoundaryPreview(preview: BoundaryPagePreview) {
        boundaryPreviewTargets.remove(preview.token)
        val view = flowView ?: return
        view.post {
            if (
                flowView === view &&
                flowSpineIndex >= 0 &&
                !pageShotSpeculationPaused &&
                !pageShotBudget.isSpeculativeAdmissionPaused
            ) {
                requestBoundaryPreview(preview.forward, view.boundaryPreviewGenerationToken())
            }
        }
    }

    private fun cancelBoundaryPreviewRequest(forward: Boolean) {
        boundaryPreviewJobTokens.remove(forward)
        boundaryPreviewJobs.remove(forward)?.cancel(true)
        boundaryPreviewSessions.remove(forward)?.let(::disposeBoundaryPreviewSession)
        boundaryPreviewTargets.entries.removeAll { (_, target) -> target.forward == forward }
    }

    private fun trimBoundaryPreviewStatePreservingActiveTurn() {
        val activeToken = flowView?.activeBoundaryPreviewToken()
        val activeTarget = activeToken?.let(boundaryPreviewTargets::get)
        boundaryPreviewGeneration++
        boundaryPreviewJobs.values.forEach { it.cancel(true) }
        boundaryPreviewJobs.clear()
        boundaryPreviewJobTokens.clear()
        boundaryPreviewSessions.values.toList().forEach(::disposeBoundaryPreviewSession)
        boundaryPreviewSessions.clear()
        boundaryPreviewTargets.clear()
        if (activeToken != null && activeTarget != null) {
            boundaryPreviewTargets[activeToken] = activeTarget.copy(generation = boundaryPreviewGeneration)
        }
    }

    private fun invalidateBoundaryPreviewState(clearViewSlots: Boolean) {
        boundaryPreviewGeneration++
        boundaryPreviewJobs.values.forEach { it.cancel(true) }
        boundaryPreviewJobs.clear()
        boundaryPreviewJobTokens.clear()
        boundaryPreviewSessions.values.toList().forEach(::disposeBoundaryPreviewSession)
        boundaryPreviewSessions.clear()
        boundaryPreviewTargets.clear()
        if (clearViewSlots) flowView?.clearBoundaryPreviews()
    }

    private fun disposeBoundaryPreviewSession(session: BoundaryPreviewRenderSession) {
        session.timeoutRunnable?.let(session.view::removeCallbacks)
        session.timeoutRunnable = null
        runCatching { AsyncDrawableScheduler.unschedule(session.view.textView) }
        session.loader.releaseAll()
        session.view.dispose()
        (session.view.parent as? ViewGroup)?.removeView(session.view)
    }

    /**
     * Flow offsets of full-page illustration occurrences (covers/彩插), by the same intrinsic-pixel
     * gate the legacy paged path uses ([FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX]). Occurrence identity keeps
     * an inline and a standalone use of the same resource from affecting each other's sizing policy.
     */
    private fun flowFullPageImageOffsets(flow: EpubChapterFlow): Set<Int> {
        val result = HashSet<Int>()
        flow.segments.forEach { seg ->
            val block = seg.block
            if (block is EpubDisplayBlock.Image && !block.isInlineContent) {
                val bounds = epubImageBoundsFor(block.href) ?: return@forEach
                if (maxOf(bounds.width, bounds.height) >= FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX) {
                    result += seg.layoutStart
                }
            }
        }
        return result
    }

    private fun flowAnchorFromLocator(locator: Locator): Pair<Int, Int> {
        val resolved = resolveLegacyPageLocator(locator)
        val paragraphIndex = epubIndexFromLocator(resolved, paras.size)
        val paragraphOffset = when (val strategy = resolved.strategy) {
            is LocatorStrategy.Section ->
                (strategy.charOffset - (paras.getOrNull(paragraphIndex)?.spineCharStart ?: 0)).coerceAtLeast(0)
            else -> 0
        }
        return paragraphIndex to paragraphOffset
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
        startupSession?.packageIndex?.spineItems?.indices?.toList()
            ?: paras.map { it.spineIndex }.distinct().sorted()

    /** Spine adjacent to [spineIndex] in [delta] direction, or null past the book boundary. */
    private fun adjacentSpine(spineIndex: Int, delta: Int): Int? {
        val order = flowSpineOrder()
        val pos = order.indexOf(spineIndex)
        if (pos < 0) return null
        val next = pos + delta
        return order.getOrNull(next)
    }

    private fun firstParagraphOfSpine(spineIndex: Int): Int =
        startupSession?.globalBase(spineIndex)
            ?: paras.indexOfFirst { it.spineIndex == spineIndex }.coerceAtLeast(0)

    /**
     * Turns one page in [delta] direction. Within a chapter the flow view scrolls; at a chapter
     * boundary it loads the adjacent spine — forward lands on its first page, backward on its last
     * (Phase 4 cross-chapter continuity).
     */
    private fun advanceFlowPage(delta: Int) {
        val view = flowView ?: return
        if (view.goToAdjacentPage(delta)) return
        adjacentSpine(flowSpineIndex, delta) ?: return
        view.startDiscreteBoundaryTurn(delta)
    }

    private fun handleFlowTopOffsetChanged(layoutOffset: Int) {
        val flow = flowCurrentFlow ?: return
        val (paragraphIndex, paraOffset) = flow.paragraphAtOffset(layoutOffset) ?: return
        warmCacheAround(paragraphIndex)
        _currentLocator.value = runtimeLocatorForOffset(paragraphIndex, paraOffset)
        updateChapterInfo(paragraphIndex)
        _pageCount.value = flowView?.pageCount() ?: _pageCount.value
        prewarmBoundaryPreviews()
    }

    private fun updateFlowSelection(start: Int, end: Int) {
        val flow = flowCurrentFlow ?: return
        if (start == end) { _currentTextSelection.value = null; return }
        val (sPara, sOff) = flow.paragraphAtOffset(minOf(start, end)) ?: return
        val (ePara, eOff) = flow.paragraphAtOffset(maxOf(start, end)) ?: return
        val startLoc = runtimeLocatorForOffset(sPara, sOff)
        val endLoc = runtimeLocatorForOffset(ePara, eOff)
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
        val pages = ensureLegacyPagedSlices()
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
        startupSession?.let { session ->
            val para = paras.getOrNull(paraIndex) ?: return
            val base = session.globalBase(para.spineIndex) ?: return
            val content = session.startupSpine(para.spineIndex)?.parsed ?: return
            val localIndex = (paraIndex - base).coerceIn(0, (content.paras.size - 1).coerceAtLeast(0))
            _chapterInfo.value = ChapterInfo(
                currentIndex = para.spineIndex,
                totalChapters = session.packageIndex.spineItems.size.coerceAtLeast(1),
                currentTitle = "第${para.spineIndex + 1}章",
                progressInChapter = if (content.paras.isNotEmpty()) {
                    localIndex.toFloat() / content.paras.size
                } else {
                    0f
                },
            )
            return
        }
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
        val session = startupSession
        if (session != null) {
            val sourceSpine = (_currentLocator.value.strategy as? LocatorStrategy.Section)?.spineIndex
                ?: flowSpineIndex.takeIf { it >= 0 }
                ?: 0
            val targetSpine = adjacentSpine(sourceSpine, delta) ?: return
            val target = withContext(Dispatchers.IO) {
                session.ensureAdjacent(sourceSpine, targetSpine)
            }
            if (target != null) {
                val remainsProvisional = withContext(Dispatchers.Main) {
                    if (startupSession !== session) return@withContext false
                    refreshStartupSessionSpine(session, target)
                    true
                }
                if (remainsProvisional) {
                    val charOffset = target.parsed.paras.firstOrNull()?.spineCharStart ?: 0
                    goToFlow(
                        Locator(
                            strategy = LocatorStrategy.Section(
                                spineIndex = targetSpine,
                                elementIndex = target.globalParagraphBase,
                                charOffset = charOffset,
                            ),
                            progression = session.approximateProgression(targetSpine, 0f),
                            totalProgression = session.approximateProgression(targetSpine, 0f),
                        ),
                    )
                    return
                }
            }
            awaitFullIndexBook()
        }
        val info = _chapterInfo.value
        if (chapterBoundaries.isEmpty()) return
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
        val provisionalSession = startupSession
        val requestedSection = locator.strategy as? LocatorStrategy.Section
        if (provisionalSession != null && requestedSection != null) {
            val startupTarget = withContext(Dispatchers.IO) {
                val resolved = provisionalSession.ensureSection(requestedSection) ?: return@withContext null
                val spine = provisionalSession.startupSpine(requestedSection.spineIndex)
                    ?: return@withContext null
                resolved to spine
            }
            if (startupTarget != null) {
                val (resolved, spine) = startupTarget
                val remainsProvisional = withContext(Dispatchers.Main) {
                    if (startupSession !== provisionalSession) return@withContext false
                    refreshStartupSessionSpine(provisionalSession, spine)
                    navigateFlowOnMain(
                        index = resolved.globalParagraphIndex,
                        paragraphOffset = resolved.anchor.paragraphOffset,
                        targetSpine = requestedSection.spineIndex,
                    )
                    true
                }
                if (remainsProvisional) return
            }
        }
        if (startupSession != null) awaitFullIndexBook()
        val resolved = if (locator.strategy is LocatorStrategy.Page && pagedSlices.isEmpty()) {
            withContext(Dispatchers.IO) { resolveLegacyPageLocator(locator) }
        } else {
            resolveLegacyPageLocator(locator)
        }
        val idx = epubIndexFromLocator(resolved, paras.size).coerceIn(0, (paras.size - 1).coerceAtLeast(0))
        val paraOffset = when (val s = resolved.strategy) {
            is LocatorStrategy.Section -> (s.charOffset - (paras.getOrNull(idx)?.spineCharStart ?: 0)).coerceAtLeast(0)
            else -> 0
        }
        val targetSpine = spineIndexForParagraph(idx)
        withContext(Dispatchers.IO) { lazyBook?.prefetchAroundParagraph(idx) }
        withContext(Dispatchers.Main) {
            navigateFlowOnMain(idx, paraOffset, targetSpine)
        }
    }

    private suspend fun awaitFullIndexBook(): EpubLazyBook? {
        lazyBook?.let { return it }
        val deferred = fullIndexDeferred ?: return null
        val book = runCatching { deferred.await() }.getOrNull() ?: return null
        return withContext(Dispatchers.Main) {
            if (fullIndexDeferred === deferred && lazyBook == null) {
                promoteFullIndex(bookGeneration.get(), book)
            }
            lazyBook
        }
    }

    private fun navigateFlowOnMain(
        index: Int,
        paragraphOffset: Int,
        targetSpine: Int,
    ) {
        invalidateBoundaryPreviewState(clearViewSlots = true)
        val view = flowView
        if (view == null) {
            _currentLocator.value = runtimeLocatorForOffset(index, paragraphOffset)
            updateChapterInfo(index)
            return
        }
        if (targetSpine != flowSpineIndex) {
            loadFlowChapter(
                targetSpine,
                restoreToParagraph = index,
                restoreToParagraphOffset = paragraphOffset,
            )
        } else {
            val offset = flowCurrentFlow?.offsetForParagraph(index, paragraphOffset) ?: 0
            view.goToOffset(offset)
            prewarmBoundaryPreviews()
        }
        _currentLocator.value = runtimeLocatorForOffset(index, paragraphOffset)
        updateChapterInfo(index)
    }

    private fun runtimeLocatorForOffset(paragraphIndex: Int, paragraphOffset: Int): Locator {
        val locator = epubLocatorForOffset(paras, paragraphIndex, paragraphOffset)
        val session = startupSession ?: return locator
        val section = locator.strategy as? LocatorStrategy.Section ?: return locator
        val spineCharCount = spineCharCounts.getOrNull(section.spineIndex) ?: 0
        val spineFraction = if (spineCharCount > 0) {
            section.charOffset.toFloat() / spineCharCount
        } else {
            0f
        }
        val progression = session.approximateProgression(section.spineIndex, spineFraction)
        return locator.copy(progression = progression, totalProgression = progression)
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

    override suspend fun search(query: String): List<Locator> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val indexedBook = if (startupSession != null) awaitFullIndexBook() else lazyBook
            val indexedParas = indexedBook?.paras ?: paras
            epubSearchLocators(indexedParas, query) { index -> indexedBook?.paragraphAt(index) }
        }
    }

    override fun clearTextSelection() {
        _currentTextSelection.value = null
        clearActiveComposeSelectionRanges()
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        textAnnotations = annotations
        if (flowEngineEnabled && flowView != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                invalidateBoundaryPreviewState(clearViewSlots = true)
            } else {
                flowMainHandler.post {
                    invalidateBoundaryPreviewState(clearViewSlots = true)
                    prewarmBoundaryPreviews()
                }
            }
        }
        // Flow mode owns its own single-Spannable surface; refresh its highlight spans in place
        // (no reload, no repagination) so newly added annotations paint immediately.
        flowView?.let { view -> flowCurrentFlow?.let { flow -> view.refreshHighlights(flowHighlightRanges(flow)) } }
        if (flowEngineEnabled && Looper.myLooper() == Looper.getMainLooper()) prewarmBoundaryPreviews()
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
        if (paragraphIndex !in paras.indices) return null
        return runtimeLocatorForOffset(paragraphIndex, paragraphOffset)
    }

    private fun resetBookStateForOpen() {
        bookGeneration.incrementAndGet()
        invalidateBoundaryPreviewState(clearViewSlots = false)
        flowMainHandler.removeCallbacksAndMessages(null)
        liveFlowImageLoader?.releaseAll()
        liveFlowImageLoader = null
        cacheJob?.cancel()
        cacheJob = null
        cacheScope = null
        flowView?.textView?.let { runCatching { AsyncDrawableScheduler.unschedule(it) } }
        flowView?.dispose()
        flowView = null
        flowHost = null
        flowCurrentFlow = null
        flowSpineIndex = -1
        flowAppliedPalette = null
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
        startupSession?.close()
        startupSession = null
        fullIndexDeferred = null
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
        bookGeneration.incrementAndGet()
        unregisterPageShotMemoryCallbacks()
        invalidateBoundaryPreviewState(clearViewSlots = false)
        flowMainHandler.removeCallbacksAndMessages(null)
        liveFlowImageLoader?.releaseAll()
        liveFlowImageLoader = null
        recyclerView = null
        pageRequestCallback = null
        pendingInitialLocator = null
        flowView?.textView?.let { runCatching { AsyncDrawableScheduler.unschedule(it) } }
        flowView?.dispose()
        flowView = null
        flowHost = null
        flowCurrentFlow = null
        flowSpineIndex = -1
        flowAppliedPalette = null
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
        startupSession?.close()
        startupSession = null
        fullIndexDeferred = null
        lazyBook?.close()
        lazyBook = null
        imageBoundsCache.clear()
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
        if (fontSizeSp == sp) return
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
        if (lineSpacingMultiplier == multiplier) return
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
        if (flipStyle == style) return
        flipStyle = style
        withContext(Dispatchers.Main) {
            invalidateBoundaryPreviewState(clearViewSlots = true)
            flowView?.flipStyle = style
            prewarmBoundaryPreviews()
        }
    }

    override suspend fun setSerifFont(useSourceHan: Boolean) {
        val targetFontId = "system_serif"
        if (currentFontId == targetFontId) return
        this.useSourceHan = false
        currentFontId = targetFontId
        if (flowEngineEnabled) { rebuildFlowChapter(); return }
        // Rebind active Compose text pages to pick up new fontFamily
        withContext(Dispatchers.Main) {
            activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
            activePagedCompositePages.keys.toList().forEach(::rebindActiveCompositePage)
        }
        (recyclerView?.adapter as? EpubParaAdapter)?.notifyDataSetChanged()
    }

    override suspend fun setFont(fontId: String) {
        val normalizedFontId = FontChoice.parse(fontId).serialize()
        if (currentFontId == normalizedFontId) return
        currentFontId = normalizedFontId
        useSourceHan = false
        if (flowEngineEnabled) { rebuildFlowChapter(); return }
        withContext(Dispatchers.Main) {
            activePagedTextPages.keys.toList().forEach(::rebindActiveComposeTextPage)
            activePagedCompositePages.keys.toList().forEach(::rebindActiveCompositePage)
        }
        (recyclerView?.adapter as? EpubParaAdapter)?.notifyDataSetChanged()
    }

    override suspend fun setTheme(mode: ThemeMode) {
        val palette = paletteFor(mode, context.resources.configuration)
        if (flowEngineEnabled) {
            if (themeMode == mode && flowAppliedPalette == palette) return
            themeMode = mode
            withContext(Dispatchers.Main) {
                flowHost?.background = null
                flowView?.background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
                flowView?.textView?.setTextColor(palette.ink)
                flowAppliedPalette = palette
                rebuildFlowChapter()
            }
            return
        }
        themeMode = mode
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
            invalidateBoundaryPreviewState(clearViewSlots = true)
            val view = flowView ?: return@withContext
            val (anchorParagraph, anchorOffset) = flowAnchorFromLocator(_currentLocator.value)
            view.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            view.textView.typeface = dev.readflow.core.ui.FontProvider.typefaceFor(context, currentFontId)
            applyFlowLineSpacing(view.textView)
            if (flowSpineIndex >= 0) {
                loadFlowChapter(
                    flowSpineIndex,
                    restoreToParagraph = anchorParagraph,
                    restoreToParagraphOffset = anchorOffset,
                )
            }
        }
    }

    override suspend fun setMode(mode: ReadingMode) {
        val targetKind = when (mode) {
            ReadingMode.SCROLL -> PagingKind.CONTINUOUS
            ReadingMode.PAGED -> PagingKind.PAGED
        }
        if (targetKind == _pagingKind.value) return
        if (flowEngineEnabled) {
            if (targetKind == PagingKind.PAGED && flowView == null) {
                // No flow view: legacy paged slices own pageCount. Await full-index only while
                // startupSession is still live; if promotion already retired it, still build slices.
                if (startupSession != null) {
                    awaitFullIndexBook()
                }
                withContext(Dispatchers.IO) { ensureLegacyPagedSlices() }
            }
            withContext(Dispatchers.Main) {
                invalidateBoundaryPreviewState(clearViewSlots = true)
                _pagingKind.value = targetKind
                val view = flowView
                view?.let {
                    val offset = flowCurrentFlow?.let { flow ->
                        it.topLayoutOffset().coerceIn(0, flow.text.length)
                    } ?: run {
                        val (anchor, anchorOffset) = flowAnchorFromLocator(_currentLocator.value)
                        flowCurrentFlow?.offsetForParagraph(anchor, anchorOffset) ?: 0
                    }
                    it.setModeAnchored(
                        if (targetKind == PagingKind.PAGED) EpubFlowView.Mode.PAGED else EpubFlowView.Mode.SCROLL,
                        offset,
                    )
                    _pageCount.value = it.pageCount()
                }
                if (view == null) {
                    _pageCount.value = if (targetKind == PagingKind.PAGED) pagedSlices.size else paras.size
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
        val targetPosition = internalLinkTargetIndexes[key]
        if (targetPosition == null) {
            if (flowEngineEnabled && startupSession != null) {
                cacheScope?.launch {
                    awaitFullIndexBook() ?: return@launch
                    val indexedTarget = internalLinkTargetIndexes[key] ?: return@launch
                    val target = epubLocatorForTarget(paras, indexedTarget)
                    withContext(Dispatchers.Main) { clearTextSelection() }
                    goToFlow(target)
                }
            }
            return
        }
        val target = epubLocatorForTarget(paras, targetPosition)
        if (flowEngineEnabled && flowView != null) {
            clearTextSelection()
            cacheScope?.launch { goToFlow(target) }
            return
        }
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

    private fun buildPagedSlices(
        indexedParas: List<EpubPara> = paras,
        indexedBlocks: List<EpubDisplayBlock> = lazyBook?.layoutBlocks().orEmpty(),
        indexedFile: File? = epubFile,
    ): List<EpubPageSlice> {
        val localImageBounds = mutableMapOf<String, EpubImageBoundsCacheEntry>()
        val indexedTextByParagraph = indexedBlocks
            .filterIsInstance<EpubDisplayBlock.Text>()
            .associate { block -> block.paragraphIndex to block.text }
        fun indexedImageBounds(href: String): EpubImageBounds? {
            localImageBounds[href]?.let { return it.value }
            val bounds = indexedFile?.let { decodeEpubImageBounds(it, href) }
            localImageBounds[href] = EpubImageBoundsCacheEntry(bounds)
            return bounds
        }
        return currentPageLineMeasurer().let { measurer ->
            val metrics = currentPageMetrics()
            epubPagedLayoutWithBlocks(
                paras = indexedParas,
                // The startup index already owns exact text blocks. Legacy Page conversion must not
                // depend on whichever three runtime spines happen to be resident in the LRU.
                textProvider = { index -> indexedTextByParagraph[index].orEmpty() },
                blockProvider = { indexedBlocks },
                metrics = metrics,
                lineBreaker = { text, contentWidth, textStyle ->
                    measurer.measure(text, contentWidth, textStyle)
                },
                measurement = measurer.measurement,
                inlineImageLineCost = { href -> inlineImageLineCost(href, metrics, ::indexedImageBounds) },
            )
        }
    }

    private fun ensureLegacyPagedSlices(): List<EpubPageSlice> = synchronized(legacyPagedSlicesLock) {
        if (lazyBook == null) {
            val deferred = fullIndexDeferred
            if (deferred != null) {
                val book = runBlocking { deferred.await() }
                if (fullIndexDeferred === deferred && startupSession != null) {
                    promoteFullIndex(bookGeneration.get(), book)
                }
            }
        }
        pagedSlices.takeIf { it.isNotEmpty() } ?: run {
            val generation = bookGeneration.get()
            val pages = buildPagedSlices()
            if (generation != bookGeneration.get()) {
                throw CancellationException("EPUB book changed while legacy pagination was building")
            }
            pages.also { pagedSlices = it }
        }
    }

    // "非必要不分页": small (inline-class) images flow with text on a shared page. Return the image's
    // estimated rendered height as a count of text-line units so the packer can budget it; return
    // null for large (full-page) images, which keep their own standalone page. Undecodable bounds →
    // null (treated full-page) to preserve the safe legacy behavior.
    private fun inlineImageLineCost(
        href: String,
        metrics: EpubPageMetrics,
        imageBoundsProvider: (String) -> EpubImageBounds?,
    ): Int? {
        val bounds = imageBoundsProvider(href) ?: return null
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
        const val BOUNDARY_PREVIEW_TIMEOUT_MS = 10_000L
        const val DEFAULT_MEMORY_CLASS_MIB = 256
        const val STARTUP_SPINE_START_TOLERANCE = 0.08f
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
