package dev.readflow.core.ui

import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryGridLayoutTest {

    @Test
    fun `library grid restores phone tablet and expanded proportions`() {
        // Phone keeps dense Moon-like packing; tablet uses a higher cover floor so
        // columns do not over-pack after the 8dp inter-cover gap (MyCardViewGrid).
        // Full cell composition: usable = min(width, 1120) - 2*screenEdge(20);
        // cover = (usable - 8*(cols-1)) / cols; gaps fixed 8h/10v (MyCardViewGrid).
        assertEquals(2, libraryGridColumns(360f))
        // Medium foldable / small tablet (600dp): 4 columns near 134dp — not the
        // oversized four-column tablet regression (that would be 800dp→4 large cells).
        assertEquals(4, libraryGridColumns(600f))
        assertEquals(5, libraryGridColumns(800f))
        assertEquals(7, libraryGridColumns(1_280f))
    }

    @Test
    fun `grid keeps covers near the accepted multi-device scale`() {
        // usable = min(width, 1120) - 2*20; cover = (usable - 8*(cols-1)) / cols
        // 360: usable 320 → 2×156; 600: usable 560 → 4×134; 800: 760 → 5×145.6; 1280: 1080 → 7×~147.4
        assertEquals(156f, libraryGridLayout(360f).coverWidthDp, 0.001f)
        assertEquals(134f, libraryGridLayout(600f).coverWidthDp, 0.001f)
        assertEquals(145.6f, libraryGridLayout(800f).coverWidthDp, 0.001f)
        assertEquals(147.42857f, libraryGridLayout(1_280f).coverWidthDp, 0.01f)

        // Moon+ MyCardViewGrid L/R 4dp each → 8dp inter-cover; top 8 + bottom 2 → 10dp row gap.
        for (width in listOf(360f, 600f, 800f, 1_280f)) {
            val layout = libraryGridLayout(width)
            assertEquals(8f, layout.horizontalGapDp, 0.001f, "h-gap at ${width}dp")
            assertEquals(10f, layout.verticalGapDp, 0.001f, "v-gap at ${width}dp")
            // Cell height from 70:100 aspect; whole cover cell stays ≥48dp touch floor.
            val coverHeight = layout.coverWidthDp / Dimens.coverAspectRatio
            assertTrue(
                coverHeight >= Dimens.touchTarget.value,
                "cover cell height at ${width}dp must stay ≥48dp: w=${layout.coverWidthDp} h=$coverHeight",
            )
        }
        // 800dp must stay 5 columns (not regress to 4 oversized tablet columns).
        assertEquals(5, libraryGridLayout(800f).columns)
        assertTrue(
            libraryGridLayout(800f).coverWidthDp <= LibraryCoverMaxWidthDp + 0.001f,
            "800dp tablet must not balloon covers past the 156dp phone hero",
        )
    }

    @Test
    fun `tablet packing floor keeps covers near phone scale`() {
        // Medium/Expanded must pack against the tablet floor, not the phone 116dp min.
        assertEquals(116f, libraryCoverMinWidthDp(360f), 0.001f)
        assertEquals(132f, libraryCoverMinWidthDp(600f), 0.001f)
        assertEquals(132f, libraryCoverMinWidthDp(1_280f), 0.001f)
        assertEquals(116f, Dimens.coverMinWidth.value, 0.001f)
        assertEquals(132f, Dimens.coverMinWidthTablet.value, 0.001f)

        // Phone cover stays the denser accepted cell; tablet covers must not drop below it.
        val phoneCover = libraryGridLayout(360f).coverWidthDp
        val tabletCover = libraryGridLayout(800f).coverWidthDp
        val expandedCover = libraryGridLayout(1_280f).coverWidthDp
        assertTrue(
            tabletCover >= 132f,
            "tablet covers shrank below Moon-like tablet floor: $tabletCover",
        )
        assertTrue(
            expandedCover >= 132f,
            "expanded covers shrank below Moon-like tablet floor: $expandedCover",
        )
        assertTrue(
            tabletCover <= phoneCover + 0.001f,
            "tablet covers must not grow larger than the phone hero cell: phone=$phoneCover tablet=$tabletCover",
        )
        assertTrue(
            expandedCover <= phoneCover + 0.001f,
            "expanded covers must not grow larger than the phone hero cell: phone=$phoneCover expanded=$expandedCover",
        )
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
