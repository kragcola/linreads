package dev.readflow.acceptance

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.readflow.MainActivity
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device evidence for an already resumed EPUB reader session.
 *
 * This deliberately never launches an activity, changes persisted reader state, imports a book,
 * or synthesizes a completed gesture. It skips unless the runner is attached while a real EPUB
 * [EpubFlowView] is already visible in a resumed [MainActivity].
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class EpubRapidIdleCurrentSessionInstrumentationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun resumedEpubFlowView_subthresholdCancel_preservesPositionAndRecordsRapidIdleStream() {
        instrumentation.waitForIdleSync()
        val initialSession = instrumentation.runOnMainSyncWithResult {
            currentResumedEpubSessionOrNull()
        }
        assumeTrue(NO_RESUMED_EPUB_SESSION, initialSession != null)
        val expectedSurface = checkNotNull(initialSession).flowView

        var rapidIdleProbe: ProbeHandle? = null
        var pageShotProbe: ProbeHandle? = null
        try {
            rapidIdleProbe = ProbeHandle.load(RAPID_IDLE_PROBE_CLASS_NAME)
            pageShotProbe = ProbeHandle.load(PAGE_SHOT_PROBE_CLASS_NAME)
            val rapidProbe = checkNotNull(rapidIdleProbe)
            val captureProbe = checkNotNull(pageShotProbe)

            val immediate = instrumentation.runOnMainSyncWithResult {
                val session = currentResumedEpubSessionOrNull()
                assumeTrue(
                    "Skipping: the resumed EPUB EpubFlowView disappeared or was rebound before input dispatch.",
                    session != null && session.flowView === expectedSurface,
                )
                val flowView = checkNotNull(session).flowView

                rapidProbe.reset()
                captureProbe.reset()
                val rapidBefore = RapidIdleSnapshot.from(rapidProbe.snapshot())
                val pageShotsBefore = captureProbe.total()
                assertTrue("rapid-idle probe must be enabled by reset", rapidBefore.enabled)
                assertEquals("page-shot probe must reset before input", 0, pageShotsBefore)

                val positionBefore = flowView.positionSnapshot()
                dispatchSubthresholdCancel(flowView)
                val positionAfterDispatch = flowView.positionSnapshot()
                val rapidAfterDispatch = RapidIdleSnapshot.from(rapidProbe.snapshot())
                val pageShotsAfterDispatch = captureProbe.total()

                ImmediateObservation(
                    flowView = flowView,
                    positionBefore = positionBefore,
                    positionAfterDispatch = positionAfterDispatch,
                    rapidBefore = rapidBefore,
                    rapidAfterDispatch = rapidAfterDispatch,
                    pageShotsBefore = pageShotsBefore,
                    pageShotsAfterDispatch = pageShotsAfterDispatch,
                )
            }

            logImmediateObservation(immediate)
            assertPositionUnchanged("direct sub-threshold CANCEL", immediate.positionBefore, immediate.positionAfterDispatch)
            assertRapidIdleStream(immediate.rapidAfterDispatch)
            assertEquals(
                "a sub-threshold CANCEL must not recapture a full page shot during direct dispatch",
                0,
                immediate.pageShotDelta,
            )

            instrumentation.waitForIdleSync()
            val settled = instrumentation.runOnMainSyncWithResult {
                val session = currentResumedEpubSessionOrNull()
                assumeTrue(
                    "Skipping: the resumed EPUB EpubFlowView disappeared or was rebound before idle verification.",
                    session != null && session.flowView === immediate.flowView,
                )
                SettledObservation(
                    positionAfterIdle = checkNotNull(session).flowView.positionSnapshot(),
                    rapidAfterIdle = RapidIdleSnapshot.from(rapidProbe.snapshot()),
                    pageShotsAfterIdle = captureProbe.total(),
                )
            }

            logSettledObservation(immediate, settled)
            assertPositionUnchanged("post-idle sub-threshold CANCEL", immediate.positionBefore, settled.positionAfterIdle)
        } finally {
            rapidIdleProbe?.stopQuietly()
            pageShotProbe?.stopQuietly()
        }
    }

    /** Runs entirely on the main thread and always recycles the synthetic events. */
    private fun dispatchSubthresholdCancel(target: View) {
        check(target.isAttachedToWindow && target.isShown) { "EpubFlowView must stay attached and visible" }
        check(target.width > 2 && target.height > 2) {
            "EpubFlowView is too small for non-destructive sub-threshold input: ${target.width}x${target.height}"
        }

        val x = target.width / 2f
        val y = target.height / 2f
        val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop
        val moveX = (x + (touchSlop / 2).coerceAtLeast(1)).coerceAtMost(target.width - 1f)
        check(abs(moveX - x) <= touchSlop) {
            "synthetic MOVE must remain below EpubFlowView touch slop: delta=${abs(moveX - x)} slop=$touchSlop"
        }

        val downTime = SystemClock.uptimeMillis()
        val moveTime = downTime + EVENT_STEP_MS
        val cancelTime = moveTime + EVENT_STEP_MS
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        val move = motionEvent(downTime, moveTime, MotionEvent.ACTION_MOVE, moveX, y)
        val cancel = motionEvent(downTime, cancelTime, MotionEvent.ACTION_CANCEL, moveX, y)
        try {
            target.dispatchTouchEvent(down)
            target.dispatchTouchEvent(move)
            target.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
            move.recycle()
            down.recycle()
        }
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }

    private fun currentResumedEpubSessionOrNull(): CurrentEpubSession? {
        val resumedActivities = buildList {
            addAll(
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED),
            )
            addAll(activityThreadResumedActivities())
        }.distinct()
        for (activity in resumedActivities) {
            if (activity !is MainActivity || activity.isFinishing || activity.isDestroyed) continue
            val flowView = activity.window.decorView.findDescendant { view ->
                view.isEpubFlowSurface() &&
                    view.isAttachedToWindow &&
                    view.isShown &&
                    view.isEnabled &&
                    view.width > 2 &&
                    view.height > 2
            } ?: continue
            return CurrentEpubSession(flowView)
        }
        return null
    }

    /**
     * ActivityLifecycleMonitorRegistry cannot enumerate an Activity that was resumed before the
     * instrumentation runner attached. ActivityThread is read only here, solely to attach to the
     * already-open real reader session; the runner is invoked with --no-restart so that session is
     * not force-stopped before this lookup.
     */
    private fun activityThreadResumedActivities(): List<Activity> {
        val thread = runCatching {
            Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrNull() ?: return emptyList()
        val records = runCatching {
            thread.javaClass.getDeclaredField("mActivities").apply { isAccessible = true }
                .get(thread) as? Map<*, *>
        }.getOrNull() ?: return emptyList()
        return records.values.mapNotNull { record ->
            val candidate = record ?: return@mapNotNull null
            val activity = candidate.privateField("activity") as? Activity ?: return@mapNotNull null
            val paused = candidate.privateField("paused") as? Boolean ?: return@mapNotNull null
            val stopped = candidate.privateField("stopped") as? Boolean ?: return@mapNotNull null
            activity.takeIf { !paused && !stopped }
        }
    }

    private fun Any.privateField(name: String): Any? = runCatching {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
    }.getOrNull()

    private fun View.isEpubFlowSurface(): Boolean =
        javaClass.name == EPUB_FLOW_VIEW_CLASS_NAME ||
            (this is android.widget.ScrollView && javaClass.declaredMethods.any { method ->
                method.name == "currentPageIndex" && method.parameterCount == 0
            })

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun View.positionSnapshot(): ReaderPosition = ReaderPosition(
        currentPageIndex = reflectIntNoArg("currentPageIndex"),
        scrollY = scrollY,
    )

    private fun View.reflectIntNoArg(name: String): Int {
        val method = javaClass.declaredMethods.singleOrNull { candidate ->
            candidate.name == name && candidate.parameterCount == 0
        } ?: throw AssertionError("$EPUB_FLOW_VIEW_CLASS_NAME must expose $name().")
        method.isAccessible = true
        return try {
            (method.invoke(this) as? Number)?.toInt()
                ?: throw AssertionError("$EPUB_FLOW_VIEW_CLASS_NAME.$name() must return a number.")
        } catch (error: InvocationTargetException) {
            throw AssertionError("$EPUB_FLOW_VIEW_CLASS_NAME.$name() must not throw.", error.targetException)
        }
    }

    private fun assertPositionUnchanged(
        phase: String,
        before: ReaderPosition,
        after: ReaderPosition,
    ) {
        assertEquals("$phase changed current page index", before.currentPageIndex, after.currentPageIndex)
        assertEquals("$phase changed scrollY", before.scrollY, after.scrollY)
    }

    private fun assertRapidIdleStream(snapshot: RapidIdleSnapshot) {
        assertTrue("rapid-idle probe must stay enabled while sampling", snapshot.enabled)
        assertTrue("rapid-idle probe did not observe ACTION_DOWN: $snapshot", snapshot.pointerDownCount >= 1)
        assertTrue("rapid-idle probe did not observe ACTION_MOVE: $snapshot", snapshot.pointerMoveCount >= 1)
        assertTrue("rapid-idle probe did not observe ACTION_CANCEL: $snapshot", snapshot.pointerCancelCount >= 1)
        assertEquals("test stream must not contain ACTION_UP: $snapshot", 0, snapshot.pointerUpCount)
    }

    private fun logImmediateObservation(observation: ImmediateObservation) {
        Log.i(
            TAG,
            "immediate position=${observation.positionBefore}->${observation.positionAfterDispatch} " +
                "pageShotDelta=${observation.pageShotDelta} rapidBefore=${observation.rapidBefore.summary()} " +
                "rapidAfter=${observation.rapidAfterDispatch.summary()} rawAfter=${observation.rapidAfterDispatch.raw}",
        )
    }

    private fun logSettledObservation(
        immediate: ImmediateObservation,
        settled: SettledObservation,
    ) {
        Log.i(
            TAG,
            "settled position=${immediate.positionBefore}->${settled.positionAfterIdle} " +
                "pageShotDelta=${settled.pageShotsAfterIdle - immediate.pageShotsBefore} " +
                "rapidAfterIdle=${settled.rapidAfterIdle.summary()} rawAfterIdle=${settled.rapidAfterIdle.raw}",
        )
    }

    private fun <T> Instrumentation.runOnMainSyncWithResult(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync {
            result = runCatching(block)
        }
        return checkNotNull(result) { "main-thread callback did not return a result" }.getOrThrow()
    }

    private data class CurrentEpubSession(
        val flowView: View,
    )

    private data class ReaderPosition(
        val currentPageIndex: Int,
        val scrollY: Int,
    )

    private data class ImmediateObservation(
        val flowView: View,
        val positionBefore: ReaderPosition,
        val positionAfterDispatch: ReaderPosition,
        val rapidBefore: RapidIdleSnapshot,
        val rapidAfterDispatch: RapidIdleSnapshot,
        val pageShotsBefore: Int,
        val pageShotsAfterDispatch: Int,
    ) {
        val pageShotDelta: Int
            get() = pageShotsAfterDispatch - pageShotsBefore
    }

    private data class SettledObservation(
        val positionAfterIdle: ReaderPosition,
        val rapidAfterIdle: RapidIdleSnapshot,
        val pageShotsAfterIdle: Int,
    )

    private data class RapidIdleSnapshot(
        val raw: String,
        val enabled: Boolean,
        val precacheArmCount: Int,
        val pointerDownCount: Int,
        val pointerMoveCount: Int,
        val pointerUpCount: Int,
        val pointerCancelCount: Int,
        val slideArtifactRecordCount: Int,
        val pixelTextRebindCount: Int,
        val geometryTextRebindCount: Int,
    ) {
        fun summary(): String =
            "enabled=$enabled precacheArms=$precacheArmCount down=$pointerDownCount " +
                "move=$pointerMoveCount up=$pointerUpCount cancel=$pointerCancelCount " +
                "artifactRecords=$slideArtifactRecordCount pixelRebinds=$pixelTextRebindCount " +
                "geometryRebinds=$geometryTextRebindCount"

        companion object {
            fun from(snapshot: Any?): RapidIdleSnapshot {
                val delegate = snapshot ?: throw AssertionError("rapid-idle probe snapshot must not be null.")
                return RapidIdleSnapshot(
                    raw = delegate.toString(),
                    enabled = delegate.booleanProperty("enabled"),
                    precacheArmCount = delegate.intProperty("precacheArmCount"),
                    pointerDownCount = delegate.intProperty("pointerDownCount"),
                    pointerMoveCount = delegate.intProperty("pointerMoveCount"),
                    pointerUpCount = delegate.intProperty("pointerUpCount"),
                    pointerCancelCount = delegate.intProperty("pointerCancelCount"),
                    slideArtifactRecordCount = delegate.intProperty("slideArtifactRecordCount"),
                    pixelTextRebindCount = delegate.intProperty("pixelTextRebindCount"),
                    geometryTextRebindCount = delegate.intProperty("geometryTextRebindCount"),
                )
            }

            private fun Any.booleanProperty(propertyName: String): Boolean =
                property(propertyName) as? Boolean
                    ?: throw AssertionError("probe snapshot must expose Boolean $propertyName.")

            private fun Any.intProperty(propertyName: String): Int =
                (property(propertyName) as? Number)?.toInt()
                    ?: throw AssertionError("probe snapshot must expose numeric $propertyName.")

            private fun Any.property(propertyName: String): Any? {
                val getterName = "get" + propertyName.replaceFirstChar(Char::uppercaseChar)
                val getter = javaClass.declaredMethods.singleOrNull { method ->
                    method.name == getterName && method.parameterCount == 0
                } ?: javaClass.methods.singleOrNull { method ->
                    method.name == getterName && method.parameterCount == 0
                } ?: throw AssertionError("probe snapshot must expose $getterName().")
                getter.isAccessible = true
                return try {
                    getter.invoke(this)
                } catch (error: InvocationTargetException) {
                    throw AssertionError(
                        "probe snapshot getter $getterName() must not throw.",
                        error.targetException,
                    )
                }
            }
        }
    }

    private class ProbeHandle private constructor(
        private val className: String,
        private val probeClass: Class<*>,
        private val instance: Any,
    ) {
        fun reset() {
            invokeNoArg("reset")
        }

        fun snapshot(): Any? = invokeNoArg("snapshot")

        fun total(): Int = (invokeNoArg("total") as? Number)?.toInt()
            ?: throw AssertionError("$className.total() must return a number.")

        fun stopQuietly() {
            runCatching { invokeNoArg("stop") }
        }

        private fun invokeNoArg(methodName: String): Any? {
            val method = probeClass.declaredMethods.singleOrNull { candidate ->
                candidate.name == methodName && candidate.parameterCount == 0
            } ?: throw AssertionError("$className must expose $methodName().")
            method.isAccessible = true
            return try {
                method.invoke(instance)
            } catch (error: InvocationTargetException) {
                throw AssertionError("$className.$methodName() must not throw.", error.targetException)
            }
        }

        companion object {
            fun load(className: String): ProbeHandle {
                val probeClass = try {
                    Class.forName(className)
                } catch (error: ClassNotFoundException) {
                    throw AssertionError("Expected internal probe $className to exist.", error)
                }
                val instance = try {
                    probeClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                } catch (error: ReflectiveOperationException) {
                    throw AssertionError("$className must remain a Kotlin object for instrumentation.", error)
                } ?: throw AssertionError("$className INSTANCE must not be null.")
                return ProbeHandle(className, probeClass, instance)
            }
        }
    }

    private companion object {
        private const val TAG = "EpubRapidIdleSession"
        private const val EVENT_STEP_MS = 8L
        private const val EPUB_FLOW_VIEW_CLASS_NAME = "dev.readflow.render.epub.EpubFlowView"
        private const val RAPID_IDLE_PROBE_CLASS_NAME = "dev.readflow.render.epub.EpubRapidIdleWorkProbe"
        private const val PAGE_SHOT_PROBE_CLASS_NAME = "dev.readflow.render.epub.EpubPageShotCaptureProbe"
        private const val NO_RESUMED_EPUB_SESSION =
            "Skipping: no resumed MainActivity with an attached visible EpubFlowView; " +
                "resume a real EPUB reader session before running this acceptance test."
    }
}
