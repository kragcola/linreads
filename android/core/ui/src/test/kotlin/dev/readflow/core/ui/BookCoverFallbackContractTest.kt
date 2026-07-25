package dev.readflow.core.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookCoverFallbackContractTest {

    @Test
    fun `local title cover is drawn behind an optional remote cover`() {
        val source = bookCoverSource()
        val fallbackIndex = source.indexOf("PlainStampedCover(book, clothColor)")
        val remoteCoverIndex = source.indexOf("if (book.coverUrl != null)")
        val asyncImageIndex = source.indexOf("AsyncImage(", startIndex = remoteCoverIndex)

        assertTrue(fallbackIndex >= 0, "BookCover must render a local title fallback")
        assertTrue(remoteCoverIndex >= 0, "BookCover must keep optional remote cover loading")
        assertTrue(asyncImageIndex > remoteCoverIndex, "remote cover branch must render AsyncImage")
        assertTrue(
            fallbackIndex < remoteCoverIndex,
            "local title fallback must be behind the remote image so loading and failures stay identifiable",
        )
    }

    private fun bookCoverSource(): String {
        val relativePath = Path.of("src/main/kotlin/dev/readflow/core/ui/BookCover.kt")
        val candidates = sequenceOf(
            relativePath,
            Path.of("core/ui").resolve(relativePath),
            Path.of("android/core/ui").resolve(relativePath),
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("Cannot locate BookCover.kt from ${Path.of("").toAbsolutePath()}")
        return sourcePath.toFile().readText()
    }
}
