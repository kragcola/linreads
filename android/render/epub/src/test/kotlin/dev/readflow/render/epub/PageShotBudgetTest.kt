package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageShotBudgetTest {

    @Test
    fun `device policy caps normal and low ram budgets`() {
        assertEquals(
            48L * MIB,
            pageShotBudgetCapacityBytes(memoryClassMiB = 512, isLowRamDevice = false),
        )
        // Normal devices keep a floor large enough for a tablet forward pair (see below).
        assertEquals(
            32L * MIB,
            pageShotBudgetCapacityBytes(memoryClassMiB = 128, isLowRamDevice = false),
        )
        assertEquals(
            24L * MIB,
            pageShotBudgetCapacityBytes(memoryClassMiB = 512, isLowRamDevice = true),
        )
        assertEquals(
            128L * MIB / 10L,
            pageShotBudgetCapacityBytes(memoryClassMiB = 128, isLowRamDevice = true),
        )
    }

    @Test
    fun `normal ram floor admits tablet-sized forward page-shot pair`() {
        // Emulator tablet override 1600x2560 ARGB8888 ≈ 15.625 MiB/shot; front+revealed ≈ 31.25 MiB.
        // memoryClass 192 with /8 alone yields 24 MiB and starves the warm forward slot.
        val tabletPairBytes = 1600L * 2560L * 4L * 2L
        val capacity = pageShotBudgetCapacityBytes(memoryClassMiB = 192, isLowRamDevice = false)
        assertTrue(
            capacity >= tabletPairBytes,
            "normal budget must admit a tablet front+revealed pair; capacity=$capacity pair=$tabletPairBytes",
        )
        assertTrue(capacity <= 48L * MIB)
        // Low-RAM policy must not inherit the normal floor.
        assertEquals(
            192L * MIB / 10L,
            pageShotBudgetCapacityBytes(memoryClassMiB = 192, isLowRamDevice = true),
        )
    }

    @Test
    fun `reservation charges estimated argb bytes before allocation`() {
        val budget = PageShotBudget(capacityBytes = 64L)

        val reservation = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
        )

        assertNotNull(reservation)
        assertEquals(64L, budget.reservedBytes)
        assertEquals(64L, budget.chargedBytes)
        assertNull(
            budget.tryReserve(
                widthPx = 1,
                heightPx = 1,
                kind = PageShotLeaseKind.EVICTABLE,
            ),
        )

        reservation!!.cancel()
        assertEquals(0L, budget.chargedBytes)
    }

    @Test
    fun `commit replaces estimate with actual allocation bytes until release`() {
        val budget = PageShotBudget(capacityBytes = 96L)
        val identity = Any()
        val reservation = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
        )!!

        val lease = reservation.commit(identity, allocationByteCount = 80)

        assertNotNull(lease)
        assertSame(identity, lease!!.identity)
        assertEquals(0L, budget.reservedBytes)
        assertEquals(80L, budget.leasedBytes)
        assertEquals(80L, budget.chargedBytes)

        assertTrue(lease.release())
        assertEquals(0L, budget.chargedBytes)
    }

    @Test
    fun `commit rejects an actual allocation that exceeds the remaining budget`() {
        val budget = PageShotBudget(capacityBytes = 64L)
        val reservation = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
        )!!

        assertNull(reservation.commit(Any(), allocationByteCount = 65))
        assertEquals(0L, budget.chargedBytes)
    }

    @Test
    fun `explicit pinned reservations may exceed capacity while evictable admission stays capped`() {
        val budget = PageShotBudget(capacityBytes = 24L * MIB)
        val first = budget.tryReserve(
            widthPx = 2_048,
            heightPx = 2_048,
            kind = PageShotLeaseKind.PINNED,
        )!!.commit(Any(), allocationByteCount = SHOT_BYTES.toInt())!!

        assertNull(
            budget.tryReserve(
                widthPx = 2_048,
                heightPx = 2_048,
                kind = PageShotLeaseKind.PINNED,
            ),
        )
        val secondReservation = budget.tryReserve(
            widthPx = 2_048,
            heightPx = 2_048,
            kind = PageShotLeaseKind.PINNED,
            allowOverCapacity = true,
        )
        assertNotNull(secondReservation)
        assertEquals(32L * MIB, budget.chargedBytes)

        val second = secondReservation!!.commit(Any(), allocationByteCount = SHOT_BYTES.toInt())
        assertNotNull(second)
        assertEquals(32L * MIB, budget.leasedBytes)
        assertEquals(32L * MIB, budget.chargedBytes)
        assertNull(
            budget.tryReserve(
                widthPx = 1,
                heightPx = 1,
                kind = PageShotLeaseKind.EVICTABLE,
            ),
        )
        assertNull(
            budget.tryReserve(
                widthPx = 1,
                heightPx = 1,
                kind = PageShotLeaseKind.EVICTABLE,
                allowOverCapacity = true,
            ),
        )

        assertTrue(first.release())
        assertTrue(second!!.release())
        assertEquals(0L, budget.chargedBytes)
    }

    @Test
    fun `pinned admission rejects a fourth distinct frame even when bytes fit capacity`() {
        val budget = PageShotBudget(capacityBytes = 48L * MIB)
        val leases = listOf("active.front", "active.target", "continuity.cover").map { label ->
            budget.tryReserve(
                widthPx = 2_048,
                heightPx = 1_536,
                kind = PageShotLeaseKind.PINNED,
                label = label,
                allowOverCapacity = label == "continuity.cover",
            )!!.commit(Any(), allocationByteCount = THREE_FRAME_SHOT_BYTES.toInt())!!
        }
        assertEquals(36L * MIB, budget.chargedBytes)

        assertNull(
            budget.tryReserve(
                widthPx = 2_048,
                heightPx = 1_536,
                kind = PageShotLeaseKind.PINNED,
                label = "active.extra",
                allowOverCapacity = true,
            ),
        )
        assertEquals(36L * MIB, budget.chargedBytes)

        leases.forEach { assertTrue(it.release()) }
        assertEquals(0L, budget.chargedBytes)
    }

    @Test
    fun `identity relabel transfers an existing lease without charging twice`() {
        val budget = PageShotBudget(capacityBytes = 128L)
        val identity = Any()
        val lease = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
            label = "active.front",
        )!!.commit(identity, allocationByteCount = 64)!!

        val transferred = budget.relabel(
            identity = identity,
            kind = PageShotLeaseKind.EVICTABLE,
            label = "cache.current",
        )

        assertSame(lease, transferred)
        assertEquals(PageShotLeaseKind.EVICTABLE, lease.kind)
        assertEquals("cache.current", lease.label)
        assertEquals(64L, budget.chargedBytes)
    }

    @Test
    fun `committing an already leased identity returns the existing lease`() {
        val budget = PageShotBudget(capacityBytes = 192L)
        val identity = Any()
        val original = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
            label = "active.target",
        )!!.commit(identity, allocationByteCount = 64)!!
        val duplicateReservation = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.EVICTABLE,
            label = "cache.forward",
        )!!

        val transferred = duplicateReservation.commit(identity, allocationByteCount = 64)

        assertSame(original, transferred)
        assertEquals(PageShotLeaseKind.EVICTABLE, original.kind)
        assertEquals("cache.forward", original.label)
        assertEquals(64L, budget.chargedBytes)
    }

    @Test
    fun `pausing speculative admission cancels pending evictable work until resume`() {
        val budget = PageShotBudget(capacityBytes = 192L)
        val pendingSpeculative = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.EVICTABLE,
        )!!

        budget.pauseSpeculativeAdmission()

        assertTrue(budget.isSpeculativeAdmissionPaused)
        assertEquals(0L, budget.reservedBytes)
        assertNull(pendingSpeculative.commit(Any(), allocationByteCount = 64))
        assertNull(
            budget.tryReserve(
                widthPx = 4,
                heightPx = 4,
                kind = PageShotLeaseKind.EVICTABLE,
            ),
        )
        val pinned = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
        )
        assertNotNull(pinned)
        pinned!!.cancel()

        budget.resumeSpeculativeAdmission()
        val resumed = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.EVICTABLE,
        )
        assertNotNull(resumed)
        resumed!!.cancel()
    }

    @Test
    fun `release by identity only releases the exact leased object`() {
        val budget = PageShotBudget(capacityBytes = 64L)
        val identity = EqualIdentity(1)
        val equalButDistinct = EqualIdentity(1)
        val lease = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
        )!!.commit(identity, allocationByteCount = 64)!!

        assertFalse(budget.release(equalButDistinct))
        assertEquals(64L, budget.chargedBytes)
        assertTrue(budget.release(identity))
        assertEquals(0L, budget.chargedBytes)
        assertFalse(lease.release())
    }

    @Test
    fun `eviction releases only evictable leases and returns their identities`() {
        val budget = PageShotBudget(capacityBytes = 128L)
        val pinnedIdentity = Any()
        val evictableIdentity = Any()
        val pinned = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.PINNED,
        )!!.commit(pinnedIdentity, allocationByteCount = 64)!!
        val evictable = budget.tryReserve(
            widthPx = 4,
            heightPx = 4,
            kind = PageShotLeaseKind.EVICTABLE,
        )!!.commit(evictableIdentity, allocationByteCount = 64)!!

        val evicted = budget.evictEvictable()

        assertEquals(1, evicted.size)
        assertSame(evictableIdentity, evicted.single())
        assertEquals(64L, budget.chargedBytes)
        assertFalse(evictable.release())
        assertTrue(pinned.release())
        assertEquals(0L, budget.chargedBytes)
    }

    private data class EqualIdentity(val value: Int)

    private companion object {
        const val MIB = 1024L * 1024L
        const val SHOT_BYTES = 16L * MIB
        const val THREE_FRAME_SHOT_BYTES = 12L * MIB
    }
}
