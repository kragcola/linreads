package dev.readflow.render.epub

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import java.util.ArrayDeque

/**
 * Disabled-by-default, in-memory timing evidence for rapid-idle reader work.
 * It is reset explicitly by instrumentation and never writes outside this process.
 */
internal object EpubRapidIdleWorkProbe {
    private const val MAX_RECENT_ARTIFACT_RECORDS = 16
    private const val MAX_FRAME_METRIC_WINDOWS = 32
    private const val SETTLED_FRAME_SAMPLE_LIMIT = 2
    private const val FRAME_METRIC_TIMESTAMP_GUARD_NS = 25_000_000L

    data class Snapshot(
        val enabled: Boolean,
        val precacheArmCount: Int,
        val pointerDownCount: Int,
        val queuedSlideAtDown: Boolean,
        val lastDownEventTimeNs: Long,
        val lastDownDispatchTimeNs: Long,
        val downEventToDispatchNs: Long,
        val pointerMoveCount: Int,
        val lastMoveEventTimeNs: Long,
        val lastMoveDispatchTimeNs: Long,
        val pointerUpCount: Int,
        val lastUpEventTimeNs: Long,
        val lastUpDispatchTimeNs: Long,
        val pointerCancelCount: Int,
        val lastCancelEventTimeNs: Long,
        val lastCancelDispatchTimeNs: Long,
        val slideArtifactRecordCount: Int,
        val slideArtifactRecordTotalNs: Long,
        val slideArtifactRecordMaxNs: Long,
        val slideArtifactRecordsOverlappingDownDispatch: Int,
        val pixelTextRebindCount: Int,
        val pixelTextRebindTotalNs: Long,
        val pixelTextRebindMaxNs: Long,
        val geometryTextRebindCount: Int,
        val geometryTextRebindTotalNs: Long,
        val geometryTextRebindMaxNs: Long,
        val bitmapPrepareToDrawCount: Int,
        val bitmapPrepareToDrawTotalNs: Long,
        val bitmapPrepareToDrawMaxNs: Long,
        val overlayFirstDrawCount: Int,
        val overlayFirstDrawTotalNs: Long,
        val overlayFirstDrawMaxNs: Long,
        val liveContentDispatchDrawCount: Int,
        val liveContentDispatchDrawTotalNs: Long,
        val liveContentDispatchDrawMaxNs: Long,
        val frameMetricsOverlayFrameCount: Int,
        val frameMetricsOverlayDrawTotalNs: Long,
        val frameMetricsOverlayDrawMaxNs: Long,
        val frameMetricsOverlaySyncTotalNs: Long,
        val frameMetricsOverlaySyncMaxNs: Long,
        val frameMetricsOverlayCommandIssueTotalNs: Long,
        val frameMetricsOverlayCommandIssueMaxNs: Long,
        val frameMetricsOverlaySwapBuffersTotalNs: Long,
        val frameMetricsOverlaySwapBuffersMaxNs: Long,
        val frameMetricsSettledFrameCount: Int,
        val frameMetricsSettledDrawTotalNs: Long,
        val frameMetricsSettledDrawMaxNs: Long,
        val frameMetricsSettledSyncTotalNs: Long,
        val frameMetricsSettledSyncMaxNs: Long,
        val frameMetricsSettledCommandIssueTotalNs: Long,
        val frameMetricsSettledCommandIssueMaxNs: Long,
        val frameMetricsSettledSwapBuffersTotalNs: Long,
        val frameMetricsSettledSwapBuffersMaxNs: Long,
    )

    private enum class PointerBoundary { MOVE, UP, CANCEL }

    private data class ArtifactRecordInterval(
        val startedAtNs: Long,
        val endedAtNs: Long,
    )

    private enum class FrameMetricPhase { OVERLAY, SETTLED }

    private data class FrameMetricWindow(
        val generation: Long,
        val phase: FrameMetricPhase,
        val startedAtNs: Long,
        var endedAtNs: Long = Long.MAX_VALUE,
        var sampleCount: Int = 0,
    )

    private class FrameMetricTotals {
        var frameCount = 0
        var drawTotalNs = 0L
        var drawMaxNs = 0L
        var syncTotalNs = 0L
        var syncMaxNs = 0L
        var commandIssueTotalNs = 0L
        var commandIssueMaxNs = 0L
        var swapBuffersTotalNs = 0L
        var swapBuffersMaxNs = 0L

        fun add(
            drawDurationNs: Long,
            syncDurationNs: Long,
            commandIssueDurationNs: Long,
            swapBuffersDurationNs: Long,
        ) {
            frameCount += 1
            drawTotalNs += drawDurationNs
            drawMaxNs = maxOf(drawMaxNs, drawDurationNs)
            syncTotalNs += syncDurationNs
            syncMaxNs = maxOf(syncMaxNs, syncDurationNs)
            commandIssueTotalNs += commandIssueDurationNs
            commandIssueMaxNs = maxOf(commandIssueMaxNs, commandIssueDurationNs)
            swapBuffersTotalNs += swapBuffersDurationNs
            swapBuffersMaxNs = maxOf(swapBuffersMaxNs, swapBuffersDurationNs)
        }

        fun clear() {
            frameCount = 0
            drawTotalNs = 0L
            drawMaxNs = 0L
            syncTotalNs = 0L
            syncMaxNs = 0L
            commandIssueTotalNs = 0L
            commandIssueMaxNs = 0L
            swapBuffersTotalNs = 0L
            swapBuffersMaxNs = 0L
        }
    }

    private val lock = Any()
    @Volatile private var enabled = false
    private val recentArtifactRecordIntervals = ArrayDeque<ArtifactRecordInterval>()
    private val frameMetricWindows = ArrayDeque<FrameMetricWindow>()
    private val overlayFrameMetrics = FrameMetricTotals()
    private val settledFrameMetrics = FrameMetricTotals()
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private var registeredFrameMetricsWindow: Window? = null
    private var registeredFrameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    private var registeredFrameMetricsSession = 0L
    private var frameMetricsSession = 0L
    private var nextFrameMetricGeneration = 0L
    private var overlayFrameMetricWindow: FrameMetricWindow? = null
    private var settledFrameMetricWindow: FrameMetricWindow? = null
    private var overlayFirstDrawSeen = false
    private var liveContentDispatchArmed = false

    private var precacheArmCount = 0
    private var pointerDownCount = 0
    private var queuedSlideAtDown = false
    private var lastDownEventTimeNs = 0L
    private var lastDownDispatchTimeNs = 0L
    private var downEventToDispatchNs = 0L
    private var pointerMoveCount = 0
    private var lastMoveEventTimeNs = 0L
    private var lastMoveDispatchTimeNs = 0L
    private var pointerUpCount = 0
    private var lastUpEventTimeNs = 0L
    private var lastUpDispatchTimeNs = 0L
    private var pointerCancelCount = 0
    private var lastCancelEventTimeNs = 0L
    private var lastCancelDispatchTimeNs = 0L

    private var slideArtifactRecordCount = 0
    private var slideArtifactRecordTotalNs = 0L
    private var slideArtifactRecordMaxNs = 0L
    private var slideArtifactRecordsOverlappingDownDispatch = 0
    private var activeSlideArtifactRecordStartNs: Long? = null

    private var pixelTextRebindCount = 0
    private var pixelTextRebindTotalNs = 0L
    private var pixelTextRebindMaxNs = 0L
    private var geometryTextRebindCount = 0
    private var geometryTextRebindTotalNs = 0L
    private var geometryTextRebindMaxNs = 0L
    private var bitmapPrepareToDrawCount = 0
    private var bitmapPrepareToDrawTotalNs = 0L
    private var bitmapPrepareToDrawMaxNs = 0L
    private var overlayFirstDrawCount = 0
    private var overlayFirstDrawTotalNs = 0L
    private var overlayFirstDrawMaxNs = 0L
    private var liveContentDispatchDrawCount = 0
    private var liveContentDispatchDrawTotalNs = 0L
    private var liveContentDispatchDrawMaxNs = 0L

    fun reset() {
        val registration = synchronized(lock) {
            frameMetricsSession += 1L
            clearLocked()
            enabled = true
            takeFrameMetricsRegistrationLocked()
        }
        registration?.let(::removeFrameMetricsListener)
    }

    fun stop() {
        val registration = synchronized(lock) {
            enabled = false
            frameMetricsSession += 1L
            clearLocked()
            takeFrameMetricsRegistrationLocked()
        }
        registration?.let(::removeFrameMetricsListener)
    }

    fun isEnabled(): Boolean = enabled

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            enabled = enabled,
            precacheArmCount = precacheArmCount,
            pointerDownCount = pointerDownCount,
            queuedSlideAtDown = queuedSlideAtDown,
            lastDownEventTimeNs = lastDownEventTimeNs,
            lastDownDispatchTimeNs = lastDownDispatchTimeNs,
            downEventToDispatchNs = downEventToDispatchNs,
            pointerMoveCount = pointerMoveCount,
            lastMoveEventTimeNs = lastMoveEventTimeNs,
            lastMoveDispatchTimeNs = lastMoveDispatchTimeNs,
            pointerUpCount = pointerUpCount,
            lastUpEventTimeNs = lastUpEventTimeNs,
            lastUpDispatchTimeNs = lastUpDispatchTimeNs,
            pointerCancelCount = pointerCancelCount,
            lastCancelEventTimeNs = lastCancelEventTimeNs,
            lastCancelDispatchTimeNs = lastCancelDispatchTimeNs,
            slideArtifactRecordCount = slideArtifactRecordCount,
            slideArtifactRecordTotalNs = slideArtifactRecordTotalNs,
            slideArtifactRecordMaxNs = slideArtifactRecordMaxNs,
            slideArtifactRecordsOverlappingDownDispatch = slideArtifactRecordsOverlappingDownDispatch,
            pixelTextRebindCount = pixelTextRebindCount,
            pixelTextRebindTotalNs = pixelTextRebindTotalNs,
            pixelTextRebindMaxNs = pixelTextRebindMaxNs,
            geometryTextRebindCount = geometryTextRebindCount,
            geometryTextRebindTotalNs = geometryTextRebindTotalNs,
            geometryTextRebindMaxNs = geometryTextRebindMaxNs,
            bitmapPrepareToDrawCount = bitmapPrepareToDrawCount,
            bitmapPrepareToDrawTotalNs = bitmapPrepareToDrawTotalNs,
            bitmapPrepareToDrawMaxNs = bitmapPrepareToDrawMaxNs,
            overlayFirstDrawCount = overlayFirstDrawCount,
            overlayFirstDrawTotalNs = overlayFirstDrawTotalNs,
            overlayFirstDrawMaxNs = overlayFirstDrawMaxNs,
            liveContentDispatchDrawCount = liveContentDispatchDrawCount,
            liveContentDispatchDrawTotalNs = liveContentDispatchDrawTotalNs,
            liveContentDispatchDrawMaxNs = liveContentDispatchDrawMaxNs,
            frameMetricsOverlayFrameCount = overlayFrameMetrics.frameCount,
            frameMetricsOverlayDrawTotalNs = overlayFrameMetrics.drawTotalNs,
            frameMetricsOverlayDrawMaxNs = overlayFrameMetrics.drawMaxNs,
            frameMetricsOverlaySyncTotalNs = overlayFrameMetrics.syncTotalNs,
            frameMetricsOverlaySyncMaxNs = overlayFrameMetrics.syncMaxNs,
            frameMetricsOverlayCommandIssueTotalNs = overlayFrameMetrics.commandIssueTotalNs,
            frameMetricsOverlayCommandIssueMaxNs = overlayFrameMetrics.commandIssueMaxNs,
            frameMetricsOverlaySwapBuffersTotalNs = overlayFrameMetrics.swapBuffersTotalNs,
            frameMetricsOverlaySwapBuffersMaxNs = overlayFrameMetrics.swapBuffersMaxNs,
            frameMetricsSettledFrameCount = settledFrameMetrics.frameCount,
            frameMetricsSettledDrawTotalNs = settledFrameMetrics.drawTotalNs,
            frameMetricsSettledDrawMaxNs = settledFrameMetrics.drawMaxNs,
            frameMetricsSettledSyncTotalNs = settledFrameMetrics.syncTotalNs,
            frameMetricsSettledSyncMaxNs = settledFrameMetrics.syncMaxNs,
            frameMetricsSettledCommandIssueTotalNs = settledFrameMetrics.commandIssueTotalNs,
            frameMetricsSettledCommandIssueMaxNs = settledFrameMetrics.commandIssueMaxNs,
            frameMetricsSettledSwapBuffersTotalNs = settledFrameMetrics.swapBuffersTotalNs,
            frameMetricsSettledSwapBuffersMaxNs = settledFrameMetrics.swapBuffersMaxNs,
        )
    }

    fun snapshotText(): String = snapshot().toString()

    fun notePrecacheArmed() {
        if (!enabled) return
        synchronized(lock) {
            if (enabled) precacheArmCount += 1
        }
    }

    fun notePointerDown(queuedSlide: Boolean, eventTimeNs: Long) {
        if (!enabled) return
        notePointerDown(queuedSlide, eventTimeNs, uptimeNs())
    }

    fun notePointerDown(
        queuedSlide: Boolean,
        eventTimeNs: Long,
        dispatchTimeNs: Long,
    ) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return@synchronized
            pointerDownCount += 1
            queuedSlideAtDown = queuedSlide
            lastDownEventTimeNs = eventTimeNs
            lastDownDispatchTimeNs = dispatchTimeNs
            downEventToDispatchNs = (dispatchTimeNs - eventTimeNs).coerceAtLeast(0L)
            recomputeSlideArtifactOverlapLocked()
        }
    }

    fun notePointerMove(eventTimeNs: Long) {
        notePointerBoundary(PointerBoundary.MOVE, eventTimeNs)
    }

    fun notePointerUp(eventTimeNs: Long) {
        notePointerBoundary(PointerBoundary.UP, eventTimeNs)
    }

    fun notePointerCancel(eventTimeNs: Long) {
        notePointerBoundary(PointerBoundary.CANCEL, eventTimeNs)
    }

    fun noteSlideArtifactRecordStart(startNs: Long) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return@synchronized
            slideArtifactRecordCount += 1
            activeSlideArtifactRecordStartNs = startNs
            recomputeSlideArtifactOverlapLocked()
        }
    }

    fun noteSlideArtifactRecordEnd(endNs: Long) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return@synchronized
            val startedAtNs = activeSlideArtifactRecordStartNs ?: return@synchronized
            val durationNs = (endNs - startedAtNs).coerceAtLeast(0L)
            slideArtifactRecordTotalNs += durationNs
            slideArtifactRecordMaxNs = maxOf(slideArtifactRecordMaxNs, durationNs)
            activeSlideArtifactRecordStartNs = null
            if (recentArtifactRecordIntervals.size == MAX_RECENT_ARTIFACT_RECORDS) {
                recentArtifactRecordIntervals.removeFirst()
            }
            recentArtifactRecordIntervals.addLast(ArtifactRecordInterval(startedAtNs, endNs))
            recomputeSlideArtifactOverlapLocked()
        }
    }

    fun notePixelTextRebind(startNs: Long, endNs: Long) {
        noteTimedRebind(startNs, endNs, geometry = false)
    }

    fun noteGeometryTextRebind(startNs: Long, endNs: Long) {
        noteTimedRebind(startNs, endNs, geometry = true)
    }

    fun noteBitmapPrepareToDraw(startNs: Long, endNs: Long) {
        if (!enabled) return
        val durationNs = (endNs - startNs).coerceAtLeast(0L)
        synchronized(lock) {
            if (!enabled) return@synchronized
            bitmapPrepareToDrawCount += 1
            bitmapPrepareToDrawTotalNs += durationNs
            bitmapPrepareToDrawMaxNs = maxOf(bitmapPrepareToDrawMaxNs, durationNs)
        }
    }

    /** Called immediately before a static SLIDE renderer is admitted to the overlay. */
    fun noteSlideOverlayInstalled() {
        noteSlideOverlayInstalled(uptimeNs())
    }

    /** Timestamped form used by the JVM contract test and by deterministic diagnostics. */
    fun noteSlideOverlayInstalled(atNs: Long) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return@synchronized
            closeOpenFrameMetricWindowsLocked(atNs)
            val window = FrameMetricWindow(
                generation = ++nextFrameMetricGeneration,
                phase = FrameMetricPhase.OVERLAY,
                // The first frame's intended-vsync can precede the UI callback that installs the
                // renderer by one refresh interval; retain a bounded pre-marker guard for it.
                startedAtNs = (atNs - FRAME_METRIC_TIMESTAMP_GUARD_NS).coerceAtLeast(0L),
            )
            appendFrameMetricWindowLocked(window)
            overlayFrameMetricWindow = window
            settledFrameMetricWindow = null
            overlayFirstDrawSeen = false
        }
    }

    /** Records only the first View draw for the current overlay generation. */
    fun noteOverlayDraw(startNs: Long, endNs: Long) {
        if (!enabled) return
        val durationNs = (endNs - startNs).coerceAtLeast(0L)
        synchronized(lock) {
            if (!enabled || overlayFirstDrawSeen) return@synchronized
            overlayFirstDrawSeen = true
            overlayFirstDrawCount += 1
            overlayFirstDrawTotalNs += durationNs
            overlayFirstDrawMaxNs = maxOf(overlayFirstDrawMaxNs, durationNs)
        }
    }

    fun endOverlayDrawTiming(startNs: Long?) {
        if (startNs == null || !enabled) return
        noteOverlayDraw(startNs, uptimeNs())
    }

    /** Arms one live container dispatch sample after page-shot suppression is released. */
    fun noteLiveContentRerecordArmed() {
        noteLiveContentRerecordArmed(uptimeNs())
    }

    fun noteLiveContentRerecordArmed(atNs: Long) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return@synchronized
            closeOpenFrameMetricWindowsLocked(atNs)
            val generation = overlayFrameMetricWindow?.generation ?: ++nextFrameMetricGeneration
            val window = FrameMetricWindow(
                generation = generation,
                phase = FrameMetricPhase.SETTLED,
                startedAtNs = (atNs - FRAME_METRIC_TIMESTAMP_GUARD_NS).coerceAtLeast(0L),
            )
            appendFrameMetricWindowLocked(window)
            settledFrameMetricWindow = window
            liveContentDispatchArmed = true
        }
    }

    fun beginLiveContentDispatch(): Long? {
        if (!enabled) return null
        return synchronized(lock) {
            if (!enabled || !liveContentDispatchArmed) return@synchronized null
            // One sample per release is enough to identify the expensive traversal and avoids
            // turning a diagnostic session into a full dispatch profiler.
            liveContentDispatchArmed = false
            return@synchronized uptimeNs()
        }
    }

    fun endLiveContentDispatch(startNs: Long?) {
        if (startNs == null || !enabled) return
        noteLiveContentDispatch(startNs, uptimeNs())
    }

    fun noteLiveContentDispatch(startNs: Long, endNs: Long) {
        if (!enabled) return
        val durationNs = (endNs - startNs).coerceAtLeast(0L)
        synchronized(lock) {
            if (!enabled) return@synchronized
            liveContentDispatchDrawCount += 1
            liveContentDispatchDrawTotalNs += durationNs
            liveContentDispatchDrawMaxNs = maxOf(liveContentDispatchDrawMaxNs, durationNs)
        }
    }

    /**
     * Attributes one Window.FrameMetrics sample to the timestamped overlay or settled window.
     * This overload is intentionally pure with respect to Android Window registration so the
     * attribution rules remain testable on the JVM.
     */
    fun noteFrameMetrics(
        sampleAtNs: Long,
        drawDurationNs: Long,
        syncDurationNs: Long,
        commandIssueDurationNs: Long,
        swapBuffersDurationNs: Long,
    ) {
        noteFrameMetrics(
            registrationSession = synchronized(lock) { frameMetricsSession },
            sampleAtNs = sampleAtNs,
            drawDurationNs = drawDurationNs,
            syncDurationNs = syncDurationNs,
            commandIssueDurationNs = commandIssueDurationNs,
            swapBuffersDurationNs = swapBuffersDurationNs,
        )
    }

    /** Installs the debug-only Window.FrameMetrics listener for the currently visible reader. */
    fun observeView(view: View) {
        if (!enabled) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post { observeView(view) }
            return
        }
        // EpubReflowEngine intentionally builds reader views from androidContext(); once attached,
        // the decor/root context is the reliable Activity window owner.
        val window = findWindow(view.rootView.context) ?: return
        val registration = synchronized(lock) {
            if (!enabled) return@synchronized null
            if (
                registeredFrameMetricsWindow === window &&
                    registeredFrameMetricsSession == frameMetricsSession
            ) {
                return@synchronized null
            }
            val old = takeFrameMetricsRegistrationLocked()
            val session = frameMetricsSession
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                noteAndroidFrameMetrics(session, metrics)
            }
            registeredFrameMetricsWindow = window
            registeredFrameMetricsListener = listener
            registeredFrameMetricsSession = session
            old to listener
        } ?: return

        val (old, listener) = registration
        old?.let(::removeFrameMetricsListener)
        runCatching {
            window.addOnFrameMetricsAvailableListener(listener, mainHandler)
        }.onFailure {
            synchronized(lock) {
                if (registeredFrameMetricsListener === listener) {
                    registeredFrameMetricsWindow = null
                    registeredFrameMetricsListener = null
                    registeredFrameMetricsSession = 0L
                }
            }
        }
    }

    fun beginTimingNs(): Long? {
        if (!enabled) return null
        return uptimeNs()
    }

    fun endSlideArtifactRecordTiming(startNs: Long?) {
        if (startNs == null || !enabled) return
        noteSlideArtifactRecordEnd(uptimeNs())
    }

    fun endPixelTextRebindTiming(startNs: Long?) {
        if (startNs == null || !enabled) return
        notePixelTextRebind(startNs, uptimeNs())
    }

    fun endGeometryTextRebindTiming(startNs: Long?) {
        if (startNs == null || !enabled) return
        noteGeometryTextRebind(startNs, uptimeNs())
    }

    fun endBitmapPrepareToDrawTiming(startNs: Long?) {
        if (startNs == null || !enabled) return
        noteBitmapPrepareToDraw(startNs, uptimeNs())
    }

    private fun noteAndroidFrameMetrics(registrationSession: Long, metrics: FrameMetrics) {
        val callbackAtNs = System.nanoTime()
        val sampleAtNs = runCatching {
            metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        }.getOrDefault(0L).takeIf { it > 0L } ?: callbackAtNs
        noteFrameMetrics(
            registrationSession = registrationSession,
            sampleAtNs = sampleAtNs,
            fallbackSampleAtNs = callbackAtNs,
            drawDurationNs = metricOrZero(metrics, FrameMetrics.DRAW_DURATION),
            syncDurationNs = metricOrZero(metrics, FrameMetrics.SYNC_DURATION),
            commandIssueDurationNs = metricOrZero(metrics, FrameMetrics.COMMAND_ISSUE_DURATION),
            swapBuffersDurationNs = metricOrZero(metrics, FrameMetrics.SWAP_BUFFERS_DURATION),
        )
    }

    private fun metricOrZero(metrics: FrameMetrics, metric: Int): Long =
        runCatching { metrics.getMetric(metric) }.getOrDefault(0L).coerceAtLeast(0L)

    private fun noteFrameMetrics(
        registrationSession: Long,
        sampleAtNs: Long,
        fallbackSampleAtNs: Long? = null,
        drawDurationNs: Long,
        syncDurationNs: Long,
        commandIssueDurationNs: Long,
        swapBuffersDurationNs: Long,
    ) {
        synchronized(lock) {
            if (!enabled || registrationSession != frameMetricsSession) return@synchronized
            fun matches(candidate: FrameMetricWindow, timestampNs: Long): Boolean =
                timestampNs >= candidate.startedAtNs &&
                    timestampNs <= candidate.endedAtNs &&
                    (candidate.phase != FrameMetricPhase.SETTLED ||
                        candidate.sampleCount < SETTLED_FRAME_SAMPLE_LIMIT)

            val window = frameMetricWindows.toList().asReversed().firstOrNull { candidate ->
                matches(candidate, sampleAtNs)
            } ?: fallbackSampleAtNs?.let { fallback ->
                frameMetricWindows.toList().asReversed().firstOrNull { candidate ->
                    matches(candidate, fallback)
                }
            }
                ?: return@synchronized
            val safeDraw = drawDurationNs.coerceAtLeast(0L)
            val safeSync = syncDurationNs.coerceAtLeast(0L)
            val safeCommandIssue = commandIssueDurationNs.coerceAtLeast(0L)
            val safeSwapBuffers = swapBuffersDurationNs.coerceAtLeast(0L)
            when (window.phase) {
                FrameMetricPhase.OVERLAY -> overlayFrameMetrics.add(
                    safeDraw,
                    safeSync,
                    safeCommandIssue,
                    safeSwapBuffers,
                )
                FrameMetricPhase.SETTLED -> settledFrameMetrics.add(
                    safeDraw,
                    safeSync,
                    safeCommandIssue,
                    safeSwapBuffers,
                )
            }
            window.sampleCount += 1
            if (
                window.phase == FrameMetricPhase.SETTLED &&
                    window.sampleCount >= SETTLED_FRAME_SAMPLE_LIMIT
            ) {
                window.endedAtNs = minOf(window.endedAtNs, sampleAtNs)
            }
        }
    }

    private fun appendFrameMetricWindowLocked(window: FrameMetricWindow) {
        if (frameMetricWindows.size == MAX_FRAME_METRIC_WINDOWS) {
            frameMetricWindows.removeFirst()
        }
        frameMetricWindows.addLast(window)
    }

    private fun closeOpenFrameMetricWindowsLocked(atNs: Long) {
        overlayFrameMetricWindow?.let { window ->
            if (window.endedAtNs == Long.MAX_VALUE) window.endedAtNs = atNs
        }
        settledFrameMetricWindow?.let { window ->
            if (window.endedAtNs == Long.MAX_VALUE) window.endedAtNs = atNs
        }
    }

    private fun takeFrameMetricsRegistrationLocked(): Pair<Window, Window.OnFrameMetricsAvailableListener>? {
        val window = registeredFrameMetricsWindow
        val listener = registeredFrameMetricsListener
        registeredFrameMetricsWindow = null
        registeredFrameMetricsListener = null
        registeredFrameMetricsSession = 0L
        return if (window != null && listener != null) window to listener else null
    }

    private fun removeFrameMetricsListener(
        registration: Pair<Window, Window.OnFrameMetricsAvailableListener>,
    ) {
        val remove = Runnable {
            runCatching {
                registration.first.removeOnFrameMetricsAvailableListener(registration.second)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else mainHandler.post(remove)
    }

    private fun findWindow(context: Context): Window? {
        var current: Context? = context
        repeat(8) {
            when (val candidate = current) {
                is Activity -> return candidate.window
                is ContextWrapper -> current = candidate.baseContext
                else -> return null
            }
        }
        return null
    }

    private fun notePointerBoundary(boundary: PointerBoundary, eventTimeNs: Long) {
        if (!enabled) return
        val dispatchTimeNs = uptimeNs()
        synchronized(lock) {
            if (!enabled) return@synchronized
            when (boundary) {
                PointerBoundary.MOVE -> {
                    pointerMoveCount += 1
                    lastMoveEventTimeNs = eventTimeNs
                    lastMoveDispatchTimeNs = dispatchTimeNs
                }
                PointerBoundary.UP -> {
                    pointerUpCount += 1
                    lastUpEventTimeNs = eventTimeNs
                    lastUpDispatchTimeNs = dispatchTimeNs
                }
                PointerBoundary.CANCEL -> {
                    pointerCancelCount += 1
                    lastCancelEventTimeNs = eventTimeNs
                    lastCancelDispatchTimeNs = dispatchTimeNs
                }
            }
        }
    }

    private fun noteTimedRebind(startNs: Long, endNs: Long, geometry: Boolean) {
        if (!enabled) return
        val durationNs = (endNs - startNs).coerceAtLeast(0L)
        synchronized(lock) {
            if (!enabled) return@synchronized
            if (geometry) {
                geometryTextRebindCount += 1
                geometryTextRebindTotalNs += durationNs
                geometryTextRebindMaxNs = maxOf(geometryTextRebindMaxNs, durationNs)
            } else {
                pixelTextRebindCount += 1
                pixelTextRebindTotalNs += durationNs
                pixelTextRebindMaxNs = maxOf(pixelTextRebindMaxNs, durationNs)
            }
        }
    }

    private fun recomputeSlideArtifactOverlapLocked() {
        if (pointerDownCount == 0) {
            slideArtifactRecordsOverlappingDownDispatch = 0
            return
        }
        val completedCount = recentArtifactRecordIntervals.count { interval ->
            interval.startedAtNs <= lastDownDispatchTimeNs &&
                interval.endedAtNs >= lastDownEventTimeNs
        }
        val activeCount = activeSlideArtifactRecordStartNs
            ?.takeIf { it <= lastDownDispatchTimeNs }
            ?.let { 1 }
            ?: 0
        slideArtifactRecordsOverlappingDownDispatch = completedCount + activeCount
    }

    private fun clearLocked() {
        recentArtifactRecordIntervals.clear()

        precacheArmCount = 0
        pointerDownCount = 0
        queuedSlideAtDown = false
        lastDownEventTimeNs = 0L
        lastDownDispatchTimeNs = 0L
        downEventToDispatchNs = 0L
        pointerMoveCount = 0
        lastMoveEventTimeNs = 0L
        lastMoveDispatchTimeNs = 0L
        pointerUpCount = 0
        lastUpEventTimeNs = 0L
        lastUpDispatchTimeNs = 0L
        pointerCancelCount = 0
        lastCancelEventTimeNs = 0L
        lastCancelDispatchTimeNs = 0L

        slideArtifactRecordCount = 0
        slideArtifactRecordTotalNs = 0L
        slideArtifactRecordMaxNs = 0L
        slideArtifactRecordsOverlappingDownDispatch = 0
        activeSlideArtifactRecordStartNs = null

        pixelTextRebindCount = 0
        pixelTextRebindTotalNs = 0L
        pixelTextRebindMaxNs = 0L
        geometryTextRebindCount = 0
        geometryTextRebindTotalNs = 0L
        geometryTextRebindMaxNs = 0L
        bitmapPrepareToDrawCount = 0
        bitmapPrepareToDrawTotalNs = 0L
        bitmapPrepareToDrawMaxNs = 0L

        frameMetricWindows.clear()
        overlayFrameMetrics.clear()
        settledFrameMetrics.clear()
        nextFrameMetricGeneration = 0L
        overlayFrameMetricWindow = null
        settledFrameMetricWindow = null
        overlayFirstDrawSeen = false
        liveContentDispatchArmed = false
        overlayFirstDrawCount = 0
        overlayFirstDrawTotalNs = 0L
        overlayFirstDrawMaxNs = 0L
        liveContentDispatchDrawCount = 0
        liveContentDispatchDrawTotalNs = 0L
        liveContentDispatchDrawMaxNs = 0L
    }

    // FrameMetrics timestamps use the monotonic nano-time base; keep probe markers on the same
    // clock instead of fabricating nanoseconds from millisecond uptime samples.
    private fun uptimeNs(): Long = System.nanoTime()
}
