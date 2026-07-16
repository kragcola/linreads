package dev.readflow.core.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubFontReplacementSettingsTest {

    @Test
    fun `settings contract exposes persistent epub font replacements`() {
        assertTrue(
            SettingsRepository::class.java.methods.any { method ->
                method.name == "getEpubFontReplacements"
            },
        )
        assertTrue(
            SettingsRepository::class.java.methods.any { method ->
                method.name == "setEpubFontReplacements"
            },
        )
    }

    @Test
    fun `replacement codec canonicalizes families and rejects malformed entries`() {
        val adapters = Class.forName("dev.readflow.core.prefs.DataStoreSettingsRepositoryKt")
        val encode = adapters.getDeclaredMethod("encodeEpubFontReplacements", Map::class.java)
            .apply { isAccessible = true }
        val decode = adapters.getDeclaredMethod("resolvedEpubFontReplacements", String::class.java)
            .apply { isAccessible = true }

        val payload = encode.invoke(
            null,
            linkedMapOf(
                "  Book   Serif  " to "system_sans",
                "../unsafe" to "custom:../../bad.ttf",
                "Code" to "system_monospace",
            ),
        ) as String

        @Suppress("UNCHECKED_CAST")
        val decoded = decode.invoke(null, payload) as Map<String, String>
        assertEquals(
            linkedMapOf(
                "book serif" to "system_sans",
                "code" to "system_monospace",
            ),
            decoded,
        )
        assertEquals(emptyMap<String, String>(), decode.invoke(null, "not-json"))
    }
}
