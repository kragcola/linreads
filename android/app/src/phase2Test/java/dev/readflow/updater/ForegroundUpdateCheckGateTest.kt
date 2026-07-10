package dev.readflow.updater

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForegroundUpdateCheckGateTest {
    @Test
    fun `one started interval triggers one update check`() {
        val checks = mutableListOf<Job>()
        val gate = ForegroundUpdateCheckGate { Job().also(checks::add) }

        gate.onEvent(Lifecycle.Event.ON_START)
        gate.onEvent(Lifecycle.Event.ON_START)

        assertEquals(1, checks.size)
        assertTrue(checks.single().isActive)
    }

    @Test
    fun `returning after stop triggers another update check`() {
        val checks = mutableListOf<Job>()
        val gate = ForegroundUpdateCheckGate { Job().also(checks::add) }

        gate.onEvent(Lifecycle.Event.ON_START)
        gate.onEvent(Lifecycle.Event.ON_STOP)
        gate.onEvent(Lifecycle.Event.ON_START)

        assertEquals(2, checks.size)
        assertTrue(checks.first().isCancelled)
        assertTrue(checks.last().isActive)
    }

    @Test
    fun `dispose cancels an in flight foreground check`() {
        val check = Job()
        val gate = ForegroundUpdateCheckGate { check }

        gate.onEvent(Lifecycle.Event.ON_START)
        gate.dispose()

        assertTrue(check.isCancelled)
    }

    @Test
    fun `only the latest request may publish its result`() {
        val guard = LatestUpdateCheckGuard()
        val staleRequest = guard.newRequest()
        val latestRequest = guard.newRequest()
        var published = ""

        val stalePublished = guard.runIfLatest(staleRequest) { published = "stale" }
        val latestPublished = guard.runIfLatest(latestRequest) { published = "latest" }

        assertFalse(stalePublished)
        assertTrue(latestPublished)
        assertEquals("latest", published)
    }
}
