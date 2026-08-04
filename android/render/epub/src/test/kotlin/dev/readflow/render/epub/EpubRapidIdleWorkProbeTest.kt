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
        } finally {
            probe.stop()
        }

        assertCleared(snapshot = probe.snapshot(), enabled = false)
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
