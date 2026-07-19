package dev.readflow.render.epub

internal enum class PageShotLeaseKind {
    PINNED,
    EVICTABLE,
}

internal class PageShotBudget(
    val capacityBytes: Long,
    private val maxActiveShots: Int = DEFAULT_MAX_ACTIVE_SHOTS,
) {
    init {
        require(capacityBytes >= 0L) { "capacityBytes must not be negative" }
        require(maxActiveShots >= 0) { "maxActiveShots must not be negative" }
    }

    private var activeReservedBytes = 0L
    private var activeLeasedBytes = 0L
    private val activeReservations = mutableListOf<Reservation>()
    private val activeLeases = mutableListOf<Lease>()

    val reservedBytes: Long
        get() = activeReservedBytes

    val leasedBytes: Long
        get() = activeLeasedBytes

    val chargedBytes: Long
        get() = activeReservedBytes + activeLeasedBytes

    var isSpeculativeAdmissionPaused: Boolean = false
        private set

    fun tryReserve(
        widthPx: Int,
        heightPx: Int,
        kind: PageShotLeaseKind,
        label: String = "",
        allowOverCapacity: Boolean = false,
    ): Reservation? {
        if (kind == PageShotLeaseKind.EVICTABLE && isSpeculativeAdmissionPaused) return null
        if (activeReservations.size + activeLeases.size >= maxActiveShots) return null
        val estimatedBytes = estimatedArgb8888Bytes(widthPx, heightPx) ?: return null
        val mayExceedCapacity = allowOverCapacity && kind == PageShotLeaseKind.PINNED
        if (!mayExceedCapacity && estimatedBytes > capacityBytes - chargedBytes) return null
        activeReservedBytes += estimatedBytes
        return Reservation(estimatedBytes, kind, label, mayExceedCapacity).also(activeReservations::add)
    }

    fun relabel(identity: Any, kind: PageShotLeaseKind, label: String): Lease? {
        val lease = activeLeases.firstOrNull { it.identity === identity } ?: return null
        lease.relabel(kind, label)
        return lease
    }

    fun release(identity: Any): Boolean =
        activeLeases.firstOrNull { it.identity === identity }?.release() ?: false

    fun evictEvictable(): List<Any> {
        val evictable = activeLeases.filter { it.kind == PageShotLeaseKind.EVICTABLE }
        val identities = evictable.map(Lease::identity)
        evictable.forEach(Lease::release)
        return identities
    }

    fun pauseSpeculativeAdmission() {
        isSpeculativeAdmissionPaused = true
        activeReservations
            .filter { it.kind == PageShotLeaseKind.EVICTABLE }
            .forEach(Reservation::cancel)
    }

    fun resumeSpeculativeAdmission() {
        isSpeculativeAdmissionPaused = false
    }

    inner class Reservation internal constructor(
        internal val estimatedBytes: Long,
        internal val kind: PageShotLeaseKind,
        internal val label: String,
        private val mayExceedCapacity: Boolean,
    ) {
        private var active = true

        fun cancel() {
            if (!active) return
            active = false
            activeReservations.remove(this)
            activeReservedBytes -= estimatedBytes
        }

        fun commit(identity: Any, allocationByteCount: Int): Lease? {
            if (!active) return null
            active = false
            activeReservations.remove(this)
            activeReservedBytes -= estimatedBytes
            val actualBytes = allocationByteCount.toLong()
            if (actualBytes <= 0L) return null
            activeLeases.firstOrNull { it.identity === identity }?.let { existing ->
                if (actualBytes != existing.allocationByteCount) return null
                existing.relabel(kind, label)
                return existing
            }
            if (!mayExceedCapacity && actualBytes > capacityBytes - chargedBytes) return null
            activeLeasedBytes += actualBytes
            return Lease(identity, actualBytes, kind, label).also(activeLeases::add)
        }
    }

    inner class Lease internal constructor(
        val identity: Any,
        val allocationByteCount: Long,
        kind: PageShotLeaseKind,
        label: String,
    ) {
        private var active = true

        var kind: PageShotLeaseKind = kind
            private set

        var label: String = label
            private set

        internal fun relabel(kind: PageShotLeaseKind, label: String) {
            this.kind = kind
            this.label = label
        }

        fun release(): Boolean {
            if (!active) return false
            active = false
            activeLeases.remove(this)
            activeLeasedBytes -= allocationByteCount
            return true
        }
    }
}

private const val DEFAULT_MAX_ACTIVE_SHOTS = 3

private fun estimatedArgb8888Bytes(widthPx: Int, heightPx: Int): Long? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val pixels = widthPx.toLong() * heightPx.toLong()
    if (pixels > Long.MAX_VALUE / ARGB_8888_BYTES_PER_PIXEL) return null
    return pixels * ARGB_8888_BYTES_PER_PIXEL
}

internal fun pageShotBudgetCapacityBytes(
    memoryClassMiB: Int,
    isLowRamDevice: Boolean,
): Long {
    val memoryClassBytes = memoryClassMiB.coerceAtLeast(0).toLong() * MIB
    val divisor = if (isLowRamDevice) LOW_RAM_DIVISOR else NORMAL_RAM_DIVISOR
    val ceilingBytes = if (isLowRamDevice) LOW_RAM_CEILING_BYTES else NORMAL_RAM_CEILING_BYTES
    val proportional = memoryClassBytes / divisor
    // Normal devices need a floor that admits a tablet-sized forward pair (front + revealed).
    // 1600x2560 ARGB8888 × 2 ≈ 31.25 MiB; proportional /8 on memoryClass 192 is only 24 MiB.
    val floorBytes = if (isLowRamDevice) 0L else NORMAL_RAM_FLOOR_BYTES
    return minOf(ceilingBytes, maxOf(proportional, floorBytes))
}

private const val MIB = 1024L * 1024L
private const val NORMAL_RAM_CEILING_BYTES = 48L * MIB
/** Floor for non-low-RAM: enough for two full tablet page-shots under the 32 MiB opposite budget. */
private const val NORMAL_RAM_FLOOR_BYTES = 32L * MIB
private const val LOW_RAM_CEILING_BYTES = 24L * MIB
private const val NORMAL_RAM_DIVISOR = 8L
private const val LOW_RAM_DIVISOR = 10L
private const val ARGB_8888_BYTES_PER_PIXEL = 4L
