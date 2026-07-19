package dev.readflow.render.epub

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts full page-shot allocations that recapture live content (not warm-cache transfers).
 * Warm interactive turns must leave [total] unchanged after precache; cold deferred handoffs may
 * capture on later animation frames, never on the threshold MOVE itself.
 */
internal object EpubPageShotCaptureProbe {
    private val total = AtomicInteger(0)
    @Volatile private var enabled = false

    fun reset() {
        total.set(0)
        enabled = true
    }

    fun stop() {
        enabled = false
        total.set(0)
    }

    fun noteCapture() {
        if (!enabled) return
        total.incrementAndGet()
    }

    fun total(): Int = total.get()
}
