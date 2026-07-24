package dev.readflow.render.epub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.R as RenderApiR
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Continuous-flow reading surface (方案 C, Moon+ Reader model). One chapter → one whole-chapter
 * Spannable in a single [SelectionAwareTextView] → one StaticLayout (the TextView's OWN layout, the
 * single source of truth for pagination — 审计 C1/H3), hosted in a non-smooth [ScrollView].
 *
 * Touch is owned end-to-end (审计 H4/H5), mirroring Moon+ Reader / FBReader: a [GestureDetector]
 * classifies tap / long-press / scroll; selection is gated behind long-press so it never fights
 * page turns. PAGED: edge taps and horizontal drags can flip a page; vertical drags keep the
 * center 1/3 x 1/3 as a no-turn zone, with its inner 1/5 x 1/5 available for temporary continuous
 * scrolling. SCROLL: free scroll throughout.
 *
 * The resume anchor is always the char offset of the top-visible line (font-size stable), never a
 * page index.
 */
@SuppressLint("ViewConstructor")
internal class EpubFlowView(
    context: Context,
    private val onTapZone: (EpubFlowTapZone) -> Unit,
    private val onTopOffsetChanged: (layoutOffset: Int) -> Unit,
    private val onSelectionRange: (start: Int, end: Int) -> Unit,
    private val pageShotBudget: PageShotBudget = PageShotBudget(48L * 1024L * 1024L),
    private val onPageShotOutOfMemory: () -> Unit = {},
    private val onPinnedPageShotAdmissionNeeded: (() -> Unit)? = null,
) : ScrollView(context) {

    enum class Mode { PAGED, SCROLL }

    enum class BoundaryPageTurnPreparation { PREPARED, SNAPSHOT_UNAVAILABLE, NOT_ELIGIBLE }

    val textView = SelectionAwareTextView(context).apply {
        setTextIsSelectable(true)
        includeFontPadding = false
    }

    private val container = EpubFlowContainer(context).apply {
        addView(
            textView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private var modeValue: Mode = Mode.PAGED

    var mode: Mode
        get() = modeValue
        set(value) {
            applyMode(value, reposition = true)
        }

    fun setModeAnchored(value: Mode, layoutOffset: Int) {
        abortLocalPageShotTurnForExternalMutation()
        val hidePagedConversion = modeValue == Mode.SCROLL && value == Mode.PAGED && flow != null
        val conversionSnapshot = if (hidePagedConversion) {
            snapshotViewport(
                PageShotLeaseKind.PINNED,
                "continuity.mode",
                fullResolution = true,
            )
        } else {
            null
        }
        if (hidePagedConversion) {
            removeCallbacks(revealSafetyRunnable)
            container.animate().cancel()
            pendingRestoreOffset = layoutOffset
            pendingLandOnLast = false
            awaitingReveal = true
            awaitingStableChapter = true
            container.alpha = 0f
        }
        applyMode(value, reposition = false)
        goToOffset(layoutOffset, pagedAnchor = PagedAnchor.NEAREST, forceReport = true)
        if (hidePagedConversion) {
            showConversionSnapshot(conversionSnapshot)
            if (textView.layout != null) pendingRestoreOffset = topLayoutOffset()
            tryRevealWhenStable()
        }
    }

    private fun applyMode(value: Mode, reposition: Boolean) {
        if (modeValue != value) {
            cancelFreeFlingForLifecycle()
            abortLocalPageShotTurnForExternalMutation()
            onBoundaryPreviewConfigurationChanged?.invoke()
            clearBoundaryPreviews()
            recycleCachedTextures()
        }
        modeValue = value
        repaginate(reposition = reposition)
    }

    private var paged: List<EpubFlowPage> = emptyList()
    private var pageHeightPx: Int = 0
    private var currentPage: Int = 0
    private enum class PagedMotionState { ALIGNED, DRAGGING_FREE, FLING_FREE, FREE_REST, ALIGN_AND_TURN }
    private data class PageTurnOrigin(
        val pageProjection: Int,
        val topPx: Int,
        val clipActive: Boolean,
        val motionState: PagedMotionState,
        val window: EpubFlowPage?,
    )
    private var pagedMotionState = PagedMotionState.ALIGNED
    /** Exact settled line window. Null means the viewport is an arbitrary FREE_REST pixel position. */
    private var activePageWindow: EpubFlowPage? = null
    private var flow: EpubChapterFlow? = null
    /** Paper background painted in viewport coordinates; avoids ScrollView's scroll-translated background. */
    private var viewportBackground: Drawable? = null

    /** Resume target for the in-flight [setChapter], applied once layout is ready (before the reveal). */
    private var pendingRestoreOffset: Int? = null
    private var pendingLandOnLast = false
    /** Provider queried by the stability gate to check for pending async image decodes. */
    var pendingDecodesProvider: (() -> Boolean)? = null

    /**
     * Layout-offset ranges for the restored/visible current page plus adjacent previous and next
     * pages. Used by [pendingDecodesProvider] for relevance-aware decode gating so far-page work
     * does not hold reveal or nearby page-shot precache.
     */
    fun relevantPendingDecodeLayoutRanges(): List<IntRange> =
        decodeLayoutRangesFor(relevantPageWindows())

    /** Decode gate for a prepared boundary surface: only the page that will be revealed must be ready. */
    fun currentPageDecodeLayoutRanges(): List<IntRange> =
        paged.getOrNull(currentPage)
            ?.let { page -> decodeLayoutRangesFor(listOf(page)) }
            .orEmpty()

    private fun decodeLayoutRangesFor(windows: Collection<EpubFlowPage>): List<IntRange> {
        if (windows.isEmpty()) return emptyList()
        val direct = windows.map { page -> page.startOffset until page.endOffset }
        val layout = textView.layout ?: return direct
        val chapter = flow ?: return direct
        val previews = pageLayoutMetadataFor(layout)?.pageBoundaryImagePreviews
            ?: pageBoundaryImagePreviews(layout, chapter)
        val boundaryDependencies = previews.asSequence()
            .filter { preview ->
                windows.any { page ->
                    preview.precedingEndLineExclusive > page.startLine &&
                        preview.precedingEndLineExclusive <= page.endLineExclusive &&
                        preview.imageLine >= page.endLineExclusive
                }
            }
            .map(PageBoundaryImagePreview::imageLayoutStart)
            .filterNot { offset -> direct.any { offset in it } }
            .distinct()
            .map { offset -> offset..offset }
            .toList()
        return direct + boundaryDependencies
    }
    private var asyncImageWakeObserver: android.view.ViewTreeObserver? = null
    private var asyncImageWakeListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var asyncImageRefreshPending = false
    private val asyncImagePixelRefreshOffsets = LinkedHashSet<Int>()
    private var asyncImagePixelTextRebindPending = false
    private var asyncImageBatchWaitStartedAtMs = 0L
    private val asyncImageRefreshRunnable = object : Runnable {
        override fun run() {
            if (
                disposed ||
                (!asyncImageRefreshPending &&
                    asyncImagePixelRefreshOffsets.isEmpty() &&
                    !asyncImagePixelTextRebindPending)
            ) return
            if (turnInFlight) {
                postDelayed(this, REFLOW_DEBOUNCE_MS)
                return
            }
            if (pendingDecodesProvider?.invoke() == true) {
                // Freeze the current/adjacent image batch before one TextView display-list rebuild.
                // The provider ignores far-page work, so unrelated decodes do not hold this queue.
                val now = android.os.SystemClock.uptimeMillis()
                if (asyncImageBatchWaitStartedAtMs == 0L) asyncImageBatchWaitStartedAtMs = now
                if (now - asyncImageBatchWaitStartedAtMs < ASYNC_IMAGE_BATCH_MAX_WAIT_MS) {
                    postDelayed(this, REFLOW_DEBOUNCE_MS)
                    return
                }
            }
            asyncImageBatchWaitStartedAtMs = 0L
            if (asyncImageRefreshPending) {
                asyncImageRefreshPending = false
                asyncImagePixelRefreshOffsets.clear()
                asyncImagePixelTextRebindPending = false
                // Geometry-level async result may also supersede any staged in-place pixel redraws.
                cancelInPlacePageShotRefreshCallbacks()
                pendingInPlacePageShotRefreshSlots.clear()
                applyAsyncImageResultRefresh()
            } else {
                val offsets = asyncImagePixelRefreshOffsets.toList()
                asyncImagePixelRefreshOffsets.clear()
                val rebindText = asyncImagePixelTextRebindPending
                asyncImagePixelTextRebindPending = false
                applyAsyncImagePixelRefresh(offsets, rebindText = rebindText)
            }
        }
    }
    /**
     * Stable warm slots that need an in-place pixel redraw after PIXELS_ONLY. Owners stay leased;
     * only pixels are refreshed, one slot per animation frame while idle/aligned.
     */
    private enum class CachedPageShotSlot { FRONT, REVEALED, BACKWARD }
    private val pendingInPlacePageShotRefreshSlots = LinkedHashSet<CachedPageShotSlot>()
    private var inPlacePageShotRefreshPosted = false
    private val inPlacePageShotRefreshRunnable = Runnable { drainOneInPlacePageShotRefresh() }

    /** True between [setChapter] and the first positioned frame: content is alpha-hidden until then. */
    private var awaitingReveal = false
    private var awaitingStableChapter = false
    private var reportPositionAfterStableReveal = false
    private var stableCallbackGeneration = -1L
    private var boundaryPreviewGeneration = 0L
    private var disposed = false
    var animateChapterReveal: Boolean = true
    var pageTexturePrecacheEnabled: Boolean = true
    private var pageShotSpeculationPaused: Boolean = false
    var onChapterStable: (() -> Unit)? = null
    private var lastReportedTopOffset: Int? = null
    private val revealSafetyRunnable = Runnable {
        if (!awaitingReveal) return@Runnable
        val offset = pendingRestoreOffset
        if (offset != null && flow != null) {
            goToOffset(
                offset,
                pagedAnchor = PagedAnchor.NEAREST,
                report = !reportPositionAfterStableReveal,
                forceReport = !reportPositionAfterStableReveal,
            )
        }
        revealContent(stable = false)
    }
    private val conversionSnapshotClearRunnable = Runnable { clearConversionSnapshot() }
    /** Cross-fade animator retiring the frozen conversion snapshot; cancelled when the cover is replaced. */
    private var conversionFadeAnimator: android.animation.ValueAnimator? = null
    /** First page turn requested before the initial layout exists; replayed once pagination is ready. */
    private var pendingInitialPageTurnDelta: Int? = null
    private val continuityPageShotConfig = Bitmap.Config.ARGB_8888
    private val pageBoundaryBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pageBoundaryDestination = Rect()
    /** Reused by non-BitmapDrawable crop paint (main-thread draw only; no concurrent access). */
    private val pageBoundaryHostBoundsScratch = Rect()
    private val pageBoundaryResultBoundsScratch = Rect()

    /** Layout height we last paginated against; a change means the content reflowed (async image load). */
    private var paginatedLayoutHeight: Int = -1
    private var pageLayoutGeneration: Long = 0L
    private data class ParagraphLineInterval(
        val layoutStart: Int,
        val layoutEndExclusive: Int,
        val firstLine: Int,
        val endLineExclusive: Int,
    )
    /**
     * Synthetic crop of a large indivisible image that starts on a later page, painted into the
     * leftover band under preceding visible content on the current page. One image occurrence only
     * (same AsyncDrawable / U+FFFC); the full image still owns its own page window.
     *
     * [imageDrawableHost] is resolved once when page layout metadata is built for this generation
     * so live/page-shot draws reuse the same AsyncDrawable shell (result may still swap in place)
     * without re-running Spannable.getSpans every frame.
     */
    private data class PageBoundaryImagePreview(
        /** Layout start of the last visible content block that ends on this page (heading or body). */
        val precedingLayoutStart: Int,
        val precedingEndLineExclusive: Int,
        val precedingIsHeading: Boolean,
        val imageLayoutStart: Int,
        val imageLine: Int,
        /** Generation-owned span host; null only when the span was missing at metadata build. */
        val imageDrawableHost: Drawable? = null,
    )
    private data class PageLayoutMetadata(
        val pageGeneration: Long,
        val chapterGeneration: Long,
        val layoutWidthPx: Int,
        val layoutHeightPx: Int,
        val lineCount: Int,
        val usablePageHeightPx: Int,
        val headingLines: Set<Int>,
        val paragraphIntervals: List<ParagraphLineInterval>,
        val pageBoundaryImagePreviews: List<PageBoundaryImagePreview>,
    )
    private var pageLayoutMetadata: PageLayoutMetadata? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val density = resources.displayMetrics.density
    private val turnIntentDistancePx = 8f * density
    private val microTurnMinimumDistancePx = 4f * density
    private val flipCrossAxisLimitPx = 40 * density

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    /**
     * Crossing MOVE is applied in [onInterceptTouchEvent] because ViewGroup does not always replay
     * that event to [onTouchEvent]. When the *same* coordinates are still delivered after intercept,
     * skip that one re-apply so progress stays monotonic and page shots allocate once. A later MOVE
     * with different coordinates must still advance the gesture (cold handoff resume).
     */
    private var suppressClassifiedMoveX = Float.NaN
    private var suppressClassifiedMoveY = Float.NaN
    private var inSelectionMode = false
    private var classified = false
    private var freeScrolling = false
    private var centerDeadGesture = false
    private var flipped = false
    private var stealing = false
    private var pendingCleanTapX: Float? = null
    private var freeFlingStartedAtMs = 0L
    private var freeFlingStableFrames = 0
    private var interruptedFreeFlingNeedsRebase = false
    private var flingStopGesture = false
    /** True when a chapter continuity cover owns DOWN; the whole stream is classified in isolation. */
    private var coverConsumedGesture = false
    /** A new stream that began while the page-turn renderer still owned the viewport. */
    private var busyPageTurnGesture = false
    /** A selectable stream that began after the renderer settled but before rapid coalescing expired. */
    private var rapidIdlePageTurnGesture = false

    /**
     * Page-turn animation style (PAGED only). SLIDE = hardware overlay slide (default, GPU-composited);
     * SIMULATION = one local PAPER renderer for drag/tap/key and chapter boundaries; NONE = instant cut.
     * Switched live from settings; the next turn uses the new style.
     */
    var flipStyle: dev.readflow.core.model.PageFlipStyle = dev.readflow.core.model.PageFlipStyle.SLIDE
        set(value) {
            if (field == value) return
            abortLocalPageShotTurnForExternalMutation(restoreOrigin = true)
            recycleCachedTextures()
            field = value
            // Live settings / instrumentation flip must re-arm warm front+revealed for the parked page.
            if (mode == Mode.PAGED && isLayoutSettled()) {
                preCachePageTextures()
            }
        }
    private val pageTurnAnimated: Boolean get() = flipStyle != dev.readflow.core.model.PageFlipStyle.NONE
    /**
     * True while any local page-turn animation or finger-owned transaction is in flight. New turns and
     * reflows are deferred so the paged array cannot change beneath the page-shot owner.
     */
    private val turnInFlight: Boolean
        get() =
            flipAnimator?.isRunning == true ||
                interactiveTurnState != InteractiveTurnState.NONE ||
                pagedMotionState == PagedMotionState.DRAGGING_FREE ||
                pagedMotionState == PagedMotionState.FLING_FREE
    private val pageTurnRendererBusy: Boolean
        get() =
            flipAnimator?.isRunning == true ||
                releasedLocalIntentWaiting ||
                boundaryDiscreteWaiting

    /**
     * Public idle gate for external chapter rebuilds (e.g. font prewarm completion).
     * Same predicate as internal reflow deferral: animation, interactive turn, temporary free drag,
     * or free fling.
     */
    fun isPageMutationInFlight(): Boolean = turnInFlight

    /** True while a page-turn visual transaction or an accepted rapid displacement is unresolved. */
    internal fun isPageTurnMotionActive(): Boolean =
        flipAnimator?.isRunning == true ||
            interactiveTurnState != InteractiveTurnState.NONE ||
            pageShotOverlayActive ||
            queuedPageTurnDelta != 0

    /** Includes the rapid coalescing idle window used to defer display-quality promotion. */
    internal fun isRapidTurnPerformanceModeActive(): Boolean =
        isPageTurnMotionActive() || rapidTurnSequenceActive

    private var flipAnimator: ValueAnimator? = null
    private var slideDrawable: PageSlideDrawable? = null
    private var curlDrawable: PageCurlDrawable? = null
    private val pageShotOverlayActive: Boolean
        get() = slideDrawable != null || curlDrawable != null
    private val fullViewportOverlayActive: Boolean
        get() = pageShotOverlayActive || conversionSnapshotDrawable != null
    /** Defensive settle bookkeeping; dirty cache owners are rejected before an active turn starts. */
    private var activeFlipFrontPixelRefreshPending = false
    private var activeFlipRevealedPixelRefreshPending = false
    private var localSoftwareSettleCommit: Boolean? = null
    private var conversionSnapshotDrawable: ViewportSnapshotDrawable? = null
    private var conversionSnapshotFlattener: (ViewportSnapshotDrawable, Bitmap) -> Boolean =
        { cover, destination -> cover.flattenOver(destination) }
    private var boundaryContinuityCover = false
    private var pendingBoundaryPageTurn: BoundaryPageTurn? = null
    private var chapterGeneration = 0L
    private val flipDurationMs = 280L
    private var queuedPageTurnDelta = 0
    private var rapidTurnSequenceActive = false
    private val rapidTurnIdleRunnable = object : Runnable {
        override fun run() {
            if (queuedPageTurnDelta != 0) {
                if (drainQueuedPageTurn()) {
                    onPageSettled?.invoke()
                } else if (!disposed) {
                    postDelayed(this, RAPID_TURN_IDLE_TIMEOUT_MS)
                }
            } else if (!turnInFlight) {
                rapidTurnSequenceActive = false
                preCachePageTextures()
                onPageSettled?.invoke()
            } else if (!disposed) {
                postDelayed(this, RAPID_TURN_IDLE_TIMEOUT_MS)
            }
        }
    }
    /** Finger-owned and boundary software turn states. */
    private enum class InteractiveTurnState {
        NONE,
        SOFTWARE,
        SOFTWARE_SETTLING,
        LOCAL_SHOTS_WAITING,
        BOUNDARY_WAITING,
        BOUNDARY_SOFTWARE,
        BOUNDARY_DISCRETE_WAITING,
        BOUNDARY_DISCRETE_ACTIVE,
    }
    private enum class InteractiveTurnAxis { HORIZONTAL, VERTICAL }
    private enum class InteractiveTurnStartResult { STARTED, WAITING, REJECTED }
    private data class PageTurnIntent(
        val forward: Boolean,
        val axis: InteractiveTurnAxis,
        val anchor: Float,
    )

    /** Mutually exclusive finger-owned turn state. */
    private var interactiveTurnState = InteractiveTurnState.NONE
    private val interactiveCurl: Boolean
        get() = interactiveTurnState == InteractiveTurnState.SOFTWARE ||
            interactiveTurnState == InteractiveTurnState.BOUNDARY_SOFTWARE
    private val localShotsWaiting: Boolean
        get() = interactiveTurnState == InteractiveTurnState.LOCAL_SHOTS_WAITING
    private val releasedLocalIntentWaiting: Boolean
        get() = localShotsWaiting && pendingLocalPageShotHandoff?.releasedVelocity != null
    private val boundaryWaiting: Boolean
        get() = interactiveTurnState == InteractiveTurnState.BOUNDARY_WAITING
    private val boundaryDiscreteWaiting: Boolean
        get() = interactiveTurnState == InteractiveTurnState.BOUNDARY_DISCRETE_WAITING
    private var forwardBoundaryPreview: BoundaryPagePreview? = null
    private var backwardBoundaryPreview: BoundaryPagePreview? = null
    private var activeBoundaryPreview: BoundaryPagePreview? = null
    private var deferredBoundaryFinishCommit = false
    private var boundaryPreviewBudgetDirection: Boolean? = null
    private var waitingBoundaryForward = true
    private var waitingBoundaryAxis = InteractiveTurnAxis.HORIZONTAL
    private var waitingBoundaryAnchor = 0f
    private var waitingBoundaryX = 0f
    private var waitingBoundaryY = 0f
    private var waitingDiscreteBoundaryForward = true

    /** Requests an adjacent chapter preview without turning the request into semantic navigation. */
    var onBoundaryPreviewNeeded: ((forward: Boolean, sourceChapterGeneration: Long) -> Unit)? = null
    var onBoundaryPreviewConfigurationChanged: (() -> Unit)? = null
    var canCommitBoundaryTurn: ((BoundaryPagePreview) -> Boolean)? = null
    var onBoundaryTurnCommitted: ((BoundaryPagePreview) -> Unit)? = null
    var onBoundaryTurnDiscarded: ((BoundaryPagePreview) -> Unit)? = null
    var onBoundaryPreviewEvicted: ((BoundaryPagePreview) -> Unit)? = null
    var onBoundaryPreviewRequestCancelled: ((forward: Boolean) -> Unit)? = null
    var onPageShotForeground: (() -> Unit)? = null
    /** Called before an eligible animated turn reads or captures page-shot pixels. */
    var onPageTurnCapturePreparing: (() -> Unit)? = null
    /** Called after a new visual turn has acquired both page shots and accepted ownership. */
    var onPageTurnStarted: (() -> Unit)? = null
    /** Called after a local target is parked so its image window can prepare the following page. */
    var onPageTurnTargetParked: (() -> Unit)? = null
    var onPageSettled: (() -> Unit)? = null

    // ---- Pre-cache page textures (Moon+ Reader model) ------------------------------------------
    // After every page settle, pre-render current + previous + next. The current shot is shared until
    // a direction is chosen; takeCachedTexturesForTurn then transfers current + that neighbour and
    // recycles the unused side. MOVE therefore never captures a full-screen page in either direction.
    private var cachedFrontBitmap: Bitmap? = null
    private var cachedRevealedBitmap: Bitmap? = null
    private var cachedBackwardBitmap: Bitmap? = null
    private var cachedFromPage: Int = -1
    private var cachedTargetPage: Int = -1
    private var cachedBackwardPage: Int = -1
    private var cachedFromTopPx: Int = -1
    private var cachedTargetTopPx: Int = -1
    private var cachedBackwardTopPx: Int = -1
    private data class PageTextureKey(
        val topPx: Int,
        val clipTopPx: Int,
        val clipBottomPx: Int,
        val viewportWidthPx: Int,
        val viewportHeightPx: Int,
        val layoutGeneration: Long,
    )
    private data class PendingLocalPageShotHandoff(
        val token: Long,
        val origin: PageTurnOrigin,
        val targetWindow: EpubFlowPage,
        val targetPage: Int,
        val forward: Boolean,
        val axis: InteractiveTurnAxis,
        val anchor: Float,
        val viewportWidthPx: Int,
        val viewportHeightPx: Int,
        val pageGeneration: Long,
        val chapterGeneration: Long,
        val fromTextureKey: PageTextureKey,
        val targetTextureKey: PageTextureKey,
        var latestX: Float,
        var latestY: Float,
        /** Zero-copy staged front retained when directional target was still missing at MOVE. */
        var frontBitmap: Bitmap? = null,
        var targetBitmap: Bitmap? = null,
        var releasedVelocity: Float? = null,
    )
    private data class PendingPageTexturePrecache(
        val generation: Long,
        val fromTop: Int,
        val fromWindow: EpubFlowPage?,
        val previousWindow: EpubFlowPage?,
        val targetWindow: EpubFlowPage?,
        val fromPage: Int,
        val previousPage: Int,
        val targetPage: Int,
        val fromTextureKey: PageTextureKey,
        val previousTextureKey: PageTextureKey?,
        val targetTextureKey: PageTextureKey?,
        var frontBitmap: Bitmap? = null,
        var previousBitmap: Bitmap? = null,
        var targetBitmap: Bitmap? = null,
        val frontBitmapRetained: Boolean = false,
        val previousBitmapRetained: Boolean = false,
        val targetBitmapRetained: Boolean = false,
    )
    private var cachedFromTextureKey: PageTextureKey? = null
    private var cachedTargetTextureKey: PageTextureKey? = null
    private var cachedBackwardTextureKey: PageTextureKey? = null
    private var pageTexturePrecachePending: Boolean = false
    private var pageTexturePrecacheGeneration: Long = 0L
    private var pendingPageTexturePrecache: PendingPageTexturePrecache? = null
    private var capturePrecacheFrontRunnable: Runnable? = null
    private var capturePrecacheTargetRunnable: Runnable? = null
    private var capturePrecachePreviousRunnable: Runnable? = null
    private var localPageShotHandoffGeneration: Long = 0L
    private var pendingLocalPageShotHandoff: PendingLocalPageShotHandoff? = null
    private var capturePendingLocalTargetRunnable: Runnable? = null
    private var capturePendingLocalFrontRunnable: Runnable? = null
    private data class RapidFollowUpPageShot(
        val generation: Long,
        val chapterGeneration: Long,
        val pageGeneration: Long,
        val sourcePage: Int,
        val sourceTop: Int,
        val sourceWindow: EpubFlowPage?,
        val targetPage: Int,
        val targetWindow: EpubFlowPage,
        val targetKey: PageTextureKey,
        val forward: Boolean,
        var bitmap: Bitmap? = null,
    )
    private var rapidFollowUpGeneration = 0L
    private var rapidFollowUpPageShot: RapidFollowUpPageShot? = null
    private var captureRapidFollowUpRunnable: Runnable? = null

    private fun postPrecacheFrontShot(request: PendingPageTexturePrecache) {
        val runnable = Runnable { capturePrecacheFrontShot(request) }
        capturePrecacheFrontRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun postPrecacheTargetShot(request: PendingPageTexturePrecache) {
        val runnable = Runnable { capturePrecacheTargetShot(request) }
        capturePrecacheTargetRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun postPrecachePreviousShot(request: PendingPageTexturePrecache) {
        val runnable = Runnable { capturePrecachePreviousShot(request) }
        capturePrecachePreviousRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun clearPendingPageTexturePrecacheCallbacks() {
        capturePrecacheFrontRunnable?.let(::removeCallbacks)
        capturePrecacheTargetRunnable?.let(::removeCallbacks)
        capturePrecachePreviousRunnable?.let(::removeCallbacks)
        capturePrecacheFrontRunnable = null
        capturePrecacheTargetRunnable = null
        capturePrecachePreviousRunnable = null
    }

    private fun postPendingLocalTargetShot(request: PendingLocalPageShotHandoff) {
        val runnable = Runnable { capturePendingLocalTargetShot(request) }
        capturePendingLocalTargetRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun postPendingLocalFrontShot(request: PendingLocalPageShotHandoff) {
        val runnable = Runnable { capturePendingLocalFrontShotAndResume(request) }
        capturePendingLocalFrontRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun clearPendingLocalPageShotCallbacks() {
        capturePendingLocalTargetRunnable?.let(::removeCallbacks)
        capturePendingLocalFrontRunnable?.let(::removeCallbacks)
        capturePendingLocalTargetRunnable = null
        capturePendingLocalFrontRunnable = null
    }

    private fun clearRapidFollowUpPageShot() {
        rapidFollowUpGeneration += 1L
        captureRapidFollowUpRunnable?.let(::removeCallbacks)
        captureRapidFollowUpRunnable = null
        rapidFollowUpPageShot?.bitmap?.let(::recyclePageShot)
        rapidFollowUpPageShot = null
    }

    private fun scheduleRapidFollowUpPageShot(forward: Boolean) {
        if (
            disposed ||
            !rapidTurnSequenceActive ||
            !turnInFlight ||
            mode != Mode.PAGED ||
            pendingDecodesProvider?.invoke() == true ||
            hasPendingPageArtifactRefresh() ||
            textView.isLayoutRequested
        ) {
            clearRapidFollowUpPageShot()
            return
        }
        val sourceWindow = activePageWindow?.takeIf { it.topPx == scrollY }
        val targetWindow = pageWindowForTurn(forward) ?: run {
            clearRapidFollowUpPageShot()
            return
        }
        if (pageWindowHasImageDependency(targetWindow)) {
            clearRapidFollowUpPageShot()
            return
        }
        val targetPage = canonicalFloorPageIndexForTopPx(targetWindow.topPx)
        val targetKey = pageTextureKey(targetWindow.topPx, targetWindow)
        val existing = rapidFollowUpPageShot
        if (
            existing != null &&
            existing.sourcePage == currentPage &&
            existing.sourceTop == scrollY &&
            existing.targetPage == targetPage &&
            existing.targetKey == targetKey &&
            existing.forward == forward
        ) return
        clearRapidFollowUpPageShot()
        val request = RapidFollowUpPageShot(
            generation = rapidFollowUpGeneration,
            chapterGeneration = chapterGeneration,
            pageGeneration = pageLayoutGeneration,
            sourcePage = currentPage,
            sourceTop = scrollY,
            sourceWindow = sourceWindow,
            targetPage = targetPage,
            targetWindow = targetWindow,
            targetKey = targetKey,
            forward = forward,
        )
        rapidFollowUpPageShot = request
        val runnable = Runnable { captureRapidFollowUpPageShot(request) }
        captureRapidFollowUpRunnable = runnable
        postDelayed(runnable, RAPID_FOLLOW_UP_PREFETCH_DELAY_MS)
    }

    private fun rapidFollowUpPageShotIsValid(request: RapidFollowUpPageShot): Boolean =
        rapidFollowUpPageShot === request &&
            request.generation == rapidFollowUpGeneration &&
            request.chapterGeneration == chapterGeneration &&
            request.pageGeneration == pageLayoutGeneration &&
            !disposed &&
            rapidTurnSequenceActive &&
            turnInFlight &&
            mode == Mode.PAGED &&
            currentPage == request.sourcePage &&
            scrollY == request.sourceTop &&
            activePageWindow?.takeIf { it.topPx == scrollY } == request.sourceWindow &&
            pageWindowForTurn(request.forward) == request.targetWindow &&
            pageTextureKey(request.targetWindow.topPx, request.targetWindow) == request.targetKey &&
            pendingDecodesProvider?.invoke() != true &&
            !hasPendingPageArtifactRefresh() &&
            !textView.isLayoutRequested

    private fun captureRapidFollowUpPageShot(request: RapidFollowUpPageShot) {
        if (!rapidFollowUpPageShotIsValid(request)) {
            if (rapidFollowUpPageShot === request) clearRapidFollowUpPageShot()
            return
        }
        val bitmap = snapshotPageAt(
            request.targetWindow.topPx,
            request.targetWindow,
            PageShotLeaseKind.EVICTABLE,
            "rapid.follow-up",
        ) ?: run {
            clearRapidFollowUpPageShot()
            return
        }
        if (!rapidFollowUpPageShotIsValid(request)) {
            recyclePageShot(bitmap)
            if (rapidFollowUpPageShot === request) clearRapidFollowUpPageShot()
            return
        }
        request.bitmap = bitmap
        captureRapidFollowUpRunnable = null
    }

    private fun takeRapidFollowUpPageShot(
        targetPage: Int,
        targetWindow: EpubFlowPage,
        forward: Boolean,
    ): Bitmap? {
        val request = rapidFollowUpPageShot ?: return null
        val bitmap = request.bitmap?.takeUnless(Bitmap::isRecycled)
        val matches =
            request.chapterGeneration == chapterGeneration &&
                request.pageGeneration == pageLayoutGeneration &&
                request.sourcePage == currentPage &&
                request.sourceTop == scrollY &&
                request.sourceWindow == activePageWindow?.takeIf { it.topPx == scrollY } &&
                request.targetPage == targetPage &&
                request.targetWindow == targetWindow &&
                request.targetKey == pageTextureKey(targetWindow.topPx, targetWindow) &&
                request.forward == forward &&
                bitmap != null
        if (!matches) {
            clearRapidFollowUpPageShot()
            return null
        }
        captureRapidFollowUpRunnable?.let(::removeCallbacks)
        captureRapidFollowUpRunnable = null
        request.bitmap = null
        rapidFollowUpPageShot = null
        relabelPageShot(bitmap, PageShotLeaseKind.PINNED, "active.target")
        return bitmap
    }

    private fun pageWindowHasImageDependency(window: EpubFlowPage): Boolean {
        val chapter = flow ?: return true
        val ranges = decodeLayoutRangesFor(listOf(window))
        return chapter.segments.any { segment ->
            segment.block is EpubDisplayBlock.Image &&
                ranges.any { range -> segment.layoutStart in range }
        }
    }

    private fun preCachePageTextures() {
        if (disposed) return
        if (rapidTurnSequenceActive) return
        clearRapidFollowUpPageShot()
        if (!pageTexturePrecacheEnabled) return
        if (pageShotSpeculationPaused || pageShotBudget.isSpeculativeAdmissionPaused) return
        if (mode != Mode.PAGED || paged.isEmpty() || width == 0) return
        if (turnInFlight) return
        // After a turn settles (or any idle re-entry), finish deferred in-place pixel redraws first
        // so warm owners carry latest async image pixels before the next gesture.
        if (pendingInPlacePageShotRefreshSlots.isNotEmpty()) {
            resumeDeferredInPlacePageShotRefresh()
            // Let the one-slot-per-frame drain own the frames; avoid racing a full allocate path.
            return
        }
        if (!pageClipActive || pagedMotionState == PagedMotionState.DRAGGING_FREE ||
            pagedMotionState == PagedMotionState.FLING_FREE || pagedMotionState == PagedMotionState.ALIGN_AND_TURN
        ) return
        if (initialRevealActive()) return
        if (pendingDecodesProvider?.invoke() == true) return
        if (textView.isLayoutRequested) return
        if (textView.layout?.height != paginatedLayoutHeight) return
        if (pageTexturePrecachePending) return
        val fromTop = scrollY
        val fromWindow = activePageWindow?.takeIf { it.topPx == fromTop }
        val canonicalIndex = canonicalPageIndexForWindow(fromWindow)
        val availableShots = stablePageShotCapacity()
        if (availableShots == 0) {
            recycleCachedTextures()
            return
        }
        val candidatePreviousWindow = (
            if (canonicalIndex >= 0) paged.getOrNull(canonicalIndex - 1) else pageWindowForTurn(false)
            ).takeUnless { boundaryPreviewBudgetDirection == true }
        val candidateTargetWindow = (
            if (canonicalIndex >= 0) paged.getOrNull(canonicalIndex + 1) else pageWindowForTurn(true)
            ).takeUnless { boundaryPreviewBudgetDirection == false }
        val targetWindow = candidateTargetWindow.takeIf { availableShots >= 2 }
        val previousWindow = candidatePreviousWindow.takeIf {
            availableShots >= 3 || (targetWindow == null && availableShots >= 2)
        }
        val idx = if (canonicalIndex >= 0) canonicalIndex else nearestCanonicalPageIndexForScrollY(fromTop)
        val previousIdx = previousWindow?.let { canonicalFloorPageIndexForTopPx(it.topPx) }
        val nextIdx = targetWindow?.let { canonicalFloorPageIndexForTopPx(it.topPx) }
        val previousTop = previousWindow?.topPx
        val targetTop = targetWindow?.topPx
        val fromKey = pageTextureKey(fromTop, fromWindow)
        val previousKey = previousWindow?.let { pageTextureKey(it.topPx, it) }
        val targetKey = targetWindow?.let { pageTextureKey(it.topPx, it) }
        val frontMatches =
            cachedFrontBitmap?.isRecycled == false &&
            idx == cachedFromPage &&
            fromTop == cachedFromTopPx &&
            fromKey == cachedFromTextureKey
        val targetMatches = targetWindow == null ||
            (
                cachedRevealedBitmap?.isRecycled == false &&
                    (nextIdx ?: -1) == cachedTargetPage &&
                    (targetTop ?: -1) == cachedTargetTopPx &&
                    targetKey == cachedTargetTextureKey
                )
        val previousMatches = previousWindow == null ||
            (
                cachedBackwardBitmap?.isRecycled == false &&
                    (previousIdx ?: -1) == cachedBackwardPage &&
                    (previousTop ?: -1) == cachedBackwardTopPx &&
                    previousKey == cachedBackwardTextureKey
                )
        if (frontMatches && targetMatches && previousMatches) {
            if (drainQueuedPageTurn()) onPageSettled?.invoke() else scheduleRapidTurnIdle()
            return
        }
        val retainedFront = cachedFrontBitmap?.takeIf { frontMatches }
        val retainedTarget = cachedRevealedBitmap?.takeIf { targetMatches && targetWindow != null }
        val retainedPrevious = cachedBackwardBitmap?.takeIf { previousMatches && previousWindow != null }
        clearCachedTextureOwnersKeeping(setOfNotNull(retainedFront, retainedTarget, retainedPrevious))
        val generation = pageTexturePrecacheGeneration
        val request = PendingPageTexturePrecache(
            generation = generation,
            fromTop = fromTop,
            fromWindow = fromWindow,
            previousWindow = previousWindow,
            targetWindow = targetWindow,
            fromPage = idx,
            previousPage = previousIdx ?: -1,
            targetPage = nextIdx ?: -1,
            fromTextureKey = fromKey,
            previousTextureKey = previousKey,
            targetTextureKey = targetKey,
            frontBitmap = retainedFront,
            previousBitmap = retainedPrevious,
            targetBitmap = retainedTarget,
            frontBitmapRetained = retainedFront != null,
            previousBitmapRetained = retainedPrevious != null,
            targetBitmapRetained = retainedTarget != null,
        )
        pendingPageTexturePrecache = request
        pageTexturePrecachePending = true
        continuePendingPageTexturePrecache(request)
    }

    private fun continuePendingPageTexturePrecache(request: PendingPageTexturePrecache) {
        if (pendingPageTexturePrecache !== request || request.generation != pageTexturePrecacheGeneration) return
        when {
            request.frontBitmap == null -> postPrecacheFrontShot(request)
            request.targetWindow != null && request.targetBitmap == null -> postPrecacheTargetShot(request)
            request.previousWindow != null && request.previousBitmap == null -> postPrecachePreviousShot(request)
            else -> commitPendingPageTexturePrecache(request)
        }
    }

    private fun pendingPageTexturePrecacheIsValid(request: PendingPageTexturePrecache): Boolean {
        if (
            disposed ||
            !pageTexturePrecacheEnabled ||
            pendingPageTexturePrecache !== request ||
            !pageTexturePrecachePending ||
            request.generation != pageTexturePrecacheGeneration ||
            mode != Mode.PAGED ||
            paged.isEmpty() ||
            turnInFlight ||
            !pageClipActive ||
            pagedMotionState == PagedMotionState.DRAGGING_FREE ||
            pagedMotionState == PagedMotionState.FLING_FREE ||
            pagedMotionState == PagedMotionState.ALIGN_AND_TURN ||
            initialRevealActive() ||
            pendingDecodesProvider?.invoke() == true ||
            textView.isLayoutRequested ||
            textView.layout?.height != paginatedLayoutHeight ||
            scrollY != request.fromTop ||
            activePageWindow?.takeIf { it.topPx == scrollY } != request.fromWindow ||
            pageTextureKey(request.fromTop, request.fromWindow) != request.fromTextureKey
        ) return false
        if (request.previousWindow != null) {
            if (pageWindowForTurn(false) != request.previousWindow) return false
            if (pageTextureKey(request.previousWindow.topPx, request.previousWindow) != request.previousTextureKey) {
                return false
            }
        }
        if (request.targetWindow != null) {
            if (pageWindowForTurn(true) != request.targetWindow) return false
            if (pageTextureKey(request.targetWindow.topPx, request.targetWindow) != request.targetTextureKey) {
                return false
            }
        }
        return true
    }

    private fun capturePrecacheFrontShot(request: PendingPageTexturePrecache) {
        if (pendingPageTexturePrecache !== request || request.generation != pageTexturePrecacheGeneration) return
        if (request.frontBitmap != null) {
            continuePendingPageTexturePrecache(request)
            return
        }
        if (!pendingPageTexturePrecacheIsValid(request)) {
            discardPendingPageTexturePrecache(request)
            return
        }
        val front = if (request.fromWindow != null) {
            snapshotPageAt(request.fromTop, request.fromWindow)
        } else {
            snapshotViewport(PageShotLeaseKind.EVICTABLE, "local.viewport")
        }
        if (front == null) {
            discardPendingPageTexturePrecache(request)
            return
        }
        if (!pendingPageTexturePrecacheIsValid(request)) {
            recyclePageShot(front)
            discardPendingPageTexturePrecache(request)
            return
        }
        request.frontBitmap = front
        continuePendingPageTexturePrecache(request)
    }

    private fun capturePrecacheTargetShot(request: PendingPageTexturePrecache) {
        if (pendingPageTexturePrecache !== request || request.generation != pageTexturePrecacheGeneration) return
        if (request.targetBitmap != null) {
            continuePendingPageTexturePrecache(request)
            return
        }
        val window = request.targetWindow ?: run {
            discardPendingPageTexturePrecache(request)
            return
        }
        if (!pendingPageTexturePrecacheIsValid(request)) {
            discardPendingPageTexturePrecache(request)
            return
        }
        val target = snapshotPageAt(window.topPx, window)
        if (target == null) {
            discardPendingPageTexturePrecache(request)
            return
        }
        if (!pendingPageTexturePrecacheIsValid(request)) {
            recyclePageShot(target)
            discardPendingPageTexturePrecache(request)
            return
        }
        request.targetBitmap = target
        continuePendingPageTexturePrecache(request)
    }

    private fun capturePrecachePreviousShot(request: PendingPageTexturePrecache) {
        if (pendingPageTexturePrecache !== request || request.generation != pageTexturePrecacheGeneration) return
        if (request.previousBitmap != null) {
            continuePendingPageTexturePrecache(request)
            return
        }
        val window = request.previousWindow ?: run {
            discardPendingPageTexturePrecache(request)
            return
        }
        if (!pendingPageTexturePrecacheIsValid(request)) {
            discardPendingPageTexturePrecache(request)
            return
        }
        val previous = snapshotPageAt(window.topPx, window)
        if (previous == null) {
            discardPendingPageTexturePrecache(request)
            return
        }
        if (!pendingPageTexturePrecacheIsValid(request)) {
            recyclePageShot(previous)
            discardPendingPageTexturePrecache(request)
            return
        }
        request.previousBitmap = previous
        continuePendingPageTexturePrecache(request)
    }

    private fun commitPendingPageTexturePrecache(request: PendingPageTexturePrecache) {
        if (!pendingPageTexturePrecacheIsValid(request)) {
            discardPendingPageTexturePrecache(request)
            return
        }
        val front = request.frontBitmap ?: run {
            discardPendingPageTexturePrecache(request)
            return
        }
        val target = request.targetBitmap
        val previous = request.previousBitmap
        if (
            (request.targetWindow != null && target == null) ||
            (request.previousWindow != null && previous == null)
        ) {
            discardPendingPageTexturePrecache(request)
            return
        }
        request.frontBitmap = null
        request.targetBitmap = null
        request.previousBitmap = null
        pendingPageTexturePrecache = null
        pageTexturePrecachePending = false
        clearPendingPageTexturePrecacheCallbacks()
        cachedFrontBitmap = front
        cachedRevealedBitmap = target
        cachedBackwardBitmap = previous
        cachedFromPage = request.fromPage
        cachedTargetPage = request.targetPage
        cachedBackwardPage = request.previousPage
        cachedFromTopPx = request.fromTop
        cachedTargetTopPx = request.targetWindow?.topPx ?: -1
        cachedBackwardTopPx = request.previousWindow?.topPx ?: -1
        cachedFromTextureKey = request.fromTextureKey
        cachedTargetTextureKey = request.targetTextureKey
        cachedBackwardTextureKey = request.previousTextureKey
        relabelPageShot(front, PageShotLeaseKind.EVICTABLE, "cache.current")
        relabelPageShot(target, PageShotLeaseKind.EVICTABLE, "cache.forward")
        relabelPageShot(previous, PageShotLeaseKind.EVICTABLE, "cache.backward")
        if (drainQueuedPageTurn()) onPageSettled?.invoke() else scheduleRapidTurnIdle()
    }

    private fun discardPendingPageTexturePrecache(request: PendingPageTexturePrecache) {
        if (pendingPageTexturePrecache === request) {
            pageTexturePrecacheGeneration += 1L
            pageTexturePrecachePending = false
            pendingPageTexturePrecache = null
            clearPendingPageTexturePrecacheCallbacks()
        }
        if (!request.frontBitmapRetained) request.frontBitmap?.let(::recyclePageShot)
        if (!request.targetBitmapRetained) request.targetBitmap?.let(::recyclePageShot)
        if (!request.previousBitmapRetained) request.previousBitmap?.let(::recyclePageShot)
        request.frontBitmap = null
        request.targetBitmap = null
        request.previousBitmap = null
    }

    private fun recycleCachedTextures() = recycleCachedTextures(preserveRapidFollowUp = false)

    private fun recycleCachedTextures(preserveRapidFollowUp: Boolean) {
        if (!preserveRapidFollowUp) clearRapidFollowUpPageShot()
        pageTexturePrecacheGeneration += 1L
        pageTexturePrecachePending = false
        clearPendingPageTexturePrecacheCallbacks()
        // Owners are gone; any staged in-place redraws are meaningless.
        cancelInPlacePageShotRefreshCallbacks()
        pendingInPlacePageShotRefreshSlots.clear()
        val request = pendingPageTexturePrecache
        listOfNotNull(
            request?.frontBitmap,
            request?.targetBitmap,
            request?.previousBitmap,
            cachedFrontBitmap,
            cachedRevealedBitmap,
            cachedBackwardBitmap,
        ).distinct().forEach(::recyclePageShot)
        request?.let {
            request.frontBitmap = null
            request.targetBitmap = null
            request.previousBitmap = null
        }
        pendingPageTexturePrecache = null
        cachedFrontBitmap = null
        cachedRevealedBitmap = null
        cachedBackwardBitmap = null
        cachedFromPage = -1
        cachedTargetPage = -1
        cachedBackwardPage = -1
        cachedFromTopPx = -1
        cachedTargetTopPx = -1
        cachedBackwardTopPx = -1
        cachedFromTextureKey = null
        cachedTargetTextureKey = null
        cachedBackwardTextureKey = null
    }

    private fun clearCachedTextureOwnersKeeping(retained: Set<Bitmap>) {
        pageTexturePrecacheGeneration += 1L
        pageTexturePrecachePending = false
        clearPendingPageTexturePrecacheCallbacks()
        pendingPageTexturePrecache = null
        listOfNotNull(cachedFrontBitmap, cachedRevealedBitmap, cachedBackwardBitmap)
            .distinct()
            .filterNot(retained::contains)
            .forEach(::recyclePageShot)
        if (cachedFrontBitmap !in retained) {
            cachedFrontBitmap = null
            cachedFromPage = -1
            cachedFromTopPx = -1
            cachedFromTextureKey = null
        }
        if (cachedRevealedBitmap !in retained) {
            cachedRevealedBitmap = null
            cachedTargetPage = -1
            cachedTargetTopPx = -1
            cachedTargetTextureKey = null
        }
        if (cachedBackwardBitmap !in retained) {
            cachedBackwardBitmap = null
            cachedBackwardPage = -1
            cachedBackwardTopPx = -1
            cachedBackwardTextureKey = null
        }
    }

    private fun detachCachedTextureOwner(bitmap: Bitmap) {
        if (cachedFrontBitmap === bitmap) {
            cachedFrontBitmap = null
            cachedFromPage = -1
            cachedFromTopPx = -1
            cachedFromTextureKey = null
        }
        if (cachedRevealedBitmap === bitmap) {
            cachedRevealedBitmap = null
            cachedTargetPage = -1
            cachedTargetTopPx = -1
            cachedTargetTextureKey = null
        }
        if (cachedBackwardBitmap === bitmap) {
            cachedBackwardBitmap = null
            cachedBackwardPage = -1
            cachedBackwardTopPx = -1
            cachedBackwardTextureKey = null
        }
    }

    private fun recycleCachedTexturesIfStaleForTurn(
        fromPage: Int,
        fromTop: Int,
        fromWindow: EpubFlowPage?,
        targetPage: Int,
        targetTop: Int,
        targetWindow: EpubFlowPage,
    ) {
        if (cachedFrontBitmap == null && cachedRevealedBitmap == null && cachedBackwardBitmap == null) return
        val fromKey = pageTextureKey(fromTop, fromWindow)
        val targetKey = pageTextureKey(targetTop, targetWindow)
        val cacheMatchesCurrent =
            fromPage == cachedFromPage &&
                fromTop == cachedFromTopPx &&
                (cachedFromTextureKey == null || cachedFromTextureKey == fromKey) &&
                cachedFrontBitmap?.isRecycled == false
        val cacheMatchesForward =
            targetPage == cachedTargetPage &&
                targetTop == cachedTargetTopPx &&
                (cachedTargetTextureKey == null || cachedTargetTextureKey == targetKey) &&
                cachedRevealedBitmap?.isRecycled == false
        val cacheMatchesBackward =
            targetPage == cachedBackwardPage &&
                targetTop == cachedBackwardTopPx &&
                (cachedBackwardTextureKey == null || cachedBackwardTextureKey == targetKey) &&
                cachedBackwardBitmap?.isRecycled == false
        if (!cacheMatchesCurrent || (!cacheMatchesForward && !cacheMatchesBackward)) recycleCachedTextures()
    }

    /**
     * Transfers a complete, anchor-matched pre-cache pair to an interactive turn. The crossing MOVE
     * must not copy these bitmaps: even a Bitmap.copy() allocates and walks two full-screen buffers on
     * the input thread, recreating the hitch the cache exists to avoid.
     */
    private fun takePendingPageTexturesForTurn(
        fromPage: Int,
        fromTop: Int,
        fromWindow: EpubFlowPage?,
        targetPage: Int,
        targetTop: Int,
        targetWindow: EpubFlowPage,
        dirtyOwners: List<Bitmap>,
    ): Pair<Bitmap, Bitmap>? {
        val request = pendingPageTexturePrecache ?: return null
        val fromKey = pageTextureKey(fromTop, fromWindow)
        val targetKey = pageTextureKey(targetTop, targetWindow)
        val front = request.frontBitmap?.takeUnless(Bitmap::isRecycled)
        val revealed = when {
            targetPage == request.targetPage &&
                targetTop == request.targetWindow?.topPx &&
                targetWindow == request.targetWindow &&
                targetKey == request.targetTextureKey ->
                request.targetBitmap?.takeUnless(Bitmap::isRecycled)
            targetPage == request.previousPage &&
                targetTop == request.previousWindow?.topPx &&
                targetWindow == request.previousWindow &&
                targetKey == request.previousTextureKey ->
                request.previousBitmap?.takeUnless(Bitmap::isRecycled)
            else -> null
        }
        val matchesCurrent =
            pendingPageTexturePrecacheIsValid(request) &&
                fromPage == request.fromPage &&
                fromTop == request.fromTop &&
                fromWindow == request.fromWindow &&
                fromKey == request.fromTextureKey
        if (matchesCurrent && front != null && revealed != null) {
            if (dirtyOwners.any { it === front || it === revealed }) {
                recycleCachedTextures()
                return null
            }
            detachCachedTextureOwner(front)
            detachCachedTextureOwner(revealed)
            request.frontBitmap = null
            if (revealed === request.targetBitmap) request.targetBitmap = null
            if (revealed === request.previousBitmap) request.previousBitmap = null
            discardPendingPageTexturePrecache(request)
            // The active turn owns exactly this pair. Drop any unused cached/pending owner now so
            // an unrelated dirty slot cannot survive after its refresh queue is cancelled.
            recycleCachedTextures()
            relabelPageShot(front, PageShotLeaseKind.PINNED, "active.front")
            relabelPageShot(revealed, PageShotLeaseKind.PINNED, "active.target")
            return front to revealed
        }
        // A partial or stale staged owner must leave before the input path allocates live fallback shots.
        // Otherwise a quick turn can transiently retain four full-screen ARGB bitmaps.
        discardPendingPageTexturePrecache(request)
        return null
    }

    /**
     * Deferred finger cold-shot path only: when the staged precache owns a live matching front but
     * still lacks the requested directional revealed page, detach that front so
     * [beginPendingLocalPageShotHandoff] can seed it without a second front recapture. A complete
     * requested pair is left untouched for [takeCachedTexturesForTurn]'s zero-copy transfer.
     */
    private fun takePartialPendingFrontForDeferredLocalHandoff(
        fromPage: Int,
        fromTop: Int,
        fromWindow: EpubFlowPage?,
        targetPage: Int,
        targetTop: Int,
        targetWindow: EpubFlowPage,
    ): Bitmap? {
        val request = pendingPageTexturePrecache ?: return null
        val fromKey = pageTextureKey(fromTop, fromWindow)
        val targetKey = pageTextureKey(targetTop, targetWindow)
        val matchesCurrent =
            pendingPageTexturePrecacheIsValid(request) &&
                fromPage == request.fromPage &&
                fromTop == request.fromTop &&
                fromWindow == request.fromWindow &&
                fromKey == request.fromTextureKey
        if (!matchesCurrent) return null
        val front = request.frontBitmap?.takeUnless(Bitmap::isRecycled) ?: return null
        val revealed = when {
            targetPage == request.targetPage &&
                targetTop == request.targetWindow?.topPx &&
                targetWindow == request.targetWindow &&
                targetKey == request.targetTextureKey ->
                request.targetBitmap?.takeUnless(Bitmap::isRecycled)
            targetPage == request.previousPage &&
                targetTop == request.previousWindow?.topPx &&
                targetWindow == request.previousWindow &&
                targetKey == request.previousTextureKey ->
                request.previousBitmap?.takeUnless(Bitmap::isRecycled)
            else -> null
        }
        // Complete pair: leave request for takeCachedTexturesForTurn / takePendingPageTexturesForTurn.
        if (revealed != null) return null
        val dirtyOwners = pendingInPlacePageShotRefreshSlots.mapNotNull(::cachedBitmapForSlot)
        if (dirtyOwners.any { it === front }) {
            recycleCachedTextures()
            return null
        }
        // Front-only extract: detach before discard so existing cleanup cannot recycle it.
        detachCachedTextureOwner(front)
        request.frontBitmap = null
        discardPendingPageTexturePrecache(request)
        relabelPageShot(front, PageShotLeaseKind.PINNED, "active.front")
        return front
    }

    private fun takeCachedTexturesForTurn(
        fromPage: Int,
        fromTop: Int,
        fromWindow: EpubFlowPage?,
        targetPage: Int,
        targetTop: Int,
        targetWindow: EpubFlowPage,
    ): Pair<Bitmap, Bitmap>? {
        val pendingPixelRefreshOwners = pendingInPlacePageShotRefreshSlots
            .mapNotNull(::cachedBitmapForSlot)
        // Defer remaining in-place redraw frames; keep warm owners for transfer.
        cancelInPlacePageShotRefreshCallbacks()
        takePendingPageTexturesForTurn(
            fromPage,
            fromTop,
            fromWindow,
            targetPage,
            targetTop,
            targetWindow,
            pendingPixelRefreshOwners,
        )?.let { pair ->
            rememberActiveFlipPixelRefreshes(pair.first, pair.second, pendingPixelRefreshOwners)
            return pair
        }
        recycleCachedTexturesIfStaleForTurn(
            fromPage,
            fromTop,
            fromWindow,
            targetPage,
            targetTop,
            targetWindow,
        )
        val front = cachedFrontBitmap?.takeUnless(Bitmap::isRecycled) ?: return null
        val revealed = when {
            targetPage == cachedTargetPage && targetTop == cachedTargetTopPx ->
                cachedRevealedBitmap?.takeUnless(Bitmap::isRecycled)
            targetPage == cachedBackwardPage && targetTop == cachedBackwardTopPx ->
                cachedBackwardBitmap?.takeUnless(Bitmap::isRecycled)
            else -> null
        } ?: return null
        if (pendingPixelRefreshOwners.any { it === front || it === revealed }) {
            // A page shot whose image pixels are waiting for refresh is not a valid animation frame.
            // Finger turns fall through to split-frame fresh capture; taps/keys snapshot synchronously.
            recycleCachedTextures()
            return null
        }
        cachedFrontBitmap = null
        if (revealed === cachedRevealedBitmap) cachedRevealedBitmap = null
        if (revealed === cachedBackwardBitmap) cachedBackwardBitmap = null
        cachedRevealedBitmap?.let(::recyclePageShot)
        cachedBackwardBitmap?.let(::recyclePageShot)
        cachedRevealedBitmap = null
        cachedBackwardBitmap = null
        cachedFromPage = -1
        cachedTargetPage = -1
        cachedBackwardPage = -1
        cachedFromTopPx = -1
        cachedTargetTopPx = -1
        cachedBackwardTopPx = -1
        cachedFromTextureKey = null
        cachedTargetTextureKey = null
        cachedBackwardTextureKey = null
        rememberActiveFlipPixelRefreshes(front, revealed, pendingPixelRefreshOwners)
        // Only clean owners may enter an active turn. Any queued unrelated slot was recycled above.
        pendingInPlacePageShotRefreshSlots.clear()
        relabelPageShot(front, PageShotLeaseKind.PINNED, "active.front")
        relabelPageShot(revealed, PageShotLeaseKind.PINNED, "active.target")
        return front to revealed
    }

    /**
     * Records that transferred warm owners need a post-settle in-place pixel refresh. Must not
     * schedule [redrawPageShotInto] or [container.draw] while the finger/overlay still owns the turn.
     */
    private fun rememberActiveFlipPixelRefreshes(
        front: Bitmap,
        revealed: Bitmap,
        dirtyOwners: List<Bitmap>,
    ) {
        activeFlipFrontPixelRefreshPending = dirtyOwners.any { it === front }
        activeFlipRevealedPixelRefreshPending = dirtyOwners.any { it === revealed }
    }

    private fun clearActiveFlipPixelRefreshes() {
        activeFlipFrontPixelRefreshPending = false
        activeFlipRevealedPixelRefreshPending = false
    }

    /** Transfers only the warmed current page for a cross-chapter turn; target comes from its preview. */
    private fun takeCachedFrontForBoundaryTurn(
        fromPage: Int,
        fromTop: Int,
        fromWindow: EpubFlowPage?,
    ): Bitmap? {
        val dirtyOwners = pendingInPlacePageShotRefreshSlots.mapNotNull(::cachedBitmapForSlot)
        // Invalidate retained aliases before the boundary cover/overlay becomes the sole owner.
        pendingPageTexturePrecache?.let(::discardPendingPageTexturePrecache)
        // Boundary transfer may steal the front owner; never let a queued in-place redraw target it.
        cancelInPlacePageShotRefreshCallbacks()
        val front = cachedFrontBitmap?.takeUnless(Bitmap::isRecycled)
        val fromKey = pageTextureKey(fromTop, fromWindow)
        if (
            front == null || fromPage != cachedFromPage || fromTop != cachedFromTopPx ||
            (cachedFromTextureKey != null && cachedFromTextureKey != fromKey)
        ) {
            recycleCachedTextures()
            return null
        }
        if (dirtyOwners.any { it === front }) {
            recycleCachedTextures()
            return null
        }
        cachedFrontBitmap = null
        cachedRevealedBitmap?.let(::recyclePageShot)
        cachedBackwardBitmap?.let(::recyclePageShot)
        cachedRevealedBitmap = null
        cachedBackwardBitmap = null
        cachedFromPage = -1
        cachedTargetPage = -1
        cachedBackwardPage = -1
        cachedFromTopPx = -1
        cachedTargetTopPx = -1
        cachedBackwardTopPx = -1
        cachedFromTextureKey = null
        cachedTargetTextureKey = null
        cachedBackwardTextureKey = null
        pendingInPlacePageShotRefreshSlots.clear()
        relabelPageShot(front, PageShotLeaseKind.PINNED, "active.front")
        return front
    }

    // ---- Finger-tracking (跟手) software page turn ---------------------------------------------
    // Horizontal and side-column vertical drags drive software turn progress directly from finger
    // displacement. Release settles by position+axis velocity; SIMULATION uses PageCurlDrawable while
    // SLIDE uses PageSlideDrawable. Both remain in the reader's View hierarchy.
    /** The page we slide FROM (restore target on cancel); the incoming page is already parked beneath. */
    private var curlFromPage = 0
    private var curlOrigin: PageTurnOrigin? = null
    private var curlTargetWindow: EpubFlowPage? = null
    private var curlForward = true
    /** Finger coordinate where the software turn began; interpreted using [curlAxis]. */
    private var curlAnchor = 0f
    private var curlAxis = InteractiveTurnAxis.HORIZONTAL
    private var velocityTracker: VelocityTracker? = null
    private var lastVelocityEventTime = Long.MIN_VALUE
    private var lastVelocityAction = -1
    private var lastVelocityX = Float.NaN
    private var lastVelocityY = Float.NaN
    // Keep the fling gate density-independent. The distance projection below admits a deliberate
    // millimetre-scale flick while rejecting an equally short, slow tap drift.
    private val flipFlingThresholdPxPerSec =
        maxOf(minimumFlingVelocity.toFloat(), MICRO_TURN_MIN_VELOCITY_DP_PER_SEC * density)

    /**
     * When true (PAGED, parked on a page), drawing is clipped to the current page's bottom so the
     * gap below its last complete line shows the theme background — never the next page's content
     * (Moon+ Reader 的「丢弃半行、底部留白」). Disabled during a centre-column free scroll (Moon+'s
     * temporary-scroll is continuous), then re-enabled on the next page turn.
     */
    private var pageClipActive = true

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Long-press anywhere = native text selection. We stop owning the stream so the child
            // TextView gets the real held DOWN→MOVE→UP it needs for selection + drag handles.
            inSelectionMode = true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Only a clean tap (no drag, no long-press) turns a page / toggles chrome. Defer the
            // action until dispatch finishes so child ClickableSpan taps can report that they consumed
            // the same UP first.
            if (!classified && !inSelectionMode) pendingCleanTapX = e.x
            return false
        }
    })

    init {
        isSmoothScrollingEnabled = false
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addView(container)
        textView.onSelectionRangeChanged = { s, e -> onSelectionRange(s, e) }
        // A chapter's images load async with no placeholder height (审计: zero-height until decoded), so
        // each one that arrives reflows the StaticLayout and shifts every following line down. Our page
        // windows + resting scrollY are then stale → the parked page lands mid-line (half-line/half-image
        // at the edges). Re-paginate + re-anchor by content offset whenever the layout height changes, so
        // the parked page snaps back to a line top (mirrors [onSizeChanged] for the view-height case).
        //
        // Images decode in a burst (the scheduler attaches all visible ones at once), so a per-event
        // repaginate fires 3–4 times in a few frames — each a paginate + scrollTo, the visible flicker.
        // Coalesce the burst: every layout change reschedules ONE debounced pass that runs after the
        // decodes settle, doing a single paginate + single re-anchor.
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (disposed) return@addOnLayoutChangeListener
            val layout = textView.layout ?: return@addOnLayoutChangeListener
            if (flow == null || layout.height <= 0) return@addOnLayoutChangeListener
            // Only react to a genuine content reflow (height delta from a decoded image), not our own
            // re-layout after repaginate (which records the new height) or a no-op pass.
            if (layout.height == paginatedLayoutHeight) return@addOnLayoutChangeListener
            cancelPendingLocalPageShotHandoff(consumeGesture = true)
            pageLayoutMetadata = null
            if (awaitingStableChapter) removeCallbacks(revealSafetyRunnable)
            removeCallbacks(reflowRunnable)
            postDelayed(reflowRunnable, REFLOW_DEBOUNCE_MS)
            // Re-check stability after a layout change — an async image may have just filled in.
            if (awaitingStableChapter) tryRevealWhenStable()
        }
    }

    override fun setBackground(background: Drawable?) {
        if (viewportBackground !== background) {
            abortLocalPageShotTurnForExternalMutation(restoreOrigin = true)
            recycleCachedTextures()
        }
        viewportBackground = background
        super.setBackground(null)
        invalidate()
    }

    override fun getBackground(): Drawable? = viewportBackground

    override fun draw(canvas: Canvas) {
        drawLiveViewportBackground(canvas)
        super.draw(canvas)
        settledWindowAtTop(scrollY)?.takeIf {
            mode == Mode.PAGED && pageClipActive && !fullViewportOverlayActive
        }
            // A parent draws this scrolled View with a -scrollY canvas transform. Keep the preview
            // in content coordinates so it lands in the visible viewport after that transform.
            ?.let { drawPageBoundaryImagePreview(canvas, scrollY, it, canvasViewportTopPx = scrollY) }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != oldt && interactiveTurnState == InteractiveTurnState.SOFTWARE) {
            abortLocalPageShotTurnForExternalMutation()
        }
        if (t != oldt && localShotsWaiting) cancelPendingLocalPageShotHandoff(consumeGesture = true)
        if (t != oldt && conversionSnapshotDrawable != null) positionConversionSnapshot()
        if (t == oldt || mode != Mode.PAGED || paged.isEmpty()) return
        if (pagedMotionState == PagedMotionState.ALIGNED && activePageWindow?.topPx != t) {
            activePageWindow = null
            pagedMotionState = PagedMotionState.FREE_REST
            recycleCachedTextures()
        }
    }

    override fun computeScroll() {
        val before = scrollY
        super.computeScroll()
        if (pagedMotionState != PagedMotionState.FLING_FREE) return
        freeFlingStableFrames = if (scrollY == before) freeFlingStableFrames + 1 else 0
        val oldEnough = android.os.SystemClock.uptimeMillis() - freeFlingStartedAtMs >= FREE_FLING_MIN_SETTLE_MS
        if (oldEnough && freeFlingStableFrames >= FREE_FLING_STABLE_FRAMES) {
            finishTemporaryScrollRest()
        } else {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Single coalesced reaction to an async-image reflow: re-paginate against the grown layout and
     * re-anchor the parked page to the same content line. Deferred while any local page-shot animation
     * or finger turn is in flight, so a mid-turn repaginate cannot rebuild the page array or tear the
     * visible overlay. It re-arms itself for after the turn ends.
     */
    private val reflowRunnable: Runnable = Runnable {
        if (disposed) return@Runnable
        val layout = textView.layout ?: return@Runnable
        if (flow == null || layout.height <= 0) return@Runnable
        if (layout.height == paginatedLayoutHeight) return@Runnable
        if (turnInFlight) {
            postDelayed(reflowRunnable, REFLOW_DEBOUNCE_MS)
            return@Runnable
        }
        if (initialRevealActive()) {
            removeCallbacks(revealSafetyRunnable)
            container.animate().cancel()
            awaitingReveal = true
            awaitingStableChapter = true
            container.alpha = 0f
            resetConversionSnapshotFade()
        }
        val anchorOffset = if (awaitingReveal) {
            pendingRestoreOffset ?: if (paged.isNotEmpty()) topLayoutOffset() else -1
        } else {
            if (paged.isNotEmpty()) topLayoutOffset() else -1
        }
        recycleCachedTextures()
        repaginate(reposition = false, preserveQueuedTurns = true)
        if (anchorOffset >= 0) {
            goToOffset(
                anchorOffset,
                report = !reportPositionAfterStableReveal,
                forceReport = !reportPositionAfterStableReveal,
            )
        }
        if (awaitingStableChapter) {
            positionConversionSnapshot()
            tryRevealWhenStable()
        } else {
            preCachePageTextures()
        }
    }

    /**
     * The selectable child [TextView] keeps a selection cursor and, on every pre-draw, asks us (its
     * scroll parent) to bring that point on-screen — which would smooth-scroll [scrollY] off the page
     * top, bleeding the next page's lines in at the bottom and showing a visible scroll (审计 H4/H5:
     * touch + scrolling are owned end-to-end). We position the page ourselves, so refuse all auto-scroll.
     */
    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: android.graphics.Rect,
        immediate: Boolean,
    ): Boolean = false

    /**
     * Clip content rendering to the current page's bottom boundary. The ScrollView background (the
     * theme page colour, painted in [View.draw] before this runs) fills the strip below, so the
     * remainder of a short page is blank — the next page's lines/images never bleed in. The flip
     * overlay (a [Drawable] on the view overlay) draws after dispatchDraw, so it is unaffected.
     */
    override fun dispatchDraw(canvas: Canvas) {
        val clipBottom = pageClipBottomInViewport()
        val topClip = pageClipTopInViewport()
        // The page-turn overlay owns the complete viewport and draws cached page shots. Avoid
        // repainting the parked TextView (including all image spans) underneath it on every finger
        // MOVE; EpubFlowContainer keeps the parent ViewGroup overlay in the draw pass.
        container.skipContentDraw = pageShotOverlayActive
        try {
            if (clipBottom == null && topClip <= scrollY) {
                super.dispatchDraw(canvas)
                return
            }
            val save = canvas.save()
            // pageClip* helpers return content-space Y (layout coords: page top = line top, +padTop for
            // painted ink). Android's parent draw path and snapshotViewport both pre-translate the canvas
            // by -scrollY before dispatchDraw, so clipRect must stay in content coordinates. Subtracting
            // scrollY here would apply the scroll twice and truncate/blank later pages.
            //
            // The paginator works in pure StaticLayout coords (line 0 at y=0), but the child TextView paints
            // its layout shifted DOWN by its own [TextView.paddingTop] — a line at layout-y L lands at
            // content-y L + padTop. [pageClipBottomInViewport] returns the bottom in layout space, so without
            // the offset the clip falls padTop px too high and slices ~padTop off the last line's painted
            // glyphs (审计: 底部半截文字). Add padTop so the clip meets the line's PAINTED bottom; cap at the
            // viewport bottom (a near-full page then relies on the viewport edge, off by ≤padTop, invisible).
            val bottom = if (clipBottom != null) {
                minOf(scrollY + clipBottom + textView.paddingTop, scrollY + height)
            } else {
                scrollY + height
            }
            canvas.clipRect(0, topClip, width, bottom)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        } finally {
            container.skipContentDraw = false
        }
    }

    /**
     * Content-y at which to START drawing this page (>= [scrollY]). The paginator works in pure
     * StaticLayout coords (line 0 at y=0) and page tops are line tops in that space, but the child
     * TextView paints its layout shifted DOWN by its own [TextView.paddingTop]: a line at layout-y L
     * lands at canvas-y L + padTop. When parked on a page, [scrollY] equals the page's first line top
     * in LAYOUT space, so that line actually PAINTS at scrollY + padTop — and the strip [scrollY,
     * scrollY+padTop] is occupied entirely by the PREVIOUS line's box (its layout bottom == this page's
     * line top, painted +padTop down), i.e. the half-line bleed (审计: 半截的文字; the earlier fraction
     * guard dropped only ~12px, far short of padTop). Clip the top at scrollY + padTop so the previous
     * line is fully removed and this page's first line sits flush below the clip with a padTop top margin
     * — the exact mirror of [pageClipBottomInViewport]'s +padTop, one consistent content-space boundary.
     */
    private fun pageClipTopInViewport(): Int {
        if (mode != Mode.PAGED || !pageClipActive || paged.isEmpty()) return scrollY
        val layout = textView.layout ?: return scrollY
        activePageWindow?.takeIf { it.topPx == scrollY }?.let {
            return (scrollY + textView.paddingTop).coerceAtMost(scrollY + height)
        }
        val viewportTopInLayout = (scrollY - textView.paddingTop).coerceAtLeast(0)
        var line = layout.getLineForVertical(viewportTopInLayout)
        if (layout.getLineTop(line) < viewportTopInLayout) {
            if (keepsPartialViewportSlice(layout, line)) return scrollY
            line++
        }
        if (line >= layout.lineCount) return scrollY + height
        return (layout.getLineTop(line) + textView.paddingTop).coerceAtMost(scrollY + height)
    }

    /**
     * Pixel height (within the viewport) at which the current page ends, or null when no clip should
     * apply (SCROLL mode, free-scrolling, no pages, or the page already reaches the viewport bottom).
     *
     * Computed DYNAMICALLY from the live [scrollY] (Moon+ Reader's `getLastDisplayLine`): the bottom is
     * the bottom of the last line that FULLY fits in the viewport — never a page-array boundary. So even
     * after a middle-column free scroll parks scrollY mid-grid, the clip still lands on a clean line
     * boundary and the next line's top never bleeds in (审计: 滚动转分页的半截文字). When parked on a
     * page top this resolves to exactly that page's last line (pages were paginated by the same rule).
     */
    private fun pageClipBottomInViewport(): Int? {
        if (mode != Mode.PAGED || !pageClipActive || paged.isEmpty()) return null
        val layout = textView.layout ?: return null
        activePageWindow?.takeIf { it.topPx == scrollY }?.let { window ->
            val clip = window.bottomPx - scrollY
            if (window.startLine + 1 == window.endLineExclusive && clip > usablePageHeightPx()) return null
            return clip.coerceAtLeast(0)
        }
        val pageBottom = scrollY + usablePageHeightPx()
        // The current page's first line (scrollY sits on its top when parked; mid-line when free-scrolled).
        // Back-off must never cross above it — an oversized line (a full-page image taller than the
        // viewport) IS the whole page and must keep its one line, mirroring the paginator's oversized-line
        // guard (审计: 满页图被 clip 退到上一页 → 整屏裁空 → 图闪一下后消失).
        val viewportTopInLayout = (scrollY - textView.paddingTop).coerceAtLeast(0)
        var firstLine = layout.getLineForVertical(viewportTopInLayout)
        if (
            layout.getLineTop(firstLine) < viewportTopInLayout &&
            !keepsPartialViewportSlice(layout, firstLine)
        ) firstLine++
        if (firstLine >= layout.lineCount) return 0
        var line = layout.getLineForVertical(pageBottom - 1)
        // Step back off a line whose bottom spills past the viewport — that partial line belongs to the
        // next page. Leaves a blank gap at the bottom rather than a half-line (Moon+ accepts the gap).
        if (
            line > firstLine &&
            layout.getLineBottom(line) > pageBottom &&
            !keepsPartialViewportSlice(layout, line)
        ) line--
        val clip = layout.getLineBottom(line) - scrollY
        // A single line taller than the usable band is an indivisible image/block: keep its viewport
        // slice instead of clipping the whole page away. Ordinary full pages still need the clip so the
        // next line cannot paint into the reserved bottom padding.
        if (line == firstLine && clip > usablePageHeightPx()) return null
        return clip.coerceAtLeast(0)
    }

    /**
     * The real viewport height is only known after measurement — and it differs from the engine's
     * screen-derived estimate (the reader avoids the system bars). [setChapter] posts a [repaginate],
     * but on a cold open that can run before this view is sized (height == 0 → 2464 fallback → pages
     * too tall → clipped last line). Re-paginate whenever the true height arrives or changes (also
     * covers rotation), re-anchoring to the same content line so the resume position stays put.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if ((w == oldw && h == oldh) || w <= 0 || h <= 0 || flow == null) return
        pageLayoutMetadata = null
        cancelFreeFlingForLifecycle()
        abortLocalPageShotTurnForExternalMutation()
        onBoundaryPreviewConfigurationChanged?.invoke()
        clearBoundaryPreviews()
        recycleCachedTextures()
        val expectedChapterGeneration = chapterGeneration
        // Cold open: settleInitialPosition ran at height 0 (fallback) and deferred the reveal. Re-anchor
        // to the EXACT pending resume target (more accurate than the fallback-paginated top line), then
        // reveal — so the content fades in already at the resume page, with no visible scroll.
        if (awaitingReveal) {
            postAfterViewportImageBoundsRefresh(expectedChapterGeneration) resize@{
                repaginate(reposition = false)
                if (pendingLandOnLast) {
                    scrollToPage(paged.lastIndex.coerceAtLeast(0), report = !reportPositionAfterStableReveal)
                } else {
                    goToOffset(
                        pendingRestoreOffset ?: 0,
                        report = !reportPositionAfterStableReveal,
                        forceReport = !reportPositionAfterStableReveal,
                    )
                }
                if (consumePendingInitialPageTurn()) return@resize
                tryRevealWhenStable()
                preCachePageTextures()
            }
            return
        }
        val anchorOffset = if (paged.isNotEmpty()) topLayoutOffset() else -1
        postAfterViewportImageBoundsRefresh(expectedChapterGeneration) {
            // Re-anchor by offset does the only scroll; skip repaginate's own scrollTo (no double jump).
            repaginate(reposition = anchorOffset < 0)
            if (anchorOffset >= 0) {
                goToOffset(
                    anchorOffset,
                    report = !reportPositionAfterStableReveal,
                    forceReport = !reportPositionAfterStableReveal,
                )
            }
            if (awaitingStableChapter) {
                tryRevealWhenStable()
            } else {
                if (isLayoutSettled()) {
                    publishStableChapterPosition()
                    notifyChapterStable()
                }
                preCachePageTextures()
            }
        }
    }

    private fun postAfterViewportImageBoundsRefresh(
        expectedChapterGeneration: Long,
        action: () -> Unit,
    ) {
        textView.post {
            if (disposed || chapterGeneration != expectedChapterGeneration) return@post
            val boundsChanged = refreshFullPageImageBoundsForViewport(expectedChapterGeneration)
            if (disposed || chapterGeneration != expectedChapterGeneration) return@post
            if (!boundsChanged) {
                action()
                return@post
            }
            if (chapterGeneration != expectedChapterGeneration) return@post
            textView.text = textView.text
            textView.requestLayout()
            textView.post {
                if (!disposed && chapterGeneration == expectedChapterGeneration) action()
            }
        }
    }

    private fun refreshFullPageImageBoundsForViewport(expectedChapterGeneration: Long): Boolean {
        if (disposed || chapterGeneration != expectedChapterGeneration) return false
        val text = textView.text as? Spanned ?: return false
        var changed = false
        text.getSpans(0, text.length, AsyncDrawableSpan::class.java).forEach { span ->
            val drawable = span.drawable
            val resolver = drawable.imageSizeResolver as? EpubFlowImageSizeResolver ?: return@forEach
            if (!resolver.isFullPage(drawable.destination)) return@forEach
            val result = drawable.result ?: return@forEach
            val target = resolver.resolveImageSize(drawable)
            if (target == drawable.bounds) return@forEach
            result.bounds = target
            drawable.bounds = target
            changed = true
        }
        return changed
    }

    /**
     * The usable height (px) for a full-page image so its laid-out line fits within ONE page. Derived
     * from the MEASURED viewport (not the engine's screen estimate, which is ~100px taller because it
     * ignores the system bars the reader avoids — a screen-sized cover overflowed onto a blank 2nd
     * page). Drops the TextView's own vertical padding (the image line is inset by it on the first/last
     * page of a chapter) so the image never spills past the viewport. 0 before the first measure pass.
     */
    fun usablePageImageHeightPx(): Int {
        if (height <= 0) return 0
        return (height - textView.paddingTop - textView.paddingBottom).coerceAtLeast(1)
    }

    fun refreshAfterAsyncImageResult() {
        // Geometry changes (or an unknown occurrence) invalidate pagination and every cached page shot.
        // Same-geometry pixel installs use the incremental path below and preserve warm identities.
        if (turnInFlight) {
            asyncImageRefreshPending = true
            removeCallbacks(asyncImageRefreshRunnable)
            postDelayed(asyncImageRefreshRunnable, REFLOW_DEBOUNCE_MS)
            return
        }
        removeCallbacks(asyncImageRefreshRunnable)
        asyncImageRefreshPending = false
        asyncImagePixelRefreshOffsets.clear()
        asyncImagePixelTextRebindPending = false
        asyncImageBatchWaitStartedAtMs = 0L
        applyAsyncImageResultRefresh()
    }

    fun onAsyncImagePixelsChanged(layoutOffset: Int) {
        onAsyncImagePixelsChanged(layoutOffset, rebindText = false)
    }

    fun onAsyncImagePixelsChangedRequiringTextRebind(layoutOffset: Int) {
        onAsyncImagePixelsChanged(layoutOffset, rebindText = true)
    }

    private fun onAsyncImagePixelsChanged(layoutOffset: Int, rebindText: Boolean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            post { onAsyncImagePixelsChanged(layoutOffset, rebindText) }
            return
        }
        if (turnInFlight || rebindText || asyncImagePixelTextRebindPending) {
            asyncImagePixelRefreshOffsets += layoutOffset
            asyncImagePixelTextRebindPending = asyncImagePixelTextRebindPending || rebindText
            removeCallbacks(asyncImageRefreshRunnable)
            if (turnInFlight) {
                postDelayed(asyncImageRefreshRunnable, REFLOW_DEBOUNCE_MS)
            } else {
                postOnAnimation(asyncImageRefreshRunnable)
            }
            return
        }
        applyAsyncImagePixelRefresh(listOf(layoutOffset))
    }

    private fun applyAsyncImagePixelRefresh(
        layoutOffsets: Collection<Int>,
        rebindText: Boolean = false,
    ) {
        if (layoutOffsets.isEmpty()) return
        if (rebindText) {
            // A full-page AsyncDrawable needs a TextView display-list rebuild even when its reserved
            // bounds are unchanged. Keep pagination and warm page-shot identities intact; dependent
            // shots are repainted in place below after this same-geometry rebind settles.
            textView.text = textView.text
        }
        // Far-page PIXELS_ONLY must not touch the live viewport's warm shots or restart nearby
        // precache. Boundary-preview pages (preceding text that synthetically crops a next-page
        // image) count as nearby even when the image layout offset sits past the page endOffset.
        val nearbyOffsets = layoutOffsets.filter { pageShotDependsOnImageOffset(it) }
        if (nearbyOffsets.isEmpty()) {
            return
        }
        val pendingPrecache = pendingPageTexturePrecache
        if (
            pendingPrecache != null &&
            nearbyOffsets.any { offset ->
                pageWindowDependsOnImageOffset(pendingPrecache.fromWindow, offset) ||
                    pageWindowDependsOnImageOffset(pendingPrecache.previousWindow, offset) ||
                    pageWindowDependsOnImageOffset(pendingPrecache.targetWindow, offset)
            }
        ) {
            // Drop only mid-flight speculative captures. Retained warm owners stay on cached* slots.
            discardPendingPageTexturePrecache(pendingPrecache)
        }
        // Preserve leased warm bitmap identities. Recycle/realloc creates a cold gap where a finger
        // turn falls into beginPendingLocalPageShotHandoff and allocates two full-screen ARGB shots.
        // Refresh dependent slots in place, one slot per animation frame, coalescing offsets.
        var scheduledAny = false
        nearbyOffsets.forEach { offset ->
            if (pageShotSlotDependsOnImageOffset(cachedFromPage, offset)) {
                if (cachedFrontBitmap?.isRecycled == false) {
                    pendingInPlacePageShotRefreshSlots += CachedPageShotSlot.FRONT
                    scheduledAny = true
                }
            }
            if (pageShotSlotDependsOnImageOffset(cachedTargetPage, offset)) {
                if (cachedRevealedBitmap?.isRecycled == false) {
                    pendingInPlacePageShotRefreshSlots += CachedPageShotSlot.REVEALED
                    scheduledAny = true
                }
            }
            if (pageShotSlotDependsOnImageOffset(cachedBackwardPage, offset)) {
                if (cachedBackwardBitmap?.isRecycled == false) {
                    pendingInPlacePageShotRefreshSlots += CachedPageShotSlot.BACKWARD
                    scheduledAny = true
                }
            }
        }
        textView.invalidate()
        container.invalidate()
        invalidate()
        if (scheduledAny) {
            scheduleInPlacePageShotRefresh()
        } else {
            // No warm owners to repaint in place (cold cache, or only a discarded mid-flight
            // precache). Wake normal nearby precache / chapter reveal after the next pre-draw so
            // the next gesture is not permanently cold.
            onAsyncImageDecodeFinished()
        }
        // Do not call onAsyncImageDecodeFinished for the warm in-place path: preCache key-match
        // would early-return without redrawing pixels, and a recycle rebuild is the jank gap.
    }

    private fun scheduleInPlacePageShotRefresh() {
        if (disposed || inPlacePageShotRefreshPosted || pendingInPlacePageShotRefreshSlots.isEmpty()) return
        if (turnInFlight) return
        inPlacePageShotRefreshPosted = true
        postOnAnimation(inPlacePageShotRefreshRunnable)
    }

    private fun cancelInPlacePageShotRefreshCallbacks() {
        removeCallbacks(inPlacePageShotRefreshRunnable)
        inPlacePageShotRefreshPosted = false
    }

    private fun resumeDeferredInPlacePageShotRefresh() {
        if (disposed || turnInFlight || pendingInPlacePageShotRefreshSlots.isEmpty()) return
        scheduleInPlacePageShotRefresh()
    }

    private enum class InPlaceRefreshOutcome {
        /** Slot pixels redrawn into the existing warm owner. */
        REDRAWN,
        /** Slot identity/key/window no longer valid; drop and let normal precache rebuild. */
        DROPPED_STALE,
        /** Transient layout/size gate; keep queued and try again next frame. */
        DEFERRED_TRANSIENT,
    }

    /** Drains at most one dependent warm slot per animation frame while idle/aligned. */
    private fun drainOneInPlacePageShotRefresh() {
        inPlacePageShotRefreshPosted = false
        if (disposed) {
            pendingInPlacePageShotRefreshSlots.clear()
            return
        }
        if (turnInFlight) {
            // Finger owns the turn: keep warm owners, defer remaining slot redraws until settle.
            return
        }
        val slot = pendingInPlacePageShotRefreshSlots.firstOrNull() ?: return
        when (refreshCachedPageShotInPlace(slot)) {
            InPlaceRefreshOutcome.REDRAWN,
            InPlaceRefreshOutcome.DROPPED_STALE,
            -> pendingInPlacePageShotRefreshSlots.remove(slot)
            InPlaceRefreshOutcome.DEFERRED_TRANSIENT -> {
                // Keep the slot at the head of the set; retry next animation frame.
                scheduleInPlacePageShotRefresh()
                return
            }
        }
        if (pendingInPlacePageShotRefreshSlots.isNotEmpty()) {
            scheduleInPlacePageShotRefresh()
        } else {
            // A queued rapid target may now consume this refreshed artifact. Without queued input,
            // the normal current/adjacent precache path still runs through the same dispatcher.
            continueQueuedTurnsOrPrecache()
        }
    }

    /**
     * Attempts one in-place redraw of an existing warm page-shot. Returns whether the slot was
     * painted, dropped as stale, or deferred for a transient layout/size reason. Never removes the
     * warm bitmap on a transient skip — the queue holds the slot until a later frame succeeds.
     */
    private fun refreshCachedPageShotInPlace(slot: CachedPageShotSlot): InPlaceRefreshOutcome {
        val bitmap = cachedBitmapForSlot(slot)?.takeUnless(Bitmap::isRecycled)
            ?: return InPlaceRefreshOutcome.DROPPED_STALE
        val pageIndex = cachedPageForSlot(slot)
        val topPx = cachedTopForSlot(slot)
        val textureKey = cachedTextureKeyForSlot(slot)
        if (pageIndex < 0 || topPx < 0) return InPlaceRefreshOutcome.DROPPED_STALE
        // Identity must still be the same leased owner in the same slot.
        if (cachedBitmapForSlot(slot) !== bitmap) return InPlaceRefreshOutcome.DROPPED_STALE
        if (cachedPageForSlot(slot) != pageIndex) return InPlaceRefreshOutcome.DROPPED_STALE
        if (cachedTopForSlot(slot) != topPx) return InPlaceRefreshOutcome.DROPPED_STALE
        if (cachedTextureKeyForSlot(slot) != textureKey) return InPlaceRefreshOutcome.DROPPED_STALE
        if (mode != Mode.PAGED) return InPlaceRefreshOutcome.DROPPED_STALE
        // Transient gates: keep the slot queued so pixels are not silently left stale forever.
        if (width == 0 || height == 0) return InPlaceRefreshOutcome.DEFERRED_TRANSIENT
        if (textView.isLayoutRequested) return InPlaceRefreshOutcome.DEFERRED_TRANSIENT
        if (textView.layout?.height != paginatedLayoutHeight) return InPlaceRefreshOutcome.DEFERRED_TRANSIENT
        // Stale geometry vs the live window / texture key: drop and allow normal precache.
        if (!isCurrentPageShotSize(bitmap)) return InPlaceRefreshOutcome.DROPPED_STALE
        val window = paged.getOrNull(pageIndex)
        if (window != null && window.topPx != topPx) return InPlaceRefreshOutcome.DROPPED_STALE
        val expectedKey = pageTextureKey(topPx, window)
        if (textureKey != null && textureKey != expectedKey) return InPlaceRefreshOutcome.DROPPED_STALE
        redrawPageShotInto(bitmap, topPx, window)
        return InPlaceRefreshOutcome.REDRAWN
    }

    private fun cachedBitmapForSlot(slot: CachedPageShotSlot): Bitmap? = when (slot) {
        CachedPageShotSlot.FRONT -> cachedFrontBitmap
        CachedPageShotSlot.REVEALED -> cachedRevealedBitmap
        CachedPageShotSlot.BACKWARD -> cachedBackwardBitmap
    }

    private fun cachedPageForSlot(slot: CachedPageShotSlot): Int = when (slot) {
        CachedPageShotSlot.FRONT -> cachedFromPage
        CachedPageShotSlot.REVEALED -> cachedTargetPage
        CachedPageShotSlot.BACKWARD -> cachedBackwardPage
    }

    private fun cachedTopForSlot(slot: CachedPageShotSlot): Int = when (slot) {
        CachedPageShotSlot.FRONT -> cachedFromTopPx
        CachedPageShotSlot.REVEALED -> cachedTargetTopPx
        CachedPageShotSlot.BACKWARD -> cachedBackwardTopPx
    }

    private fun cachedTextureKeyForSlot(slot: CachedPageShotSlot): Any? = when (slot) {
        CachedPageShotSlot.FRONT -> cachedFromTextureKey
        CachedPageShotSlot.REVEALED -> cachedTargetTextureKey
        CachedPageShotSlot.BACKWARD -> cachedBackwardTextureKey
    }

    /**
     * Clears the existing bitmap then paints the same snapshot path used by [snapshotPageAt], so no
     * stale regions remain after a PIXELS_ONLY update.
     */
    private fun redrawPageShotInto(bitmap: Bitmap, topPx: Int, window: EpubFlowPage?) {
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        scalePageShotCanvasToViewport(canvas, bitmap)
        drawSnapshotBackground(canvas)
        val contentSave = canvas.save()
        canvas.translate(0f, -topPx.toFloat())
        val clipTop = snapshotClipTopFor(topPx, window)
        val clipBottom = snapshotClipBottomFor(topPx, window)
        canvas.clipRect(0, clipTop, width, clipBottom)
        container.draw(canvas)
        canvas.restoreToCount(contentSave)
        window?.let { drawPageBoundaryImagePreview(canvas, topPx, it, canvasViewportTopPx = 0) }
    }

    /**
     * True when a PIXELS_ONLY completion at [layoutOffset] can affect a currently held warm page
     * shot, a pending nearby precache window, or the current/adjacent page neighborhood (including
     * a synthetic page-boundary crop of an image that starts on a later page).
     */
    private fun pageShotDependsOnImageOffset(layoutOffset: Int): Boolean {
        if (pageShotSlotDependsOnImageOffset(cachedFromPage, layoutOffset)) return true
        if (pageShotSlotDependsOnImageOffset(cachedTargetPage, layoutOffset)) return true
        if (pageShotSlotDependsOnImageOffset(cachedBackwardPage, layoutOffset)) return true
        val pending = pendingPageTexturePrecache
        if (
            pending != null &&
            (
                pageWindowDependsOnImageOffset(pending.fromWindow, layoutOffset) ||
                    pageWindowDependsOnImageOffset(pending.previousWindow, layoutOffset) ||
                    pageWindowDependsOnImageOffset(pending.targetWindow, layoutOffset)
                )
        ) {
            return true
        }
        return relevantPageWindows().any { pageWindowDependsOnImageOffset(it, layoutOffset) }
    }

    private fun pageShotSlotDependsOnImageOffset(pageIndex: Int, layoutOffset: Int): Boolean =
        pageWindowDependsOnImageOffset(paged.getOrNull(pageIndex), layoutOffset)

    private fun pageWindowDependsOnImageOffset(page: EpubFlowPage?, layoutOffset: Int): Boolean {
        if (page == null) return false
        if (page.containsLayoutOffset(layoutOffset)) return true
        return pageHasBoundaryPreviewForImage(page, layoutOffset)
    }

    private fun pageHasBoundaryPreviewForImage(page: EpubFlowPage, imageLayoutStart: Int): Boolean {
        val layout = textView.layout ?: return false
        val chapter = flow ?: return false
        val previews = pageLayoutMetadataFor(layout)?.pageBoundaryImagePreviews
            ?: pageBoundaryImagePreviews(layout, chapter)
        return previews.any { item ->
            item.imageLayoutStart == imageLayoutStart &&
                // Preceding text may span earlier pages; only require it ends on this page and
                // the full image starts after this page's last line.
                item.precedingEndLineExclusive > page.startLine &&
                item.precedingEndLineExclusive <= page.endLineExclusive &&
                item.imageLine >= page.endLineExclusive
        }
    }

    /** Current page plus previous/next when those windows exist (same neighborhood as decode gating). */
    private fun relevantPageWindows(): List<EpubFlowPage> {
        if (paged.isEmpty()) return emptyList()
        val aligned = activePageWindow?.takeIf { it.topPx == scrollY }
        val index = when {
            aligned != null -> {
                val exact = paged.indexOfFirst {
                    it.topPx == aligned.topPx &&
                        it.startOffset == aligned.startOffset &&
                        it.endOffset == aligned.endOffset
                }
                if (exact >= 0) exact else canonicalFloorPageIndexForTopPx(scrollY)
            }
            else -> canonicalFloorPageIndexForTopPx(scrollY)
        }.coerceIn(0, paged.lastIndex)
        return buildList {
            paged.getOrNull(index - 1)?.let(::add)
            add(paged[index])
            paged.getOrNull(index + 1)?.let(::add)
        }
    }

    private fun pageContainsLayoutOffset(pageIndex: Int, layoutOffset: Int): Boolean =
        paged.getOrNull(pageIndex)?.containsLayoutOffset(layoutOffset) == true

    private fun EpubFlowPage.containsLayoutOffset(layoutOffset: Int): Boolean =
        layoutOffset >= startOffset && layoutOffset < endOffset

    private fun applyAsyncImageResultRefresh() {
        recycleCachedTextures()
        // Rebind the same Spannable so TextView rebuilds the layout after a Markwon AsyncDrawable
        // replaces a transparent placeholder with same-sized bitmap bounds.
        textView.text = textView.text
        textView.invalidate()
        container.invalidate()
        invalidate()
        onAsyncImageDecodeFinished()
    }

    /** Rechecks reveal/cache only after measure + layout have incorporated the completed image result. */
    fun onAsyncImageDecodeFinished() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            post { onAsyncImageDecodeFinished() }
            return
        }
        if (asyncImageWakeListener != null) return
        val observer = textView.viewTreeObserver
        if (!observer.isAlive) {
            post { onAsyncImageDecodeFinished() }
            return
        }
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (textView.isLayoutRequested) return true
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (asyncImageWakeListener === this) {
                    asyncImageWakeListener = null
                    asyncImageWakeObserver = null
                }
                if (awaitingStableChapter) tryRevealWhenStable() else continueQueuedTurnsOrPrecache()
                return true
            }
        }
        asyncImageWakeObserver = observer
        asyncImageWakeListener = listener
        observer.addOnPreDrawListener(listener)
        invalidate()
    }

    private fun clearAsyncImageWake() {
        val observer = asyncImageWakeObserver
        val listener = asyncImageWakeListener
        if (observer?.isAlive == true && listener != null) observer.removeOnPreDrawListener(listener)
        asyncImageWakeObserver = null
        asyncImageWakeListener = null
    }

    /**
     * Installs the chapter Spannable and paginates over the TextView's measured layout, then positions
     * to the resume target ([restoreOffset], or the last page when [landOnLast]) in the SAME post — so
     * the first painted frame is already at the resume point, never the chapter top jumping to it
     * (静读天下: positioned before paint). Content is held hidden until that position settles at a real
     * measured height, so even the one transient pre-post frame can't show a scroll.
     */
    fun setChapter(
        flow: EpubChapterFlow,
        spannable: CharSequence,
        pageHeightPx: Int,
        restoreOffset: Int? = null,
        landOnLast: Boolean = false,
        reportPositionAfterStableReveal: Boolean = false,
    ) {
        if (disposed) return
        val preserveQueuedTurns = pendingBoundaryPageTurn != null || boundaryContinuityCover
        cancelFreeFlingForLifecycle()
        abortLocalPageShotTurnForExternalMutation(preserveQueuedTurns = preserveQueuedTurns)
        pageLayoutMetadata = null
        clearAsyncImageWake()
        removeCallbacks(asyncImageRefreshRunnable)
        asyncImageRefreshPending = false
        asyncImagePixelRefreshOffsets.clear()
        asyncImagePixelTextRebindPending = false
        asyncImageBatchWaitStartedAtMs = 0L
        cancelInPlacePageShotRefreshCallbacks()
        pendingInPlacePageShotRefreshSlots.clear()
        clearBoundaryPreviews()
        chapterGeneration++
        val supersededBoundaryTurn = pendingBoundaryPageTurn?.let {
            it.expectedChapterGeneration != chapterGeneration
        } == true
        if (supersededBoundaryTurn) {
            clearPendingBoundaryPageTurn()
        }
        flipAnimator?.cancel()
        clearFlipOverlay()
        if (pendingBoundaryPageTurn == null && !boundaryContinuityCover) {
            clearConversionSnapshot()
        } else {
            resetConversionSnapshotFade()
        }
        interactiveTurnState = InteractiveTurnState.NONE
        removeCallbacks(reflowRunnable)
        recycleCachedTextures()
        this.flow = flow
        this.pageHeightPx = pageHeightPx.coerceAtLeast(1)
        currentPage = 0
        activePageWindow = null
        pagedMotionState = PagedMotionState.ALIGNED
        curlOrigin = null
        paginatedLayoutHeight = -1
        pendingRestoreOffset = restoreOffset
        pendingLandOnLast = landOnLast
        this.reportPositionAfterStableReveal = reportPositionAfterStableReveal
        stableCallbackGeneration = -1L
        pendingInitialPageTurnDelta = null
        awaitingReveal = true
        awaitingStableChapter = true
        lastReportedTopOffset = null
        removeCallbacks(revealSafetyRunnable)
        container.animate().cancel()
        container.alpha = 0f
        textView.text = spannable
        // Layout is available after the next measure/layout pass; paginate + position then.
        textView.post { settleInitialPosition() }
    }

    /**
     * Paginates against the current layout and snaps to the pending resume target without reporting an
     * intermediate position. Reveals the content once positioned with a real (measured) height; on a
     * cold open the first pass runs at height 0 (fallback page height), so the reveal is deferred to
     * [onSizeChanged] when the true viewport arrives.
     */
    private fun settleInitialPosition() {
        if (disposed) return
        if (textView.layout == null || flow == null) return
        repaginate(reposition = false)
        if (pendingLandOnLast) {
            scrollToPage(paged.lastIndex.coerceAtLeast(0), report = !reportPositionAfterStableReveal)
        } else {
            goToOffset(
                pendingRestoreOffset ?: 0,
                pagedAnchor = PagedAnchor.NEAREST,
                report = !reportPositionAfterStableReveal,
                forceReport = !reportPositionAfterStableReveal,
            )
        }
        if (consumePendingInitialPageTurn()) return
        if (height > 0) tryRevealWhenStable()
        preCachePageTextures()
    }

    private fun consumePendingInitialPageTurn(): Boolean {
        val delta = pendingInitialPageTurnDelta ?: return false
        if (height <= 0) return true
        pendingInitialPageTurnDelta = null
        if (mode != Mode.PAGED || paged.isEmpty()) return false
        finishInitialRevealForTurn()
        if (goToAdjacentPage(delta)) return true
        onTapZone(if (delta > 0) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
        return true
    }

    /**
     * Stability gate (MoonReader model): reveals content only when the layout geometry has settled
     * AND no async image decodes are pending. Arms an 800ms safety net so content never stays hidden
     * permanently if a decode hangs.
     */
    internal fun tryRevealWhenStable() {
        if (!awaitingStableChapter) return
        removeCallbacks(revealSafetyRunnable)
        if (isLayoutSettled()) {
            revealContent(stable = true)
        } else if (awaitingReveal) {
            // Arm 800ms safety net (MoonReader's postDelayed 800ms recheck)
            postDelayed(revealSafetyRunnable, REVEAL_SAFETY_MS)
        }
    }

    private fun isLayoutSettled(): Boolean =
        textView.layout != null &&
            height > 0 &&
            !textView.isLayoutRequested &&
            textView.layout!!.height == paginatedLayoutHeight &&
            pendingDecodesProvider?.invoke() != true

    private fun revealContent(stable: Boolean) {
        if (!awaitingReveal && !stable) return
        removeCallbacks(revealSafetyRunnable)
        removeCallbacks(conversionSnapshotClearRunnable)
        if (awaitingReveal) awaitingReveal = false
        if (stable) awaitingStableChapter = false
        if (stable && pendingBoundaryPageTurn != null) {
            container.animate().cancel()
            container.alpha = 1f
            if (consumePendingBoundaryPageTurn()) return
        }
        // A gesture recognized while the chapter was hidden owns the next visible position. Publishing
        // the transient chapter top first would persist two locators and make the text appear to jump.
        if (stable && pendingInitialPageTurnDelta == null && queuedPageTurnDelta == 0) {
            publishStableChapterPosition()
        }
        val fadingCover = conversionSnapshotDrawable
        if (fadingCover != null) {
            // The stable live page becomes fully opaque under the frozen shot first. Fading both layers
            // together makes their source-over midpoint only ~75% opaque, visibly weakening text/paper.
            container.animate().cancel()
            container.alpha = 1f
            if (!stable) return
            conversionFadeAnimator?.cancel()
            startConversionSnapshotFade(fadingCover)
            post(::notifyChapterStable)
            return
        }
        if (!animateChapterReveal) {
            container.animate().cancel()
            container.alpha = 1f
            if (!consumePendingInitialPageTurn()) continueQueuedTurnsOrPrecache()
            if (stable) notifyChapterStable()
            return
        }
        container.animate()
            .alpha(1f)
            .setDuration(REVEAL_FADE_MS)
            .withEndAction {
                if (stable) notifyChapterStable()
                if (!consumePendingInitialPageTurn()) {
                    continueQueuedTurnsOrPrecache()
                }
            }
            .start()
    }

    private fun publishStableChapterPosition() {
        if (reportPositionAfterStableReveal) {
            reportPositionAfterStableReveal = false
            reportTopOffset()
        }
    }

    private fun notifyChapterStable() {
        if (stableCallbackGeneration != boundaryPreviewGeneration) {
            stableCallbackGeneration = boundaryPreviewGeneration
            onChapterStable?.invoke()
        }
    }

    fun boundaryPreviewGenerationToken(): Long = boundaryPreviewGeneration

    /** Starts adjacent-spine preparation before the reader reaches the edge. */
    fun shouldPrewarmBoundaryPreview(forward: Boolean): Boolean =
        !disposed &&
            pageTexturePrecacheEnabled &&
            !pageShotSpeculationPaused &&
            !pageShotBudget.isSpeculativeAdmissionPaused &&
            !turnInFlight &&
            mode == Mode.PAGED &&
            paged.isNotEmpty() &&
            isLayoutSettled() &&
            pagesUntilBoundary(forward) <= BOUNDARY_PREWARM_DISTANCE_PAGES

    private fun pagesUntilBoundary(forward: Boolean): Int =
        if (forward) paged.lastIndex - currentPage else currentPage

    fun boundaryPreviewIsRequired(forward: Boolean): Boolean =
        (boundaryWaiting && waitingBoundaryForward == forward) ||
            (boundaryDiscreteWaiting && waitingDiscreteBoundaryForward == forward)

    /** Releases a required turn immediately when the engine proves that no preview can be produced. */
    fun rejectBoundaryPreview(forward: Boolean, sourceChapterGeneration: Long) {
        if (sourceChapterGeneration != boundaryPreviewGeneration) return
        when {
            boundaryWaiting && waitingBoundaryForward == forward -> {
                cancelWaitingBoundaryTurn(invalidateRequest = false)
                flipped = true
            }
            boundaryDiscreteWaiting && waitingDiscreteBoundaryForward == forward -> {
                interactiveTurnState = InteractiveTurnState.NONE
                clearBoundaryWaitFeedback()
                releasePageShotBudgetForBoundaryPreview(forward)
            }
            else -> releasePageShotBudgetForBoundaryPreview(forward)
        }
    }

    /** Gives a boundary target the directional slot when a third full-screen shot is too expensive. */
    fun preparePageShotBudgetForBoundaryPreview(forward: Boolean, required: Boolean): Boolean {
        if (width <= 0 || height <= 0) return false
        val existingDirection = boundaryPreviewBudgetDirection
        val switchingDirection = existingDirection != null && existingDirection != forward
        if (
            switchingDirection &&
            !required &&
            shouldPrewarmBoundaryPreview(checkNotNull(existingDirection))
        ) return false
        if (switchingDirection) {
            onBoundaryPreviewRequestCancelled?.invoke(checkNotNull(existingDirection))
            val obsoletePreview = if (forward) backwardBoundaryPreview else forwardBoundaryPreview
            if (obsoletePreview != null) {
                if (forward) backwardBoundaryPreview = null else forwardBoundaryPreview = null
                onBoundaryPreviewEvicted?.invoke(obsoletePreview)
                recyclePageShot(obsoletePreview.bitmap)
            }
        }
        boundaryPreviewBudgetDirection = forward
        pendingPageTexturePrecache?.let(::discardPendingPageTexturePrecache)
        // Reserve this direction before the background renderer is ready. A programmatic goToPage
        // can otherwise fill the third local slot after the request starts and starve the preview.
        if (threePageShotsFitOppositeBudget() && pageShotBudget.hasAvailableShotSlot) return true

        if (required) {
            val oppositePreview = if (forward) backwardBoundaryPreview else forwardBoundaryPreview
            if (oppositePreview != null) {
                if (forward) backwardBoundaryPreview = null else forwardBoundaryPreview = null
                onBoundaryPreviewEvicted?.invoke(oppositePreview)
                recyclePageShot(oppositePreview.bitmap)
            }
        }
        if (forward) {
            cachedBackwardBitmap?.let(::recyclePageShot)
            cachedBackwardBitmap = null
            cachedBackwardPage = -1
            cachedBackwardTopPx = -1
            cachedBackwardTextureKey = null
        } else {
            cachedRevealedBitmap?.let(::recyclePageShot)
            cachedRevealedBitmap = null
            cachedTargetPage = -1
            cachedTargetTopPx = -1
            cachedTargetTextureKey = null
        }
        return true
    }

    fun boundaryPreviewBudgetAllows(forward: Boolean): Boolean =
        threePageShotsFitOppositeBudget() || boundaryPreviewBudgetDirection == forward

    fun releasePageShotBudgetForBoundaryPreview(forward: Boolean, resumePrecache: Boolean = true) {
        if (boundaryPreviewBudgetDirection != forward) return
        val slotted = if (forward) forwardBoundaryPreview != null else backwardBoundaryPreview != null
        val active = activeBoundaryPreview?.forward == forward
        if (slotted || active) return
        boundaryPreviewBudgetDirection = null
        if (resumePrecache) preCachePageTextures()
    }

    private fun preparePinnedLocalWorkingPairBudget() {
        if (width <= 0 || height <= 0) return
        // A visible continuity cover already occupies the third slot while a cold finger handoff
        // captures its target and front on consecutive frames. Drop inactive boundary work before
        // that handoff instead of forcing the second capture to evict it on the input critical path.
        if (threePageShotsFitOppositeBudget() && conversionSnapshotDrawable == null) return
        if (
            forwardBoundaryPreview == null &&
            backwardBoundaryPreview == null &&
            boundaryPreviewBudgetDirection == null
        ) return
        val slotted = listOfNotNull(forwardBoundaryPreview, backwardBoundaryPreview)
        val slottedDirections = slotted.mapTo(HashSet()) {
            it.forward
        }
        val pendingDirection = boundaryPreviewBudgetDirection
        if (pendingDirection != null && pendingDirection !in slottedDirections) {
            onBoundaryPreviewRequestCancelled?.invoke(pendingDirection)
        }
        boundaryPreviewBudgetDirection = null
        slotted.forEach { preview ->
            if (preview.forward) forwardBoundaryPreview = null else backwardBoundaryPreview = null
            onBoundaryPreviewEvicted?.invoke(preview)
            recyclePageShot(preview.bitmap)
        }
    }

    /** Drops only speculative owners; active turns and continuity covers keep their PINNED frames. */
    fun pausePageShotSpeculationAndTrim() {
        pageShotSpeculationPaused = true
        cancelWaitingBoundaryTurn(invalidateRequest = false)
        if (boundaryDiscreteWaiting) {
            interactiveTurnState = InteractiveTurnState.NONE
            clearBoundaryWaitFeedback()
        }
        recycleCachedTextures()
        listOfNotNull(forwardBoundaryPreview, backwardBoundaryPreview).forEach { preview ->
            onBoundaryTurnDiscarded?.invoke(preview)
            recyclePageShot(preview.bitmap)
        }
        forwardBoundaryPreview = null
        backwardBoundaryPreview = null
        if (activeBoundaryPreview == null) boundaryPreviewBudgetDirection = null
    }

    fun evictSpeculativePageShotsForPinnedAllocation(preserveBoundaryDirection: Boolean? = null) {
        recycleCachedTextures()
        val preservedDirection = preserveBoundaryDirection ?: activeBoundaryPreview?.forward
        val slotted = listOfNotNull(forwardBoundaryPreview, backwardBoundaryPreview)
        slotted
            .filterNot { it.forward == preservedDirection }
            .forEach { preview ->
                if (preview.forward) forwardBoundaryPreview = null else backwardBoundaryPreview = null
                onBoundaryPreviewEvicted?.invoke(preview)
                recyclePageShot(preview.bitmap)
            }
        val budgetDirection = boundaryPreviewBudgetDirection
        if (
            budgetDirection != null &&
            budgetDirection != preservedDirection
        ) {
            onBoundaryPreviewRequestCancelled?.invoke(budgetDirection)
            boundaryPreviewBudgetDirection = null
        }
    }

    fun resumePageShotSpeculation() {
        if (disposed) return
        pageShotSpeculationPaused = false
        preCachePageTextures()
    }

    fun activeBoundaryPreviewToken(): Long? = activeBoundaryPreview?.token

    /** Captures the first or last stable page for an adjacent-spine preview renderer. */
    fun snapshotBoundaryLandingPage(landOnLast: Boolean, required: Boolean = false): Bitmap? {
        if (!isLayoutSettled() || mode != Mode.PAGED || paged.isEmpty()) return null
        val target = if (landOnLast) paged.lastIndex else 0
        scrollToPage(target, report = false)
        val kind = if (required) PageShotLeaseKind.PINNED else PageShotLeaseKind.EVICTABLE
        val label = if (required) "active.boundary.target" else "boundary.preview"
        return snapshotViewport(kind, label)
    }

    fun clearBoundaryPreviews() {
        deferredBoundaryFinishCommit = false
        boundaryPreviewGeneration++
        val boundaryOwnsAnimator =
            activeBoundaryPreview != null ||
                interactiveTurnState == InteractiveTurnState.BOUNDARY_SOFTWARE ||
                interactiveTurnState == InteractiveTurnState.BOUNDARY_DISCRETE_ACTIVE
        if (boundaryOwnsAnimator) flipAnimator?.cancel()
        cancelWaitingBoundaryTurn(invalidateRequest = false)
        if (boundaryDiscreteWaiting) {
            interactiveTurnState = InteractiveTurnState.NONE
            clearBoundaryWaitFeedback()
        }
        activeBoundaryPreview?.let { preview ->
            activeBoundaryPreview = null
            clearFlipOverlay()
            onBoundaryTurnDiscarded?.invoke(preview)
        }
        invalidateBoundaryPreviews()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        deferredBoundaryFinishCommit = false
        boundaryPreviewGeneration++
        cancelFreeFlingForLifecycle()
        abortLocalPageShotTurnForExternalMutation()
        clearAsyncImageWake()
        removeCallbacks(revealSafetyRunnable)
        removeCallbacks(reflowRunnable)
        removeCallbacks(asyncImageRefreshRunnable)
        asyncImageRefreshPending = false
        asyncImagePixelRefreshOffsets.clear()
        asyncImagePixelTextRebindPending = false
        asyncImageBatchWaitStartedAtMs = 0L
        cancelInPlacePageShotRefreshCallbacks()
        pendingInPlacePageShotRefreshSlots.clear()
        removeCallbacks(conversionSnapshotClearRunnable)
        flipAnimator?.cancel()
        flipAnimator = null
        activeBoundaryPreview?.let { onBoundaryTurnDiscarded?.invoke(it) }
        activeBoundaryPreview = null
        cancelWaitingBoundaryTurn(invalidateRequest = false)
        clearFlipOverlay()
        invalidateBoundaryPreviews()
        clearPendingBoundaryPageTurn()
        clearConversionSnapshot()
        recycleCachedTextures()
        recycleTracker()
        pendingDecodesProvider = null
        onChapterStable = null
        onBoundaryPreviewNeeded = null
        onBoundaryPreviewConfigurationChanged = null
        canCommitBoundaryTurn = null
        onBoundaryTurnCommitted = null
        onBoundaryTurnDiscarded = null
        onBoundaryPreviewEvicted = null
        onBoundaryPreviewRequestCancelled = null
        onPageShotForeground = null
        onPageTurnCapturePreparing = null
        onPageTurnStarted = null
        onPageTurnTargetParked = null
        onPageSettled = null
        awaitingReveal = false
        awaitingStableChapter = false
        pendingRestoreOffset = null
        pendingLandOnLast = false
        pendingInitialPageTurnDelta = null
        interruptedFreeFlingNeedsRebase = false
        pageLayoutMetadata = null
        flow = null
        textView.text = ""
    }

    private fun startConversionSnapshotFade(fadingCover: ViewportSnapshotDrawable) {
        val startAlpha = fadingCover.alphaValue
        if (startAlpha <= 0) {
            if (conversionSnapshotDrawable === fadingCover) clearConversionSnapshot()
            if (!consumePendingInitialPageTurn()) continueQueuedTurnsOrPrecache()
            return
        }
        conversionFadeAnimator = android.animation.ValueAnimator.ofInt(startAlpha, 0).apply {
            duration = (REVEAL_FADE_MS * startAlpha / 255L).coerceAtLeast(1L)
            addUpdateListener { fadingCover.alphaValue = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    conversionFadeAnimator = null
                    // Only retire the cover this animation owned; a new conversion may have
                    // installed a fresh snapshot mid-fade (do not recycle someone else's cover).
                    if (!cancelled && conversionSnapshotDrawable === fadingCover) {
                        clearConversionSnapshot()
                        if (!consumePendingInitialPageTurn()) continueQueuedTurnsOrPrecache()
                    }
                }
            })
        }.also { it.start() }
    }

    private fun initialRevealActive(): Boolean =
        awaitingReveal || container.alpha < 1f

    private fun finishInitialRevealForTurn() {
        if (!initialRevealActive() && conversionSnapshotDrawable == null) return
        removeCallbacks(revealSafetyRunnable)
        container.animate().cancel()
        awaitingReveal = false
        container.alpha = 1f
        clearConversionSnapshot()
    }

    /**
     * Re-applies annotation and transient search highlights in place without rebuilding the chapter.
     *
     * Only [ReaderTextHighlightSpan] / [ReaderSearchHighlightSpan] are stripped and replaced — plain
     * [BackgroundColorSpan] (CSS background, selection) stays intact. Span changes don't alter text
     * length, so the StaticLayout just redraws (no repagination, no scroll disruption). Offsets are
     * absolute layout offsets within the chapter.
     */
    fun refreshHighlights(
        annotationRanges: List<dev.readflow.render.api.ReaderTextHighlightRange>,
        searchRanges: List<dev.readflow.render.api.ReaderTextHighlightRange> = emptyList(),
    ) {
        abortLocalPageShotTurnForExternalMutation(restoreOrigin = true)
        recycleCachedTextures()
        val text = textView.text as? android.text.Spannable ?: return
        text.getSpans(0, text.length, dev.readflow.render.api.ReaderTextHighlightSpan::class.java)
            .forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, dev.readflow.render.api.ReaderSearchHighlightSpan::class.java)
            .forEach { text.removeSpan(it) }
        annotationRanges.forEach { range ->
            val s = range.start.coerceIn(0, text.length)
            val e = range.end.coerceIn(s, text.length)
            if (e > s) {
                text.setSpan(
                    dev.readflow.render.api.ReaderTextHighlightSpan(range.color),
                    s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        searchRanges.forEach { range ->
            val s = range.start.coerceIn(0, text.length)
            val e = range.end.coerceIn(s, text.length)
            if (e > s) {
                text.setSpan(
                    dev.readflow.render.api.ReaderSearchHighlightSpan(range.color),
                    s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        textView.invalidate()
    }

    /** Back-compat single-list form used by older tests (annotation ranges only). */
    fun refreshHighlights(ranges: List<dev.readflow.render.api.ReaderTextHighlightRange>) {
        refreshHighlights(annotationRanges = ranges, searchRanges = emptyList())
    }

    /**
     * Recomputes the page windows over the TextView's current StaticLayout. When [reposition] is true
     * (cold open, mode switch) it also snaps [scrollY] to the current page's top; callers that re-anchor
     * by content offset right after (reflow / size change) pass false, so the page only scrolls ONCE
     * (a [scrollTo] here followed by [goToOffset] is the visible flicker on every image decode).
     */
    private fun repaginate(
        reposition: Boolean = true,
        preserveQueuedTurns: Boolean = false,
    ) {
        if (disposed) return
        abortLocalPageShotTurnForExternalMutation(
            restoreOrigin = true,
            preserveQueuedTurns = preserveQueuedTurns,
        )
        val layout = textView.layout ?: return
        val f = flow ?: return
        paginatedLayoutHeight = layout.height
        // Paginate against the view's REAL measured viewport, not the engine's screen-derived estimate
        // — the reader avoids the system bars, so the visible height is shorter than the screen. Using
        // the true height keeps each page's last line fully visible and lets the page clip engage
        // (审计 C1/H3: the view's own geometry is the single source of truth). [pageHeightPx] is only a
        // pre-measurement fallback (height == 0 before the first layout pass).
        val effectivePageH = usablePageHeightPx()
        val geometry = EpubLayoutLineGeometry(layout)
        val nextGeneration = pageLayoutGeneration + 1L
        val nextMetadata = if (mode == Mode.PAGED) {
            buildPageLayoutMetadata(layout, f, nextGeneration, effectivePageH)
        } else {
            null
        }
        val nextPaged = if (nextMetadata != null) {
            epubDropSeparatorOnlyPages(
                pages = epubPaginateFlow(
                    geometry = geometry,
                    pageHeightPx = effectivePageH,
                    isHeadingLine = nextMetadata.headingLines::contains,
                    paragraphLineRange = { nextMetadata.paragraphLineRange(layout, it) },
                ),
                contentSegments = f.segments,
            )
        } else {
            emptyList()
        }
        paged = nextPaged
        pageLayoutGeneration = nextGeneration
        pageLayoutMetadata = nextMetadata
        container.minimumScrollableHeightPx = if (mode == Mode.PAGED && paged.isNotEmpty() && height > 0) {
            paged.last().topPx + height
        } else {
            0
        }
        if (reposition && mode == Mode.PAGED && paged.isNotEmpty()) {
            currentPage = currentPage.coerceIn(0, paged.lastIndex)
            pageClipActive = true
            scrollTo(0, paged[currentPage].topPx)
            activePageWindow = paged[currentPage]
            pagedMotionState = PagedMotionState.ALIGNED
        } else if (mode != Mode.PAGED || paged.isEmpty()) {
            activePageWindow = null
            pagedMotionState = PagedMotionState.ALIGNED
        } else {
            activePageWindow = null
            pagedMotionState = PagedMotionState.FREE_REST
        }
        if (reposition) rebaseInterruptedFreeFlingAtCurrentViewport()
    }

    private fun buildPageLayoutMetadata(
        layout: Layout,
        f: EpubChapterFlow,
        generation: Long,
        usablePageHeightPx: Int,
    ): PageLayoutMetadata {
        val pageBoundaryImagePreviews = pageBoundaryImagePreviews(layout, f)
        // Keep-with-next still applies only to heading→image groups (paginator keepTogether).
        val keepTogetherHeadingStarts = pageBoundaryImagePreviews
            .filter(PageBoundaryImagePreview::precedingIsHeading)
            .mapTo(HashSet()) { it.precedingLayoutStart }
        val headingLines = HashSet<Int>()
        val paragraphIntervals = ArrayList<ParagraphLineInterval>(f.segments.size)
        f.segments.forEach { segment ->
            if (segment.layoutEnd <= segment.layoutStart) return@forEach
            val firstLine = layout.getLineForOffset(segment.layoutStart)
            val lastLine = layout.getLineForOffset(segment.layoutEnd - 1)
            paragraphIntervals += ParagraphLineInterval(
                layoutStart = segment.layoutStart,
                layoutEndExclusive = segment.layoutEnd,
                firstLine = firstLine,
                endLineExclusive = lastLine + 1,
            )
            val block = segment.block
            if (
                block is EpubDisplayBlock.Text &&
                block.headingLevel != null &&
                segment.layoutStart !in keepTogetherHeadingStarts
            ) {
                for (line in firstLine..lastLine) headingLines += line
            }
        }
        return PageLayoutMetadata(
            pageGeneration = generation,
            chapterGeneration = chapterGeneration,
            layoutWidthPx = layout.width,
            layoutHeightPx = layout.height,
            lineCount = layout.lineCount,
            usablePageHeightPx = usablePageHeightPx,
            headingLines = headingLines,
            paragraphIntervals = paragraphIntervals,
            pageBoundaryImagePreviews = pageBoundaryImagePreviews,
        )
    }

    /**
     * Any non-inline block image immediately after preceding text content can paint a cropped top
     * preview in leftover page space when the full image moves to the next page. One occurrence
     * only — the image still owns a single layout offset / U+FFFC.
     */
    private fun pageBoundaryImagePreviews(
        layout: Layout,
        f: EpubChapterFlow,
    ): List<PageBoundaryImagePreview> = buildList {
        f.segments.forEachIndexed { index, preceding ->
            val textBlock = preceding.block as? EpubDisplayBlock.Text ?: return@forEachIndexed
            if (preceding.layoutEnd <= preceding.layoutStart) return@forEachIndexed
            // Headings and body paragraphs both qualify; skip empty / pure-separator text.
            if (textBlock.text.isBlank()) return@forEachIndexed
            val nextContent = f.nextContentSegmentAfter(index) ?: return@forEachIndexed
            val image = nextContent.block as? EpubDisplayBlock.Image ?: return@forEachIndexed
            if (image.isInlineContent) return@forEachIndexed
            val imageLayoutStart = nextContent.layoutStart
            add(
                PageBoundaryImagePreview(
                    precedingLayoutStart = preceding.layoutStart,
                    precedingEndLineExclusive = layout.getLineForOffset(preceding.layoutEnd - 1) + 1,
                    precedingIsHeading = textBlock.headingLevel != null,
                    imageLayoutStart = imageLayoutStart,
                    imageLine = layout.getLineForOffset(imageLayoutStart),
                    // Resolve once per page-layout generation; AsyncDrawable.result may still
                    // replace in place without changing this shell host.
                    imageDrawableHost = imageDrawableHostAt(imageLayoutStart),
                ),
            )
        }
    }

    private fun PageLayoutMetadata.paragraphLineRange(layout: Layout, line: Int): IntRange {
        val offset = layout.getLineStart(line)
        var low = 0
        var high = paragraphIntervals.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (paragraphIntervals[middle].layoutStart <= offset) low = middle + 1 else high = middle
        }
        val interval = paragraphIntervals.getOrNull(low - 1)
        return if (interval != null && offset < interval.layoutEndExclusive) {
            interval.firstLine..interval.endLineExclusive
        } else {
            line..(line + 1)
        }
    }

    private fun pageLayoutMetadataFor(layout: Layout): PageLayoutMetadata? =
        pageLayoutMetadata?.takeIf { metadata ->
            metadata.pageGeneration == pageLayoutGeneration &&
                metadata.chapterGeneration == chapterGeneration &&
                metadata.layoutWidthPx == layout.width &&
                metadata.layoutHeightPx == layout.height &&
                metadata.lineCount == layout.lineCount &&
                metadata.usablePageHeightPx == usablePageHeightPx()
        }

    private fun headingLineSet(layout: Layout, f: EpubChapterFlow): Set<Int> {
        // Headings that keep-with-next an attached image are excluded so the group rides together.
        val keepTogetherHeadingStarts = pageBoundaryImagePreviews(layout, f)
            .filter(PageBoundaryImagePreview::precedingIsHeading)
            .mapTo(HashSet()) { it.precedingLayoutStart }
        val lines = HashSet<Int>()
        f.segments.forEach { seg ->
            val block = seg.block
            if (
                block is EpubDisplayBlock.Text &&
                block.headingLevel != null &&
                seg.layoutEnd > seg.layoutStart &&
                seg.layoutStart !in keepTogetherHeadingStarts
            ) {
                val first = layout.getLineForOffset(seg.layoutStart)
                val last = layout.getLineForOffset((seg.layoutEnd - 1).coerceAtLeast(seg.layoutStart))
                for (l in first..last) lines += l
            }
        }
        return lines
    }

    private fun paragraphLineRange(layout: Layout, f: EpubChapterFlow, line: Int): IntRange {
        val offset = layout.getLineStart(line)
        val seg = f.segments.firstOrNull { offset in it.layoutStart until it.layoutEnd }
            ?: return line..(line + 1)
        val first = layout.getLineForOffset(seg.layoutStart)
        val last = layout.getLineForOffset((seg.layoutEnd - 1).coerceAtLeast(seg.layoutStart))
        return first..(last + 1)
    }

    fun pageCount(): Int = if (mode == Mode.PAGED) paged.size.coerceAtLeast(1) else 1

    fun currentPageIndex(): Int = currentPage

    /** Installs one exclusively owned adjacent-chapter frame in the matching direction slot. */
    fun offerBoundaryPreview(preview: BoundaryPagePreview): Boolean =
        offerBoundaryPreview(preview, forcePinned = false)

    fun offerRetainedBoundaryPreview(preview: BoundaryPagePreview): Boolean =
        offerBoundaryPreview(preview, forcePinned = true)

    private fun offerBoundaryPreview(preview: BoundaryPagePreview, forcePinned: Boolean): Boolean {
        if (
            disposed ||
            preview.bitmap.isRecycled ||
            preview.sourceChapterGeneration != boundaryPreviewGeneration ||
            mode != Mode.PAGED
        ) {
            recyclePageShot(preview.bitmap)
            return false
        }
        preview.retainedSurface = forcePinned
        val replaced = if (preview.forward) forwardBoundaryPreview else backwardBoundaryPreview
        if (replaced !== preview && replaced != null) {
            onBoundaryTurnDiscarded?.invoke(replaced)
            recyclePageShot(replaced.bitmap)
        }
        val kind = if (forcePinned || boundaryPreviewIsRequired(preview.forward)) {
            PageShotLeaseKind.PINNED
        } else {
            PageShotLeaseKind.EVICTABLE
        }
        relabelPageShot(preview.bitmap, kind, if (kind == PageShotLeaseKind.PINNED) {
            "active.boundary.target"
        } else {
            "boundary.preview"
        })
        if (kind == PageShotLeaseKind.EVICTABLE && pageShotBudget.chargedBytes > pageShotBudget.capacityBytes) {
            recyclePageShot(preview.bitmap)
            return false
        }
        if (preview.forward) forwardBoundaryPreview = preview else backwardBoundaryPreview = preview

        if (boundaryWaiting && waitingBoundaryForward == preview.forward) {
            startWaitingBoundaryTurnIfReady()
        } else if (boundaryDiscreteWaiting && waitingDiscreteBoundaryForward == preview.forward) {
            startWaitingDiscreteBoundaryTurnIfReady()
        }
        return true
    }

    private fun takeBoundaryPreview(forward: Boolean): BoundaryPagePreview? {
        val preview = if (forward) forwardBoundaryPreview else backwardBoundaryPreview
        if (forward) forwardBoundaryPreview = null else backwardBoundaryPreview = null
        if (
            preview != null &&
            !preview.bitmap.isRecycled &&
            preview.sourceChapterGeneration == boundaryPreviewGeneration
        ) {
            relabelPageShot(preview.bitmap, PageShotLeaseKind.PINNED, "active.boundary.target")
            return preview
        }
        preview?.bitmap?.takeUnless(Bitmap::isRecycled)?.let(::recyclePageShot)
        return null
    }

    private fun restoreBoundaryPreviewAfterOutgoingFailure(preview: BoundaryPagePreview): Boolean {
        if (
            preview.bitmap.isRecycled ||
            preview.sourceChapterGeneration != boundaryPreviewGeneration
        ) return false
        val replaced = if (preview.forward) forwardBoundaryPreview else backwardBoundaryPreview
        if (replaced !== preview && replaced != null) {
            onBoundaryTurnDiscarded?.invoke(replaced)
            recyclePageShot(replaced.bitmap)
        }
        val kind = if (preview.retainedSurface) PageShotLeaseKind.PINNED else PageShotLeaseKind.EVICTABLE
        relabelPageShot(
            preview.bitmap,
            kind,
            if (kind == PageShotLeaseKind.PINNED) "active.boundary.target" else "boundary.preview",
        )
        if (kind == PageShotLeaseKind.EVICTABLE && pageShotBudget.chargedBytes > pageShotBudget.capacityBytes) {
            recyclePageShot(preview.bitmap)
            return false
        }
        if (preview.forward) forwardBoundaryPreview = preview else backwardBoundaryPreview = preview
        return true
    }

    private fun invalidateBoundaryPreviews() {
        listOfNotNull(forwardBoundaryPreview, backwardBoundaryPreview).forEach { preview ->
            recyclePageShot(preview.bitmap)
        }
        forwardBoundaryPreview = null
        backwardBoundaryPreview = null
        boundaryPreviewBudgetDirection = null
    }

    fun startDiscreteBoundaryTurn(delta: Int): Boolean {
        if (disposed || delta == 0 || mode != Mode.PAGED || paged.isEmpty() || width <= 0 || height <= 0) {
            return false
        }
        if (boundaryContinuityCover && awaitingStableChapter) return true
        if (turnInFlight) return true
        val forward = delta > 0
        val preview = takeBoundaryPreview(forward)
        if (preview == null) {
            val requestPreview = onBoundaryPreviewNeeded ?: return false
            waitingDiscreteBoundaryForward = forward
            interactiveTurnState = InteractiveTurnState.BOUNDARY_DISCRETE_WAITING
            requestPreview(forward, boundaryPreviewGeneration)
            return true
        }
        startBoundaryDiscreteTurn(preview)
        return true
    }

    private fun startWaitingDiscreteBoundaryTurnIfReady() {
        if (!boundaryDiscreteWaiting) return
        val preview = takeBoundaryPreview(waitingDiscreteBoundaryForward) ?: return
        clearBoundaryWaitFeedback()
        startBoundaryDiscreteTurn(preview)
    }

    private fun startBoundaryDiscreteTurn(preview: BoundaryPagePreview) {
        if (canCommitBoundaryTurn?.invoke(preview) == false) {
            recyclePageShot(preview.bitmap)
            onBoundaryTurnDiscarded?.invoke(preview)
            interactiveTurnState = InteractiveTurnState.NONE
            return
        }
        if (!pageTurnAnimated) {
            val commitCallback = onBoundaryTurnCommitted ?: run {
                recyclePageShot(preview.bitmap)
                onBoundaryTurnDiscarded?.invoke(preview)
                interactiveTurnState = InteractiveTurnState.NONE
                return
            }
            val fromWindow = activePageWindow?.takeIf { it.topPx == scrollY }
            val reverseBitmap = takeCachedFrontForBoundaryTurn(
                fromPage = currentPage,
                fromTop = scrollY,
                fromWindow = fromWindow,
            ) ?: snapshotViewport(PageShotLeaseKind.PINNED, "active.boundary.front")
            if (reverseBitmap == null) {
                if (!restoreBoundaryPreviewAfterOutgoingFailure(preview)) {
                    recyclePageShot(preview.bitmap)
                    onBoundaryTurnDiscarded?.invoke(preview)
                }
                interactiveTurnState = InteractiveTurnState.NONE
                return
            }
            showConversionSnapshot(preview.bitmap)
            boundaryContinuityCover = true
            interactiveTurnState = InteractiveTurnState.NONE
            preview.reverseBitmap = reverseBitmap
            commitCallback.invoke(preview)
            return
        }
        onPageTurnCapturePreparing?.invoke()
        activeBoundaryPreview = preview
        val fromWindow = activePageWindow?.takeIf { it.topPx == scrollY }
        val outgoing = takeCachedFrontForBoundaryTurn(
            fromPage = currentPage,
            fromTop = scrollY,
            fromWindow = fromWindow,
        ) ?: snapshotViewport(PageShotLeaseKind.PINNED, "active.boundary.front") ?: run {
            // The failure belongs to the current-page capture, not the already rendered adjacent
            // target. Keep that valid preview warm so a later healthy tap can retry immediately.
            activeBoundaryPreview = null
            if (!restoreBoundaryPreviewAfterOutgoingFailure(preview)) {
                recyclePageShot(preview.bitmap)
                onBoundaryTurnDiscarded?.invoke(preview)
            }
            interactiveTurnState = InteractiveTurnState.NONE
            return
        }
        onPageTurnStarted?.invoke()
        interactiveTurnState = InteractiveTurnState.BOUNDARY_DISCRETE_ACTIVE
        if (useSimulationDiscreteRenderer()) {
            val drawable = PageCurlDrawable(
                outgoing,
                preview.bitmap,
                width,
                height,
                preview.forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            curlDrawable = drawable
        } else {
            val drawable = PageSlideDrawable(
                outgoing,
                preview.bitmap,
                width,
                height,
                preview.forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            slideDrawable = drawable
        }
        applyFlipProgress(0f, preview.forward)
        flipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = activeFlipDurationMs()
            interpolator = activeFlipInterpolator()
            addUpdateListener { applyFlipProgress(it.animatedValue as Float, preview.forward) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishBoundaryInteractiveTurn(commit = true)
                override fun onAnimationCancel(animation: Animator) = finishBoundaryInteractiveTurn(commit = false)
            })
            start()
        }
    }

    /**
     * Moves one page in [delta] direction. Returns true if it moved within this chapter; returns
     * false at a chapter boundary so the caller can start the adjacent-preview transaction.
     */
    fun goToAdjacentPage(delta: Int): Boolean {
        if (delta == 0) return false
        // The outgoing page shot owns the Window until the adjacent chapter is stable and its one
        // prepared turn is consumed. Likewise, a live local page-shot turn may already have parked the content
        // on this chapter's final page; reporting a boundary from that intermediate state would make the
        // engine replace the chapter mid-animation.
        if (pendingBoundaryPageTurn != null || (boundaryContinuityCover && awaitingStableChapter) || turnInFlight) {
            enqueuePageTurn(delta)
            return true
        }
        if (mode == Mode.PAGED) {
            if (awaitingReveal && flow != null && (height <= 0 || paged.isEmpty())) {
                pendingInitialPageTurnDelta = delta.coerceIn(-1, 1)
                if (height > 0) tryRevealWhenStable()
                return true
            }
            if (paged.isEmpty()) {
                if (awaitingReveal && flow != null) {
                    pendingInitialPageTurnDelta = delta.coerceIn(-1, 1)
                    return true
                }
                return false
            }
            val cancelsQueuedDirection =
                (queuedPageTurnDelta > 0 && delta < 0) || (queuedPageTurnDelta < 0 && delta > 0)
            if (cancelsQueuedDirection) {
                enqueuePageTurn(delta)
                if (drainQueuedPageTurn()) onPageSettled?.invoke()
                return true
            }
            val target = pageWindowForTurn(forward = delta > 0) ?: return false
            if (rapidTurnSequenceActive || rapidIdlePageTurnGesture) {
                enqueuePageTurn(delta)
                if (drainQueuedPageTurn()) onPageSettled?.invoke()
                return true
            }
            goToPageAnimated(target, forward = delta > 0)
            return true
        }
        // SCROLL: page by viewport; boundary only at the true scroll extremes.
        val maxScroll = (container.height - height).coerceAtLeast(0)
        if (delta > 0 && scrollY >= maxScroll) return false
        if (delta < 0 && scrollY <= 0) return false
        scrollTo(0, (scrollY + delta * pageHeightPx).coerceIn(0, maxScroll))
        reportTopOffset()
        return true
    }

    private fun enqueuePageTurn(delta: Int) {
        val step = delta.coerceIn(-1, 1)
        if (step == 0) return
        queuedPageTurnDelta = (queuedPageTurnDelta + step).coerceIn(
            -MAX_QUEUED_PAGE_TURNS,
            MAX_QUEUED_PAGE_TURNS,
        )
        armRapidTurnSequence()
        if (turnInFlight) {
            if (queuedPageTurnDelta == 0) {
                clearRapidFollowUpPageShot()
            } else {
                scheduleRapidFollowUpPageShot(forward = queuedPageTurnDelta > 0)
            }
        }
    }

    private fun clearQueuedPageTurns() {
        removeCallbacks(rapidTurnIdleRunnable)
        clearRapidFollowUpPageShot()
        queuedPageTurnDelta = 0
        rapidTurnSequenceActive = false
        busyPageTurnGesture = false
        rapidIdlePageTurnGesture = false
    }

    fun takeQueuedPageTurnsForPromotion(): Pair<Int, Boolean> {
        val state = queuedPageTurnDelta to rapidTurnSequenceActive
        clearQueuedPageTurns()
        return state
    }

    fun acceptPromotedPageTurns(delta: Int, rapidSequence: Boolean) {
        if (disposed || (delta == 0 && !rapidSequence)) return
        queuedPageTurnDelta = (queuedPageTurnDelta + delta).coerceIn(
            -MAX_QUEUED_PAGE_TURNS,
            MAX_QUEUED_PAGE_TURNS,
        )
        if (rapidSequence || queuedPageTurnDelta != 0) armRapidTurnSequence()
    }

    private fun armRapidTurnSequence() {
        removeCallbacks(rapidTurnIdleRunnable)
        rapidTurnSequenceActive = true
        postDelayed(rapidTurnIdleRunnable, RAPID_TURN_IDLE_TIMEOUT_MS)
    }

    private fun scheduleRapidTurnIdle() {
        if (!rapidTurnSequenceActive) return
        removeCallbacks(rapidTurnIdleRunnable)
        postDelayed(rapidTurnIdleRunnable, RAPID_TURN_IDLE_TIMEOUT_MS)
    }

    private fun continueQueuedTurnsOrPrecache() {
        // A transferred warm owner may still need one in-place pixel refresh after settle. The
        // normal precache path intentionally returns during a rapid sequence, so explicitly drain
        // this queue before trying to start the next visual transaction.
        if (!turnInFlight && pendingInPlacePageShotRefreshSlots.isNotEmpty()) {
            resumeDeferredInPlacePageShotRefresh()
            return
        }
        if (drainQueuedPageTurn()) return
        if (rapidTurnSequenceActive) scheduleRapidTurnIdle() else preCachePageTextures()
    }

    private fun hasPendingPageArtifactRefresh(): Boolean =
        asyncImageRefreshPending ||
            asyncImagePixelRefreshOffsets.isNotEmpty() ||
            asyncImagePixelTextRebindPending ||
            pendingInPlacePageShotRefreshSlots.isNotEmpty()

    private fun drainQueuedPageTurn(): Boolean {
        if (
            queuedPageTurnDelta == 0 ||
            disposed ||
            turnInFlight ||
            mode != Mode.PAGED ||
            paged.isEmpty() ||
            initialRevealActive() ||
            awaitingStableChapter ||
            pendingDecodesProvider?.invoke() == true ||
            hasPendingPageArtifactRefresh() ||
            textView.isLayoutRequested
        ) return false
        val delta = if (queuedPageTurnDelta > 0) 1 else -1
        armRapidTurnSequence()
        val availableLocalSteps = if (delta > 0) paged.lastIndex - currentPage else currentPage
        if (availableLocalSteps > 0) {
            val targetPage = currentPage + delta
            val targetWindow = paged[targetPage]
            if (!goToPageAnimated(targetWindow, forward = delta > 0)) return false
            queuedPageTurnDelta -= delta
            return true
        }
        queuedPageTurnDelta -= delta
        onTapZone(if (delta > 0) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
        if (!turnInFlight && queuedPageTurnDelta != 0) queuedPageTurnDelta = 0
        return true
    }

    private fun activeFlipDurationMs(): Long = flipDurationMs

    private fun activeFlipInterpolator(): android.animation.TimeInterpolator =
        if (rapidTurnSequenceActive) LinearInterpolator() else DecelerateInterpolator(1.6f)

    private fun useSimulationDiscreteRenderer(): Boolean =
        flipStyle == dev.readflow.core.model.PageFlipStyle.SIMULATION

    fun prepareBoundaryPageTurn(delta: Int): Boolean =
        prepareBoundaryPageTurnResult(delta) == BoundaryPageTurnPreparation.PREPARED

    fun prepareBoundaryPageTurnResult(delta: Int): BoundaryPageTurnPreparation {
        if (
            delta == 0 ||
            mode != Mode.PAGED ||
            paged.isEmpty() ||
            width == 0 ||
            height == 0 ||
            turnInFlight
        ) {
            return BoundaryPageTurnPreparation.NOT_ELIGIBLE
        }
        onPageTurnCapturePreparing?.invoke()
        val conversionCapture = conversionSnapshotCopy()
        if (conversionCapture === ConversionSnapshotCapture.Failed) {
            return BoundaryPageTurnPreparation.SNAPSHOT_UNAVAILABLE
        }
        val frozenOutgoing = (conversionCapture as? ConversionSnapshotCapture.Captured)?.bitmap
        finishInitialRevealForTurn()
        if (pageWindowForTurn(forward = delta > 0) != null) {
            frozenOutgoing?.let(::recyclePageShot)
            return BoundaryPageTurnPreparation.NOT_ELIGIBLE
        }
        val fromWindow = activePageWindow?.takeIf { it.topPx == scrollY }
        val warmedOutgoing = if (frozenOutgoing == null) {
            takeCachedFrontForBoundaryTurn(currentPage, scrollY, fromWindow)
        } else {
            null
        }
        val outgoing = frozenOutgoing ?: warmedOutgoing ?: snapshotViewport(
            PageShotLeaseKind.PINNED,
            "continuity.boundary",
            fullResolution = true,
        )
            ?: return BoundaryPageTurnPreparation.SNAPSHOT_UNAVAILABLE
        clearPendingBoundaryPageTurn()
        pendingBoundaryPageTurn = BoundaryPageTurn(
            forward = delta > 0,
            expectedChapterGeneration = chapterGeneration + 1,
        )
        // The same outgoing frame remains visibly installed while the adjacent chapter lays out. The
        // target chapter is alpha-hidden during that window, so without this owner the reader flashes blank.
        showConversionSnapshot(outgoing)
        boundaryContinuityCover = true
        return BoundaryPageTurnPreparation.PREPARED
    }

    fun goToPage(index: Int) {
        cancelFreeFlingForLifecycle()
        abortLocalPageShotTurnForExternalMutation()
        scrollToPage(index, report = true)
        rebaseInterruptedFreeFlingAtCurrentViewport()
        onPageSettled?.invoke()
        // Programmatic park (TOC/bookmark/search/F1 harness) must warm front+revealed without a
        // prior interactive settle animation that would otherwise call preCachePageTextures.
        if (mode == Mode.PAGED && isLayoutSettled()) {
            preCachePageTextures()
        }
    }

    private fun canonicalScrollTopForPage(index: Int): Int? {
        if (mode != Mode.PAGED || paged.isEmpty() || index !in paged.indices) return null
        return paged[index].topPx.coerceAtLeast(0)
    }

    private fun textureTopPxForPage(index: Int): Int? =
        canonicalScrollTopForPage(index)

    private fun capturePageTurnOrigin(): PageTurnOrigin {
        val alignedWindow = activePageWindow?.takeIf { it.topPx == scrollY }
        val state = if (alignedWindow != null) PagedMotionState.ALIGNED else PagedMotionState.FREE_REST
        return PageTurnOrigin(
            pageProjection = if (alignedWindow != null) currentPage else nearestCanonicalPageIndexForScrollY(scrollY),
            topPx = scrollY,
            clipActive = pageClipActive,
            motionState = state,
            window = alignedWindow,
        )
    }

    private fun restorePageTurnOrigin(origin: PageTurnOrigin) {
        currentPage = origin.pageProjection.coerceIn(0, paged.lastIndex.coerceAtLeast(0))
        pageClipActive = origin.clipActive
        if (scrollY != origin.topPx) scrollTo(0, origin.topPx)
        activePageWindow = origin.window
        pagedMotionState = origin.motionState
        invalidate()
    }

    private fun parkOnPageWindow(window: EpubFlowPage, report: Boolean) {
        currentPage = canonicalFloorPageIndexForTopPx(window.topPx)
        pageClipActive = true
        pagedMotionState = PagedMotionState.ALIGN_AND_TURN
        ensurePagedTopReachable(window.topPx)
        if (scrollY != window.topPx) scrollTo(0, window.topPx)
        activePageWindow = window
        pagedMotionState = PagedMotionState.ALIGNED
        if (report) reportTopOffset()
        invalidate()
    }

    private fun pageWindowForTurn(forward: Boolean): EpubFlowPage? {
        val layout = textView.layout ?: return null
        val f = flow ?: return null
        val geometry = EpubLayoutLineGeometry(layout)
        val aligned = activePageWindow?.takeIf { it.topPx == scrollY }
        val metadata = pageLayoutMetadataFor(layout)
        val canonicalIndex = canonicalPageIndexForWindow(aligned)
        if (canonicalIndex >= 0) {
            // Canonical pages already include heading keep-together, widow/orphan handling, and
            // separator-only filtering. Recomputing the reverse window with the looser dynamic
            // algorithm is not symmetric: repeated heading/image round trips can shrink the live
            // window until page clipping hides the text and then the image. Dynamic calculation is
            // reserved for FREE_REST and raw tail windows that are not members of [paged].
            return paged.getOrNull(canonicalIndex + if (forward) 1 else -1)
        }
        return if (forward) {
            val startLine = aligned?.endLineExclusive ?: (lastFullyVisibleLine(layout, scrollY) + 1)
            val headingLines = metadata?.headingLines ?: headingLineSet(layout, f)
            epubNextAuthoredPageFromStartLine(
                geometry = geometry,
                startLine = startLine,
                pageHeightPx = usablePageHeightPx(),
                contentSegments = f.segments,
                isHeadingLine = { it in headingLines },
                paragraphLineRange = { line ->
                    metadata?.paragraphLineRange(layout, line) ?: paragraphLineRange(layout, f, line)
                },
            )
        } else {
            val endLineExclusive = aligned?.startLine
                ?: layout.getLineForVertical(viewportTopInLayout(scrollY))
            epubPreviousAuthoredPageEndingAtLine(
                geometry = geometry,
                endLineExclusive = endLineExclusive,
                pageHeightPx = usablePageHeightPx(),
                contentSegments = f.segments,
            )
        }
    }

    private fun viewportTopInLayout(topPx: Int): Int =
        (topPx - textView.paddingTop).coerceAtLeast(0)

    private fun isOversizedLine(layout: Layout, line: Int): Boolean =
        layout.getLineBottom(line) - layout.getLineTop(line) > usablePageHeightPx()

    private fun keepsPartialViewportSlice(layout: Layout, line: Int): Boolean =
        isOversizedLine(layout, line) || isImageLine(layout, line)

    private fun isImageLine(layout: Layout, line: Int): Boolean {
        val text = layout.text as? Spanned ?: return false
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        return text.getSpans(start, end, ImageSpan::class.java).isNotEmpty() ||
            text.getSpans(start, end, AsyncDrawableSpan::class.java).isNotEmpty()
    }

    private fun lastFullyVisibleLine(layout: Layout, topPx: Int): Int {
        val bottomInLayout = topPx + usablePageHeightPx()
        var line = layout.getLineForVertical((bottomInLayout - 1).coerceAtLeast(0))
        val firstCandidate = layout.getLineForVertical(viewportTopInLayout(topPx))
        while (line > firstCandidate && layout.getLineBottom(line) > bottomInLayout) line--
        // An oversized single-line image/block is indivisible and advances as one logical line.
        return line.coerceIn(firstCandidate, layout.lineCount - 1)
    }

    private fun canonicalFloorPageIndexForTopPx(topPx: Int): Int {
        if (paged.isEmpty()) return 0
        var low = 0
        var high = paged.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (paged[middle].topPx <= topPx) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(0)
    }

    private fun canonicalPageIndexExactlyAt(topPx: Int): Int {
        if (paged.isEmpty()) return -1
        val index = canonicalFloorPageIndexForTopPx(topPx)
        return index.takeIf { paged[it].topPx == topPx } ?: -1
    }

    private fun canonicalPageIndexForWindow(window: EpubFlowPage?): Int {
        window ?: return -1
        paged.getOrNull(currentPage)?.takeIf { it == window }?.let { return currentPage }
        var index = canonicalPageIndexExactlyAt(window.topPx)
        while (index >= 0 && paged[index].topPx == window.topPx) {
            if (paged[index] == window) return index
            index--
        }
        return -1
    }

    private fun nearestCanonicalPageIndexForScrollY(y: Int): Int {
        if (paged.isEmpty()) return 0
        val maxScroll = (container.height - height).coerceAtLeast(0)
        val clamped = y.coerceIn(0, maxScroll)
        val lastIndex = paged.lastIndex
        if (clamped >= maxScroll && paged[lastIndex].topPx >= maxScroll) return lastIndex
        val floorIndex = canonicalFloorPageIndexForTopPx(clamped)
        val nextIndex = floorIndex + 1
        if (nextIndex > lastIndex) return floorIndex
        val floorTop = paged[floorIndex].topPx.coerceIn(0, maxScroll)
        val nextTop = paged[nextIndex].topPx.coerceIn(0, maxScroll)
        return if (clamped - floorTop <= nextTop - clamped) floorIndex else nextIndex
    }

    private fun ensurePagedTopReachable(topPx: Int) {
        if (mode != Mode.PAGED || height <= 0) return
        val requiredHeight = topPx.coerceAtLeast(0) + height
        if (requiredHeight > container.minimumScrollableHeightPx) {
            container.minimumScrollableHeightPx = requiredHeight
        }
    }

    private fun pagedScrollMaxPx(): Int =
        maxOf(
            paged.lastOrNull()?.topPx ?: 0,
            (container.minimumScrollableHeightPx - height).coerceAtLeast(0),
        )

    private fun snapToNearestCanonicalPageAnchor(report: Boolean): Int {
        if (mode != Mode.PAGED || paged.isEmpty()) return currentPage
        val targetPage = nearestCanonicalPageIndexForScrollY(scrollY)
        val targetTop = canonicalScrollTopForPage(targetPage) ?: return currentPage
        if (currentPage != targetPage || scrollY != targetTop) recycleCachedTextures()
        currentPage = targetPage
        pageClipActive = true
        if (scrollY != targetTop) scrollTo(0, targetTop)
        activePageWindow = paged[targetPage]
        pagedMotionState = PagedMotionState.ALIGNED
        if (report) reportTopOffset()
        invalidate()
        return currentPage
    }

    /**
     * Snaps to page [index]'s top. [report] gates the locator callback: the interactive curl parks the
     * incoming page (and snaps the outgoing one clean for the snapshot) BEFORE the turn is committed, so
     * those intermediate moves must stay silent — only the committed page reports its offset.
     */
    private fun scrollToPage(index: Int, report: Boolean) {
        if (mode != Mode.PAGED || paged.isEmpty()) return
        currentPage = index.coerceIn(0, paged.lastIndex)
        pageClipActive = true
        scrollTo(0, canonicalScrollTopForPage(currentPage) ?: paged[currentPage].topPx)
        activePageWindow = paged[currentPage]
        pagedMotionState = PagedMotionState.ALIGNED
        rebaseInterruptedFreeFlingAtCurrentViewport()
        if (report) reportTopOffset()
    }

    /**
     * Page turn with a hardware slide (滑动翻页): snapshot the current and target pages, jump the real
     * content to the target, then animate the two page shots as one strip (see [PageSlideDrawable]). Turns
     * instantly only when animation is off; a missing snapshot reports failure so queued input can retry.
     */
    private fun goToPageAnimated(targetWindow: EpubFlowPage, forward: Boolean): Boolean {
        val origin = capturePageTurnOrigin()
        val targetPage = canonicalFloorPageIndexForTopPx(targetWindow.topPx)
        if (turnInFlight) return false
        if (pageTurnAnimated && mode == Mode.PAGED && width > 0 && height > 0) {
            preparePinnedLocalWorkingPairBudget()
            onPageTurnCapturePreparing?.invoke()
        }
        val conversionCapture = conversionSnapshotCopy()
        if (conversionCapture === ConversionSnapshotCapture.Failed) return false
        var frozenOutgoing = (conversionCapture as? ConversionSnapshotCapture.Captured)?.bitmap
        finishInitialRevealForTurn()
        if (!pageTurnAnimated || mode != Mode.PAGED || width == 0 || height == 0) {
            frozenOutgoing?.let(::recyclePageShot)
            parkOnPageWindow(targetWindow, report = true)
            preCachePageTextures()
            return true
        }
        val targetTop = targetWindow.topPx
        val fromTop = origin.topPx
        val rapidTurn = rapidTurnSequenceActive
        val prefetchedTarget = if (frozenOutgoing == null && rapidTurn) {
            takeRapidFollowUpPageShot(targetPage, targetWindow, forward)
        } else {
            null
        }
        val cached = if (frozenOutgoing == null && !rapidTurn) {
            takeCachedTexturesForTurn(
                origin.pageProjection,
                fromTop,
                origin.window,
                targetPage,
                targetTop,
                targetWindow,
            )
        } else {
            null
        }
        val rapidOutgoing = if (frozenOutgoing == null && rapidTurn) {
            takeCachedFrontForBoundaryTurn(origin.pageProjection, fromTop, origin.window)
        } else {
            null
        }
        if (rapidTurn && frozenOutgoing != null) recycleCachedTextures()
        val outgoing = frozenOutgoing ?: rapidOutgoing ?: cached?.first ?: snapshotViewport(
            PageShotLeaseKind.PINNED,
            "active.front",
        ) ?: run {
            prefetchedTarget?.let(::recyclePageShot)
            cached?.second?.let(::recyclePageShot)
            return false
        }
        val revealed = prefetchedTarget ?: cached?.second ?: snapshotPageAt(
            targetTop,
            targetWindow,
            PageShotLeaseKind.PINNED,
            "active.target",
            fullResolution = frozenOutgoing != null,
        ) ?: run {
            recyclePageShot(outgoing)
            return false
        }
        // Park the incoming page silently beneath the page-shot overlay. Locator publication belongs
        // to the completed visual transaction; cancellation restores the exact outgoing viewport.
        parkOnPageWindow(targetWindow, report = false)
        onPageTurnTargetParked?.invoke()
        onPageTurnStarted?.invoke()
        startFlip(
            outgoing = outgoing,
            revealed = revealed,
            forward = forward,
            onCommitted = {
                retainSettledLocalPageShots(origin, targetWindow, forward, committed = true)
                reportTopOffset()
            },
            onCancelled = {
                retainSettledLocalPageShots(origin, targetWindow, forward, committed = false)
                restorePageTurnOrigin(origin)
            },
        )
        if (rapidTurnSequenceActive) scheduleRapidFollowUpPageShot(forward)
        return true
    }

    private fun allocatePageShot(
        widthPx: Int,
        heightPx: Int,
        kind: PageShotLeaseKind,
        label: String,
        config: Bitmap.Config = continuityPageShotConfig,
        draw: (Bitmap) -> Unit,
    ): Bitmap? {
        val bytesPerPixel = pageShotBytesPerPixel(config)
        var reservation = pageShotBudget.tryReserve(
            widthPx = widthPx,
            heightPx = heightPx,
            kind = kind,
            label = label,
            bytesPerPixel = bytesPerPixel,
        )
        if (reservation == null && kind == PageShotLeaseKind.PINNED) {
            evictSpeculativePageShotsForPinnedAllocation()
            onPinnedPageShotAdmissionNeeded?.invoke()
            val estimatedBytes = widthPx.toLong() * heightPx.toLong() * bytesPerPixel
            val activePairBytes = if (estimatedBytes > Long.MAX_VALUE / ACTIVE_PAGE_SHOT_PAIR_SIZE) {
                Long.MAX_VALUE
            } else {
                estimatedBytes * ACTIVE_PAGE_SHOT_PAIR_SIZE
            }
            // Only the two frames required by an active turn may exceed the soft device budget.
            // A third continuity/directional frame must fit the real budget or free/reuse an owner.
            val pinnedCeilingBytes = maxOf(pageShotBudget.capacityBytes, activePairBytes)
            if (pageShotBudget.chargedBytes + estimatedBytes > pinnedCeilingBytes) return null
            reservation = pageShotBudget.tryReserve(
                widthPx = widthPx,
                heightPx = heightPx,
                kind = kind,
                label = label,
                allowOverCapacity = true,
                bytesPerPixel = bytesPerPixel,
            )
        }
        val admittedReservation = reservation ?: return null
        var allocated: Bitmap? = null
        return try {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, config)
            allocated = bitmap
            val lease = admittedReservation.commit(bitmap, bitmap.allocationByteCount)
            if (lease == null) {
                bitmap.recycle()
                return null
            }
            // Full viewport/page recapture (not warm-cache transfer). Probe for interactive MOVE tests.
            EpubPageShotCaptureProbe.noteCapture()
            draw(bitmap)
            bitmap
        } catch (_: OutOfMemoryError) {
            admittedReservation.cancel()
            allocated?.let(::recyclePageShot)
            onPageShotOutOfMemory()
            null
        } catch (_: Throwable) {
            admittedReservation.cancel()
            allocated?.let(::recyclePageShot)
            null
        }
    }

    private fun recyclePageShot(bitmap: Bitmap) {
        pageShotBudget.release(bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun relabelPageShot(bitmap: Bitmap?, kind: PageShotLeaseKind, label: String) {
        bitmap?.takeUnless(Bitmap::isRecycled)?.let { pageShotBudget.relabel(it, kind, label) }
    }

    private fun drawPageBoundaryImagePreview(
        canvas: Canvas,
        pageTopPx: Int,
        window: EpubFlowPage,
        canvasViewportTopPx: Int,
    ) {
        val layout = textView.layout ?: return
        val chapter = flow ?: return
        val previews = pageLayoutMetadataFor(layout)?.pageBoundaryImagePreviews
            ?: pageBoundaryImagePreviews(layout, chapter)
        val preview = previews.firstOrNull { item ->
            // Body/heading may begin on an earlier page; match when this page holds the end of
            // preceding visible text and the indivisible image owns a later page.
            item.precedingEndLineExclusive > window.startLine &&
                item.precedingEndLineExclusive <= window.endLineExclusive &&
                item.imageLine >= window.endLineExclusive
        } ?: return

        // Prefer the bottom of the page's last visible content line (body or heading), so leftover
        // space under any preceding text can host the cropped next-page image top.
        //
        // Do NOT raise the crop to layout.getLineTop(imageLine): that coordinate is on a later page
        // (often >> viewport height). maxOf(precedingBottom, imageTop) collapsed the crop band to
        // empty and left a title-only blank remainder.
        val lastWindowLine = (window.endLineExclusive - 1).coerceAtLeast(window.startLine)
        val precedingBottom =
            layout.getLineBottom(lastWindowLine) + textView.paddingTop - pageTopPx
        val previewTopInViewport = precedingBottom.coerceIn(0, height)
        val previewBottomInViewport = (height - textView.paddingBottom).coerceAtLeast(previewTopInViewport)
        if (previewBottomInViewport <= previewTopInViewport) return

        val imageLineHeight = layout.getLineBottom(preview.imageLine) -
            layout.getLineTop(preview.imageLine)
        // Crop only when the full image line is taller than the leftover band (indivisible image).
        if (imageLineHeight <= previewBottomInViewport - previewTopInViewport) return
        // Markwon AsyncDrawable.draw() only forwards to result.draw and never copies shell setBounds
        // onto the nested result. Prefer generation-owned host from metadata; paint decoded
        // BitmapDrawable pixels via canvas.drawBitmap at the crop destination (bounds-only draw
        // leaves leftover blank: shell setBounds does not move result, and LEGACY Robolectric
        // paints BitmapDrawable at 0,0).
        val host = preview.imageDrawableHost ?: return
        val result = (host as? AsyncDrawable)?.result
        val paintTarget = result ?: host
        val sourceWidth = paintTarget.bounds.width().takeIf { it > 0 }
            ?: paintTarget.intrinsicWidth.takeIf { it > 0 }
            ?: host.bounds.width().takeIf { it > 0 }
            ?: host.intrinsicWidth.takeIf { it > 0 }
            ?: return
        val sourceHeight = paintTarget.bounds.height().takeIf { it > 0 }
            ?: paintTarget.intrinsicHeight.takeIf { it > 0 }
            ?: host.bounds.height().takeIf { it > 0 }
            ?: host.intrinsicHeight.takeIf { it > 0 }
            ?: return
        val availableWidth = (width - textView.paddingLeft - textView.paddingRight).coerceAtLeast(1)
        val scale = minOf(1f, availableWidth.toFloat() / sourceWidth)
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val left = textView.paddingLeft + (availableWidth - targetWidth) / 2
        val previewTop = canvasViewportTopPx + previewTopInViewport
        val previewBottom = canvasViewportTopPx + previewBottomInViewport
        pageBoundaryDestination.set(left, previewTop, left + targetWidth, previewTop + targetHeight)

        val save = canvas.save()
        try {
            canvas.clipRect(left, previewTop, left + targetWidth, previewBottom)
            paintCropImageInto(canvas, paintTarget, host, result, pageBoundaryDestination)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /**
     * Paints [paintTarget] into [dst] for the page-boundary crop. Bitmap pixels are blitted at
     * [dst] explicitly; other drawables use temporary bounds + [Drawable.draw].
     */
    private fun paintCropImageInto(
        canvas: Canvas,
        paintTarget: Drawable,
        host: Drawable,
        result: Drawable?,
        dst: Rect,
    ) {
        if (paintTarget is EpubImagePixelSource) {
            paintTarget.drawPixels(canvas, dst)
            return
        }
        val bitmap = (paintTarget as? BitmapDrawable)
            ?.bitmap
            ?.takeUnless { it.isRecycled }
        if (bitmap != null) {
            // Scale directly on Canvas. Creating a resized bitmap here would add a full image
            // allocation to page preview/snapshot rendering and reintroduce turn-time jank.
            canvas.drawBitmap(bitmap, null, dst, pageBoundaryBitmapPaint)
            return
        }
        // Allocation-free scratch (single main-thread draw path).
        EpubBoundaryImageHostProbe.noteNonBitmapBoundsCopy()
        pageBoundaryHostBoundsScratch.set(host.bounds)
        val resultDrawable = result
        if (resultDrawable != null) {
            pageBoundaryResultBoundsScratch.set(resultDrawable.bounds)
        }
        try {
            paintTarget.bounds = dst
            if (resultDrawable != null) host.bounds = dst
            paintTarget.draw(canvas)
        } finally {
            if (resultDrawable != null) {
                resultDrawable.bounds = pageBoundaryResultBoundsScratch
                host.bounds = pageBoundaryHostBoundsScratch
            } else {
                host.bounds = pageBoundaryHostBoundsScratch
            }
        }
    }

    /**
     * Span host drawable at [layoutOffset]: AsyncDrawable shell (result may be nested) or ImageSpan.
     * Crop painting must prefer [AsyncDrawable.result] when present. Call only from page-layout
     * metadata build (generation-owned cache), not from the live/page-shot draw hot path.
     */
    private fun imageDrawableHostAt(layoutOffset: Int): Drawable? {
        EpubBoundaryImageHostProbe.noteSpanHostLookup()
        val text = textView.text as? Spanned ?: return null
        val end = (layoutOffset + 1).coerceAtMost(text.length)
        text.getSpans(layoutOffset, end, AsyncDrawableSpan::class.java)
            .firstOrNull()
            ?.drawable
            ?.let { return it }
        return text.getSpans(layoutOffset, end, ImageSpan::class.java)
            .firstOrNull()
            ?.drawable
    }

    /** Full-size diagnostic capture retained for reflection-backed pixel/continuity tests. */
    private fun snapshotViewport(): Bitmap? =
        snapshotViewport(PageShotLeaseKind.EVICTABLE, "local.viewport", fullResolution = true)

    private fun snapshotViewport(
        kind: PageShotLeaseKind,
        label: String,
        fullResolution: Boolean = false,
    ): Bitmap? {
        if (width == 0 || height == 0) return null
        val shotWidth = if (fullResolution) width else motionPageShotWidthPx()
        val shotHeight = if (fullResolution) height else motionPageShotHeightPx()
        val config = if (fullResolution) continuityPageShotConfig else motionPageShotConfig()
        return allocatePageShot(shotWidth, shotHeight, kind, label, config) { bmp ->
            val canvas = Canvas(bmp)
            scalePageShotCanvasToViewport(canvas, bmp)
            drawSnapshotBackground(canvas)
            val save = canvas.save()
            // Public View.draw(Canvas) does not apply the -scroll transform supplied by the normal
            // parent/ViewRoot draw path. dispatchDraw expects that content-space transform already.
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            dispatchDraw(canvas)
            canvas.restoreToCount(save)
            activePageWindow?.takeIf { it.topPx == scrollY }
                ?.let { drawPageBoundaryImagePreview(canvas, scrollY, it, canvasViewportTopPx = 0) }
        }
    }

    /**
     * Renders the PAGED page whose content top is [topPx] into a fresh bitmap (theme bg + that page's
     * lines, clipped to the page's last fully-fitting line). Used to prewarm local page-turn shots for
     * an arbitrary page without moving the live scroll position. Null on OOM or if the view isn't
     * measured / not paged.
     */
    fun snapshotPageAt(topPx: Int): Bitmap? =
        snapshotPageAt(
            topPx,
            settledWindowAtTop(topPx),
            PageShotLeaseKind.EVICTABLE,
            "local.page",
            fullResolution = true,
        )

    private fun snapshotPageAt(
        topPx: Int,
        window: EpubFlowPage?,
    ): Bitmap? = snapshotPageAt(topPx, window, PageShotLeaseKind.EVICTABLE, "local.page")

    private fun snapshotPageAt(
        topPx: Int,
        window: EpubFlowPage?,
        kind: PageShotLeaseKind,
        label: String,
        fullResolution: Boolean = false,
    ): Bitmap? {
        if (width == 0 || height == 0) return null
        val shotWidth = if (fullResolution) width else motionPageShotWidthPx()
        val shotHeight = if (fullResolution) height else motionPageShotHeightPx()
        val config = if (fullResolution) continuityPageShotConfig else motionPageShotConfig()
        return allocatePageShot(shotWidth, shotHeight, kind, label, config) { bmp ->
            val canvas = Canvas(bmp)
            scalePageShotCanvasToViewport(canvas, bmp)
            drawSnapshotBackground(canvas)
            val contentSave = canvas.save()
            canvas.translate(0f, -topPx.toFloat())
            val clipTop = snapshotClipTopFor(topPx, window)
            val clipBottom = snapshotClipBottomFor(topPx, window)
            canvas.clipRect(0, clipTop, width, clipBottom)
            val skipContentDraw = container.skipContentDraw
            container.skipContentDraw = false
            try {
                container.draw(canvas)
            } finally {
                container.skipContentDraw = skipContentDraw
            }
            canvas.restoreToCount(contentSave)
            window?.let { drawPageBoundaryImagePreview(canvas, topPx, it, canvasViewportTopPx = 0) }
        }
    }

    private fun scalePageShotCanvasToViewport(canvas: Canvas, bitmap: Bitmap) {
        canvas.scale(
            bitmap.width.toFloat() / width.toFloat(),
            bitmap.height.toFloat() / height.toFloat(),
        )
    }

    private fun isCurrentPageShotSize(bitmap: Bitmap): Boolean {
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return false
        return (bitmap.width == width && bitmap.height == height) ||
            (bitmap.width == motionPageShotWidthPx() && bitmap.height == motionPageShotHeightPx())
    }

    private fun drawSnapshotBackground(canvas: Canvas) {
        drawViewportBackgroundAtOrigin(canvas)
    }

    private fun drawLiveViewportBackground(canvas: Canvas) {
        val save = canvas.save()
        // The normal ViewRoot/parent draw path has already translated this ScrollView by -scroll.
        // Cancel that transform for the viewport-owned paper so its phase never follows the content.
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        drawViewportBackgroundAtOrigin(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawViewportBackgroundAtOrigin(canvas: Canvas) {
        viewportBackground?.let { bg ->
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
        }
    }

    private fun snapshotClipTopFor(topPx: Int): Int =
        snapshotClipTopFor(topPx, settledWindowAtTop(topPx))

    private fun snapshotClipTopFor(topPx: Int, window: EpubFlowPage?): Int {
        val layout = textView.layout ?: return topPx
        if (window?.topPx == topPx) {
            return (topPx + textView.paddingTop).coerceAtMost(topPx + height)
        }
        val viewportTopInLayout = (topPx - textView.paddingTop).coerceAtLeast(0)
        var line = layout.getLineForVertical(viewportTopInLayout)
        if (layout.getLineTop(line) < viewportTopInLayout) {
            if (keepsPartialViewportSlice(layout, line)) return topPx
            line++
        }
        if (line >= layout.lineCount) return topPx + height
        return (layout.getLineTop(line) + textView.paddingTop).coerceAtMost(topPx + height)
    }

    private fun snapshotClipBottomFor(topPx: Int, window: EpubFlowPage?): Int {
        val layout = textView.layout ?: return topPx + height
        window?.takeIf { it.topPx == topPx }?.let { settled ->
            val clipBottom = (settled.bottomPx - topPx).coerceAtLeast(0)
            if (
                settled.startLine + 1 == settled.endLineExclusive &&
                clipBottom > usablePageHeightPx()
            ) return topPx + height
            return snapshotClipBottomFor(topPx, clipBottom)
        }
        val pageBottom = topPx + usablePageHeightPx()
        val viewportTopInLayout = (topPx - textView.paddingTop).coerceAtLeast(0)
        var firstLine = layout.getLineForVertical(viewportTopInLayout)
        if (
            layout.getLineTop(firstLine) < viewportTopInLayout &&
            !keepsPartialViewportSlice(layout, firstLine)
        ) firstLine++
        if (firstLine >= layout.lineCount) return topPx
        var line = layout.getLineForVertical(pageBottom - 1)
        if (
            line > firstLine &&
            layout.getLineBottom(line) > pageBottom &&
            !keepsPartialViewportSlice(layout, line)
        ) line--
        val rawClipBottom = layout.getLineBottom(line) - topPx
        if (line == firstLine && rawClipBottom > usablePageHeightPx()) return topPx + height
        val clipBottom = rawClipBottom.coerceIn(0, height)
        return snapshotClipBottomFor(topPx, clipBottom)
    }

    private fun snapshotClipBottomFor(topPx: Int, clipBottom: Int): Int =
        minOf(
            topPx + clipBottom + textView.paddingTop,
            topPx + height - textView.paddingBottom,
        )

    private fun settledWindowAtTop(topPx: Int): EpubFlowPage? =
        activePageWindow?.takeIf { it.topPx == topPx }
            ?: canonicalPageIndexExactlyAt(topPx).takeIf { it >= 0 }?.let(paged::get)

    private fun pageTextureKey(topPx: Int, window: EpubFlowPage?): PageTextureKey =
        PageTextureKey(
            topPx = topPx,
            clipTopPx = snapshotClipTopFor(topPx, window),
            clipBottomPx = snapshotClipBottomFor(topPx, window),
            viewportWidthPx = width,
            viewportHeightPx = height,
            layoutGeneration = pageLayoutGeneration,
        )

    /** Content-top px of paged index [index], or null if out of range / not paged. */
    fun pageTopPxAt(index: Int): Int? =
        if (mode == Mode.PAGED && index in paged.indices) paged[index].topPx else null

    /** The paged index currently parked at the top of the viewport (Moon+ anchor). */
    fun currentPagedIndex(): Int {
        if (mode != Mode.PAGED || paged.isEmpty()) return 0
        val maxScroll = (container.height - height).coerceAtLeast(0)
        return if (scrollY >= maxScroll) paged.lastIndex
        else canonicalFloorPageIndexForTopPx(scrollY)
    }


    /**
     * Drives the active page-turn animation to [progress] (0 = outgoing covers viewport, 1 = complete).
     * SLIDE: the outgoing snapshot moves inside [PageSlideDrawable] AND the incoming page (the real
     * [container]) is already parked on the target page. Both normal and rapid turns use one frozen
     * outgoing/target pair so a visual transaction cannot mix image quality generations. SIMULATION
     * draws the revealed shot flat inside [PageCurlDrawable], while only the outgoing shot warps over it.
     */
    private fun applyFlipProgress(progress: Float, forward: Boolean) {
        slideDrawable?.let {
            it.progress = progress
        }
        curlDrawable?.progress = progress
        container.translationX = 0f
        container.translationY = 0f
    }

    private fun takeActiveFlipBitmaps(): Pair<Bitmap, Bitmap>? {
        val front = slideDrawable?.takeFrontBitmap() ?: curlDrawable?.takeFrontBitmap()
        val revealed = slideDrawable?.takeRevealedBitmap() ?: curlDrawable?.takeRevealedBitmap()
        if (front == null || revealed == null) {
            front?.let(::recyclePageShot)
            revealed?.let(::recyclePageShot)
            return null
        }
        return front to revealed
    }

    private fun retainSettledLocalPageShots(
        origin: PageTurnOrigin,
        targetWindow: EpubFlowPage,
        forward: Boolean,
        committed: Boolean,
    ) {
        val (front, revealed) = takeActiveFlipBitmaps() ?: return
        recycleCachedTextures(preserveRapidFollowUp = true)
        val targetPage = canonicalFloorPageIndexForTopPx(targetWindow.topPx)
        val originKey = pageTextureKey(origin.topPx, origin.window)
        val targetKey = pageTextureKey(targetWindow.topPx, targetWindow)
        if (committed) {
            cachedFrontBitmap = revealed
            cachedFromPage = targetPage
            cachedFromTopPx = targetWindow.topPx
            cachedFromTextureKey = targetKey
            if (forward) {
                cachedBackwardBitmap = front
                cachedBackwardPage = origin.pageProjection
                cachedBackwardTopPx = origin.topPx
                cachedBackwardTextureKey = originKey
            } else {
                cachedRevealedBitmap = front
                cachedTargetPage = origin.pageProjection
                cachedTargetTopPx = origin.topPx
                cachedTargetTextureKey = originKey
            }
        } else {
            cachedFrontBitmap = front
            cachedFromPage = origin.pageProjection
            cachedFromTopPx = origin.topPx
            cachedFromTextureKey = originKey
            if (forward) {
                cachedRevealedBitmap = revealed
                cachedTargetPage = targetPage
                cachedTargetTopPx = targetWindow.topPx
                cachedTargetTextureKey = targetKey
            } else {
                cachedBackwardBitmap = revealed
                cachedBackwardPage = targetPage
                cachedBackwardTopPx = targetWindow.topPx
                cachedBackwardTextureKey = targetKey
            }
        }
        relabelPageShot(cachedFrontBitmap, PageShotLeaseKind.EVICTABLE, "cache.current")
        relabelPageShot(cachedRevealedBitmap, PageShotLeaseKind.EVICTABLE, "cache.forward")
        relabelPageShot(cachedBackwardBitmap, PageShotLeaseKind.EVICTABLE, "cache.backward")
        trimStablePageShotsToBudget()
        restoreActiveFlipPixelRefreshes(front, revealed)
    }

    private fun restoreActiveFlipPixelRefreshes(front: Bitmap, revealed: Bitmap) {
        val refreshFront = activeFlipFrontPixelRefreshPending
        val refreshRevealed = activeFlipRevealedPixelRefreshPending
        activeFlipFrontPixelRefreshPending = false
        activeFlipRevealedPixelRefreshPending = false
        if (refreshFront) queueCachedBitmapForPixelRefresh(front)
        if (refreshRevealed) queueCachedBitmapForPixelRefresh(revealed)
    }

    private fun queueCachedBitmapForPixelRefresh(bitmap: Bitmap) {
        when {
            cachedFrontBitmap === bitmap -> pendingInPlacePageShotRefreshSlots += CachedPageShotSlot.FRONT
            cachedRevealedBitmap === bitmap -> pendingInPlacePageShotRefreshSlots += CachedPageShotSlot.REVEALED
            cachedBackwardBitmap === bitmap -> pendingInPlacePageShotRefreshSlots += CachedPageShotSlot.BACKWARD
        }
    }

    private fun trimStablePageShotsToBudget() {
        while (pageShotBudget.chargedBytes > pageShotBudget.capacityBytes) {
            val victim = cachedBackwardBitmap ?: cachedRevealedBitmap ?: cachedFrontBitmap ?: return
            detachCachedTextureOwner(victim)
            recyclePageShot(victim)
        }
    }

    private fun retainInteractiveLocalPageShots(committed: Boolean, forward: Boolean) {
        val origin = curlOrigin ?: return
        val targetWindow = curlTargetWindow ?: return
        retainSettledLocalPageShots(origin, targetWindow, forward, committed)
    }

    private fun startFlip(
        outgoing: Bitmap,
        revealed: Bitmap,
        forward: Boolean,
        onCommitted: () -> Unit = {},
        onCancelled: () -> Unit = {},
    ) {
        flipAnimator?.cancel()
        clearFlipOverlay(preserveActivePixelRefreshes = true)
        // The overlay draws in content coords (canvas translated by scrollY). After [goToPage] the
        // content is parked on the incoming page, so scrollY is the new viewport top — place the overlay
        // there so it covers exactly what's on screen (each drawable translates to its bounds internally).
        val bounds = intArrayOf(0, scrollY, width, scrollY + height)
        if (useSimulationDiscreteRenderer()) {
            // CoolReader-PAPER-style local sine strips: flat body, flexible moving edge, no 3D.
            val drawable = PageCurlDrawable(
                outgoing,
                checkNotNull(revealed) { "PAPER turns require a revealed page artifact" },
                width,
                height,
                forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(bounds[0], bounds[1], bounds[2], bounds[3])
            overlay.add(drawable)
            curlDrawable = drawable
        } else {
            val drawable = PageSlideDrawable(
                outgoing, revealed, width, height, forward, density, ::recyclePageShot,
            )
            drawable.setBounds(bounds[0], bounds[1], bounds[2], bounds[3])
            overlay.add(drawable)
            slideDrawable = drawable
        }
        applyFlipProgress(0f, forward)
        flipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = activeFlipDurationMs()
            interpolator = activeFlipInterpolator()
            addUpdateListener { a -> applyFlipProgress(a.animatedValue as Float, forward) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    onCommitted()
                    clearFlipOverlay()
                    continueQueuedTurnsOrPrecache()
                    onPageSettled?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    onCancelled()
                    clearFlipOverlay()
                    continueQueuedTurnsOrPrecache()
                    onPageSettled?.invoke()
                }
            })
            start()
        }
    }

    /**
     * Gives an external viewport mutation final ownership. Unlike an input CANCEL, this must not
     * restore the gesture origin or publish the page silently parked beneath the overlay.
     */
    private fun abortLocalPageShotTurnForExternalMutation(
        restoreOrigin: Boolean = false,
        preserveQueuedTurns: Boolean = false,
    ) {
        if (deferredBoundaryFinishCommit) {
            finishBoundaryInteractiveTurn(commit = false, resumeIdle = false)
            busyPageTurnGesture = false
        }
        if (!preserveQueuedTurns) clearQueuedPageTurns()
        cancelPendingLocalPageShotHandoff(consumeGesture = true)
        val state = interactiveTurnState
        if (
            state != InteractiveTurnState.SOFTWARE &&
            state != InteractiveTurnState.SOFTWARE_SETTLING
        ) return
        val origin = curlOrigin
        val settlingCommit = localSoftwareSettleCommit
        flipAnimator?.removeAllUpdateListeners()
        flipAnimator?.removeAllListeners()
        flipAnimator?.cancel()
        flipAnimator = null
        interactiveTurnState = InteractiveTurnState.NONE
        if (restoreOrigin) {
            when {
                state == InteractiveTurnState.SOFTWARE -> origin?.let(::restorePageTurnOrigin)
                settlingCommit == true -> reportTopOffset()
                else -> Unit // A cancelled settle already restored its origin before animation.
            }
        }
        curlOrigin = null
        curlTargetWindow = null
        localSoftwareSettleCommit = null
        clearFlipOverlay()
        flipped = true
    }

    private fun clearFlipOverlay(preserveActivePixelRefreshes: Boolean = false) {
        cancelPendingLocalPageShotHandoff(consumeGesture = true)
        slideDrawable?.let {
            overlay.remove(it)
            it.recycle()
        }
        slideDrawable = null
        curlDrawable?.let {
            overlay.remove(it)
            it.recycle()
        }
        curlDrawable = null
        if (!preserveActivePixelRefreshes) clearActiveFlipPixelRefreshes()
        if (
            interactiveTurnState == InteractiveTurnState.SOFTWARE ||
            interactiveTurnState == InteractiveTurnState.SOFTWARE_SETTLING ||
            interactiveTurnState == InteractiveTurnState.BOUNDARY_SOFTWARE ||
            interactiveTurnState == InteractiveTurnState.BOUNDARY_DISCRETE_ACTIVE
        ) {
            interactiveTurnState = InteractiveTurnState.NONE
        }
        localSoftwareSettleCommit = null
        // Re-centre the parked live content after any previous turn path.
        container.translationX = 0f
        container.translationY = 0f
    }

    private fun showConversionSnapshot(bitmap: Bitmap?) {
        clearConversionSnapshot()
        bitmap ?: return
        relabelPageShot(bitmap, PageShotLeaseKind.PINNED, "continuity.cover")
        val drawable = ViewportSnapshotDrawable(bitmap, ::recyclePageShot)
        conversionSnapshotDrawable = drawable
        positionConversionSnapshot()
        overlay.add(drawable)
    }

    private fun conversionSnapshotCopy(): ConversionSnapshotCapture {
        val cover = conversionSnapshotDrawable ?: return ConversionSnapshotCapture.NoCover
        if (cover.alphaValue >= 255) {
            val copy = allocatePageShot(
                cover.bitmapWidth,
                cover.bitmapHeight,
                PageShotLeaseKind.PINNED,
                "continuity.copy",
            ) { cover.copyInto(it) } ?: return ConversionSnapshotCapture.Failed
            return ConversionSnapshotCapture.Captured(copy)
        }

        val liveComposition = snapshotViewport(
            PageShotLeaseKind.PINNED,
            "continuity.composition",
            fullResolution = true,
        ) ?: return ConversionSnapshotCapture.Failed
        if (cover.alphaValue <= 0 || conversionSnapshotFlattener(cover, liveComposition)) {
            return ConversionSnapshotCapture.Captured(liveComposition)
        }

        recyclePageShot(liveComposition)
        return ConversionSnapshotCapture.Failed
    }

    private fun positionConversionSnapshot() {
        // ViewOverlay is drawn in this ScrollView's content coordinates on Android runtime. Keep the
        // viewport-sized page shot over the visible scroll window while the live paged frame settles.
        conversionSnapshotDrawable?.setBounds(0, scrollY, width, scrollY + height)
    }

    private fun resetConversionSnapshotFade() {
        conversionFadeAnimator?.cancel()
        conversionFadeAnimator = null
        removeCallbacks(conversionSnapshotClearRunnable)
        conversionSnapshotDrawable?.alphaValue = 255
    }

    private fun clearConversionSnapshot() {
        conversionFadeAnimator?.cancel()
        conversionFadeAnimator = null
        removeCallbacks(conversionSnapshotClearRunnable)
        conversionSnapshotDrawable?.let {
            overlay.remove(it)
            it.recycle()
        }
        conversionSnapshotDrawable = null
        boundaryContinuityCover = false
    }

    private fun consumePendingBoundaryPageTurn(): Boolean {
        val turn = pendingBoundaryPageTurn ?: return false
        if (turn.expectedChapterGeneration != chapterGeneration) {
            clearPendingBoundaryPageTurn()
            return false
        }
        pendingBoundaryPageTurn = null
        onPageTurnCapturePreparing?.invoke()
        val conversionCapture = conversionSnapshotCopy()
        if (conversionCapture === ConversionSnapshotCapture.Failed) return false
        val outgoing = (conversionCapture as? ConversionSnapshotCapture.Captured)?.bitmap
        clearConversionSnapshot()
        if (outgoing == null) return false
        if (!pageTurnAnimated || mode != Mode.PAGED || width == 0 || height == 0 || turnInFlight) {
            recyclePageShot(outgoing)
            return false
        }
        val revealed = snapshotViewport(PageShotLeaseKind.PINNED, "active.target") ?: run {
            recyclePageShot(outgoing)
            return false
        }
        onPageTurnStarted?.invoke()
        startFlip(outgoing, revealed, turn.forward)
        return true
    }

    private fun clearPendingBoundaryPageTurn() {
        pendingBoundaryPageTurn = null
    }

    // ---- Finger-tracking interactive slide -----------------------------------------------------

    /** Test/direct entry retains the synchronous fallback; real MOVE input uses the overload below. */
    private fun beginInteractiveCurl(
        forward: Boolean,
        axis: InteractiveTurnAxis,
        anchor: Float,
    ): InteractiveTurnStartResult = beginInteractiveCurl(
        forward = forward,
        axis = axis,
        anchor = anchor,
        latestX = null,
        latestY = null,
    )

    /**
     * Begins a finger-driven local turn. Warm shots transfer immediately. On a cold gesture, target and
     * visible-front shots are rendered on separate animation frames and the held finger resumes at its
     * latest MOVE coordinate; the input thread never draws two full-screen bitmaps in the threshold MOVE.
     */
    private fun beginInteractiveCurl(
        forward: Boolean,
        axis: InteractiveTurnAxis,
        anchor: Float,
        latestX: Float?,
        latestY: Float?,
    ): InteractiveTurnStartResult {
        if (disposed || !pageTurnAnimated || mode != Mode.PAGED || paged.isEmpty() || width == 0 || height == 0) {
            return InteractiveTurnStartResult.REJECTED
        }
        if (boundaryContinuityCover && awaitingStableChapter) return InteractiveTurnStartResult.REJECTED
        if (turnInFlight) return InteractiveTurnStartResult.REJECTED
        val origin = capturePageTurnOrigin()
        val targetWindow = pageWindowForTurn(forward)
        if (targetWindow == null) {
            val preview = takeBoundaryPreview(forward)
            if (preview == null) {
                val requestPreview = onBoundaryPreviewNeeded ?: return InteractiveTurnStartResult.REJECTED
                interactiveTurnState = InteractiveTurnState.BOUNDARY_WAITING
                waitingBoundaryForward = forward
                waitingBoundaryAxis = axis
                waitingBoundaryAnchor = anchor
                requestPreview(forward, boundaryPreviewGeneration)
                return InteractiveTurnStartResult.WAITING
            }
            return startBoundaryInteractiveCurl(
                preview = preview,
                origin = origin,
                axis = axis,
                anchor = anchor,
            )
        }
        onPageTurnCapturePreparing?.invoke()
        preparePinnedLocalWorkingPairBudget()
        val deferColdShots = latestX != null && latestY != null
        val targetTop = targetWindow.topPx
        val targetPage = canonicalFloorPageIndexForTopPx(targetTop)
        if (deferColdShots && conversionSnapshotDrawable != null) {
            return beginPendingLocalPageShotHandoff(
                origin = origin,
                targetWindow = targetWindow,
                targetPage = targetPage,
                forward = forward,
                axis = axis,
                anchor = anchor,
                latestX = latestX,
                latestY = latestY,
            )
        }
        val conversionCapture = if (deferColdShots) {
            ConversionSnapshotCapture.NoCover
        } else {
            conversionSnapshotCopy()
        }
        if (conversionCapture === ConversionSnapshotCapture.Failed) {
            flipped = true
            restorePageTurnOrigin(origin)
            return InteractiveTurnStartResult.REJECTED
        }
        val frozenOutgoing = (conversionCapture as? ConversionSnapshotCapture.Captured)?.bitmap
        var seededPartialFront: Bitmap? = null
        val cached = if (frozenOutgoing == null) {
            // Real finger deferred path: retain a matching staged front before takeCachedTexturesForTurn
            // would discard an incomplete precache owner.
            if (deferColdShots) {
                seededPartialFront = takePartialPendingFrontForDeferredLocalHandoff(
                    origin.pageProjection,
                    origin.topPx,
                    origin.window,
                    targetPage,
                    targetTop,
                    targetWindow,
                )
            }
            takeCachedTexturesForTurn(
                origin.pageProjection,
                origin.topPx,
                origin.window,
                targetPage,
                targetTop,
                targetWindow,
            )
        } else {
            null
        }
        if (deferColdShots && frozenOutgoing == null && cached == null) {
            return beginPendingLocalPageShotHandoff(
                origin = origin,
                targetWindow = targetWindow,
                targetPage = targetPage,
                forward = forward,
                axis = axis,
                anchor = anchor,
                latestX = latestX,
                latestY = latestY,
                frontBitmap = seededPartialFront,
            )
        }
        // Complete-pair / synchronous path must not keep a detached front (extract returns null then).
        seededPartialFront?.let(::recyclePageShot)
        val outgoing = frozenOutgoing ?: cached?.first ?: snapshotViewport(
            PageShotLeaseKind.PINNED,
            "active.front",
        ) ?: run {
            cached?.second?.let(::recyclePageShot)
            restorePageTurnOrigin(origin)
            return InteractiveTurnStartResult.REJECTED
        }
        val revealed = cached?.second ?: snapshotPageAt(
            targetTop,
            targetWindow,
            PageShotLeaseKind.PINNED,
            "active.target",
        ) ?: run {
            recyclePageShot(outgoing)
            restorePageTurnOrigin(origin)
            return InteractiveTurnStartResult.REJECTED
        }
        return startLocalInteractiveCurl(
            origin = origin,
            targetWindow = targetWindow,
            forward = forward,
            axis = axis,
            anchor = anchor,
            outgoing = outgoing,
            revealed = revealed,
        )
    }

    private fun beginPendingLocalPageShotHandoff(
        origin: PageTurnOrigin,
        targetWindow: EpubFlowPage,
        targetPage: Int,
        forward: Boolean,
        axis: InteractiveTurnAxis,
        anchor: Float,
        latestX: Float,
        latestY: Float,
        frontBitmap: Bitmap? = null,
    ): InteractiveTurnStartResult {
        // A queued background precache must not race the two finger-owned shots or increase peak memory.
        // Seeded front was already detached from pending/cache before this call.
        recycleCachedTextures()
        val token = localPageShotHandoffGeneration + 1L
        localPageShotHandoffGeneration = token
        val request = PendingLocalPageShotHandoff(
            token = token,
            origin = origin,
            targetWindow = targetWindow,
            targetPage = targetPage,
            forward = forward,
            axis = axis,
            anchor = anchor,
            viewportWidthPx = width,
            viewportHeightPx = height,
            pageGeneration = pageLayoutGeneration,
            chapterGeneration = chapterGeneration,
            fromTextureKey = pageTextureKey(origin.topPx, origin.window),
            targetTextureKey = pageTextureKey(targetWindow.topPx, targetWindow),
            latestX = latestX,
            latestY = latestY,
            frontBitmap = frontBitmap,
        )
        pendingLocalPageShotHandoff = request
        interactiveTurnState = InteractiveTurnState.LOCAL_SHOTS_WAITING
        clearPendingLocalPageShotFeedback()
        postPendingLocalTargetShot(request)
        return InteractiveTurnStartResult.WAITING
    }

    private fun pendingLocalPageShotHandoffIsValid(request: PendingLocalPageShotHandoff): Boolean {
        if (
            disposed ||
            pendingLocalPageShotHandoff !== request ||
            request.token != localPageShotHandoffGeneration ||
            !localShotsWaiting ||
            !pageTurnAnimated ||
            mode != Mode.PAGED ||
            width != request.viewportWidthPx ||
            height != request.viewportHeightPx ||
            pageLayoutGeneration != request.pageGeneration ||
            chapterGeneration != request.chapterGeneration ||
            scrollY != request.origin.topPx ||
            pageClipActive != request.origin.clipActive ||
            activePageWindow != request.origin.window ||
            textView.isLayoutRequested ||
            textView.layout?.height != paginatedLayoutHeight ||
            pendingDecodesProvider?.invoke() == true
        ) return false
        if (pageTextureKey(request.origin.topPx, request.origin.window) != request.fromTextureKey) return false
        val currentTarget = pageWindowForTurn(request.forward) ?: return false
        return currentTarget == request.targetWindow &&
            canonicalFloorPageIndexForTopPx(currentTarget.topPx) == request.targetPage &&
            pageTextureKey(currentTarget.topPx, currentTarget) == request.targetTextureKey
    }

    private fun capturePendingLocalTargetShot(request: PendingLocalPageShotHandoff) {
        if (pendingLocalPageShotHandoff !== request || request.token != localPageShotHandoffGeneration) return
        if (request.targetBitmap != null) return
        if (!pendingLocalPageShotHandoffIsValid(request)) {
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        val target = snapshotPageAt(
            request.targetWindow.topPx,
            request.targetWindow,
            PageShotLeaseKind.PINNED,
            "active.target",
        )
        if (target == null) {
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        if (!pendingLocalPageShotHandoffIsValid(request)) {
            recyclePageShot(target)
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        request.targetBitmap = target
        postPendingLocalFrontShot(request)
    }

    private fun capturePendingLocalFrontShotAndResume(request: PendingLocalPageShotHandoff) {
        if (pendingLocalPageShotHandoff !== request || request.token != localPageShotHandoffGeneration) return
        val target = request.targetBitmap ?: run {
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        if (!pendingLocalPageShotHandoffIsValid(request)) {
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        // Keep this defensive reset at the ownership handoff so no stale render-node translation can
        // ever be baked into the outgoing snapshot.
        clearPendingLocalPageShotFeedback()
        // Prefer a zero-copy staged front when the deferred MOVE extracted one; clear ownership
        // before the overlay takes the pin so cancel cannot double-recycle.
        val seededFront = request.frontBitmap?.takeUnless(Bitmap::isRecycled)
        request.frontBitmap = null
        val outgoing = if (seededFront != null) {
            seededFront
        } else {
            val conversionCapture = conversionSnapshotCopy()
            when (conversionCapture) {
                ConversionSnapshotCapture.Failed -> null
                ConversionSnapshotCapture.NoCover -> snapshotViewport(
                    PageShotLeaseKind.PINNED,
                    "active.front",
                )
                is ConversionSnapshotCapture.Captured -> conversionCapture.bitmap
            }
        }
        if (outgoing == null) {
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        if (!pendingLocalPageShotHandoffIsValid(request)) {
            recyclePageShot(outgoing)
            cancelPendingLocalPageShotHandoff(request, consumeGesture = true)
            return
        }
        val latestX = request.latestX
        val latestY = request.latestY
        val releasedVelocity = request.releasedVelocity
        request.targetBitmap = null
        pendingLocalPageShotHandoff = null
        localPageShotHandoffGeneration += 1L
        clearPendingLocalPageShotCallbacks()
        interactiveTurnState = InteractiveTurnState.NONE
        startLocalInteractiveCurl(
            origin = request.origin,
            targetWindow = request.targetWindow,
            forward = request.forward,
            axis = request.axis,
            anchor = request.anchor,
            outgoing = outgoing,
            revealed = target,
        )
        updateInteractiveCurl(latestX, latestY)
        if (releasedVelocity != null) endInteractiveCurl(releasedVelocity)
    }

    private fun startLocalInteractiveCurl(
        origin: PageTurnOrigin,
        targetWindow: EpubFlowPage,
        forward: Boolean,
        axis: InteractiveTurnAxis,
        anchor: Float,
        outgoing: Bitmap,
        revealed: Bitmap,
    ): InteractiveTurnStartResult {
        finishInitialRevealForTurn()
        // Park content on the incoming page beneath the overlay; stays silent until the turn commits.
        parkOnPageWindow(targetWindow, report = false)
        onPageTurnTargetParked?.invoke()
        flipAnimator?.cancel()
        clearFlipOverlay(preserveActivePixelRefreshes = true)
        if (flipStyle == dev.readflow.core.model.PageFlipStyle.SIMULATION) {
            val drawable = PageCurlDrawable(
                outgoing,
                revealed,
                width,
                height,
                forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            curlDrawable = drawable
        } else {
            val drawable = PageSlideDrawable(
                outgoing,
                revealed,
                width,
                height,
                forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            slideDrawable = drawable
        }
        interactiveTurnState = InteractiveTurnState.SOFTWARE
        curlFromPage = origin.pageProjection
        curlOrigin = origin
        curlTargetWindow = targetWindow
        localSoftwareSettleCommit = null
        curlForward = forward
        curlAxis = axis
        curlAnchor = anchor
        applyFlipProgress(0f, forward)
        return InteractiveTurnStartResult.STARTED
    }

    private fun updatePendingLocalPageShotHandoff(x: Float, y: Float): Boolean {
        val request = pendingLocalPageShotHandoff ?: return false
        val coordinate = if (request.axis == InteractiveTurnAxis.HORIZONTAL) x else y
        val travel = if (request.forward) request.anchor - coordinate else coordinate - request.anchor
        if (travel < 0f) {
            // The finger crossed back over its anchor before shots were ready. Release this target so
            // the same MOVE can be classified again in the opposite direction.
            cancelPendingLocalPageShotHandoff(consumeGesture = false)
            return false
        }
        request.latestX = x
        request.latestY = y
        return true
    }

    private fun clearPendingLocalPageShotFeedback() {
        container.translationX = 0f
        container.translationY = 0f
    }

    private fun releasePendingLocalPageShotHandoff(ev: MotionEvent, velocity: Float) {
        val request = pendingLocalPageShotHandoff ?: return
        request.latestX = ev.x
        request.latestY = ev.y
        val coordinate = if (request.axis == InteractiveTurnAxis.HORIZONTAL) ev.x else ev.y
        val extent = if (request.axis == InteractiveTurnAxis.HORIZONTAL) width else height
        val travel = if (request.forward) request.anchor - coordinate else coordinate - request.anchor
        val progress = if (extent > 0) (travel / extent.toFloat()).coerceIn(0f, 1f) else 0f
        if (!shouldCommitInteractiveTurn(request.forward, progress, extent, velocity, cancelled = false)) {
            cancelPendingLocalPageShotHandoff(request, consumeGesture = false)
            return
        }
        request.releasedVelocity = velocity
    }

    private fun cancelPendingLocalPageShotHandoff(consumeGesture: Boolean) {
        cancelPendingLocalPageShotHandoff(pendingLocalPageShotHandoff, consumeGesture)
    }

    private fun cancelPendingLocalPageShotHandoff(
        request: PendingLocalPageShotHandoff?,
        consumeGesture: Boolean,
    ) {
        if (request == null) {
            if (localShotsWaiting) {
                clearPendingLocalPageShotFeedback()
                interactiveTurnState = InteractiveTurnState.NONE
            }
            return
        }
        val ownsCurrent =
            pendingLocalPageShotHandoff === request && request.token == localPageShotHandoffGeneration
        if (ownsCurrent) {
            localPageShotHandoffGeneration += 1L
            clearPendingLocalPageShotCallbacks()
            pendingLocalPageShotHandoff = null
            clearPendingLocalPageShotFeedback()
        }
        request.frontBitmap?.let(::recyclePageShot)
        request.frontBitmap = null
        request.targetBitmap?.let(::recyclePageShot)
        request.targetBitmap = null
        if (!ownsCurrent) return
        if (localShotsWaiting) interactiveTurnState = InteractiveTurnState.NONE
        if (consumeGesture) flipped = true
        onPageSettled?.invoke()
    }

    private fun startBoundaryInteractiveCurl(
        preview: BoundaryPagePreview,
        origin: PageTurnOrigin,
        axis: InteractiveTurnAxis,
        anchor: Float,
    ): InteractiveTurnStartResult {
        if (canCommitBoundaryTurn?.invoke(preview) == false) {
            onBoundaryTurnDiscarded?.invoke(preview)
            recyclePageShot(preview.bitmap)
            restorePageTurnOrigin(origin)
            return InteractiveTurnStartResult.REJECTED
        }
        onPageTurnCapturePreparing?.invoke()
        activeBoundaryPreview = preview
        val conversionCapture = conversionSnapshotCopy()
        if (conversionCapture === ConversionSnapshotCapture.Failed) {
            activeBoundaryPreview = null
            if (!restoreBoundaryPreviewAfterOutgoingFailure(preview)) {
                onBoundaryTurnDiscarded?.invoke(preview)
                recyclePageShot(preview.bitmap)
            }
            restorePageTurnOrigin(origin)
            return InteractiveTurnStartResult.REJECTED
        }
        val frozenOutgoing = (conversionCapture as? ConversionSnapshotCapture.Captured)?.bitmap
        val warmedOutgoing = if (frozenOutgoing == null) {
            takeCachedFrontForBoundaryTurn(origin.pageProjection, origin.topPx, origin.window)
        } else {
            null
        }
        val outgoing = frozenOutgoing ?: warmedOutgoing ?: snapshotViewport(
            PageShotLeaseKind.PINNED,
            "active.boundary.front",
        ) ?: run {
            activeBoundaryPreview = null
            if (!restoreBoundaryPreviewAfterOutgoingFailure(preview)) {
                onBoundaryTurnDiscarded?.invoke(preview)
                recyclePageShot(preview.bitmap)
            }
            restorePageTurnOrigin(origin)
            return InteractiveTurnStartResult.REJECTED
        }
        finishInitialRevealForTurn()
        flipAnimator?.cancel()
        clearFlipOverlay()
        if (flipStyle == dev.readflow.core.model.PageFlipStyle.SIMULATION) {
            val drawable = PageCurlDrawable(
                outgoing,
                preview.bitmap,
                width,
                height,
                preview.forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            curlDrawable = drawable
        } else {
            val drawable = PageSlideDrawable(
                outgoing,
                preview.bitmap,
                width,
                height,
                preview.forward,
                density,
                ::recyclePageShot,
            )
            drawable.setBounds(0, scrollY, width, scrollY + height)
            overlay.add(drawable)
            slideDrawable = drawable
        }
        interactiveTurnState = InteractiveTurnState.BOUNDARY_SOFTWARE
        curlFromPage = origin.pageProjection
        curlOrigin = origin
        curlTargetWindow = null
        curlForward = preview.forward
        curlAxis = axis
        curlAnchor = anchor
        onPageTurnStarted?.invoke()
        applyFlipProgress(0f, preview.forward)
        return InteractiveTurnStartResult.STARTED
    }

    private fun startWaitingBoundaryTurnIfReady() {
        if (!boundaryWaiting) return
        val preview = takeBoundaryPreview(waitingBoundaryForward) ?: return
        clearBoundaryWaitFeedback()
        if (!pageTurnAnimated) {
            flipped = true
            startBoundaryDiscreteTurn(preview)
            return
        }
        val origin = capturePageTurnOrigin()
        val result = startBoundaryInteractiveCurl(
            preview = preview,
            origin = origin,
            axis = waitingBoundaryAxis,
            anchor = waitingBoundaryAnchor,
        )
        if (result == InteractiveTurnStartResult.STARTED) {
            updateInteractiveCurl(waitingBoundaryX, waitingBoundaryY)
        } else if (result == InteractiveTurnStartResult.REJECTED) {
            // The preview was restored to its directional slot by the failed start. End only this
            // finger wait so ACTION_UP cannot cancel the still-valid engine token; a later gesture
            // may retry the same rendered target.
            interactiveTurnState = InteractiveTurnState.NONE
            waitingBoundaryX = 0f
            waitingBoundaryY = 0f
        }
    }

    /** Drives turn progress from finger displacement along the classified gesture axis. */
    private fun updateInteractiveCurl(x: Float, y: Float) {
        if (slideDrawable == null && curlDrawable == null) return
        val coordinate = if (curlAxis == InteractiveTurnAxis.HORIZONTAL) x else y
        val extent = if (curlAxis == InteractiveTurnAxis.HORIZONTAL) width else height
        val travel = if (curlForward) curlAnchor - coordinate else coordinate - curlAnchor
        applyFlipProgress((travel / extent.toFloat()).coerceIn(0f, 1f), curlForward)
    }

    /**
     * Settles the interactive turn on release: commit (animate to fully turned, keep the incoming page)
     * once the classified drag still exceeds the directional threshold, or when it flung hard enough
     * in the turn direction. Returning inside that threshold springs back; ACTION_CANCEL always aborts.
     */
    private fun endInteractiveCurl(velocity: Float) {
        settleInteractiveCurl(velocity = velocity, cancelled = false)
    }

    private fun cancelInteractiveCurl() {
        settleInteractiveCurl(velocity = 0f, cancelled = true)
    }

    private fun settleInteractiveCurl(velocity: Float, cancelled: Boolean) {
        val progressNow = slideDrawable?.progress ?: curlDrawable?.progress
        if (progressNow == null) {
            interactiveTurnState = InteractiveTurnState.NONE
            localSoftwareSettleCommit = null
            curlOrigin = null
            curlTargetWindow = null
            return
        }
        val extentPx = if (curlAxis == InteractiveTurnAxis.HORIZONTAL) width else height
        val commit = shouldCommitInteractiveTurn(
            forward = curlForward,
            progress = progressNow,
            extentPx = extentPx,
            velocity = velocity,
            cancelled = cancelled,
        )
        if (activeBoundaryPreview == null) {
            interactiveTurnState = InteractiveTurnState.SOFTWARE_SETTLING
            localSoftwareSettleCommit = commit
        } else {
            interactiveTurnState = InteractiveTurnState.BOUNDARY_SOFTWARE
            localSoftwareSettleCommit = null
        }
        val start = progressNow
        val end = if (commit) 1f else 0f
        if (!commit) {
            // Cancelled: restore the exact outgoing viewport beneath as the overlay retreats.
            curlOrigin?.let(::restorePageTurnOrigin)
            val left = 0; val top = scrollY
            slideDrawable?.setBounds(left, top, width, top + height)
            curlDrawable?.setBounds(left, top, width, top + height)
        }
        val forward = curlForward
        val distance = kotlin.math.abs(end - start)
        val progressVelocity = if (curlForward) -velocity else velocity
        val velocityTowardTarget = if (commit) progressVelocity else -progressVelocity
        val towardProgressPerSecond = if (extentPx > 0) {
            (velocityTowardTarget / extentPx.toFloat()).coerceAtLeast(0f)
        } else {
            0f
        }
        val baseDuration = (flipDurationMs * distance).toLong().coerceIn(80L, flipDurationMs)
        val velocityDuration = if (towardProgressPerSecond > 0f) {
            // A decelerating continuation travels at roughly half its initial velocity. Cap at the
            // distance-based duration so release speed can shorten a settle, never make it linger.
            ((distance / towardProgressPerSecond) * 2_000f).toLong()
                .coerceIn(80L, flipDurationMs)
        } else {
            baseDuration
        }
        val settleDuration = minOf(baseDuration, velocityDuration)
        val initialSlope = if (distance > 0f) {
            (towardProgressPerSecond * (settleDuration / 1_000f) / distance).coerceIn(0f, 2.8f)
        } else {
            0f
        }
        flipAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = settleDuration
            // Cubic Hermite continuation: starts at the release velocity and arrives with zero
            // velocity. A stationary release eases in instead of jumping to a fixed high speed.
            interpolator = android.animation.TimeInterpolator { fraction ->
                val t = fraction.coerceIn(0f, 1f)
                val t2 = t * t
                val t3 = t2 * t
                initialSlope * t + (3f - 2f * initialSlope) * t2 + (initialSlope - 2f) * t3
            }
            addUpdateListener { a -> applyFlipProgress(a.animatedValue as Float, forward) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (activeBoundaryPreview != null) {
                        finishBoundaryInteractiveTurn(commit)
                    } else {
                        retainInteractiveLocalPageShots(commit, forward)
                        clearFlipOverlay()
                        if (commit) reportTopOffset()
                        curlOrigin = null
                        curlTargetWindow = null
                        continueQueuedTurnsOrPrecache()
                        onPageSettled?.invoke()
                    }
                }
                override fun onAnimationCancel(animation: Animator) {
                    if (activeBoundaryPreview != null) {
                        finishBoundaryInteractiveTurn(commit = false)
                    } else {
                        retainInteractiveLocalPageShots(commit, forward)
                        if (commit) {
                            // The release decision already committed and parked the target. Internal
                            // invalidation may stop only the remaining visual settle; it must finalize
                            // that transaction rather than reinterpret it as an input cancellation.
                            reportTopOffset()
                        } else {
                            curlOrigin?.let(::restorePageTurnOrigin)
                        }
                        clearFlipOverlay()
                        curlOrigin = null
                        curlTargetWindow = null
                        continueQueuedTurnsOrPrecache()
                        onPageSettled?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun finishBoundaryInteractiveTurn(commit: Boolean, resumeIdle: Boolean = true) {
        if (commit && busyPageTurnGesture) {
            deferredBoundaryFinishCommit = true
            return
        }
        deferredBoundaryFinishCommit = false
        val preview = activeBoundaryPreview ?: return
        activeBoundaryPreview = null
        interactiveTurnState = InteractiveTurnState.NONE
        val commitCallback = onBoundaryTurnCommitted
        if (commit && commitCallback != null && canCommitBoundaryTurn?.invoke(preview) != false) {
            val turnBitmaps = takeActiveFlipBitmaps()
            if (turnBitmaps != null) {
                val (front, revealed) = turnBitmaps
                showConversionSnapshot(revealed)
                boundaryContinuityCover = true
                clearFlipOverlay()
                curlOrigin = null
                preview.reverseBitmap = front
                commitCallback.invoke(preview)
                return
            }
        }
        curlOrigin?.let(::restorePageTurnOrigin)
        clearFlipOverlay()
        curlOrigin = null
        if (resumeIdle) {
            onBoundaryTurnDiscarded?.invoke(preview)
        } else {
            onBoundaryPreviewEvicted?.invoke(preview)
        }
        releasePageShotBudgetForBoundaryPreview(preview.forward, resumePrecache = resumeIdle)
        if (resumeIdle) {
            preCachePageTextures()
            onPageSettled?.invoke()
        }
    }

    /** Jumps to the final page (PAGED) or the bottom of the chapter (SCROLL) — back-into-prev-spine. */
    fun goToLastPage() {
        abortLocalPageShotTurnForExternalMutation()
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            goToPage(paged.lastIndex)
        } else {
            scrollTo(0, (container.height - height).coerceAtLeast(0))
            reportTopOffset()
        }
    }

    /** Re-arms complete-line clipping without moving the arbitrary FREE_REST viewport. */
    private fun settleTemporaryScrollAnchor() {
        finishTemporaryScrollRest()
    }

    private fun releaseTemporaryScroll(fingerVelocityY: Float) {
        val scrollVelocity = (-fingerVelocityY).roundToInt()
            .coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
        val maxScroll = pagedScrollMaxPx()
        val canFling = abs(scrollVelocity) >= minimumFlingVelocity &&
            ((scrollVelocity > 0 && scrollY < maxScroll) || (scrollVelocity < 0 && scrollY > 0))
        if (!canFling) {
            finishTemporaryScrollRest()
            return
        }
        activePageWindow = null
        pagedMotionState = PagedMotionState.FLING_FREE
        pageClipActive = false
        currentPage = nearestCanonicalPageIndexForScrollY(scrollY)
        recycleCachedTextures()
        freeFlingStartedAtMs = android.os.SystemClock.uptimeMillis()
        freeFlingStableFrames = 0
        fling(scrollVelocity)
        postInvalidateOnAnimation()
    }

    private fun finishTemporaryScrollRest() {
        if (pagedMotionState == PagedMotionState.FLING_FREE) fling(0)
        pageClipActive = true
        activePageWindow = null
        pagedMotionState = PagedMotionState.FREE_REST
        if (paged.isNotEmpty()) {
            currentPage = nearestCanonicalPageIndexForScrollY(scrollY)
            recycleCachedTextures()
        }
        rebaseInterruptedFreeFlingAtCurrentViewport()
        invalidate()
        reportTopOffset()
        preCachePageTextures()
        // Free-scroll rest is a genuine idle settle — drain deferred external rebuilds (font prewarm).
        onPageSettled?.invoke()
    }

    private fun stopFreeFlingForTouch(): Boolean {
        if (pagedMotionState != PagedMotionState.FLING_FREE) return false
        cancelFreeFlingForLifecycle()
        finishTemporaryScrollRest()
        return true
    }

    private fun cancelFreeFlingForLifecycle() {
        if (pagedMotionState != PagedMotionState.FLING_FREE) return
        // ScrollView does not expose its OverScroller abort API. The zero-velocity replacement first
        // stops at the current pixel; a later programmatic jump must rebase it at that final viewport.
        interruptedFreeFlingNeedsRebase = true
        fling(0)
        freeFlingStableFrames = 0
        pageClipActive = true
        activePageWindow = null
        pagedMotionState = PagedMotionState.FREE_REST
    }

    private fun rebaseInterruptedFreeFlingAtCurrentViewport() {
        if (!interruptedFreeFlingNeedsRebase) return
        fling(0)
        interruptedFreeFlingNeedsRebase = false
    }

    private enum class PagedAnchor { FLOOR, NEAREST }

    /** Scrolls to show [layoutOffset] (its page in PAGED, or that offset at top in SCROLL). */
    fun goToOffset(layoutOffset: Int) {
        cancelFreeFlingForLifecycle()
        abortLocalPageShotTurnForExternalMutation()
        goToOffset(layoutOffset, pagedAnchor = PagedAnchor.FLOOR)
        rebaseInterruptedFreeFlingAtCurrentViewport()
    }

    private fun goToOffset(
        layoutOffset: Int,
        pagedAnchor: PagedAnchor = PagedAnchor.FLOOR,
        report: Boolean = true,
        forceReport: Boolean = false,
    ) {
        val layout = textView.layout ?: return
        val y = layout.getLineTop(layout.getLineForOffset(layoutOffset.coerceAtLeast(0)))
        if (mode == Mode.PAGED && paged.isNotEmpty()) {
            currentPage = when (pagedAnchor) {
                PagedAnchor.FLOOR -> canonicalFloorPageIndexForTopPx(y)
                PagedAnchor.NEAREST -> nearestCanonicalPageIndexForScrollY(y)
            }
            pageClipActive = true
            scrollTo(0, canonicalScrollTopForPage(currentPage) ?: paged[currentPage].topPx)
            activePageWindow = paged[currentPage]
            pagedMotionState = PagedMotionState.ALIGNED
        } else {
            activePageWindow = null
            scrollTo(0, y)
        }
        rebaseInterruptedFreeFlingAtCurrentViewport()
        if (report) reportTopOffset(force = forceReport)
    }

    /** Char offset of the line at the top of the viewport — the locator-stable resume anchor. */
    fun topLayoutOffset(): Int {
        val layout = textView.layout ?: return 0
        activePageWindow?.takeIf { mode == Mode.PAGED && pageClipActive && it.topPx == scrollY }?.let {
            return it.startOffset
        }
        val topInLayout = viewportTopInLayout(scrollY)
        var line = layout.getLineForVertical(topInLayout)
        if (
            mode == Mode.PAGED && pageClipActive && layout.getLineTop(line) < topInLayout &&
            !keepsPartialViewportSlice(layout, line)
        ) line++
        return layout.getLineStart(line.coerceAtMost(layout.lineCount - 1))
    }

    /**
     * Stable viewport-start anchor for typography reflow. While a previous rebuild is still settling,
     * keep its requested anchor instead of sampling the temporarily invalidated TextView layout.
     */
    fun typographyReflowAnchorOffset(): Int =
        pendingRestoreOffset?.takeIf { awaitingStableChapter } ?: topLayoutOffset()

    /** Publishes and warms a chapter that was fully laid out while owned by a hidden boundary slot. */
    fun activatePreparedChapter() {
        if (disposed) return
        animateChapterReveal = true
        pageTexturePrecacheEnabled = true
        reportTopOffset(force = true)
        continueQueuedTurnsOrPrecache()
        onPageSettled?.invoke()
    }

    /** Retires active-only page shots while preserving this laid-out chapter for an immediate reverse. */
    fun prepareForBoundaryReuse() {
        if (disposed) return
        animateChapterReveal = false
        pageTexturePrecacheEnabled = false
        clearConversionSnapshot()
        recycleCachedTextures()
    }

    private fun reportTopOffset(force: Boolean = false) {
        if (disposed) return
        val offset = topLayoutOffset()
        if (!force && lastReportedTopOffset == offset) return
        lastReportedTopOffset = offset
        onTopOffsetChanged(offset)
    }

    // ---- Touch FSM (owned end-to-end; selection gated behind long-press) ----------------------

    /**
     * MoonReader-inspired PAGED routing: horizontal drags turn from anywhere. For vertical drags,
     * center 1/3 x 1/3 is a no-turn zone and only the inner 1/5 x 1/5 box can become temporary scroll;
     * everything outside that center square stays on the page-turn path.
     */
    private fun pagedTouchZoneAtDown(): EpubPagedTouchZone =
        EpubPagedTouchZones.classify(width, height, downX, downY)

    /**
     * The [GestureDetector] sees the whole stream here (intercept runs before any child consumes),
     * so tap + long-press are classified centrally. We only *steal* the stream — return true — once
     * a drag is classified as a page-flip or a middle-zone free-scroll; until then the child TextView
     * keeps the events it needs for native long-press selection (the bug a prior always-intercept
     * version introduced). SCROLL mode steals every drag.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (coverConsumedGesture) {
            pendingCleanTapX = null
            trackVelocity(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    val decision = releasedPageTurnDecision(ev)
                    if (boundaryContinuityCover && awaitingStableChapter) {
                        val delta = decision.intent?.let { if (it.forward) 1 else -1 }
                            ?: if (!decision.handled) {
                                when (pagedTapZone(ev.x)) {
                                    EpubFlowTapZone.PREV -> -1
                                    EpubFlowTapZone.NEXT -> 1
                                    EpubFlowTapZone.MENU -> 0
                                }
                            } else {
                                0
                            }
                        if (delta != 0) enqueuePageTurn(delta)
                    } else {
                        decision.intent?.let { intent ->
                            if (!turnInFlight) startReleasedPageTurn(intent, decision.velocity, ev)
                        }
                    }
                }
                recycleTracker()
                coverConsumedGesture = false
            }
            return true
        }
        if (boundaryContinuityCover && awaitingStableChapter) {
            pendingCleanTapX = null
            coverConsumedGesture = ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL
            classified = false
            freeScrolling = false
            centerDeadGesture = false
            stealing = false
            flingStopGesture = false
            recycleTracker()
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                flipped = false
                inSelectionMode = false
                trackVelocity(ev)
            }
            return true
        }
        if (busyPageTurnGesture) {
            pendingCleanTapX = null
            trackVelocity(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    val decision = releasedPageTurnDecision(ev)
                    val delta = decision.intent?.let { if (it.forward) 1 else -1 }
                        ?: if (!decision.handled) {
                            when (pagedTapZone(ev.x)) {
                                EpubFlowTapZone.PREV -> -1
                                EpubFlowTapZone.NEXT -> 1
                                EpubFlowTapZone.MENU -> 0
                            }
                        } else {
                            0
                        }
                    if (delta != 0) {
                        enqueuePageTurn(delta)
                        if (!turnInFlight && drainQueuedPageTurn()) onPageSettled?.invoke()
                    } else if (!decision.handled) {
                        onTapZone(EpubFlowTapZone.MENU)
                    }
                }
                recycleTracker()
                busyPageTurnGesture = false
                if (deferredBoundaryFinishCommit) {
                    deferredBoundaryFinishCommit = false
                    finishBoundaryInteractiveTurn(commit = true)
                }
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && pageTurnRendererBusy) {
            pendingCleanTapX = null
            busyPageTurnGesture = true
            rapidIdlePageTurnGesture = false
            downX = ev.x
            downY = ev.y
            lastY = ev.y
            classified = false
            freeScrolling = false
            centerDeadGesture = false
            flipped = false
            inSelectionMode = false
            stealing = false
            setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
            textView.setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
            trackVelocity(ev)
            return true
        }
        if (releasedLocalIntentWaiting) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelPendingLocalPageShotHandoff(consumeGesture = false)
            flingStopGesture = stopFreeFlingForTouch()
            rapidIdlePageTurnGesture = rapidTurnSequenceActive
            pendingCleanTapX = null
            setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, false)
            textView.setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, false)
        }
        if (flingStopGesture) {
            setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
            textView.setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                flingStopGesture = false
            }
            return true
        }
        val handled = super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val tapX = pendingCleanTapX
                pendingCleanTapX = null
                val contentConsumed = textInteractiveTapWasConsumed()
                val microTurnHandled =
                    !flingStopGesture && !classified && !inSelectionMode && !contentConsumed &&
                        tryStartReleasedMicroTurn(ev)
                if (microTurnHandled) {
                    setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
                    textView.setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
                } else if (tapX != null && !flingStopGesture && !classified && !inSelectionMode && !contentConsumed) {
                    val zone = handleTap(tapX)
                    if (zone != EpubFlowTapZone.MENU) {
                        setTag(RenderApiR.id.selection_aware_interactive_tap_consumed, true)
                    }
                }
                rapidIdlePageTurnGesture = false
                recycleTracker()
                flingStopGesture = false
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingCleanTapX = null
                rapidIdlePageTurnGesture = false
                flingStopGesture = false
            }
        }
        return handled
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!disposed && visibility == View.VISIBLE) onPageShotForeground?.invoke()
    }

    private fun textInteractiveTapWasConsumed(): Boolean =
        textView.getTag(RenderApiR.id.selection_aware_interactive_tap_consumed) == true

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        trackVelocity(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                classified = false
                freeScrolling = false
                centerDeadGesture = false
                flipped = false
                inSelectionMode = false
                stealing = false
                clearSuppressClassifiedMoveRedelivery()
            }
            MotionEvent.ACTION_MOVE -> {
                if (inSelectionMode) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!classified && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    flingStopGesture = false
                    classified = true
                    val verticalDominant = abs(dy) >= abs(dx)
                    val pagedZone = if (mode == Mode.PAGED) pagedTouchZoneAtDown() else null
                    freeScrolling = mode == Mode.SCROLL ||
                        (verticalDominant && pagedZone == EpubPagedTouchZone.TemporaryScroll)
                    centerDeadGesture = mode == Mode.PAGED &&
                        verticalDominant &&
                        !freeScrolling &&
                        pagedZone != EpubPagedTouchZone.PageTurn
                    // Inner-center temporary scroll is continuous; drop the page clip so the reader can
                    // peek across the boundary. The next flip restores it through the canonical anchor gate.
                    if (freeScrolling && mode == Mode.PAGED) {
                        activePageWindow = null
                        pagedMotionState = PagedMotionState.DRAGGING_FREE
                        pageClipActive = false
                        recycleCachedTextures()
                        invalidate()
                    }
                    stealing = true // own the rest of this gesture
                    // ViewGroup cancels the former child on this crossing MOVE but does not replay the
                    // same event to our onTouchEvent, so apply its full DOWN-to-MOVE displacement here.
                    // If the same MOVE is still delivered to onTouchEvent, suppress that redelivery only.
                    applyClassifiedMove(ev)
                    suppressClassifiedMoveX = ev.x
                    suppressClassifiedMoveY = ev.y
                    return true
                }
            }
        }
        return stealing
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        trackVelocity(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reaches here only when no child claimed DOWN (e.g. tap on blank margin).
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                classified = false
                freeScrolling = false
                centerDeadGesture = false
                flipped = false
                inSelectionMode = false
                clearSuppressClassifiedMoveRedelivery()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (inSelectionMode) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!classified && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    flingStopGesture = false
                    classified = true
                    val verticalDominant = abs(dy) >= abs(dx)
                    val pagedZone = if (mode == Mode.PAGED) pagedTouchZoneAtDown() else null
                    freeScrolling = mode == Mode.SCROLL ||
                        (verticalDominant && pagedZone == EpubPagedTouchZone.TemporaryScroll)
                    centerDeadGesture = mode == Mode.PAGED &&
                        verticalDominant &&
                        !freeScrolling &&
                        pagedZone != EpubPagedTouchZone.PageTurn
                    if (freeScrolling && mode == Mode.PAGED) {
                        activePageWindow = null
                        pagedMotionState = PagedMotionState.DRAGGING_FREE
                        pageClipActive = false
                        recycleCachedTextures()
                        invalidate()
                    }
                }
                if (classified && !shouldSuppressClassifiedMoveRedelivery(ev)) {
                    applyClassifiedMove(ev)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                clearSuppressClassifiedMoveRedelivery()
                if (!classified && !inSelectionMode && tryStartReleasedMicroTurn(ev)) {
                    rapidIdlePageTurnGesture = false
                    recycleTracker()
                    return true
                } else if (rapidIdlePageTurnGesture && classified && !inSelectionMode) {
                    val decision = releasedPageTurnDecision(ev)
                    decision.intent?.let { startReleasedPageTurn(it, decision.velocity, ev) }
                } else if (interactiveCurl) {
                    endInteractiveCurl(computeTurnVelocity())
                } else if (localShotsWaiting) {
                    val request = pendingLocalPageShotHandoff
                    val velocity = request?.let { computeTurnVelocity(it.axis) } ?: 0f
                    releasePendingLocalPageShotHandoff(ev, velocity)
                } else if (boundaryWaiting) {
                    releaseWaitingBoundaryTurn(ev, computeTurnVelocity(waitingBoundaryAxis))
                } else if (freeScrolling) {
                    if (mode == Mode.PAGED) {
                        releaseTemporaryScroll(computeVerticalVelocity())
                    } else {
                        reportTopOffset()
                    }
                }
                rapidIdlePageTurnGesture = false
                recycleTracker()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearSuppressClassifiedMoveRedelivery()
                if (interactiveCurl) {
                    cancelInteractiveCurl()
                } else if (localShotsWaiting) {
                    cancelPendingLocalPageShotHandoff(consumeGesture = false)
                } else if (boundaryWaiting) {
                    cancelWaitingBoundaryTurn()
                } else if (freeScrolling) {
                    if (mode == Mode.PAGED) finishTemporaryScrollRest() else reportTopOffset()
                }
                classified = false
                freeScrolling = false
                centerDeadGesture = false
                inSelectionMode = false
                stealing = false
                rapidIdlePageTurnGesture = false
                recycleTracker()
                return true
            }
        }
        return true
    }

    private fun clearSuppressClassifiedMoveRedelivery() {
        suppressClassifiedMoveX = Float.NaN
        suppressClassifiedMoveY = Float.NaN
    }

    /**
     * Returns true when [ev] is a redelivery of the intercept-applied threshold MOVE (same x/y).
     * Consumes the suppress token either way so a later distinct MOVE is never dropped.
     */
    private fun shouldSuppressClassifiedMoveRedelivery(ev: MotionEvent): Boolean {
        if (suppressClassifiedMoveX.isNaN() || suppressClassifiedMoveY.isNaN()) return false
        val sameRedelivery =
            ev.x == suppressClassifiedMoveX && ev.y == suppressClassifiedMoveY
        clearSuppressClassifiedMoveRedelivery()
        return sameRedelivery
    }

    private fun cancelWaitingBoundaryTurn(invalidateRequest: Boolean = true) {
        if (!boundaryWaiting) return
        val forward = waitingBoundaryForward
        interactiveTurnState = InteractiveTurnState.NONE
        waitingBoundaryX = 0f
        waitingBoundaryY = 0f
        clearBoundaryWaitFeedback()
        if (invalidateRequest) onBoundaryPreviewRequestCancelled?.invoke(forward)
        releasePageShotBudgetForBoundaryPreview(forward)
    }

    private fun clearBoundaryWaitFeedback() {
        container.translationX = 0f
        container.translationY = 0f
    }

    private fun releaseWaitingBoundaryTurn(ev: MotionEvent, velocity: Float) {
        val coordinate = if (waitingBoundaryAxis == InteractiveTurnAxis.HORIZONTAL) ev.x else ev.y
        val extent = if (waitingBoundaryAxis == InteractiveTurnAxis.HORIZONTAL) width else height
        val travel = if (waitingBoundaryForward) {
            waitingBoundaryAnchor - coordinate
        } else {
            coordinate - waitingBoundaryAnchor
        }
        val progress = if (extent > 0) (travel / extent.toFloat()).coerceIn(0f, 1f) else 0f
        if (
            pageTurnAnimated &&
            !shouldCommitInteractiveTurn(
                forward = waitingBoundaryForward,
                progress = progress,
                extentPx = extent,
                velocity = velocity,
                cancelled = false,
            )
        ) {
            cancelWaitingBoundaryTurn()
            return
        }
        // Boundary preview work can outlive the finger. Keep the live page anchored until the
        // real two-shot overlay is ready; this also clears any translation left by older callers.
        clearBoundaryWaitFeedback()
        waitingDiscreteBoundaryForward = waitingBoundaryForward
        interactiveTurnState = InteractiveTurnState.BOUNDARY_DISCRETE_WAITING
        waitingBoundaryX = 0f
        waitingBoundaryY = 0f
    }

    private fun shouldCommitInteractiveTurn(
        forward: Boolean,
        progress: Float,
        extentPx: Int,
        velocity: Float,
        cancelled: Boolean,
    ): Boolean {
        if (cancelled) return false
        val flung = if (forward) {
            velocity < -flipFlingThresholdPxPerSec
        } else {
            velocity > flipFlingThresholdPxPerSec
        }
        return progress * extentPx >= turnIntentDistancePx || flung
    }

    private data class ReleasedPageTurnDecision(
        val handled: Boolean,
        val intent: PageTurnIntent? = null,
        val velocity: Float = 0f,
    )

    private fun releasedPageTurnDecision(ev: MotionEvent): ReleasedPageTurnDecision {
        if (disposed || mode != Mode.PAGED || paged.isEmpty()) return ReleasedPageTurnDecision(handled = false)
        val dx = ev.x - downX
        val dy = ev.y - downY
        val horizontal = abs(dx) >= abs(dy)
        val primary = if (horizontal) abs(dx) else abs(dy)
        val cross = if (horizontal) abs(dy) else abs(dx)
        if (primary < microTurnMinimumDistancePx) return ReleasedPageTurnDecision(handled = false)
        // GestureDetector may still call this a tap when the platform touch slop is larger than our
        // micro-turn floor. From this distance onward a rejected turn is drift, never an edge tap.
        classified = true
        if (cross > primary * MICRO_TURN_MAX_CROSS_AXIS_RATIO) return ReleasedPageTurnDecision(handled = true)
        val axis = if (horizontal) InteractiveTurnAxis.HORIZONTAL else InteractiveTurnAxis.VERTICAL
        if (axis == InteractiveTurnAxis.VERTICAL && pagedTouchZoneAtDown() != EpubPagedTouchZone.PageTurn) {
            return ReleasedPageTurnDecision(handled = true)
        }
        val forward = if (horizontal) dx < 0f else dy < 0f
        val velocity = computeTurnVelocity(axis)
        val velocityMatches = if (forward) {
            velocity < -flipFlingThresholdPxPerSec
        } else {
            velocity > flipFlingThresholdPxPerSec
        }
        val projectedTravel = primary + abs(velocity) * MICRO_TURN_PROJECTION_SECONDS
        val distanceAccepted = primary >= turnIntentDistancePx
        if (!distanceAccepted && (!velocityMatches || projectedTravel < turnIntentDistancePx)) {
            return ReleasedPageTurnDecision(handled = true)
        }

        val anchor = if (horizontal) downX else downY
        return ReleasedPageTurnDecision(
            handled = true,
            intent = PageTurnIntent(forward, axis, anchor),
            velocity = velocity,
        )
    }

    private fun startReleasedPageTurn(intent: PageTurnIntent, velocity: Float, ev: MotionEvent) {
        // A live gesture that reaches execution retires any pre-layout initial-reveal fallback.
        pendingInitialPageTurnDelta = null
        if (rapidIdlePageTurnGesture) {
            val delta = if (intent.forward) 1 else -1
            if (!goToAdjacentPage(delta)) {
                onTapZone(if (intent.forward) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
            }
            return
        }
        startPageTurnIntent(intent, ev)
        when {
            interactiveCurl -> endInteractiveCurl(velocity)
            localShotsWaiting -> releasePendingLocalPageShotHandoff(ev, velocity)
            boundaryWaiting -> releaseWaitingBoundaryTurn(ev, velocity)
        }
    }

    private fun tryStartReleasedMicroTurn(ev: MotionEvent): Boolean {
        if (turnInFlight) return false
        val decision = releasedPageTurnDecision(ev)
        decision.intent?.let { startReleasedPageTurn(it, decision.velocity, ev) }
        return decision.handled
    }

    private fun applyClassifiedMove(ev: MotionEvent) {
        if (freeScrolling) {
            // Drive the temporary scroll ourselves; release preserves this exact pixel viewport and
            // may continue with native inertia. The next page turn performs complete-line alignment.
            val deltaY = (lastY - ev.y).toInt()
            val maxScroll = if (mode == Mode.PAGED && paged.isNotEmpty()) {
                pagedScrollMaxPx()
            } else {
                (container.height - height).coerceAtLeast(0)
            }
            val target = (scrollY + deltaY).coerceIn(0, maxScroll)
            if (target != scrollY) scrollTo(0, target)
            lastY = ev.y
        } else if (!centerDeadGesture) {
            driveFlip(ev.x - downX, ev.y - downY, ev)
        }
    }

    /**
     * Routes a classified non-scroll drag. A horizontal drag becomes a local PAPER turn under
     * SIMULATION or a slide otherwise. Once started, later moves feed progress along its classified
     * axis. Drag, tap/key, and chapter-boundary turns share this same View-overlay ownership model.
     */
    private fun driveFlip(dx: Float, dy: Float, ev: MotionEvent) {
        if (interactiveCurl) {
            updateInteractiveCurl(ev.x, ev.y)
            return
        }
        if (localShotsWaiting && updatePendingLocalPageShotHandoff(ev.x, ev.y)) return
        if (boundaryWaiting) {
            waitingBoundaryX = ev.x
            waitingBoundaryY = ev.y
            return
        }
        if (rapidIdlePageTurnGesture) return
        if (flipped) return
        val horizontalDominant = abs(dx) >= abs(dy)
        val intent = if (horizontalDominant) {
            if (abs(dx) <= turnIntentDistancePx || abs(dy) >= flipCrossAxisLimitPx) return
            PageTurnIntent(dx < 0f, InteractiveTurnAxis.HORIZONTAL, downX)
        } else {
            if (abs(dy) <= turnIntentDistancePx || abs(dx) >= flipCrossAxisLimitPx) return
            PageTurnIntent(dy < 0f, InteractiveTurnAxis.VERTICAL, downY)
        }
        startPageTurnIntent(intent, ev)
    }

    private fun startPageTurnIntent(intent: PageTurnIntent, ev: MotionEvent): InteractiveTurnStartResult {
        if (!pageTurnAnimated) {
            beginNonAnimatedDragTurn(intent.forward, intent.axis, intent.anchor)
            if (boundaryWaiting) {
                waitingBoundaryX = ev.x
                waitingBoundaryY = ev.y
                return InteractiveTurnStartResult.WAITING
            }
            return InteractiveTurnStartResult.STARTED
        }
        val result = beginInteractiveCurl(intent.forward, intent.axis, intent.anchor, ev.x, ev.y)
        when (result) {
            InteractiveTurnStartResult.STARTED -> {
                if (interactiveTurnState != InteractiveTurnState.BOUNDARY_SOFTWARE) {
                    onPageTurnStarted?.invoke()
                }
                updateInteractiveCurl(ev.x, ev.y)
            }
            InteractiveTurnStartResult.WAITING -> {
                if (localShotsWaiting) {
                    onPageTurnStarted?.invoke()
                    updatePendingLocalPageShotHandoff(ev.x, ev.y)
                } else {
                    waitingBoundaryX = ev.x
                    waitingBoundaryY = ev.y
                }
            }
            InteractiveTurnStartResult.REJECTED -> flipped = true
        }
        return result
    }

    private fun beginNonAnimatedDragTurn(
        forward: Boolean,
        axis: InteractiveTurnAxis,
        anchor: Float,
    ) {
        if (pageWindowForTurn(forward) != null) {
            flipped = true
            onTapZone(if (forward) EpubFlowTapZone.NEXT else EpubFlowTapZone.PREV)
            return
        }
        val preview = takeBoundaryPreview(forward)
        if (preview != null) {
            flipped = true
            startBoundaryDiscreteTurn(preview)
            return
        }
        val requestPreview = onBoundaryPreviewNeeded ?: run {
            flipped = true
            return
        }
        interactiveTurnState = InteractiveTurnState.BOUNDARY_WAITING
        waitingBoundaryForward = forward
        waitingBoundaryAxis = axis
        waitingBoundaryAnchor = anchor
        requestPreview(forward, boundaryPreviewGeneration)
    }

    private fun trackVelocity(ev: MotionEvent) {
        if (
            ev.eventTime == lastVelocityEventTime &&
            ev.actionMasked == lastVelocityAction &&
            ev.x == lastVelocityX &&
            ev.y == lastVelocityY
        ) return
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) recycleTracker()
        val vt = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        vt.addMovement(ev)
        lastVelocityEventTime = ev.eventTime
        lastVelocityAction = ev.actionMasked
        lastVelocityX = ev.x
        lastVelocityY = ev.y
    }

    private fun computeTurnVelocity(axis: InteractiveTurnAxis = curlAxis): Float {
        val vt = velocityTracker ?: return 0f
        vt.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        return if (axis == InteractiveTurnAxis.HORIZONTAL) vt.xVelocity else vt.yVelocity
    }

    private fun computeVerticalVelocity(): Float {
        val vt = velocityTracker ?: return 0f
        vt.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        return vt.yVelocity
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
        lastVelocityEventTime = Long.MIN_VALUE
        lastVelocityAction = -1
        lastVelocityX = Float.NaN
        lastVelocityY = Float.NaN
    }

    private fun pagedTapZone(x: Float): EpubFlowTapZone = when {
        mode != Mode.PAGED -> EpubFlowTapZone.MENU
        x < width / 3f -> EpubFlowTapZone.PREV
        x > width * 2f / 3f -> EpubFlowTapZone.NEXT
        else -> EpubFlowTapZone.MENU
    }

    private fun handleTap(x: Float): EpubFlowTapZone {
        // SCROLL mode has no pages: navigation is by finger scroll, so edge taps must not page-jump.
        // Any tap toggles the menu instead (matches mainstream readers' scroll/webtoon mode).
        val zone = pagedTapZone(x)
        val rapidDelta = if (rapidIdlePageTurnGesture) {
            when (zone) {
                EpubFlowTapZone.PREV -> -1
                EpubFlowTapZone.NEXT -> 1
                EpubFlowTapZone.MENU -> 0
            }
        } else {
            0
        }
        if (rapidDelta == 0 || !goToAdjacentPage(rapidDelta)) onTapZone(zone)
        return zone
    }

    private companion object {
        /** The opposite speculative frame is admitted only while all three fit this tighter cap. */
        const val PAGE_SHOT_OPPOSITE_BUDGET_BYTES = 32L * 1024L * 1024L
        const val ACTIVE_PAGE_SHOT_PAIR_SIZE = 2L
        /** Coalesce window for async-image reflows: collapses a decode burst into ONE paginate+anchor. */
        const val REFLOW_DEBOUNCE_MS = 80L
        /** Paint completed images even if another relevant decoder never returns. */
        const val ASYNC_IMAGE_BATCH_MAX_WAIT_MS = 800L

        /** Fade-in for the chapter's first positioned frame — long enough to hide a one-frame settle. */
        const val REVEAL_FADE_MS = 120L
        /** Maximum wait for layout stability before forcing reveal (MoonReader 800ms safety net). */
        const val REVEAL_SAFETY_MS = 800L
        const val BOUNDARY_PREWARM_DISTANCE_PAGES = 2
        const val MICRO_TURN_MIN_VELOCITY_DP_PER_SEC = 90f
        const val MICRO_TURN_PROJECTION_SECONDS = 0.04f
        const val MICRO_TURN_MAX_CROSS_AXIS_RATIO = 0.5f
        const val MAX_QUEUED_PAGE_TURNS = 12
        const val RAPID_TURN_IDLE_TIMEOUT_MS = 320L
        const val RAPID_FOLLOW_UP_PREFETCH_DELAY_MS = 32L
        const val FREE_FLING_MIN_SETTLE_MS = 64L
        const val FREE_FLING_STABLE_FRAMES = 2
    }

    private fun stablePageShotCapacity(): Int {
        if (width <= 0 || height <= 0) return 0
        val bytesPerShot = motionPageShotByteCount()
        val localOwnedBytes = listOfNotNull(
            cachedFrontBitmap,
            cachedRevealedBitmap,
            cachedBackwardBitmap,
        ).distinct().filterNot(Bitmap::isRecycled).sumOf { it.allocationByteCount.toLong() }
        val externalBytes = (pageShotBudget.chargedBytes - localOwnedBytes).coerceAtLeast(0L)
        val externalShotCount = if (externalBytes == 0L) {
            0
        } else {
            ((externalBytes + bytesPerShot - 1L) / bytesPerShot).toInt()
        }
        return (3 downTo 0).first { localShotCount ->
            val totalBytes = externalBytes + bytesPerShot * localShotCount
            val totalShotCount = externalShotCount + localShotCount
            totalBytes <= pageShotBudget.capacityBytes &&
                (totalShotCount <= 2 || totalBytes <= PAGE_SHOT_OPPOSITE_BUDGET_BYTES)
        }
    }

    private fun threePageShotsFitOppositeBudget(): Boolean {
        if (width <= 0 || height <= 0) return false
        val bytesPerShot = motionPageShotByteCount()
        return bytesPerShot * 3L <= minOf(pageShotBudget.capacityBytes, PAGE_SHOT_OPPOSITE_BUDGET_BYTES)
    }

    @Suppress("DEPRECATION")
    private fun motionPageShotConfig(): Bitmap.Config =
        if (viewportBackground?.opacity == PixelFormat.OPAQUE) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }

    private fun motionPageShotBytesPerPixel(): Int = pageShotBytesPerPixel(motionPageShotConfig())

    private fun pageShotBytesPerPixel(config: Bitmap.Config): Int = when (config) {
        Bitmap.Config.ALPHA_8 -> 1
        Bitmap.Config.RGB_565,
        @Suppress("DEPRECATION") Bitmap.Config.ARGB_4444,
        -> 2
        Bitmap.Config.RGBA_F16 -> 8
        else -> 4
    }

    private fun motionPageShotWidthPx(): Int {
        return width.coerceAtLeast(1)
    }

    private fun motionPageShotHeightPx(): Int {
        return height.coerceAtLeast(1)
    }

    private fun motionPageShotByteCount(): Long =
        motionPageShotWidthPx().toLong() *
            motionPageShotHeightPx().toLong() *
            motionPageShotBytesPerPixel()

    private fun usablePageHeightPx(): Int {
        if (height <= 0) return pageHeightPx.coerceAtLeast(1)
        return (height - textView.paddingTop - textView.paddingBottom).coerceAtLeast(1)
    }

    private class EpubFlowContainer(context: Context) : FrameLayout(context) {
        /** Set only around dispatchDraw while a page-shot overlay owns the complete viewport. */
        var skipContentDraw: Boolean = false

        override fun dispatchDraw(canvas: Canvas) {
            if (skipContentDraw) return
            super.dispatchDraw(canvas)
        }

        var minimumScrollableHeightPx: Int = 0
            set(value) {
                val next = value.coerceAtLeast(0)
                if (field == next) return
                field = next
                if (next > height && measuredWidth > 0) {
                    // ScrollView clamps scrollTo() against the child's current laid-out height. Tail
                    // pagination and hidden backward previews can park on the final raw line in the
                    // same task that discovers this extent, before ViewRoot performs another measure.
                    measure(
                        MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(next, MeasureSpec.EXACTLY),
                    )
                    layout(left, top, left + measuredWidth, top + measuredHeight)
                }
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (minimumScrollableHeightPx > measuredHeight) {
                setMeasuredDimension(measuredWidth, minimumScrollableHeightPx)
            }
        }
    }
}

private data class BoundaryPageTurn(
    val forward: Boolean,
    val expectedChapterGeneration: Long,
)

/** Exclusively owned target frame for one adjacent-spine turn. */
internal data class BoundaryPagePreview(
    val token: Long,
    val forward: Boolean,
    val sourceChapterGeneration: Long,
    val bitmap: Bitmap,
) {
    /** True when the engine retained the adjacent chapter surface for an immediate reverse turn. */
    var retainedSurface: Boolean = false
    /** Outgoing page-shot transferred only during a successful commit callback. */
    var reverseBitmap: Bitmap? = null
}

private sealed interface ConversionSnapshotCapture {
    data object NoCover : ConversionSnapshotCapture
    data object Failed : ConversionSnapshotCapture
    data class Captured(val bitmap: Bitmap) : ConversionSnapshotCapture
}

private class ViewportSnapshotDrawable(
    private val bitmap: Bitmap,
    private val bitmapRecycler: (Bitmap) -> Unit,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var alphaValue: Int = 255
        set(value) {
            field = value.coerceIn(0, 255)
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        paint.alpha = alphaValue
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    fun recycle() {
        bitmapRecycler(bitmap)
    }

    val bitmapWidth: Int get() = bitmap.width
    val bitmapHeight: Int get() = bitmap.height

    fun copyInto(destination: Bitmap) {
        check(!bitmap.isRecycled && !destination.isRecycled)
        Canvas(destination).drawBitmap(bitmap, 0f, 0f, null)
    }

    fun flattenOver(destination: Bitmap): Boolean {
        if (bitmap.isRecycled || destination.isRecycled || !destination.isMutable) return false
        return try {
            val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = alphaValue }
            Canvas(destination).drawBitmap(
                bitmap,
                null,
                android.graphics.Rect(0, 0, destination.width, destination.height),
                blendPaint,
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal enum class EpubFlowTapZone { PREV, NEXT, MENU }
