package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubCssFontCatalogTest {

    @Test
    fun `catalog prefers book mapping over global and embedded`() {
        val embedded = mapOf(
            "story" to EpubFontFace(family = "Story", srcPath = "fonts/story.ttf"),
            "code" to EpubFontFace(family = "Code", srcPath = "fonts/code.ttf"),
        )
        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = embedded,
            bookReplacements = mapOf("Story" to "system_sans"),
            globalReplacements = mapOf("story" to "system_serif", "code" to "system_monospace"),
            fontUsages = listOf(fontUsage("story", "Story"), fontUsage("code", "Code")),
        )
        val byFamily = catalog.associateBy { it.family }
        assertEquals(EpubCssFontMappingStatus.BOOK_MAPPED, byFamily.getValue("story").status)
        assertEquals("system_sans", byFamily.getValue("story").mappedFontId)
        assertEquals(EpubCssFontMappingStatus.GLOBAL_MAPPED, byFamily.getValue("code").status)
        assertEquals("system_monospace", byFamily.getValue("code").mappedFontId)
    }

    @Test
    fun `catalog marks embedded without mapping and unresolved without face`() {
        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = mapOf(
                "body" to EpubFontFace(family = "Body", srcPath = "fonts/body.otf"),
            ),
            bookReplacements = emptyMap(),
            globalReplacements = emptyMap(),
            fontUsages = listOf(fontUsage("missing face", "Missing Face"), fontUsage("body", "Body")),
        )
        val byFamily = catalog.associateBy { it.family }
        assertEquals(EpubCssFontMappingStatus.EMBEDDED, byFamily.getValue("body").status)
        assertEquals("fonts/body.otf", byFamily.getValue("body").embeddedSrcPath)
        assertEquals(EpubCssFontMappingStatus.UNRESOLVED, byFamily.getValue("missing face").status)
        assertEquals("Missing Face", byFamily.getValue("missing face").displayName)
    }

    @Test
    fun `family keys normalize quotes case and whitespace`() {
        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = mapOf(
                "  \"Book   Serif\"  " to EpubFontFace(family = "Book Serif", srcPath = "a.ttf"),
            ),
            bookReplacements = mapOf("'BOOK SERIF'" to "system_serif"),
            globalReplacements = emptyMap(),
            fontUsages = listOf(fontUsage("book serif", "Book Serif")),
        )
        assertEquals(1, catalog.size)
        assertEquals("book serif", catalog.single().family)
        assertEquals(EpubCssFontMappingStatus.BOOK_MAPPED, catalog.single().status)
    }

    @Test
    fun `merge book font maps first spine face wins`() {
        val first = epubBookFontMapFromFaces(
            listOf(EpubFontFace(family = "Story", srcPath = "first.ttf")),
        )
        val second = epubBookFontMapFromFaces(
            listOf(
                EpubFontFace(family = "Story", srcPath = "second.ttf"),
                EpubFontFace(family = "Code", srcPath = "code.ttf"),
            ),
        )
        val merged = mergeEpubBookFontMaps(listOf(first, second))
        assertEquals("first.ttf", merged.faceForFamily("Story")?.srcPath)
        assertEquals("code.ttf", merged.faceForFamily("Code")?.srcPath)
        assertTrue(mergeEpubBookFontMaps(emptyList()).facesByFamily.isEmpty())
    }

    @Test
    fun `mergeReplacementLayers book overrides global same key`() {
        val layered = mergeReplacementLayers(
            bookReplacements = mapOf("story" to "system_sans"),
            globalReplacements = mapOf("story" to "system_serif", "code" to "system_monospace"),
        )
        assertEquals(
            mapOf("story" to "system_sans", "code" to "system_monospace"),
            layered,
        )
    }

    @Test
    fun `catalog never advertises an unused embedded face`() {
        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = mapOf(
                "used" to EpubFontFace(family = "Used", srcPath = "used.ttf"),
                "unused" to EpubFontFace(family = "Unused", srcPath = "unused.ttf"),
            ),
            bookReplacements = emptyMap(),
            globalReplacements = emptyMap(),
            fontUsages = listOf(fontUsage("used", "Used")),
        )

        assertEquals(listOf("used"), catalog.map { it.family })
    }

    @Test
    fun `effective resolution walks fallback tokens and exposes the embedded target`() {
        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = mapOf(
                "mincho" to EpubFontFace(family = "Mincho", srcPath = "fonts/mincho.otf"),
            ),
            bookReplacements = emptyMap(),
            globalReplacements = emptyMap(),
            fontUsages = listOf(
                fontUsage(
                    family = "unknown",
                    displayName = "Unknown",
                    fontFamilyChain = listOf("unknown", "mincho", "serif"),
                ),
            ),
        )

        val entry = catalog.single()
        assertEquals("unknown", entry.family)
        assertEquals(EpubCssFontMappingStatus.EMBEDDED, entry.status)
        assertEquals(EpubCssFontEffectiveSource.EMBEDDED, entry.effectiveSource)
        assertEquals("mincho", entry.effectiveFamily)
        assertEquals("fonts/mincho.otf", entry.embeddedSrcPath)
    }

    @Test
    fun `book base replacement wins before global exact and missing targets fall through`() {
        val usage = fontUsage(
            family = "story-bold",
            displayName = "Story-Bold",
            fontFamilyChain = listOf("story-bold", "mincho", "serif"),
        )
        val embedded = mapOf(
            "mincho" to EpubFontFace(family = "Mincho", srcPath = "fonts/mincho.otf"),
        )

        val bookWins = buildEpubCssFontCatalog(
            embeddedFaces = embedded,
            bookReplacements = mapOf("story" to "system_sans"),
            globalReplacements = mapOf("story-bold" to "system_serif"),
            fontUsages = listOf(usage),
            availableReplacementIds = setOf("system_sans", "system_serif"),
        ).single()
        assertEquals(EpubCssFontMappingStatus.BOOK_MAPPED, bookWins.status)
        assertEquals("system_sans", bookWins.mappedFontId)
        assertEquals("story-bold", bookWins.effectiveFamily)

        val missingFallsThrough = buildEpubCssFontCatalog(
            embeddedFaces = embedded,
            bookReplacements = mapOf("story" to "custom:missing.ttf"),
            globalReplacements = emptyMap(),
            fontUsages = listOf(usage),
            availableReplacementIds = emptySet(),
        ).single()
        assertEquals(EpubCssFontMappingStatus.EMBEDDED, missingFallsThrough.status)
        assertEquals("mincho", missingFallsThrough.effectiveFamily)
    }

    private fun fontUsage(
        family: String,
        displayName: String,
        fontFamilyChain: List<String> = listOf(family),
    ) = EpubCssFontUsage(
        family = family,
        displayName = displayName,
        fontFamilyChain = fontFamilyChain,
        occurrenceCount = 1,
        coveredChars = 6,
        excerpt = "sample",
        excerptMatchStart = 0,
        excerptMatchEnd = 6,
    )
}
