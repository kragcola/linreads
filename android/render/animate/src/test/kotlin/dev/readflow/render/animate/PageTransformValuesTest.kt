package dev.readflow.render.animate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageTransformValuesTest {

    @Test
    fun `curl transform rotates outgoing and incoming pages around opposite edges`() {
        val outgoing = curlTransformFor(position = -0.5f)
        val incoming = curlTransformFor(position = 0.5f)

        assertEquals(1f, outgoing.pivotXFraction)
        assertEquals(22.5f, outgoing.rotationY)
        assertEquals(0f, incoming.pivotXFraction)
        assertEquals(-22.5f, incoming.rotationY)
    }

    @Test
    fun `curl transform fades pages only slightly inside the viewport`() {
        val halfTurn = curlTransformFor(position = 0.5f)

        assertEquals(0.925f, halfTurn.alpha)
        assertEquals(1f, curlTransformFor(position = 0f).alpha)
    }

    @Test
    fun `curl transform hides pages outside the active pager window`() {
        assertEquals(0f, curlTransformFor(position = -1.1f).alpha)
        assertEquals(0f, curlTransformFor(position = 1.1f).alpha)
    }
}
