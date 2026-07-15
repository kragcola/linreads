package dev.readflow.features.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreenVisualContractTest {

    @Test
    fun `library masthead stays compact tactile and accessible`() {
        val source = libraryScreenSource()

        assertFalse(
            "the compact masthead must not use display-scale typography",
            source.contains("style = ReadflowType.display"),
        )
        assertTrue(
            "the masthead must use a restrained title style",
            source.contains("style = ReadflowType.title"),
        )
        assertTrue(
            "the masthead must add low-cost ruled paper texture",
            source.contains("drawLine("),
        )
        assertFalse(
            "the masthead texture must not use gradients",
            source.contains("Gradient"),
        )
        assertTrue(
            "all three masthead controls must keep the 48dp touch target",
            source.split("size(Dimens.touchTarget)").size - 1 >= 3,
        )
    }

    private fun libraryScreenSource(): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/dev/readflow/features/library/LibraryScreen.kt"),
            File(workingDir, "features/library/src/main/kotlin/dev/readflow/features/library/LibraryScreen.kt"),
            File(workingDir, "android/features/library/src/main/kotlin/dev/readflow/features/library/LibraryScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("LibraryScreen.kt not found from ${workingDir.absolutePath}")
    }
}
