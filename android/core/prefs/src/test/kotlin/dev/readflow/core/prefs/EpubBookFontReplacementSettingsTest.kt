package dev.readflow.core.prefs

import dev.readflow.core.model.FontChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubBookFontReplacementSettingsTest {

    @Test
    fun `settings contract exposes persistent book-scoped epub font replacements`() {
        assertTrue(
            SettingsRepository::class.java.methods.any { method ->
                method.name == "getEpubBookFontReplacements"
            },
        )
        assertTrue(
            SettingsRepository::class.java.methods.any { method ->
                method.name == "setEpubBookFontReplacements"
            },
        )
        assertTrue(
            SettingsRepository::class.java.methods.any { method ->
                method.name == "getPendingImportedFontDeletions"
            },
        )
        assertTrue(
            SettingsRepository::class.java.methods.any { method ->
                method.name == "completeImportedFontDeletion"
            },
        )
    }

    @Test
    fun `book font codec canonicalizes book ids families and rejects malformed entries`() {
        val payload = encodeEpubBookFontReplacements(
            linkedMapOf(
                "  book-1  " to linkedMapOf(
                    "  \"Book   Serif\"  " to "system_sans",
                    "../unsafe" to "custom:../../bad.ttf",
                ),
                "" to mapOf("x" to "system_serif"),
                "book-2" to mapOf("Code" to "system_monospace"),
            ),
        )
        val decoded = resolvedEpubBookFontReplacements(payload)
        assertEquals(
            mapOf(
                "book-1" to mapOf("book serif" to "system_sans"),
                "book-2" to mapOf("code" to "system_monospace"),
            ),
            decoded,
        )
        assertEquals(emptyMap<String, Map<String, String>>(), resolvedEpubBookFontReplacements("not-json"))
        assertEquals(emptyMap<String, Map<String, String>>(), resolvedEpubBookFontReplacements(null))
    }

    @Test
    fun `merged replacements give book maps priority over global`() {
        val merged = mergedEpubFontReplacements(
            global = mapOf(
                "story" to "system_serif",
                "code" to "system_monospace",
            ),
            bookScoped = mapOf(
                "  Story  " to "system_sans",
                "Title" to "system_serif",
            ),
        )
        assertEquals(
            mapOf(
                "code" to "system_monospace",
                "story" to "system_sans",
                "title" to "system_serif",
            ),
            merged,
        )
    }

    @Test
    fun `canonical book identity rejects blank and control characters`() {
        assertEquals("shelf:local:abc", canonicalBookIdentity("  shelf:local:abc  "))
        assertNull(canonicalBookIdentity(" "))
        assertNull(canonicalBookIdentity("a\u0000b"))
        assertNull(canonicalBookIdentity(null))
    }

    @Test
    fun `family key normalizes quotes case and whitespace`() {
        assertEquals("book serif", canonicalEpubFontFamilyKey("  \"Book   Serif\"  "))
        assertEquals("story", canonicalEpubFontFamilyKey("'STORY'"))
        assertNull(canonicalEpubFontFamilyKey("   "))
        assertNull(canonicalEpubFontFamilyKey(null))
    }

    @Test
    fun `empty book map encode roundtrip stays empty`() {
        val payload = encodeEpubBookFontReplacements(emptyMap())
        assertEquals(emptyMap<String, Map<String, String>>(), resolvedEpubBookFontReplacements(payload))
    }

    @Test
    fun `deleting an imported font removes every reference and falls back current text`() {
        val removed = "custom:Novel.ttf"

        val cleaned = removedImportedFontReferences(
            currentFontId = removed,
            globalReplacements = mapOf(
                "body" to removed,
                "code" to "system_monospace",
            ),
            bookReplacements = mapOf(
                "book-a" to mapOf("story" to removed, "code" to "system_monospace"),
                "book-b" to mapOf("title" to removed),
            ),
            removedFontId = removed,
        )

        assertEquals("system_serif", cleaned.currentFontId)
        assertEquals(mapOf("code" to "system_monospace"), cleaned.globalReplacements)
        assertEquals(
            mapOf("book-a" to mapOf("code" to "system_monospace")),
            cleaned.bookReplacements,
        )
    }

    @Test
    fun `pending deletion codec retains only valid imported font ids`() {
        assertEquals(
            setOf(FontChoice.Custom("Novel.ttf"), FontChoice.Custom("Code.otf")),
            resolvedPendingImportedFontDeletions(
                setOf(
                    "custom:Novel.ttf",
                    "custom:Code.otf",
                    "system_serif",
                    "custom:../../unsafe.ttf",
                    "custom:not-a-font.txt",
                ),
            ),
        )
    }
}
