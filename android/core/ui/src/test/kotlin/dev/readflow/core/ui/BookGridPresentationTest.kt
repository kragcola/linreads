package dev.readflow.core.ui

import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        // Negative / zero depths must never invent a black wash on the front cover.
        assertEquals(0f, bundleLayerScrimAlpha(0), 0.0001f)
        assertEquals(0f, bundleLayerScrimAlpha(-1), 0.0001f)
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
    fun `bundle stack source never paints a full rect scrim on the front layer`() {
        // Composition contract for 1..4 layers: draw order is back→front (index n-1..0),
        // front (topBooks[0] / depth 0) attaches neither black drawRect nor material wash.
        val source = bundleStackSource()
        assertTrue(
            source.contains("for (i in (layerCount - 1) downTo 0)"),
            "draw loop must paint high depth first so index 0 composites last (front)",
        )
        assertTrue(
            source.contains("if (layerDepth > 0 && shadowAlpha > 0f)"),
            "BundleStack must gate the black scrim so depth 0 never draws a full-rect overlay",
        )
        assertTrue(
            source.contains("showMaterialDepth = false"),
            "grouped covers must disable BookCover material depth so the front has no dark wash",
        )
        assertFalse(
            source.contains("showMaterialDepth = layerDepth == 0"),
            "front must not re-enable material depth (full-rect paper wash)",
        )
        assertTrue(
            source.contains("bundleLayerScrimAlpha(layerDepth)"),
            "scrim alpha must come from the pure depth helper",
        )
        assertFalse(
            Regex("""drawRect\(Color\.Black\.copy\(alpha = shadowAlpha\)\)""")
                .containsMatchIn(source) &&
                !source.contains("if (layerDepth > 0 && shadowAlpha > 0f)"),
            "unguarded black scrim draw must not exist on the front path",
        )
        // Effective front compositing for every layer count: depth0 alpha 0 and no scrim branch.
        for (layerCount in 1..4) {
            val frontDepth = bundleLayerDepth(topBookIndex = 0, layerCount = layerCount)
            assertEquals(0, frontDepth)
            assertEquals(0f, bundleLayerScrimAlpha(frontDepth), 0.0001f)
            // Back layers (when present) may keep intentional depth scrim.
            if (layerCount > 1) {
                val backDepth = bundleLayerDepth(topBookIndex = layerCount - 1, layerCount = layerCount)
                assertTrue(backDepth > 0)
                assertTrue(bundleLayerScrimAlpha(backDepth) > 0f)
            }
        }
    }

    private fun bundleStackSource(): String {
        val relativePath = java.nio.file.Path.of(
            "src/main/kotlin/dev/readflow/core/ui/BundleStack.kt",
        )
        val candidates = sequenceOf(
            relativePath,
            java.nio.file.Path.of("core/ui").resolve(relativePath),
            java.nio.file.Path.of("android/core/ui").resolve(relativePath),
        )
        val sourcePath = candidates.firstOrNull(java.nio.file.Files::exists)
            ?: error("Cannot locate BundleStack.kt from ${java.nio.file.Path.of("").toAbsolutePath()}")
        return sourcePath.toFile().readText()
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
