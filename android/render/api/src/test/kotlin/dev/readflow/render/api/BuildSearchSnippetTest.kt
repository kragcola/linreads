package dev.readflow.render.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildSearchSnippetTest {

    @Test
    fun `keeps short latin match without ellipsis when whole source fits`() {
        val source = "Hello needle world"
        val start = source.indexOf("needle")
        val snippet = buildSearchSnippet(source, start, "needle".length, maxChars = 80)
        assertEquals("Hello needle world", snippet)
        assertFalse(snippet.contains('…'))
    }

    @Test
    fun `collapses whitespace and newlines into a single line`() {
        val source = "prefix\n\n  mid   needle\t\ttail"
        val start = source.indexOf("needle")
        val snippet = buildSearchSnippet(source, start, "needle".length, maxChars = 80)
        assertEquals("prefix mid needle tail", snippet)
        assertFalse(snippet.contains('\n'))
        assertFalse(snippet.contains('\t'))
    }

    @Test
    fun `clips long latin context with leading and trailing ellipsis around match`() {
        val left = "a".repeat(40)
        val right = "b".repeat(40)
        val source = left + "MATCH" + right
        val start = left.length
        val snippet = buildSearchSnippet(source, start, "MATCH".length, maxChars = 20)
        assertTrue(snippet.startsWith('…'), snippet)
        assertTrue(snippet.endsWith('…'), snippet)
        assertTrue(snippet.contains("MATCH"), snippet)
        // Body (excluding ellipsis) must respect maxChars.
        val body = snippet.trim('…')
        assertTrue(body.length <= 20, "body=$body len=${body.length}")
    }

    @Test
    fun `handles CJK without spaces and keeps the match`() {
        val source = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏" +
            "关键字" +
            "云腾致雨露结为霜金生丽水玉出昆冈"
        val start = source.indexOf("关键字")
        val snippet = buildSearchSnippet(source, start, "关键字".length, maxChars = 16)
        assertTrue(snippet.contains("关键字"), snippet)
        val body = snippet.trim('…')
        assertTrue(body.length <= 16, "body=$body")
        assertTrue(snippet.startsWith('…') || snippet.endsWith('…'), snippet)
    }

    @Test
    fun `is bounds-safe for out-of-range match coordinates`() {
        assertEquals("", buildSearchSnippet("", 0, 1))
        assertEquals("", buildSearchSnippet("abc", 0, 0, maxChars = 0))
        val short = buildSearchSnippet("short", 100, 5)
        assertEquals("short", short)
        val negative = buildSearchSnippet("needle here", -3, 6)
        assertTrue(negative.contains("needle") || negative == "needle here", negative)
    }

    @Test
    fun `preserves match when it is longer than maxChars by anchoring at match start`() {
        val match = "X".repeat(30)
        val source = "pre" + match + "post"
        val snippet = buildSearchSnippet(source, 3, match.length, maxChars = 10)
        assertTrue(snippet.contains('X'), snippet)
        val body = snippet.trim('…')
        assertEquals(10, body.length)
        assertTrue(body.all { it == 'X' }, body)
    }

    @Test
    fun `match near beginning rebalances unused left budget to the right`() {
        val match = "MATCH"
        val prefix = "AB"
        val suffix = "R".repeat(80)
        val source = prefix + match + suffix
        val maxChars = 20
        val snippet = buildSearchSnippet(source, prefix.length, match.length, maxChars = maxChars)
        val body = snippet.trim('…')
        assertEquals(maxChars, body.length, "full budget must be used after rebalance: $snippet")
        assertTrue(body.startsWith(prefix + match), body)
        assertTrue(body.endsWith("R".repeat(maxChars - prefix.length - match.length)), body)
        assertFalse(snippet.startsWith('…'), "at start of source there is no leading ellipsis: $snippet")
        assertTrue(snippet.endsWith('…'), snippet)
        assertTrue(snippet.contains("MATCH"), snippet)
    }

    @Test
    fun `match near end rebalances unused right budget to the left`() {
        val match = "MATCH"
        val prefix = "L".repeat(80)
        val suffix = "XY"
        val source = prefix + match + suffix
        val maxChars = 20
        val snippet = buildSearchSnippet(source, prefix.length, match.length, maxChars = maxChars)
        val body = snippet.trim('…')
        assertEquals(maxChars, body.length, "full budget must be used after rebalance: $snippet")
        assertTrue(body.endsWith(match + suffix), body)
        assertTrue(body.startsWith("L".repeat(maxChars - match.length - suffix.length)), body)
        assertTrue(snippet.startsWith('…'), snippet)
        assertFalse(snippet.endsWith('…'), "at end of source there is no trailing ellipsis: $snippet")
        assertTrue(snippet.contains("MATCH"), snippet)
    }

    @Test
    fun `multi-word query source collapses whitespace runs around match`() {
        val source = "alpha\n\t  beta   gamma\t\tneedle   delta\n  epsilon"
        val start = source.indexOf("needle")
        val snippet = buildSearchSnippet(source, start, "needle".length, maxChars = 80)
        assertEquals("alpha beta gamma needle delta epsilon", snippet)
        assertFalse(snippet.contains('\n'))
        assertFalse(snippet.contains('\t'))
        assertFalse(snippet.contains("  "), "no double spaces after collapse: $snippet")
        assertTrue(snippet.contains("needle"))
    }

    @Test
    fun `leading whitespace removal still maps original match indices correctly`() {
        val source = "   \n\t  hello world tail"
        val start = source.indexOf("hello")
        assertTrue(start > 0, "match must sit after leading whitespace")
        val snippet = buildSearchSnippet(source, start, "hello".length, maxChars = 80)
        assertEquals("hello world tail", snippet)
        assertFalse(snippet.startsWith(' '), snippet)
        assertTrue(snippet.startsWith("hello"), snippet)
    }

    @Test
    fun `zero-length match returns point context rather than empty output`() {
        val source = "abcdefghij"
        val snippet = buildSearchSnippet(source, matchStart = 5, matchLength = 0, maxChars = 6)
        assertTrue(snippet.isNotEmpty(), "point context must not be empty")
        // Zero-length match expands around the insertion point (index 5 → char 'f').
        assertTrue(snippet.contains('f') || snippet.contains('e') || snippet.contains('g'), snippet)
        val body = snippet.trim('…')
        assertTrue(body.length <= 6, "body=$body")
        assertTrue(body.isNotEmpty(), body)
        // Short source with room: entire collapsed text is fine when it fits.
        val short = buildSearchSnippet("abc", 1, 0, maxChars = 80)
        assertEquals("abc", short)
    }
}
