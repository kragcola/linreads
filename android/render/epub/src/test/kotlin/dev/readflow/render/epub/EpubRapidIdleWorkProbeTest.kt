package dev.readflow.render.epub

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubRapidIdleWorkProbeTest {

    @Test
    fun `probe records rapid idle work only while explicitly enabled`() {
        val probe = loadProbe()

        assertCleared(snapshot = probe.snapshot(), enabled = false)

        try {
            probe.reset()
            assertCleared(snapshot = probe.snapshot(), enabled = true)

            probe.notePrecacheArmed()
            probe.noteSlideArtifactRecordStart(1_010L)
            probe.noteSlideArtifactRecordEnd(1_050L)
            probe.notePointerDown(
                queuedSlide = true,
                eventTimeNs = 1_000L,
                dispatchTimeNs = 1_040L,
            )
            val inputSnapshot = probe.snapshot()
            assertEquals(1, inputSnapshot.int("precacheArmCount"))
            assertEquals(1, inputSnapshot.int("pointerDownCount"))
            assertTrue(inputSnapshot.boolean("queuedSlideAtDown"))
            assertEquals(40L, inputSnapshot.long("downEventToDispatchNs"))
            assertEquals(1, inputSnapshot.int("slideArtifactRecordCount"))
            assertEquals(
                1,
                inputSnapshot.int("slideArtifactRecordsOverlappingDownDispatch"),
            )

            probe.noteSlideArtifactRecordStart(1_060L)
            probe.noteSlideArtifactRecordEnd(1_080L)
            val artifactSnapshot = probe.snapshot()
            assertEquals(2, artifactSnapshot.int("slideArtifactRecordCount"))
            assertEquals(60L, artifactSnapshot.long("slideArtifactRecordTotalNs"))
            assertEquals(40L, artifactSnapshot.long("slideArtifactRecordMaxNs"))
            assertEquals(
                1,
                artifactSnapshot.int("slideArtifactRecordsOverlappingDownDispatch"),
            )

            probe.notePixelTextRebind(startNs = 2_000L, endNs = 2_018L)
            probe.noteGeometryTextRebind(startNs = 3_000L, endNs = 3_045L)
            val rebindSnapshot = probe.snapshot()
            assertEquals(1, rebindSnapshot.int("pixelTextRebindCount"))
            assertEquals(18L, rebindSnapshot.long("pixelTextRebindTotalNs"))
            assertEquals(18L, rebindSnapshot.long("pixelTextRebindMaxNs"))
            assertEquals(1, rebindSnapshot.int("geometryTextRebindCount"))
            assertEquals(45L, rebindSnapshot.long("geometryTextRebindTotalNs"))
            assertEquals(45L, rebindSnapshot.long("geometryTextRebindMaxNs"))

            probe.noteBitmapPrepareToDraw(startNs = 4_000L, endNs = 4_027L)
            val bitmapPrepareSnapshot = probe.snapshot()
            assertEquals(1, bitmapPrepareSnapshot.int("bitmapPrepareToDrawCount"))
            assertEquals(27L, bitmapPrepareSnapshot.long("bitmapPrepareToDrawTotalNs"))
            assertEquals(27L, bitmapPrepareSnapshot.long("bitmapPrepareToDrawMaxNs"))
        } finally {
            probe.stop()
        }

        assertCleared(snapshot = probe.snapshot(), enabled = false)
    }

    @Test
    fun `probe separates overlay frames from the bounded settled rerecord window`() {
        val probe = loadProbe()

        try {
            probe.reset()
            probe.noteSlideOverlayInstalled(atNs = 1_000L)
            probe.noteOverlayDraw(startNs = 1_010L, endNs = 1_030L)
            probe.noteFrameMetrics(
                sampleAtNs = 1_040L,
                drawDurationNs = 11L,
                syncDurationNs = 12L,
                commandIssueDurationNs = 13L,
                swapBuffersDurationNs = 14L,
            )

            probe.noteLiveContentRerecordArmed(atNs = 2_000L)
            probe.noteLiveContentDispatch(startNs = 2_010L, endNs = 2_070L)
            probe.noteFrameMetrics(
                sampleAtNs = 2_080L,
                drawDurationNs = 21L,
                syncDurationNs = 22L,
                commandIssueDurationNs = 23L,
                swapBuffersDurationNs = 24L,
            )
            probe.noteFrameMetrics(
                sampleAtNs = 2_100L,
                drawDurationNs = 31L,
                syncDurationNs = 32L,
                commandIssueDurationNs = 33L,
                swapBuffersDurationNs = 34L,
            )
            // The settled attribution is deliberately capped to two frame samples.
            probe.noteFrameMetrics(
                sampleAtNs = 2_120L,
                drawDurationNs = 41L,
                syncDurationNs = 42L,
                commandIssueDurationNs = 43L,
                swapBuffersDurationNs = 44L,
            )

            val snapshot = probe.snapshot()
            assertEquals(1, snapshot.int("overlayFirstDrawCount"))
            assertEquals(20L, snapshot.long("overlayFirstDrawTotalNs"))
            assertEquals(1, snapshot.int("liveContentDispatchDrawCount"))
            assertEquals(60L, snapshot.long("liveContentDispatchDrawTotalNs"))
            assertEquals(1, snapshot.int("frameMetricsOverlayFrameCount"))
            assertEquals(11L, snapshot.long("frameMetricsOverlayDrawTotalNs"))
            assertEquals(13L, snapshot.long("frameMetricsOverlayCommandIssueTotalNs"))
            assertEquals(2, snapshot.int("frameMetricsSettledFrameCount"))
            assertEquals(52L, snapshot.long("frameMetricsSettledDrawTotalNs"))
            assertEquals(54L, snapshot.long("frameMetricsSettledCommandIssueTotalNs"))
        } finally {
            probe.stop()
        }
    }

    private fun assertCleared(snapshot: ProbeSnapshot, enabled: Boolean) {
        assertEquals(enabled, snapshot.boolean("enabled"))
        assertEquals(0, snapshot.int("precacheArmCount"))
        assertEquals(0, snapshot.int("pointerDownCount"))
        assertFalse(snapshot.boolean("queuedSlideAtDown"))
        assertEquals(0L, snapshot.long("downEventToDispatchNs"))
        assertEquals(0, snapshot.int("slideArtifactRecordCount"))
        assertEquals(0L, snapshot.long("slideArtifactRecordTotalNs"))
        assertEquals(0L, snapshot.long("slideArtifactRecordMaxNs"))
        assertEquals(0, snapshot.int("slideArtifactRecordsOverlappingDownDispatch"))
        assertEquals(0, snapshot.int("pixelTextRebindCount"))
        assertEquals(0L, snapshot.long("pixelTextRebindTotalNs"))
        assertEquals(0L, snapshot.long("pixelTextRebindMaxNs"))
        assertEquals(0, snapshot.int("geometryTextRebindCount"))
        assertEquals(0L, snapshot.long("geometryTextRebindTotalNs"))
        assertEquals(0L, snapshot.long("geometryTextRebindMaxNs"))
        assertEquals(0, snapshot.int("bitmapPrepareToDrawCount"))
        assertEquals(0L, snapshot.long("bitmapPrepareToDrawTotalNs"))
        assertEquals(0L, snapshot.long("bitmapPrepareToDrawMaxNs"))
        assertEquals(0, snapshot.int("overlayFirstDrawCount"))
        assertEquals(0L, snapshot.long("overlayFirstDrawTotalNs"))
        assertEquals(0L, snapshot.long("overlayFirstDrawMaxNs"))
        assertEquals(0, snapshot.int("liveContentDispatchDrawCount"))
        assertEquals(0L, snapshot.long("liveContentDispatchDrawTotalNs"))
        assertEquals(0L, snapshot.long("liveContentDispatchDrawMaxNs"))
        assertEquals(0, snapshot.int("frameMetricsOverlayFrameCount"))
        assertEquals(0L, snapshot.long("frameMetricsOverlayDrawTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlayDrawMaxNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlaySyncTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlaySyncMaxNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlayCommandIssueTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlayCommandIssueMaxNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlaySwapBuffersTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsOverlaySwapBuffersMaxNs"))
        assertEquals(0, snapshot.int("frameMetricsSettledFrameCount"))
        assertEquals(0L, snapshot.long("frameMetricsSettledDrawTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledDrawMaxNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledSyncTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledSyncMaxNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledCommandIssueTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledCommandIssueMaxNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledSwapBuffersTotalNs"))
        assertEquals(0L, snapshot.long("frameMetricsSettledSwapBuffersMaxNs"))
    }

    private fun loadProbe(): ProbeHandle {
        val probeClass = try {
            Class.forName(PROBE_CLASS_NAME)
        } catch (error: ClassNotFoundException) {
            throw AssertionError(
                "Expected disabled-by-default internal probe $PROBE_CLASS_NAME to exist.",
                error,
            )
        }
        val instance = try {
            probeClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        } catch (error: ReflectiveOperationException) {
            throw AssertionError(
                "$PROBE_CLASS_NAME must be a Kotlin object so the JVM test can reset it safely.",
                error,
            )
        } ?: throw AssertionError("$PROBE_CLASS_NAME INSTANCE must not be null.")
        return ProbeHandle(probeClass, instance)
    }

    private class ProbeHandle(
        private val probeClass: Class<*>,
        private val instance: Any,
    ) {
        fun reset() {
            invoke("reset")
        }

        fun stop() {
            invoke("stop")
        }

        fun snapshot(): ProbeSnapshot = ProbeSnapshot(invoke("snapshot"))

        fun notePrecacheArmed() {
            invoke("notePrecacheArmed")
        }

        fun notePointerDown(queuedSlide: Boolean, eventTimeNs: Long, dispatchTimeNs: Long) {
            invoke("notePointerDown", queuedSlide, eventTimeNs, dispatchTimeNs)
        }

        fun noteSlideArtifactRecordStart(startNs: Long) {
            invoke("noteSlideArtifactRecordStart", startNs)
        }

        fun noteSlideArtifactRecordEnd(endNs: Long) {
            invoke("noteSlideArtifactRecordEnd", endNs)
        }

        fun notePixelTextRebind(startNs: Long, endNs: Long) {
            invoke("notePixelTextRebind", startNs, endNs)
        }

        fun noteGeometryTextRebind(startNs: Long, endNs: Long) {
            invoke("noteGeometryTextRebind", startNs, endNs)
        }

        fun noteBitmapPrepareToDraw(startNs: Long, endNs: Long) {
            invoke("noteBitmapPrepareToDraw", startNs, endNs)
        }

        fun noteSlideOverlayInstalled(atNs: Long) {
            invoke("noteSlideOverlayInstalled", atNs)
        }

        fun noteOverlayDraw(startNs: Long, endNs: Long) {
            invoke("noteOverlayDraw", startNs, endNs)
        }

        fun noteLiveContentRerecordArmed(atNs: Long) {
            invoke("noteLiveContentRerecordArmed", atNs)
        }

        fun noteLiveContentDispatch(startNs: Long, endNs: Long) {
            invoke("noteLiveContentDispatch", startNs, endNs)
        }

        fun noteFrameMetrics(
            sampleAtNs: Long,
            drawDurationNs: Long,
            syncDurationNs: Long,
            commandIssueDurationNs: Long,
            swapBuffersDurationNs: Long,
        ) {
            invoke(
                "noteFrameMetrics",
                sampleAtNs,
                drawDurationNs,
                syncDurationNs,
                commandIssueDurationNs,
                swapBuffersDurationNs,
            )
        }

        private fun invoke(methodName: String, vararg arguments: Any): Any? {
            val method = probeClass.declaredMethods.singleOrNull { candidate ->
                candidate.name == methodName && candidate.parameterTypes.contentEquals(
                    arguments.map(::parameterType).toTypedArray(),
                )
            } ?: throw AssertionError(
                "$PROBE_CLASS_NAME must expose $methodName(" +
                    arguments.joinToString { it.javaClass.simpleName } +
                    ").",
            )
            method.isAccessible = true
            return try {
                method.invoke(instance, *arguments)
            } catch (error: InvocationTargetException) {
                throw AssertionError(
                    "$PROBE_CLASS_NAME.$methodName must not throw for this probe event.",
                    error.targetException,
                )
            }
        }

        private fun parameterType(argument: Any): Class<*> = when (argument) {
            is Boolean -> Boolean::class.javaPrimitiveType!!
            is Long -> Long::class.javaPrimitiveType!!
            else -> argument.javaClass
        }
    }

    private class ProbeSnapshot(private val delegate: Any?) {
        fun boolean(propertyName: String): Boolean = value(propertyName) as? Boolean
            ?: throw AssertionError("Probe snapshot must expose Boolean $propertyName.")

        fun int(propertyName: String): Int = (value(propertyName) as? Number)?.toInt()
            ?: throw AssertionError("Probe snapshot must expose numeric $propertyName.")

        fun long(propertyName: String): Long = (value(propertyName) as? Number)?.toLong()
            ?: throw AssertionError("Probe snapshot must expose numeric $propertyName.")

        private fun value(propertyName: String): Any? {
            val snapshot = delegate
                ?: throw AssertionError("$PROBE_CLASS_NAME.snapshot() must not return null.")
            val getterName = "get" + propertyName.replaceFirstChar(Char::uppercaseChar)
            val getter = findGetter(snapshot.javaClass, getterName)
                ?: throw AssertionError("Probe snapshot must expose $getterName().")
            getter.isAccessible = true
            return try {
                getter.invoke(snapshot)
            } catch (error: InvocationTargetException) {
                throw AssertionError("Probe snapshot getter $getterName() must not throw.", error.targetException)
            }
        }

        private fun findGetter(snapshotClass: Class<*>, getterName: String): Method? =
            snapshotClass.declaredMethods.singleOrNull { method ->
                method.name == getterName && method.parameterCount == 0
            } ?: snapshotClass.methods.singleOrNull { method ->
                method.name == getterName && method.parameterCount == 0
            }
    }

    private companion object {
        const val PROBE_CLASS_NAME = "dev.readflow.render.epub.EpubRapidIdleWorkProbe"
    }
}
