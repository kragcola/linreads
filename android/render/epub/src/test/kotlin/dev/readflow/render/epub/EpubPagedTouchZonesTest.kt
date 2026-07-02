package dev.readflow.render.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubPagedTouchZonesTest {

    @Test
    fun `inner center box is the only paged temporary scroll zone`() {
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 115f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 215f),
        )
    }

    @Test
    fun `center box boundaries remain safe zones`() {
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 120f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 180f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 240f),
        )
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 360f),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 100f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 200f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 200f),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 400f),
        )
    }

    @Test
    fun `center box boundaries remain safe with non divisible viewport sizes`() {
        val width = 1007
        val height = 1601
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(
                width = width,
                height = height,
                downX = width * 0.40f,
                downY = height * 0.50f,
            ),
        )
        assertEquals(
            EpubPagedTouchZone.TemporaryScroll,
            EpubPagedTouchZones.classify(
                width = width,
                height = height,
                downX = width * 0.50f,
                downY = height * 0.40f,
            ),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(
                width = width,
                height = height,
                downX = width / 3f,
                downY = height * 0.50f,
            ),
        )
        assertEquals(
            EpubPagedTouchZone.CenterDead,
            EpubPagedTouchZones.classify(
                width = width,
                height = height,
                downX = width * 0.50f,
                downY = height / 3f,
            ),
        )
    }

    @Test
    fun `left and right reading edges remain page turn zones`() {
        assertEquals(
            EpubPagedTouchZone.PageTurn,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 50f, downY = 300f),
        )
        assertEquals(
            EpubPagedTouchZone.PageTurn,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 250f, downY = 300f),
        )
    }

    @Test
    fun `middle column outside center third is not temporary scroll`() {
        assertEquals(
            EpubPagedTouchZone.PageTurn,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 100f),
        )
        assertEquals(
            EpubPagedTouchZone.PageTurn,
            EpubPagedTouchZones.classify(width = 300, height = 600, downX = 150f, downY = 500f),
        )
    }
}
