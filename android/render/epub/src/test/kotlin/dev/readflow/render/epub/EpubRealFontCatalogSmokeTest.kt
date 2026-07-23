package dev.readflow.render.epub

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class EpubRealFontCatalogSmokeTest {

    @Test
    fun `real multi-font epub exposes actual usage excerpts for every catalog row`() {
        val file = System.getProperty("readflow.realMultiFontEpub")?.let(::File)
        assumeTrue(
            file?.isFile == true,
            "Set -Dreadflow.realMultiFontEpub=/path/to/book.epub to run the real font catalog smoke.",
        )

        val book = EpubParser().parseLazyBook(file!!)
        val catalog = book.cssFontCatalog()
        val declaredFamilies = book.mergedBookFontMap().facesByFamily.size

        assertTrue(
            declaredFamilies >= 10,
            "Expected at least 10 declared CSS font families, found $declaredFamilies.",
        )
        assertTrue(catalog.size >= 5, "Expected a multi-font usage catalog, found ${catalog.size} rows.")
        catalog.forEach { entry ->
            assertTrue(entry.excerpt.isNotBlank(), "${entry.family} has no real book excerpt.")
            assertTrue(
                entry.excerptMatchStart in 0 until entry.excerptMatchEnd,
                "${entry.family} has an empty or invalid preview range.",
            )
            assertTrue(
                entry.excerptMatchEnd <= entry.excerpt.length,
                "${entry.family} preview range escapes its excerpt.",
            )
            assertTrue(entry.coveredChars > 0, "${entry.family} has no measured character coverage.")
        }
        println(
            "EPUB_FONT_CATALOG|${file.name}" +
                "|declaredFamilies=$declaredFamilies" +
                "|families=${catalog.size}" +
                "|embedded=${catalog.count { it.status == EpubCssFontMappingStatus.EMBEDDED }}" +
                "|unresolved=${catalog.count { it.status == EpubCssFontMappingStatus.UNRESOLVED }}" +
                "|coveredChars=${catalog.sumOf { it.coveredChars.toLong() }}",
        )
    }
}
