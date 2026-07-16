package dev.readflow.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderMenuConfigTest {

    @Test
    fun `v1 defaults are all six commands visible in existing bottom-bar order`() {
        val defaults = ReaderMenuConfig.v1Defaults()
        assertEquals(ReaderMenuConfig.VERSION_V1, defaults.version)
        assertEquals(
            listOf(
                ReaderCommandId.TOC,
                ReaderCommandId.SEARCH,
                ReaderCommandId.BOOKMARKS,
                ReaderCommandId.ANNOTATIONS,
                ReaderCommandId.FONT,
                ReaderCommandId.THEME,
            ),
            defaults.entries.map { it.id },
        )
        assertTrue(defaults.entries.all { it.visible })
    }

    @Test
    fun `serialized command ids are stable wire names not positions or labels`() {
        assertEquals("TOC", ReaderCommandId.TOC.wireId)
        assertEquals("SEARCH", ReaderCommandId.SEARCH.wireId)
        assertEquals("BOOKMARKS", ReaderCommandId.BOOKMARKS.wireId)
        assertEquals("ANNOTATIONS", ReaderCommandId.ANNOTATIONS.wireId)
        assertEquals("FONT", ReaderCommandId.FONT.wireId)
        assertEquals("THEME", ReaderCommandId.THEME.wireId)

        val encoded = ReaderMenuConfig.encode(ReaderMenuConfig.v1Defaults())
        assertTrue(encoded.contains("\"TOC\""))
        assertTrue(encoded.contains("\"SEARCH\""))
        assertTrue(encoded.contains("\"BOOKMARKS\""))
        assertTrue(encoded.contains("\"version\":1"))
        assertFalse(encoded.contains("目录"))
        assertFalse(encoded.contains("搜索"))
    }

    @Test
    fun `round-trip encode decode preserves order visibility and version`() {
        val original = ReaderMenuConfig(
            version = ReaderMenuConfig.VERSION_V1,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = false),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = false),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
            ),
        )
        val decoded = ReaderMenuConfig.decodeOrDefaults(ReaderMenuConfig.encode(original))
        assertEquals(original.version, decoded.version)
        assertEquals(original.entries, decoded.entries)
    }

    @Test
    fun `unknown and duplicate persisted ids are dropped while known order and visibility stay`() {
        val raw = """
            {
              "version": 1,
              "entries": [
                {"id":"SEARCH","visible":false},
                {"id":"UNKNOWN_CMD","visible":true},
                {"id":"SEARCH","visible":true},
                {"id":"FONT","visible":true},
                {"id":"not-a-real-id","visible":false}
              ]
            }
        """.trimIndent()
        val resolved = ReaderMenuConfig.decodeOrDefaults(raw)
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
            resolved.entries,
        )
    }

    @Test
    fun `missing catalog ids are appended using current defaults`() {
        val raw = """
            {
              "version": 1,
              "entries": [
                {"id":"THEME","visible":false},
                {"id":"TOC","visible":true}
              ]
            }
        """.trimIndent()
        val resolved = ReaderMenuConfig.decodeOrDefaults(raw)
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.THEME, visible = false),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
            ),
            resolved.entries,
        )
    }

    @Test
    fun `corrupt empty and blank payloads recover to v1 defaults`() {
        val defaults = ReaderMenuConfig.v1Defaults()
        assertEquals(defaults, ReaderMenuConfig.decodeOrDefaults(null))
        assertEquals(defaults, ReaderMenuConfig.decodeOrDefaults(""))
        assertEquals(defaults, ReaderMenuConfig.decodeOrDefaults("   "))
        assertEquals(defaults, ReaderMenuConfig.decodeOrDefaults("not-json"))
        assertEquals(defaults, ReaderMenuConfig.decodeOrDefaults("{"))
        assertEquals(defaults, ReaderMenuConfig.decodeOrDefaults("""{"version":1,"entries":null}"""))
    }

    @Test
    fun `resolve drops unknowns duplicates and fills missing ids deterministically`() {
        val partial = ReaderMenuConfig(
            version = 99,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = false),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
            ),
        )
        val resolved = ReaderMenuConfig.resolve(partial)
        assertEquals(ReaderMenuConfig.VERSION_V1, resolved.version)
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = false),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
            resolved.entries,
        )
    }

    @Test
    fun `moveCommandUp swaps with previous entry and preserves visibility version and catalog`() {
        val start = ReaderMenuConfig(
            version = ReaderMenuConfig.VERSION_V1,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = false),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
        )
        val moved = ReaderMenuConfig.moveCommandUp(start, ReaderCommandId.BOOKMARKS)
        assertEquals(ReaderMenuConfig.VERSION_V1, moved.version)
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = false),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
            moved.entries,
        )
        assertEquals(ReaderCommandId.entries.toSet(), moved.entries.map { it.id }.toSet())
    }

    @Test
    fun `moveCommandDown swaps with next entry and preserves visibility`() {
        val start = ReaderMenuConfig.v1Defaults().let {
            ReaderMenuConfig.setCommandVisible(it, ReaderCommandId.FONT, visible = false)
        }
        val moved = ReaderMenuConfig.moveCommandDown(start, ReaderCommandId.FONT)
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = false),
            ),
            moved.entries,
        )
    }

    @Test
    fun `moveCommandUp on first entry is a no-op and moveCommandDown on last is a no-op`() {
        val defaults = ReaderMenuConfig.v1Defaults()
        assertEquals(defaults, ReaderMenuConfig.moveCommandUp(defaults, ReaderCommandId.TOC))
        assertEquals(defaults, ReaderMenuConfig.moveCommandDown(defaults, ReaderCommandId.THEME))
    }

    @Test
    fun `move preserves each entry visibility across encode decode`() {
        val reordered = ReaderMenuConfig.moveCommandUp(
            ReaderMenuConfig.setCommandVisible(
                ReaderMenuConfig.v1Defaults(),
                ReaderCommandId.SEARCH,
                visible = false,
            ),
            ReaderCommandId.SEARCH,
        )
        val roundTripped = ReaderMenuConfig.decodeOrDefaults(ReaderMenuConfig.encode(reordered))
        assertEquals(reordered.entries, roundTripped.entries)
        assertFalse(roundTripped.entries.first { it.id == ReaderCommandId.SEARCH }.visible)
        assertEquals(ReaderCommandId.SEARCH, roundTripped.entries.first().id)
    }

    @Test
    fun `restoreDefaults returns full v1 catalog all visible`() {
        val customized = ReaderMenuConfig(
            version = ReaderMenuConfig.VERSION_V1,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.THEME, visible = false),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = false),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = false),
            ),
        )
        assertEquals(ReaderMenuConfig.v1Defaults(), ReaderMenuConfig.restoreDefaults())
        assertEquals(ReaderMenuConfig.v1Defaults(), ReaderMenuConfig.restoreDefaults().let {
            // restore ignores prior state — pure factory
            ReaderMenuConfig.restoreDefaults()
        })
        assertTrue(customized.entries.any { !it.visible })
        assertTrue(ReaderMenuConfig.restoreDefaults().entries.all { it.visible })
    }

    @Test
    fun `serialized rapid move and visibility reducers never lose prior updates`() {
        // Simulates ViewModel mutex serialization: each step reads prior result.
        var config = ReaderMenuConfig.v1Defaults()
        config = ReaderMenuConfig.setCommandVisible(config, ReaderCommandId.ANNOTATIONS, false)
        config = ReaderMenuConfig.moveCommandUp(config, ReaderCommandId.THEME)
        config = ReaderMenuConfig.moveCommandUp(config, ReaderCommandId.THEME)
        config = ReaderMenuConfig.setCommandVisible(config, ReaderCommandId.SEARCH, false)
        config = ReaderMenuConfig.moveCommandDown(config, ReaderCommandId.TOC)
        config = ReaderMenuConfig.setCommandVisible(config, ReaderCommandId.ANNOTATIONS, true)

        // TOC↓ SEARCH(hide) THEME↑↑ ANNOTATIONS(show after hide):
        // SEARCH(f), TOC, BOOKMARKS, THEME, ANNOTATIONS(t), FONT
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
            ),
            config.entries,
        )
        assertEquals(ReaderMenuConfig.VERSION_V1, config.version)
        assertEquals(6, config.entries.size)
        assertEquals(ReaderCommandId.entries.toSet(), config.entries.map { it.id }.toSet())
    }
}
