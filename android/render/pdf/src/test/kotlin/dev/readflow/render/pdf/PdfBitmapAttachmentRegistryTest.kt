package dev.readflow.render.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfBitmapAttachmentRegistryTest {

    @Test
    fun `release clears owners that still reference the released value before releasing once`() {
        val released = mutableListOf<FakeBitmap>()
        val page0 = FakeBitmap(0)
        val page1 = FakeBitmap(1)
        val owner0 = FakeOwner(page0)
        val owner1 = FakeOwner(page1)
        val registry = PdfBitmapAttachmentRegistry<FakeBitmap, FakeOwner>(
            attachedValue = { it.bitmap },
            clearAttachment = { it.bitmap = null },
        )

        registry.track(owner0)
        registry.track(owner1)
        registry.release(page0, released::add)

        assertNull(owner0.bitmap)
        assertEquals(page1, owner1.bitmap)
        assertEquals(listOf(page0), released)
    }

    private data class FakeBitmap(val page: Int)

    private class FakeOwner(var bitmap: FakeBitmap?)
}
