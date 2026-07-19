package dev.readflow.render.epub

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts Spannable span-host resolutions for page-boundary image crop draws.
 *
 * Live draw and page-shot capture must reuse generation-owned host drawables from page layout
 * metadata; repeated [Spannable.getSpans] / firstOrNull on every frame is the jank hot path this
 * probe makes observable in unit tests.
 */
internal object EpubBoundaryImageHostProbe {
    private val spanHostLookups = AtomicInteger(0)
    private val nonBitmapBoundsCopies = AtomicInteger(0)
    @Volatile private var enabled = false

    fun reset() {
        spanHostLookups.set(0)
        nonBitmapBoundsCopies.set(0)
        enabled = true
    }

    fun stop() {
        enabled = false
        spanHostLookups.set(0)
        nonBitmapBoundsCopies.set(0)
    }

    fun noteSpanHostLookup() {
        if (!enabled) return
        spanHostLookups.incrementAndGet()
    }

    fun noteNonBitmapBoundsCopy() {
        if (!enabled) return
        nonBitmapBoundsCopies.incrementAndGet()
    }

    fun spanHostLookups(): Int = spanHostLookups.get()

    fun nonBitmapBoundsCopies(): Int = nonBitmapBoundsCopies.get()
}
