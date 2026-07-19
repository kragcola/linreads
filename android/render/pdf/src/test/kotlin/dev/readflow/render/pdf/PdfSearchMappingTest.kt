package dev.readflow.render.pdf

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure mapping / lifecycle / reflection-fallback tests.
 *
 * Uses [PdfRect] (not android.graphics.RectF) so JVM unit tests need no Robolectric
 * and no real PDF / device / framework text API.
 */
class PdfSearchMappingTest {

    @Test
    fun `mapPdfFrameworkMatches builds fixed page locators and needle matchLength`() {
        val matches = listOf(
            PdfFrameworkTextMatch(
                textStartIndex = 5,
                bounds = listOf(PdfRect(10f, 20f, 40f, 30f)),
            ),
        )
        val mapped = mapPdfFrameworkMatches(
            pageIndex = 2,
            pageCount = 10,
            query = "hello",
            pageText = "xxxx hello world",
            matches = matches,
            pageWidthPt = 612f,
            pageHeightPt = 792f,
        )
        assertEquals(1, mapped.size)
        val hit = mapped.single().hit
        val page = hit.locator.strategy as LocatorStrategy.Page
        assertEquals(2, page.index)
        assertEquals(10, page.total)
        assertEquals(5, hit.matchLength)
        assertTrue(hit.snippet.contains("hello"))
        assertEquals(2, mapped.single().pageIndex)
        assertEquals(612f, mapped.single().pageWidthPt)
        assertEquals(1, mapped.single().pagePointBounds.size)
        assertEquals(10f, mapped.single().pagePointBounds.single().left)
        assertEquals(20f, mapped.single().pagePointBounds.single().top)
        assertEquals(40f, mapped.single().pagePointBounds.single().right)
        assertEquals(30f, mapped.single().pagePointBounds.single().bottom)
    }

    @Test
    fun `mapPdfFrameworkMatches returns empty for empty query or out of range page`() {
        val match = PdfFrameworkTextMatch(0, listOf(PdfRect(0f, 0f, 1f, 1f)))
        assertTrue(
            mapPdfFrameworkMatches(0, 5, "", "text", listOf(match), 100f, 100f).isEmpty(),
        )
        assertTrue(
            mapPdfFrameworkMatches(9, 5, "q", "text", listOf(match), 100f, 100f).isEmpty(),
        )
        assertTrue(
            mapPdfFrameworkMatches(0, 0, "q", "text", listOf(match), 100f, 100f).isEmpty(),
        )
    }

    @Test
    fun `normalizePdfPageRectsToBitmap scales page points into bitmap pixels`() {
        val src = listOf(PdfRect(0f, 0f, 306f, 396f)) // half of 612x792
        val out = normalizePdfPageRectsToBitmap(
            pagePointBounds = src,
            pageWidthPt = 612f,
            pageHeightPt = 792f,
            bitmapWidthPx = 612,
            bitmapHeightPx = 792,
        )
        assertEquals(1, out.size)
        assertEquals(0f, out[0].left, 0.01f)
        assertEquals(0f, out[0].top, 0.01f)
        assertEquals(306f, out[0].right, 0.01f)
        assertEquals(396f, out[0].bottom, 0.01f)
    }

    @Test
    fun `normalizePdfPageRectsToBitmap returns empty for non positive dimensions`() {
        val src = listOf(PdfRect(0f, 0f, 10f, 10f))
        assertTrue(normalizePdfPageRectsToBitmap(src, 0f, 100f, 100, 100).isEmpty())
        assertTrue(normalizePdfPageRectsToBitmap(src, 100f, 100f, 0, 100).isEmpty())
        assertTrue(normalizePdfPageRectsToBitmap(emptyList(), 100f, 100f, 100, 100).isEmpty())
    }

    @Test
    fun `mapBitmapRectsToView centers FIT_CENTER mapping`() {
        val rects = listOf(PdfRect(10f, 10f, 20f, 20f))
        val view = mapBitmapRectsToView(
            bitmapRects = rects,
            drawableWidth = 100,
            drawableHeight = 100,
            contentLeft = 0f,
            contentTop = 0f,
            contentWidth = 200f,
            contentHeight = 200f,
            zoomScale = 1f,
        )
        assertEquals(1, view.size)
        assertEquals(20f, view[0].left, 0.01f)
        assertEquals(20f, view[0].top, 0.01f)
        assertEquals(40f, view[0].right, 0.01f)
        assertEquals(40f, view[0].bottom, 0.01f)
    }

    @Test
    fun `pdfSearchSnippet falls back to needle when page text empty`() {
        assertEquals("needle", pdfSearchSnippet("", 0, 6, "needle"))
    }
}

class PdfSearchLifecycleTest {

    @Test
    fun `generation invalidation discards stale results`() {
        var generation = 0
        val openGen = generation
        assertTrue(PdfSearchLifecycle.isResultFresh(generation, openGen))

        generation = PdfSearchLifecycle.nextGeneration(generation) // close
        assertFalse(PdfSearchLifecycle.isResultFresh(generation, openGen))

        generation = PdfSearchLifecycle.nextGeneration(generation) // reopen
        assertFalse(PdfSearchLifecycle.isResultFresh(generation, openGen))
        assertTrue(PdfSearchLifecycle.isResultFresh(generation, generation))
    }

    @Test
    fun `supportsSearch false when api missing or book closed true when open and available`() {
        // API missing (binding unavailable) while book is open.
        assertFalse(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = false,
                bookOpen = true,
            ),
        )
        // API available but book closed.
        assertFalse(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = true,
                bookOpen = false,
            ),
        )
        // Open-session capability is API binding + open book only — not content probing.
        assertTrue(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = true,
                bookOpen = true,
            ),
        )
    }

    /**
     * Invariant: API identity preservation independent of page content.
     * [resolvePdfOpenTextSession] must never discard a working API because early pages lack text.
     */
    @Test
    fun `open text session preserves available api identity when book open searchCapable true`() {
        val fake = FakePdfFrameworkTextApi(isAvailable = true)
        val session = resolvePdfOpenTextSession(resolvedApi = fake, bookOpen = true)
        assertTrue(session.searchCapable)
        assertSame(fake, session.api)
    }

    @Test
    fun `open text session preserves available api identity when book closed searchCapable false`() {
        // Closed-book helper keeps the resolved API identity (capability false).
        // Production invalidate publishes PdfOpenTextSessionUnavailable separately.
        val fake = FakePdfFrameworkTextApi(isAvailable = true)
        val session = resolvePdfOpenTextSession(resolvedApi = fake, bookOpen = false)
        assertFalse(session.searchCapable)
        assertSame(fake, session.api)
    }

    @Test
    fun `open text session preserves Unavailable identity when book open searchCapable false`() {
        val unavailable = PdfFrameworkTextApi.Unavailable
        val session = resolvePdfOpenTextSession(resolvedApi = unavailable, bookOpen = true)
        assertFalse(session.searchCapable)
        assertSame(unavailable, session.api)
    }

    @Test
    fun `selectionCapable false for closed book unavailable api or no selection support`() {
        val selectable = FakePdfFrameworkTextApi(isAvailable = true, supportsSelection = true)
        val closed = resolvePdfOpenTextSession(resolvedApi = selectable, bookOpen = false)
        assertFalse(closed.selectionCapable)
        assertSame(selectable, closed.api)

        val unavailable = PdfFrameworkTextApi.Unavailable
        val openUnavailable = resolvePdfOpenTextSession(resolvedApi = unavailable, bookOpen = true)
        assertFalse(openUnavailable.selectionCapable)
        assertSame(unavailable, openUnavailable.api)

        val noSelect = FakePdfFrameworkTextApi(isAvailable = true, supportsSelection = false)
        val openNoSelect = resolvePdfOpenTextSession(resolvedApi = noSelect, bookOpen = true)
        assertFalse(openNoSelect.selectionCapable)
        assertSame(noSelect, openNoSelect.api)
        // Search remains content-independent: available API + open book only.
        assertTrue(openNoSelect.searchCapable)
    }

    @Test
    fun `selectionCapable true only for open available selectable api preserves identity`() {
        val fake = FakePdfFrameworkTextApi(isAvailable = true, supportsSelection = true)
        val session = resolvePdfOpenTextSession(resolvedApi = fake, bookOpen = true)
        assertTrue(session.selectionCapable)
        assertTrue(session.searchCapable)
        assertSame(fake, session.api)
    }
}

/**
 * Double for framework reflection: records queries/selections and returns canned data without
 * touching PdfRenderer.Page or flagged API-36 types.
 */
internal class FakePdfFrameworkTextApi(
    override val isAvailable: Boolean = true,
    override val supportsSelection: Boolean = true,
    private val pageTextByIndex: Map<Int, String> = emptyMap(),
    private val matchesByQuery: Map<String, Map<Int, List<PdfFrameworkTextMatch>>> = emptyMap(),
    private val selectionByPage: Map<Int, PdfFrameworkPageSelection?> = emptyMap(),
) : PdfFrameworkTextApi {
    val searchQueries = mutableListOf<String>()
    val selectCalls = mutableListOf<Triple<Int, PdfSelectionBoundarySpec, PdfSelectionBoundarySpec>>()

    override fun hasTextOnPage(page: android.graphics.pdf.PdfRenderer.Page): Boolean =
        error("tests must not open real pages; use pageText/search doubles")

    override fun pageText(page: android.graphics.pdf.PdfRenderer.Page): String =
        error("tests must not open real pages; use pageText/search doubles")

    override fun searchPage(
        page: android.graphics.pdf.PdfRenderer.Page,
        query: String,
    ): List<PdfFrameworkTextMatch> =
        error("tests must not open real pages; use pageText/search doubles")

    override fun selectContent(
        page: android.graphics.pdf.PdfRenderer.Page,
        start: PdfSelectionBoundarySpec,
        stop: PdfSelectionBoundarySpec,
    ): PdfFrameworkPageSelection? =
        error("tests must not open real pages; use selectFor doubles")

    fun hasTextForPage(pageIndex: Int): Boolean =
        pageTextByIndex[pageIndex].orEmpty().isNotBlank()

    fun pageTextFor(pageIndex: Int): String = pageTextByIndex[pageIndex].orEmpty()

    fun searchFor(pageIndex: Int, query: String): List<PdfFrameworkTextMatch> {
        searchQueries += query
        return matchesByQuery[query]?.get(pageIndex).orEmpty()
    }

    fun selectFor(
        pageIndex: Int,
        start: PdfSelectionBoundarySpec,
        stop: PdfSelectionBoundarySpec,
    ): PdfFrameworkPageSelection? {
        if (!supportsSelection) return null
        selectCalls += Triple(pageIndex, start, stop)
        return selectionByPage[pageIndex]
    }
}

class PdfFrameworkTextApiFallbackTest {

    @Test
    fun `Unavailable api reports unavailable without advertising search`() {
        val api = PdfFrameworkTextApi.Unavailable
        assertFalse(api.isAvailable)
        assertFalse(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = api.isAvailable,
                bookOpen = true,
            ),
        )
    }

    @Test
    fun `fake text api maps matches when api available without early text proof`() {
        val fake = FakePdfFrameworkTextApi(
            pageTextByIndex = mapOf(0 to "alpha beta gamma"),
            matchesByQuery = mapOf(
                "beta" to mapOf(
                    0 to listOf(
                        PdfFrameworkTextMatch(6, listOf(PdfRect(1f, 2f, 3f, 4f))),
                    ),
                ),
            ),
        )
        assertTrue(fake.hasTextForPage(0))
        assertFalse(fake.hasTextForPage(1))
        val pageMatches = fake.searchFor(0, "beta")
        val mapped = mapPdfFrameworkMatches(
            pageIndex = 0,
            pageCount = 3,
            query = "beta",
            pageText = fake.pageTextFor(0),
            matches = pageMatches,
            pageWidthPt = 100f,
            pageHeightPt = 200f,
        )
        assertEquals(1, mapped.size)
        assertEquals(4, mapped.single().hit.matchLength)
        assertEquals(6, mapped.single().hit.matchStart)
        assertEquals(0, (mapped.single().hit.locator.strategy as LocatorStrategy.Page).index)
        assertEquals(1f, mapped.single().pagePointBounds.single().left)
        assertTrue(PdfSearchLifecycle.supportsSearch(fake.isAvailable, bookOpen = true))
    }

    @Test
    fun `image only session stays search capable and returns empty hits when api available`() {
        val pageCount = 2
        val fake = FakePdfFrameworkTextApi(
            pageTextByIndex = mapOf(0 to "", 1 to "   "),
            matchesByQuery = emptyMap(),
        )
        // No early-page text required to advertise search.
        val earlyText = (0 until pageCount).any { fake.hasTextForPage(it) }
        assertFalse(earlyText)
        // API identity preservation independent of page content (empty pages).
        val session = resolvePdfOpenTextSession(resolvedApi = fake, bookOpen = true)
        assertSame(fake, session.api)
        assertTrue(session.searchCapable)
        assertTrue(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = fake.isAvailable,
                bookOpen = true,
            ),
        )
        // User-triggered full-document scan yields honest empty results.
        val hits = (0 until pageCount).flatMap { pageIndex ->
            mapPdfFrameworkMatches(
                pageIndex = pageIndex,
                pageCount = pageCount,
                query = "needle",
                pageText = fake.pageTextFor(pageIndex),
                matches = fake.searchFor(pageIndex, "needle"),
                pageWidthPt = 100f,
                pageHeightPt = 100f,
            )
        }
        assertTrue(hits.isEmpty())
        assertFalse(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = false,
                bookOpen = true,
            ),
        )
        assertFalse(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = fake.isAvailable,
                bookOpen = false,
            ),
        )
    }

    @Test
    fun `late text beyond legacy open probe page limit is discoverable without early page text proof`() {
        // Test-local only: production open resolves API with no N-page content probe.
        val LEGACY_OPEN_TEXT_PROBE_PAGE_LIMIT = 8
        val needle = "needle"
        val latePage = LEGACY_OPEN_TEXT_PROBE_PAGE_LIMIT // zero-based; past former probe window
        val pageCount = latePage + 1
        val pageTextByIndex = (0 until pageCount).associateWith { index ->
            if (index == latePage) "cover $needle trailing" else ""
        }
        val fake = FakePdfFrameworkTextApi(
            pageTextByIndex = pageTextByIndex,
            matchesByQuery = mapOf(
                needle to mapOf(
                    latePage to listOf(
                        PdfFrameworkTextMatch(
                            textStartIndex = 6,
                            bounds = listOf(PdfRect(10f, 20f, 40f, 30f)),
                        ),
                    ),
                ),
            ),
        )
        // Former open-time probe window (indices 0 until LEGACY_OPEN_TEXT_PROBE_PAGE_LIMIT) has no text.
        val earlyProbeFound = (0 until LEGACY_OPEN_TEXT_PROBE_PAGE_LIMIT).any { fake.hasTextForPage(it) }
        assertFalse(earlyProbeFound)
        assertTrue(fake.hasTextForPage(latePage))
        // Session capability does not require early text proof; API identity preserved.
        val session = resolvePdfOpenTextSession(resolvedApi = fake, bookOpen = true)
        assertSame(fake, session.api)
        assertTrue(session.searchCapable)
        assertTrue(PdfSearchLifecycle.supportsSearch(fake.isAvailable, bookOpen = true))

        // Full-document user search maps the late hit (no production N-page open limit).
        val mapped = (0 until pageCount).flatMap { pageIndex ->
            mapPdfFrameworkMatches(
                pageIndex = pageIndex,
                pageCount = pageCount,
                query = needle,
                pageText = fake.pageTextFor(pageIndex),
                matches = fake.searchFor(pageIndex, needle),
                pageWidthPt = 612f,
                pageHeightPt = 792f,
            )
        }
        assertEquals(1, mapped.size)
        val page = mapped.single().hit.locator.strategy as LocatorStrategy.Page
        assertEquals(latePage, page.index)
        assertEquals(pageCount, page.total)
        assertEquals(needle.length, mapped.single().hit.matchLength)
        assertEquals(6, mapped.single().hit.matchStart)
    }
}