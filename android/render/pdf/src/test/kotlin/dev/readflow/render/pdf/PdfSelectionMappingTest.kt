package dev.readflow.render.pdf

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure mapping tests for PDF framework selection → PageText ReaderTextSelection,
 * annotation filtering, range→rect paint keys, overlay separation, and a11y copy.
 *
 * No PdfRenderer / Robolectric — only local models and pure helpers.
 */
class PdfSelectionMappingTest {

    private fun boundary(index: Int, x: Int? = null, y: Int? = null) =
        PdfFrameworkSelectionBoundary(index = index, pointX = x, pointY = y, isRtl = false)

    private fun pageSelection(
        page: Int,
        startIndex: Int,
        stopIndex: Int,
        text: String,
        bounds: List<PdfRect> = listOf(PdfRect(1f, 2f, 30f, 14f)),
    ) = PdfFrameworkPageSelection(
        page = page,
        start = boundary(startIndex, 1, 2),
        stop = boundary(stopIndex, 30, 14),
        selectedTextContents = listOf(PdfFrameworkTextContent(text, bounds)),
    )

    @Test
    fun `mapPageSelection builds same-page PageText start and end with char offsets`() {
        val pageText = "Hello selected world"
        // "selected" at index 6..14
        val selection = pageSelection(page = 1, startIndex = 6, stopIndex = 14, text = "selected")
        val mapped = mapPageSelectionToReaderTextSelection(
            pageIndex = 1,
            pageCount = 10,
            pageText = pageText,
            selection = selection,
        )
        assertNotNull(mapped)
        val start = mapped!!.start.strategy as LocatorStrategy.PageText
        val end = mapped.end.strategy as LocatorStrategy.PageText
        assertEquals(1, start.index)
        assertEquals(10, start.total)
        assertEquals(6, start.charOffset)
        assertEquals(1, end.index)
        assertEquals(14, end.charOffset)
        assertEquals("selected", mapped.selectedText)
        assertEquals(0.1f, mapped.start.progression)
    }

    @Test
    fun `mapPageSelection fails closed on blank text page mismatch or inverted empty range`() {
        val pageText = "abcdef"
        assertNull(
            mapPageSelectionToReaderTextSelection(
                pageIndex = 0,
                pageCount = 5,
                pageText = pageText,
                selection = pageSelection(0, 0, 3, text = "   "),
            ),
        )
        assertNull(
            mapPageSelectionToReaderTextSelection(
                pageIndex = 0,
                pageCount = 5,
                pageText = pageText,
                selection = pageSelection(page = 2, startIndex = 0, stopIndex = 2, text = "ab"),
            ),
        )
        assertNull(
            mapPageSelectionToReaderTextSelection(
                pageIndex = 9,
                pageCount = 5,
                pageText = pageText,
                selection = pageSelection(9, 0, 2, "ab"),
            ),
        )
        assertNull(
            mapPageSelectionToReaderTextSelection(
                pageIndex = 0,
                pageCount = 0,
                pageText = pageText,
                selection = pageSelection(0, 0, 2, "ab"),
            ),
        )
    }

    @Test
    fun `resolveCharRange prefers framework indices then text locate`() {
        val pageText = "alpha beta gamma"
        val withIndices = pageSelection(0, 6, 10, "beta")
        assertEquals(6 to 10, resolveCharRange(pageText, withIndices))

        val noIndices = PdfFrameworkPageSelection(
            page = 0,
            start = boundary(-1),
            stop = boundary(-1),
            selectedTextContents = listOf(
                PdfFrameworkTextContent("beta", listOf(PdfRect(0f, 0f, 1f, 1f))),
            ),
        )
        assertEquals(6 to 10, resolveCharRange(pageText, noIndices))

        val missing = PdfFrameworkPageSelection(
            page = 0,
            start = boundary(-1),
            stop = boundary(-1),
            selectedTextContents = listOf(
                PdfFrameworkTextContent("zzz", emptyList()),
            ),
        )
        assertNull(resolveCharRange(pageText, missing))
    }

    @Test
    fun `resolveCharRange fails closed for out of range negative collapse and text mismatch`() {
        val pageText = "hello world" // length 11

        // Out-of-range framework indices are never trusted (no lo..length fabrication).
        // Unresolvable selectedText → null; locatable text → deterministic indexOf fallback.
        assertNull(
            resolveCharRange(
                pageText,
                pageSelection(0, 0, 99, "not-in-page"),
            ),
        )
        assertEquals(
            0 to 11,
            resolveCharRange(
                pageText,
                pageSelection(0, 0, 99, "hello world"),
            ),
        )
        assertEquals(
            10 to 11,
            resolveCharRange(
                pageText,
                pageSelection(0, 10, 20, "d"),
            ),
        )
        // lo == length (exclusive window past end) + text not in page → null
        assertNull(
            resolveCharRange(
                pageText,
                pageSelection(0, 11, 12, "x"),
            ),
        )

        // Negative indices: never rely on framework ends; locate when possible
        assertEquals(
            0 to 5,
            resolveCharRange(
                pageText,
                pageSelection(0, -1, 5, "hello"),
            ),
        )
        assertNull(
            resolveCharRange(
                pageText,
                pageSelection(0, -1, 5, "nope"),
            ),
        )
        // Only one end non-negative still rejects the index path
        assertNull(
            resolveCharRange(
                pageText,
                pageSelection(0, 0, -1, "nope"),
            ),
        )

        // Zero collapse (start==stop): reject indices; locate or null
        assertEquals(
            2 to 3,
            resolveCharRange(
                pageText,
                pageSelection(0, 3, 3, "l"),
            ),
        )
        assertNull(
            resolveCharRange(
                pageText,
                pageSelection(0, 3, 3, "zzz"),
            ),
        )

        // Valid window but selectedText mismatches substring → try locate, fail if not found
        assertNull(
            resolveCharRange(
                pageText,
                pageSelection(0, 0, 5, "XXXXX"),
            ),
        )

        // Mismatch indices but text still locatable → fallback to indexOf
        assertEquals(
            6 to 11,
            resolveCharRange(
                pageText,
                pageSelection(0, 0, 5, "world"),
            ),
        )

        // Empty pageText never fabricates from indices alone
        assertNull(
            resolveCharRange(
                "",
                pageSelection(0, 0, 4, "test"),
            ),
        )

        // Empty selectedText with valid indices still allowed only when selectedText empty
        assertEquals(
            0 to 5,
            resolveCharRange(
                pageText,
                PdfFrameworkPageSelection(
                    page = 0,
                    start = boundary(0),
                    stop = boundary(5),
                    selectedTextContents = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `selection gesture event freshness drops stale completions including finished clear`() {
        var openGen = 1
        var eventId = 0
        var published: String? = null

        fun onGestureEvent(label: String, finished: Boolean, apply: () -> Unit) {
            eventId = PdfSelectionGestureLifecycle.nextEventId(eventId)
            val capturedOpen = openGen
            val capturedEvent = eventId
            // Simulate async completion out of order: older events may finish later.
            apply()
            if (
                !PdfSelectionGestureLifecycle.isEventFresh(
                    openBookGeneration = openGen,
                    eventOpenBookGeneration = capturedOpen,
                    currentEventId = eventId,
                    resultEventId = capturedEvent,
                )
            ) {
                return
            }
            if (finished && label == "clear") {
                published = null
            } else {
                published = label
            }
        }

        // Event 1 starts (slow), event 2 completes first, then event 1 must not overwrite.
        val event1Id = PdfSelectionGestureLifecycle.nextEventId(eventId).also { eventId = it }
        val event1Open = openGen
        eventId = PdfSelectionGestureLifecycle.nextEventId(eventId)
        val event2Id = eventId
        // Latest (event 2) applies
        assertTrue(
            PdfSelectionGestureLifecycle.isEventFresh(openGen, openGen, eventId, event2Id),
        )
        published = "latest"
        // Stale event 1 completes late
        assertFalse(
            PdfSelectionGestureLifecycle.isEventFresh(openGen, event1Open, eventId, event1Id),
        )
        // Would-have-been overwrite rejected
        assertEquals("latest", published)

        // Finished clear from an older event after a newer move must not wipe selection
        eventId = PdfSelectionGestureLifecycle.nextEventId(eventId)
        val staleFinishId = eventId
        val staleFinishOpen = openGen
        eventId = PdfSelectionGestureLifecycle.nextEventId(eventId) // newer move
        published = "newer-move"
        assertFalse(
            PdfSelectionGestureLifecycle.isEventFresh(
                openGen,
                staleFinishOpen,
                eventId,
                staleFinishId,
            ),
        )
        assertEquals("newer-move", published)

        // clearTextSelection / cancel bumps event id so in-flight publish is rejected
        eventId = PdfSelectionGestureLifecycle.nextEventId(eventId)
        val cancelledEvent = eventId
        eventId = PdfSelectionGestureLifecycle.nextEventId(eventId) // invalidate
        assertFalse(
            PdfSelectionGestureLifecycle.isEventFresh(openGen, openGen, eventId, cancelledEvent),
        )

        // openBook / close bumps open generation
        val preCloseEvent = eventId
        openGen = PdfSearchLifecycle.nextGeneration(openGen)
        assertFalse(
            PdfSelectionGestureLifecycle.isEventFresh(
                openBookGeneration = openGen,
                eventOpenBookGeneration = 1,
                currentEventId = eventId,
                resultEventId = preCloseEvent,
            ),
        )

        // Explicit path using helper that mirrors engine publish gate
        onGestureEvent("a", finished = false) { /* enqueue */ }
        onGestureEvent("b", finished = false) { /* enqueue */ }
        assertEquals("b", published)
        onGestureEvent("clear", finished = true) { /* finish fail path */ }
        assertNull(published)
    }

    @Test
    fun `host does not intercept idle scroll or pinch while selecting only when active`() {
        assertFalse(
            PdfSelectionGestureLifecycle.hostInterceptsForSelection(
                selecting = false,
                scaleInProgress = false,
            ),
        )
        assertFalse(
            PdfSelectionGestureLifecycle.hostInterceptsForSelection(
                selecting = false,
                scaleInProgress = true,
            ),
        )
        assertFalse(
            PdfSelectionGestureLifecycle.hostInterceptsForSelection(
                selecting = true,
                scaleInProgress = true,
            ),
        )
        assertTrue(
            PdfSelectionGestureLifecycle.hostInterceptsForSelection(
                selecting = true,
                scaleInProgress = false,
            ),
        )
    }

    @Test
    fun `pageTextAnnotationsForPage ignores foreign and cross-page locators`() {
        val samePage = ReaderTextAnnotation(
            id = "a1",
            start = Locator(LocatorStrategy.PageText(2, 10, 0)),
            end = Locator(LocatorStrategy.PageText(2, 10, 5)),
            selectedText = "hello",
            note = null,
            color = 0x66FFE082,
        )
        val otherPage = ReaderTextAnnotation(
            id = "a2",
            start = Locator(LocatorStrategy.PageText(3, 10, 0)),
            end = Locator(LocatorStrategy.PageText(3, 10, 5)),
            selectedText = "other",
            note = null,
            color = 0x66FFE082,
        )
        val crossPage = ReaderTextAnnotation(
            id = "a3",
            start = Locator(LocatorStrategy.PageText(2, 10, 0)),
            end = Locator(LocatorStrategy.PageText(3, 10, 5)),
            selectedText = "cross",
            note = null,
            color = 0x66FFE082,
        )
        val section = ReaderTextAnnotation(
            id = "a4",
            start = Locator(LocatorStrategy.Section(0, 1, 0)),
            end = Locator(LocatorStrategy.Section(0, 1, 4)),
            selectedText = "epub",
            note = null,
            color = 0x66FFE082,
        )
        val filtered = pageTextAnnotationsForPage(
            listOf(samePage, otherPage, crossPage, section),
            pageIndex = 2,
        )
        assertEquals(listOf(samePage), filtered)
        assertEquals(0 to 5, pageTextAnnotationCharRange(samePage))
        assertNull(pageTextAnnotationCharRange(crossPage))
        assertNull(pageTextAnnotationCharRange(section))
    }

    @Test
    fun `mapAnnotationBoundsForPage preserves colors and skips missing geometry`() {
        val ann = ReaderTextAnnotation(
            id = "yellow",
            start = Locator(LocatorStrategy.PageText(0, 3, 1)),
            end = Locator(LocatorStrategy.PageText(0, 3, 4)),
            selectedText = "abc",
            note = null,
            color = 0x66FFE082.toInt(),
        )
        val missingGeom = ReaderTextAnnotation(
            id = "no-geom",
            start = Locator(LocatorStrategy.PageText(0, 3, 5)),
            end = Locator(LocatorStrategy.PageText(0, 3, 8)),
            selectedText = "def",
            note = null,
            color = 0x6600FF00,
        )
        val rects = mapAnnotationBoundsForPage(
            annotations = listOf(ann, missingGeom),
            pageIndex = 0,
            boundsByAnnotationId = mapOf(
                "yellow" to listOf(PdfRect(10f, 20f, 40f, 30f)),
            ),
        )
        assertEquals(1, rects.size)
        assertEquals(0x66FFE082.toInt(), rects.single().color)
        assertEquals(10f, rects.single().rect.left)
    }

    @Test
    fun `annotationPaintKey is stable and changes when range or color changes`() {
        val a = ReaderTextAnnotation(
            id = "id",
            start = Locator(LocatorStrategy.PageText(0, 5, 1)),
            end = Locator(LocatorStrategy.PageText(0, 5, 4)),
            selectedText = "x",
            note = null,
            color = 1,
        )
        val same = a.copy(selectedText = "different text ignored by key")
        val colorChange = a.copy(color = 2)
        assertEquals(annotationPaintKey(listOf(a)), annotationPaintKey(listOf(same)))
        assertTrue(annotationPaintKey(listOf(a)) != annotationPaintKey(listOf(colorChange)))
    }

    @Test
    fun `overlay layers stay independent via separate clear helpers`() {
        // Pure contract: three rect providers never share identity; clearing search
        // does not imply empty annotation/selection lists at the mapping layer.
        val search = listOf(PdfRect(0f, 0f, 1f, 1f))
        val selection = listOf(PdfRect(2f, 2f, 3f, 3f))
        val annotations = listOf(
            PdfColoredRect(PdfRect(4f, 4f, 5f, 5f), color = 0x66FFE082.toInt()),
        )
        val clearedSearch = emptyList<PdfRect>()
        assertTrue(search.isNotEmpty())
        assertTrue(selection.isNotEmpty())
        assertTrue(annotations.isNotEmpty())
        assertTrue(clearedSearch.isEmpty())
        // Mapping annotation paint still works when search is empty.
        assertEquals(
            annotations,
            mapAnnotationBoundsForPage(
                annotations = listOf(
                    ReaderTextAnnotation(
                        id = "a",
                        start = Locator(LocatorStrategy.PageText(0, 1, 0)),
                        end = Locator(LocatorStrategy.PageText(0, 1, 1)),
                        selectedText = "a",
                        note = null,
                        color = 0x66FFE082.toInt(),
                    ),
                ),
                pageIndex = 0,
                boundsByAnnotationId = mapOf("a" to listOf(PdfRect(4f, 4f, 5f, 5f))),
            ),
        )
    }

    @Test
    fun `selectionPagePointBounds drops empty rects`() {
        val selection = PdfFrameworkPageSelection(
            page = 0,
            start = boundary(0),
            stop = boundary(2),
            selectedTextContents = listOf(
                PdfFrameworkTextContent(
                    "ab",
                    listOf(PdfRect(0f, 0f, 0f, 0f), PdfRect(1f, 1f, 5f, 8f)),
                ),
            ),
        )
        val bounds = selectionPagePointBounds(selection)
        assertEquals(1, bounds.size)
        assertEquals(1f, bounds.single().left)
    }

    @Test
    fun `pdfPageContentDescription announces page and selection snippet`() {
        assertEquals("第 1 页，共 5 页", pdfPageContentDescription(0, 5, null))
        assertEquals("第 2 页，共 5 页", pdfPageContentDescription(1, 5, "   "))
        assertTrue(
            pdfPageContentDescription(0, 5, "重点内容").contains("已选中：重点内容"),
        )
        val long = "x".repeat(50)
        val desc = pdfPageContentDescription(0, 3, long)
        assertTrue(desc.contains("已选中："))
        assertTrue(desc.endsWith("…"))
    }

    @Test
    fun `bitmapPixelToPagePoint scales and clamps`() {
        val pt = bitmapPixelToPagePoint(
            bitmapX = 306f,
            bitmapY = 396f,
            pageWidthPt = 612f,
            pageHeightPt = 792f,
            bitmapWidthPx = 612,
            bitmapHeightPx = 792,
        )
        assertEquals(306f, pt.first, 0.01f)
        assertEquals(396f, pt.second, 0.01f)
        val zero = bitmapPixelToPagePoint(10f, 10f, 100f, 100f, 0, 100)
        assertEquals(0f to 0f, zero)
    }

    @Test
    fun `generation clear discards stale selection like search`() {
        var generation = 0
        val openGen = generation
        assertTrue(PdfSearchLifecycle.isResultFresh(generation, openGen))
        generation = PdfSearchLifecycle.nextGeneration(generation) // close/openBook
        assertFalse(PdfSearchLifecycle.isResultFresh(generation, openGen))
    }

    @Test
    fun `fake selectContent fails closed when selection unsupported`() {
        val disabled = FakePdfFrameworkTextApi(supportsSelection = false)
        assertFalse(disabled.supportsSelection)
        assertNull(
            disabled.selectFor(
                pageIndex = 0,
                start = PdfSelectionBoundarySpec.Point(1, 2),
                stop = PdfSelectionBoundarySpec.Point(3, 4),
            ),
        )
        assertTrue(disabled.selectCalls.isEmpty())

        val selection = pageSelection(0, 0, 4, "test")
        val enabled = FakePdfFrameworkTextApi(
            selectionByPage = mapOf(0 to selection),
        )
        val got = enabled.selectFor(
            0,
            PdfSelectionBoundarySpec.CharIndex(0),
            PdfSelectionBoundarySpec.CharIndex(4),
        )
        assertEquals(selection, got)
        assertEquals(1, enabled.selectCalls.size)
        // Map through pure helper as engine would.
        val mapped = mapPageSelectionToReaderTextSelection(
            pageIndex = 0,
            pageCount = 2,
            pageText = "test more",
            selection = got!!,
        )
        assertEquals("test", mapped?.selectedText)
        assertEquals(
            LocatorStrategy.PageText(0, 2, 0),
            mapped?.start?.strategy,
        )
    }

    @Test
    fun `Unavailable api does not advertise selection`() {
        val api = PdfFrameworkTextApi.Unavailable
        assertFalse(api.isAvailable)
        assertFalse(api.supportsSelection)
        // Production fail-closed: callers check supportsSelection before opening a page.
        assertFalse(
            PdfSearchLifecycle.supportsSearch(
                apiAvailable = api.isAvailable,
                bookOpen = true,
            ),
        )
    }
}

/**
 * Lifecycle / paint-key slice for setTextAnnotations idempotency and reopen clear contract.
 * Pure — no engine instance required.
 */
class PdfAnnotationLifecycleTest {

    @Test
    fun `setTextAnnotations paint key skips equivalent lists`() {
        val ann = ReaderTextAnnotation(
            id = "p1",
            start = Locator(LocatorStrategy.PageText(1, 20, 10)),
            end = Locator(LocatorStrategy.PageText(1, 20, 20)),
            selectedText = "chunk",
            note = "n",
            color = 0x66FFE082.toInt(),
        )
        var applied = 0
        var lastKey = ""
        fun apply(list: List<ReaderTextAnnotation>) {
            val key = annotationPaintKey(list)
            if (key == lastKey) return
            lastKey = key
            applied++
        }
        apply(listOf(ann))
        apply(listOf(ann)) // identical
        apply(listOf(ann.copy(selectedText = "ignored by key"))) // still same paint key
        assertEquals(1, applied)
        apply(listOf(ann.copy(color = 99)))
        assertEquals(2, applied)
    }

    @Test
    fun `reopen generation clears live selection identity`() {
        // Simulate open → select → close/open: generation bump invalidates prior selection work.
        var generation = 0
        val selectGen = generation
        var liveSelection: String? = "hello"
        generation = PdfSearchLifecycle.nextGeneration(generation) // close
        if (!PdfSearchLifecycle.isResultFresh(generation, selectGen)) {
            liveSelection = null
        }
        assertNull(liveSelection)
        generation = PdfSearchLifecycle.nextGeneration(generation) // openBook
        assertNull(liveSelection)
        assertTrue(PdfSearchLifecycle.isResultFresh(generation, generation))
    }

    @Test
    fun `same-id range replacement clears geometry and rejects stale async put`() {
        // Mirrors setTextAnnotations: paint-key change → bump list gen + clear bounds;
        // only a resolution that still matches open + list gen may put geometry.
        data class Geom(val rangeLabel: String)

        var openGen = 1
        var listGen = 0
        val bounds = HashMap<String, Geom>()
        var lastKey = ""

        fun setAnnotations(list: List<ReaderTextAnnotation>) {
            val key = annotationPaintKey(list)
            if (key == lastKey) return
            lastKey = key
            listGen = PdfAnnotationGeometryLifecycle.nextGeneration(listGen)
            bounds.clear()
        }

        fun tryPutGeometry(
            resolutionOpen: Int,
            resolutionList: Int,
            id: String,
            geom: Geom,
        ): Boolean {
            if (!PdfAnnotationGeometryLifecycle.isResolutionFresh(
                    openBookGeneration = openGen,
                    resolutionOpenBookGeneration = resolutionOpen,
                    annotationListGeneration = listGen,
                    resolutionAnnotationListGeneration = resolutionList,
                )
            ) {
                return false
            }
            bounds[id] = geom
            return true
        }

        val rangeA = ReaderTextAnnotation(
            id = "shared",
            start = Locator(LocatorStrategy.PageText(0, 10, 0)),
            end = Locator(LocatorStrategy.PageText(0, 10, 5)),
            selectedText = "hello",
            note = null,
            color = 1,
        )
        val rangeB = rangeA.copy(
            start = Locator(LocatorStrategy.PageText(0, 10, 5)),
            end = Locator(LocatorStrategy.PageText(0, 10, 10)),
            selectedText = "world",
        )

        setAnnotations(listOf(rangeA))
        val genA = listGen
        assertTrue(tryPutGeometry(openGen, genA, "shared", Geom("0-5")))
        assertEquals("0-5", bounds["shared"]?.rangeLabel)

        // Same id, new PageText range → must drop old geometry and bump list gen.
        setAnnotations(listOf(rangeB))
        assertTrue(bounds.isEmpty())
        assertTrue(listGen > genA)

        // Stale completion for range A must not put after range B is active.
        assertFalse(tryPutGeometry(openGen, genA, "shared", Geom("0-5-stale")))
        assertTrue(bounds.isEmpty())

        // Fresh completion for range B is accepted.
        val genB = listGen
        assertTrue(tryPutGeometry(openGen, genB, "shared", Geom("5-10")))
        assertEquals("5-10", bounds["shared"]?.rangeLabel)

        // Open/close generation change rejects even matching list gen.
        val genBeforeClose = listGen
        openGen = PdfSearchLifecycle.nextGeneration(openGen)
        listGen = PdfAnnotationGeometryLifecycle.nextGeneration(listGen) // clearAnnotationState
        bounds.clear()
        assertFalse(tryPutGeometry(1, genBeforeClose, "shared", Geom("zombie")))
        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `annotation geometry resolution freshness requires both generations`() {
        assertTrue(
            PdfAnnotationGeometryLifecycle.isResolutionFresh(
                openBookGeneration = 2,
                resolutionOpenBookGeneration = 2,
                annotationListGeneration = 3,
                resolutionAnnotationListGeneration = 3,
            ),
        )
        assertFalse(
            PdfAnnotationGeometryLifecycle.isResolutionFresh(
                openBookGeneration = 2,
                resolutionOpenBookGeneration = 2,
                annotationListGeneration = 4,
                resolutionAnnotationListGeneration = 3,
            ),
        )
        assertFalse(
            PdfAnnotationGeometryLifecycle.isResolutionFresh(
                openBookGeneration = 3,
                resolutionOpenBookGeneration = 2,
                annotationListGeneration = 3,
                resolutionAnnotationListGeneration = 3,
            ),
        )
        // list gen 0 means never invalidated for a real set — reject put
        assertFalse(
            PdfAnnotationGeometryLifecycle.isResolutionFresh(
                openBookGeneration = 1,
                resolutionOpenBookGeneration = 1,
                annotationListGeneration = 0,
                resolutionAnnotationListGeneration = 0,
            ),
        )
    }
}
