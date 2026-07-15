package dev.readflow.core.ui

import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookGridPresentationTest {

    @Test
    fun `book covers are square while controls keep accessible touch targets`() {
        assertEquals(0.dp, Dimens.coverCorner)
        assertEquals(48.dp, Dimens.touchTarget)
    }

    @Test
    fun `shelf cover aspect ratio matches Moon plus 70 to 100`() {
        // Moon+ width:height = 70:100 → 0.7f; BookGrid/drag consume Dimens.coverAspectRatio.
        assertEquals(0.7f, Dimens.coverAspectRatio)
    }

    @Test
    fun `bundle layers keep the front cover visually dominant`() {
        assertEquals(1f, bundleCoverFraction(1))
        assertEquals(0.94f, bundleCoverFraction(2))
        assertEquals(0.90f, bundleCoverFraction(3))
        assertEquals(0.86f, bundleCoverFraction(4))
    }

    @Test
    fun `topBooks index zero is front depth with zero full-rect scrim`() {
        for (layerCount in 1..4) {
            val depth = bundleLayerDepth(topBookIndex = 0, layerCount = layerCount)
            assertEquals(0, depth, "topBooks[0] must be depth 0 for layerCount=$layerCount")
            assertEquals(0f, bundleLayerScrimAlpha(depth), 0.0001f)
        }
    }

    @Test
    fun `deeper topBooks indices increase depth within four layers and keep 0_18 scrim coeff`() {
        for (layerCount in 1..4) {
            val depths = (0 until layerCount).map { bundleLayerDepth(it, layerCount) }
            assertEquals(0, depths.first())
            assertTrue(
                depths.zipWithNext().all { (before, after) -> after > before },
                "depths must increase with topBooks index for layerCount=$layerCount: $depths",
            )
            assertTrue(
                depths.all { it in 0 until layerCount },
                "depths must stay in 0..${layerCount - 1}: $depths",
            )
            assertTrue(depths.last() < 4, "supported stack is four layers: $depths")
        }
        // Coefficient 0.18 per back layer remains unchanged.
        assertEquals(0.18f, bundleLayerScrimAlpha(1), 0.0001f)
        assertEquals(0.36f, bundleLayerScrimAlpha(2), 0.0001f)
        assertEquals(0.54f, bundleLayerScrimAlpha(3), 0.0001f)
    }

    @Test
    fun `single books omit labels while bundles keep the accepted badge text`() {
        val book = BookMeta(
            id = "book-1",
            title = "Single title",
            author = "Author",
            format = BookFormat.EPUB,
        )

        assertNull(libraryItemLabel(LibraryItem.Single(book)))
        assertEquals(
            "Reading list · 1 本",
            libraryItemLabel(
                LibraryItem.Bundle(
                    BookBundle(
                        id = "bundle-1",
                        name = "Reading list",
                        books = listOf(book),
                    ),
                ),
            ),
        )
    }
}
