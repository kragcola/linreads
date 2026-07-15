package dev.readflow.core.ui

import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryGridLayoutTest {

    @Test
    fun `library grid keeps larger covers proportional from phone to tablet`() {
        assertEquals(2, libraryGridColumns(360f))
        assertEquals(4, libraryGridColumns(800f))
        assertEquals(6, libraryGridColumns(1_280f))
    }

    @Test
    fun `grid grows covers while preserving compact and expanded breathing room`() {
        assertEquals(158f, libraryGridLayout(360f).coverWidthDp, 0.001f)
        assertEquals(180f, libraryGridLayout(800f).coverWidthDp, 0.001f)
        assertEquals(178f, libraryGridLayout(1_280f).coverWidthDp, 0.001f)

        assertEquals(12f, libraryGridLayout(360f).gapDp, 0.001f)
        assertEquals(16f, libraryGridLayout(800f).gapDp, 0.001f)
        assertEquals(20f, libraryGridLayout(1_280f).gapDp, 0.001f)
    }

    @Test
    fun `expanded grid content width remains capped`() {
        assertEquals(1_200f, libraryGridLayout(1_280f).effectiveWidthDp)
        assertEquals(Dimens.maxContentWidth.value, libraryGridLayout(1_600f).effectiveWidthDp)
    }

    @Test
    fun `tablet and expanded covers stay within a stable readable band`() {
        val oversizedWidths = (600..1_600 step 40)
            .map(Int::toFloat)
            .associateWith { libraryGridLayout(it).coverWidthDp }
            .filterValues { it !in 160f..206f }

        assertTrue(
            oversizedWidths.isEmpty(),
            "Cover widths leave 160..206dp band: $oversizedWidths",
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
