package dev.readflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeProfileTest {

    @Test
    fun `round-trip encode then decode`() {
        val original = ThemeProfile(
            name = "测试主题",
            fontSize = 24,
            lineSpacing = 1.5f,
            themeMode = "DARK",
            fontChoice = "custom:myfont.ttf",
            txtEncoding = "GBK",
            readingMode = "PAGED",
        )
        val encoded = original.encode()
        val decoded = ThemeProfile.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `dirty JSON returns null`() {
        assertNull(ThemeProfile.decode("not json"))
        assertNull(ThemeProfile.decode(""))
        // {} is valid kotlinx.serialization (all defaults) — not null
    }

    @Test
    fun `unknown keys are ignored`() {
        val json = """{"name":"t","fontSize":18,"lineSpacing":1.5,"themeMode":"LIGHT","fontChoice":"system","txtEncoding":"AUTO","readingMode":"SCROLL","unknownKey":42}"""
        val decoded = ThemeProfile.decode(json)
        assertNotNull(decoded)
        assertEquals("t", decoded!!.name)
    }

    @Test
    fun `validated clamp out-of-range values`() {
        val bad = ThemeProfile(fontSize = 100, lineSpacing = 5f, themeMode = "BOGUS", txtEncoding = "BOGUS")
        val safe = ThemeProfile.validated(bad)
        assertEquals(32, safe.fontSize)
        assertEquals(2.2f, safe.lineSpacing)
        assertEquals("SYSTEM", safe.themeMode)
        assertEquals("AUTO", safe.txtEncoding)
    }

    @Test
    fun `validated preserves good values`() {
        val good = ThemeProfile(fontSize = 20, lineSpacing = 1.8f, themeMode = "SEPIA", txtEncoding = "UTF_8")
        val safe = ThemeProfile.validated(good)
        assertEquals(20, safe.fontSize)
        assertEquals(1.8f, safe.lineSpacing)
        assertEquals("SEPIA", safe.themeMode)
        assertEquals("UTF_8", safe.txtEncoding)
    }
}
