package dev.readflow.core.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShelfBoardVisualContractTest {

    @Test
    fun `shelf board uses a restrained themed paper and line treatment`() {
        val source = shelfBoardSource()

        assertTrue(source.contains(".height(8.dp)"), "ShelfBoard must keep its 8dp footprint")
        assertFalse(
            Regex("(?:vertical|horizontal|radial|sweep)Gradient").containsMatchIn(source),
            "ShelfBoard must not use gradients",
        )
        assertFalse(source.contains("Color(0x"), "ShelfBoard colors must come from the active palette")
        assertTrue(source.contains("palette.paperDeep"), "ShelfBoard must use the themed deep paper tone")

        val lineDrawCount = Regex("\\bdrawLine\\(").findAll(source).count()
        assertTrue(
            lineDrawCount in 2..3,
            "ShelfBoard should use only a few lines for its edges and paper texture: $lineDrawCount",
        )
    }

    private fun shelfBoardSource(): String {
        val relativePath = Path.of(
            "src/main/kotlin/dev/readflow/core/ui/ShelfBoard.kt",
        )
        val candidates = sequenceOf(
            relativePath,
            Path.of("core/ui").resolve(relativePath),
            Path.of("android/core/ui").resolve(relativePath),
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("Cannot locate ShelfBoard.kt from ${Path.of("").toAbsolutePath()}")

        return sourcePath.toFile().readText()
    }
}
