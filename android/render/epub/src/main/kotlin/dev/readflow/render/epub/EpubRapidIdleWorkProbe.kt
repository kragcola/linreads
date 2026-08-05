package dev.readflow.render.epub

import android.os.SystemClock
import java.util.ArrayDeque

/**
 * Disabled-by-default, in-memory timing evidence for rapid-idle reader work.
 * It is reset explicitly by instrumentation and never writes outside this process.
 */
internal object EpubRapidIdleWorkProbe {
    private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
    private const val MAX_RECENT_ARTIFACT_RECORDS = 16

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
    )

    private enum class PointerBoundary { MOVE, UP, CANCEL }

    private data class ArtifactRecordInterval(
        val startedAtNs: Long,
        val endedAtNs: Long,
    )

    private val lock = Any()
    @Volatile private var enabled = false
    private val recentArtifactRecordIntervals = ArrayDeque<ArtifactRecordInterval>()

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

    fun reset() {
        synchronized(lock) {
            clearLocked()
            enabled = true
        }
    }

    fun stop() {
        synchronized(lock) {
            enabled = false
            clearLocked()
        }
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
    }

    private fun uptimeNs(): Long = SystemClock.uptimeMillis() * NANOSECONDS_PER_MILLISECOND
}
