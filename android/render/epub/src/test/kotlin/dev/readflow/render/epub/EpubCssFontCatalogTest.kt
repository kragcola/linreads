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
            referencedFamilies = listOf("Missing Face", "\"Body\""),
        )
        val byFamily = catalog.associateBy { it.family }
        assertEquals(EpubCssFontMappingStatus.EMBEDDED, byFamily.getValue("body").status)
        assertEquals("fonts/body.otf", byFamily.getValue("body").embeddedSrcPath)
        assertEquals(EpubCssFontMappingStatus.UNRESOLVED, byFamily.getValue("missing face").status)
        // Referenced-only families use the canonical key as displayName when no @font-face exists.
        assertEquals("missing face", byFamily.getValue("missing face").displayName)
    }

    @Test
    fun `family keys normalize quotes case and whitespace`() {
        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = mapOf(
                "  \"Book   Serif\"  " to EpubFontFace(family = "Book Serif", srcPath = "a.ttf"),
            ),
            bookReplacements = mapOf("'BOOK SERIF'" to "system_serif"),
            globalReplacements = emptyMap(),
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
}
