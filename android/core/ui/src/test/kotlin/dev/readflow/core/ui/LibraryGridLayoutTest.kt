package dev.readflow.core.ui

import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryGridLayoutTest {

    @Test
    fun `library grid restores phone tablet and expanded proportions`() {
        // Moon+ fixed 8dp horizontal gap yields denser columns than 20/24/28.
        assertEquals(2, libraryGridColumns(360f))
        assertEquals(6, libraryGridColumns(800f))
        assertEquals(8, libraryGridColumns(1_280f))
    }

    @Test
    fun `grid keeps covers near the accepted multi-device scale`() {
        // usable = min(width, 1120) - 2*20; cover = (usable - 8*(cols-1)) / cols
        assertEquals(156f, libraryGridLayout(360f).coverWidthDp, 0.001f)
        assertEquals(120f, libraryGridLayout(800f).coverWidthDp, 0.001f)
        assertEquals(128f, libraryGridLayout(1_280f).coverWidthDp, 0.001f)

        // Moon+ MyCardViewGrid L/R 4dp each → 8dp inter-cover; top 8 + bottom 2 → 10dp row gap.
        assertEquals(8f, libraryGridLayout(360f).horizontalGapDp, 0.001f)
        assertEquals(8f, libraryGridLayout(800f).horizontalGapDp, 0.001f)
        assertEquals(8f, libraryGridLayout(1_280f).horizontalGapDp, 0.001f)
        assertEquals(10f, libraryGridLayout(360f).verticalGapDp, 0.001f)
        assertEquals(10f, libraryGridLayout(800f).verticalGapDp, 0.001f)
        assertEquals(10f, libraryGridLayout(1_280f).verticalGapDp, 0.001f)
    }

    @Test
    fun `shelf tokens pin Moon plus fixed gaps and retained cover aspect`() {
        assertEquals(8f, Dimens.gridGapHorizontal.value, 0.001f)
        assertEquals(10f, Dimens.gridGapVertical.value, 0.001f)
        assertEquals(0.7f, Dimens.coverAspectRatio)
        assertEquals(20f, Dimens.screenEdge.value, 0.001f)
        assertEquals(48f, Dimens.touchTarget.value, 0.001f)
    }

    @Test
    fun `expanded grid content width remains capped`() {
        assertEquals(1_120f, libraryGridLayout(1_280f).effectiveWidthDp)
        assertEquals(Dimens.maxContentWidth.value, libraryGridLayout(1_600f).effectiveWidthDp)
    }

    @Test
    fun `tablet and expanded covers never become oversized`() {
        val oversizedWidths = (600..1_600 step 40)
            .map(Int::toFloat)
            .associateWith { libraryGridLayout(it).coverWidthDp }
            .filterValues { it > 156f }

        assertTrue(
            oversizedWidths.isEmpty(),
            "Cover widths exceed 156dp: $oversizedWidths",
        )
    }

    @Test
    fun `column count never decreases when the window grows across expanded gap`() {
        val columns = (995..1_021).map { width -> libraryGridColumns(width.toFloat()) }

        assertTrue(
            columns.zipWithNext().all { (before, after) -> after >= before },
            "Column count regressed across 995..1021dp: $columns",
        )
    }

    @Test
    fun `display type is compact enough for the library header`() {
        assertEquals(26.sp, ReadflowType.display.fontSize)
        assertEquals(34.sp, ReadflowType.display.lineHeight)
    }

    @Test
    fun `title type retains the previous settings proportion`() {
        assertEquals(22.sp, ReadflowType.title.fontSize)
        assertEquals(30.sp, ReadflowType.title.lineHeight)
    }

}
