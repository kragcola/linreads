package dev.readflow.render.api

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderSearchHighlightSpanTest {

    @Test
    fun `annotation and search spans coexist with distinct types and colors`() {
        val text = "hello search world"
        val annotation = ReaderTextHighlightRange(0, 5, 0x66FFE082)
        val search = ReaderTextHighlightRange(6, 12, READER_SEARCH_HIGHLIGHT_COLOR)

        val spanned = text.withTextHighlightSpans(
            ranges = listOf(annotation),
            searchRanges = listOf(search),
        ) as Spanned

        val annotationSpans = spanned.getSpans(0, spanned.length, ReaderTextHighlightSpan::class.java)
        val searchSpans = spanned.getSpans(0, spanned.length, ReaderSearchHighlightSpan::class.java)
        assertEquals(1, annotationSpans.size)
        assertEquals(1, searchSpans.size)
        assertEquals(0, spanned.getSpanStart(annotationSpans[0]))
        assertEquals(5, spanned.getSpanEnd(annotationSpans[0]))
        assertEquals(0x66FFE082, annotationSpans[0].backgroundColor)
        assertEquals(6, spanned.getSpanStart(searchSpans[0]))
        assertEquals(12, spanned.getSpanEnd(searchSpans[0]))
        assertEquals(READER_SEARCH_HIGHLIGHT_COLOR, searchSpans[0].backgroundColor)
    }

    @Test
    fun `clearing search spans leaves annotation and plain BackgroundColorSpan intact`() {
        val base = SpannableString("plain annotation search")
        base.setSpan(
            BackgroundColorSpan(0x33FF0000),
            0,
            5,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val withAnnotation = base.withTextHighlightSpans(
            ranges = listOf(ReaderTextHighlightRange(6, 16, 0x66FFE082)),
            searchRanges = listOf(ReaderTextHighlightRange(17, 23, READER_SEARCH_HIGHLIGHT_COLOR)),
        )
        val cleared = withAnnotation.withSearchHighlightSpans(emptyList()) as Spanned

        assertEquals(0, cleared.getSpans(0, cleared.length, ReaderSearchHighlightSpan::class.java).size)
        assertEquals(1, cleared.getSpans(0, cleared.length, ReaderTextHighlightSpan::class.java).size)
        val plain = cleared.getSpans(0, cleared.length, BackgroundColorSpan::class.java)
            .filter { it !is ReaderTextHighlightSpan && it !is ReaderSearchHighlightSpan }
        assertEquals(1, plain.size)
        assertEquals(0x33FF0000, plain.single().backgroundColor)
        assertEquals(0, cleared.getSpanStart(plain.single()))
        assertEquals(5, cleared.getSpanEnd(plain.single()))
    }

    @Test
    fun `replacing search range does not delete annotation spans`() {
        val annotation = ReaderTextHighlightRange(0, 4, 0x66FFE082)
        val first = "word one two".withTextHighlightSpans(
            ranges = listOf(annotation),
            searchRanges = listOf(ReaderTextHighlightRange(5, 8, READER_SEARCH_HIGHLIGHT_COLOR)),
        )
        val second = first.withTextHighlightSpans(
            ranges = listOf(annotation),
            searchRanges = listOf(ReaderTextHighlightRange(9, 12, READER_SEARCH_HIGHLIGHT_COLOR)),
        ) as Spanned

        val annotationSpans = second.getSpans(0, second.length, ReaderTextHighlightSpan::class.java)
        val searchSpans = second.getSpans(0, second.length, ReaderSearchHighlightSpan::class.java)
        assertEquals(1, annotationSpans.size)
        assertEquals(1, searchSpans.size)
        assertEquals(0, second.getSpanStart(annotationSpans[0]))
        assertEquals(4, second.getSpanEnd(annotationSpans[0]))
        assertEquals(9, second.getSpanStart(searchSpans[0]))
        assertEquals(12, second.getSpanEnd(searchSpans[0]))
        assertTrue(searchSpans.single() is ReaderSearchHighlightSpan)
    }

    @Test
    fun `shared search highlight color is semi-transparent blue distinct from annotation yellow`() {
        assertEquals(0x664A90E2, READER_SEARCH_HIGHLIGHT_COLOR)
        assertTrue(READER_SEARCH_HIGHLIGHT_COLOR != 0x66FFE082)
        val span = ReaderSearchHighlightSpan()
        assertEquals(READER_SEARCH_HIGHLIGHT_COLOR, span.backgroundColor)
    }
}
