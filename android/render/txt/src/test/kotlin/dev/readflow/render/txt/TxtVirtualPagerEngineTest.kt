package dev.readflow.render.txt

import android.app.Activity
import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReaderTypographyRange
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelectionAwareTextView
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TxtVirtualPagerEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val attachedActivityControllers = mutableListOf<ActivityController<Activity>>()

    @After
    fun tearDown() {
        attachedActivityControllers.asReversed().forEach { controller ->
            controller.pause().stop().destroy()
        }
        attachedActivityControllers.clear()
        Dispatchers.resetMain()
    }

    private fun attachToActivityWindow(view: View) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        controller.get().setContentView(view)
        attachedActivityControllers += controller
    }

    @Test
    fun `engine fallback typography matches the new install defaults`() {
        val context = RuntimeEnvironment.getApplication()
        val engine = TxtVirtualPagerEngine(context)

        assertEquals(
            ReaderTypographyRange.DEFAULT_FONT_SIZE.toFloat(),
            engine.privateField("fontSizeSp") as Float,
            0.001f,
        )
        assertEquals(
            ReaderTypographyRange.DEFAULT_LINE_SPACING,
            engine.privateField("lineSpacingMultiplier") as Float,
            0.001f,
        )
        assertEquals("source_han", engine.privateField("currentFontId"))
    }

    @Test
    fun `empty file opens and enters paged mode with a stable empty locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-empty-", suffix = ".txt").toFile()
        val engine = TxtVirtualPagerEngine(context)

        val opened = engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(420, 640)
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0) as FrameLayout

        assertEquals(LocatorStrategy.ByteOffset(offset = 0L, length = 0), opened.strategy)
        assertEquals(opened, engine.currentLocator.value)
        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        assertEquals(1, engine.pageCount.value)
        assertEquals(0, (page.getChildAt(0) as LinearLayout).childCount)
        assertEquals(0, engine.pageIndexForLocator(engine.currentLocator.value))
    }

    @Test
    fun `paged CJK rows are unweighted and measure to visible bounds`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-cjk-page-", suffix = ".txt").toFile()
        file.writeText(
            listOf(
                "围城（节选）\n作者：钱钟书",
                "红海早过了，船在印度洋面上开驶，但是太阳依然不饶人地迟落早起。",
                "方鸿渐从船舱的舷窗里看这种情况，觉得眼前的海景好得不落实。",
            ).joinToString("\n\n"),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)
        engine.setViewportSize(720, 1280)
        engine.openBook(Uri.fromFile(file))
        engine.setMode(ReadingMode.PAGED)

        val page = engine.createPageView(0) as FrameLayout
        val column = page.getChildAt(0) as LinearLayout
        assertTrue("the seeded CJK page must bind body rows", column.childCount > 0)
        for (index in 0 until column.childCount) {
            val row = column.getChildAt(index) as SelectionAwareTextView
            val params = row.layoutParams as LinearLayout.LayoutParams
            assertEquals(
                "WRAP_CONTENT paragraph rows must not consume vertical weight and collapse under ViewPager measure",
                0f,
                params.weight,
                0f,
            )
        }

        page.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 720, 1280)
        assertTrue("the paged CJK column must have visible bounds", column.width > 0 && column.height > 0)
        assertTrue(
            "every bound paragraph must have visible bounds",
            (0 until column.childCount).all { index ->
                val row = column.getChildAt(index)
                row.width > 0 && row.height > 0
            },
        )
    }

    @Test
    fun `paged page composites many short paragraphs into one text surface`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val paragraphs = (0 until 80).map { index ->
            "Short paragraph %02d keeps the real reader page densely populated.".format(index)
        }
        val file = kotlin.io.path.createTempFile(
            prefix = "readflow-txt-composited-page-",
            suffix = ".txt",
        ).toFile()
        file.writeText(paragraphs.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(720, 1280)
        engine.setMode(ReadingMode.PAGED)

        val page = engine.createPageView(0) as FrameLayout
        val column = page.getChildAt(0) as LinearLayout
        assertEquals(
            "one page must bind one selectable text surface instead of one TextView per paragraph",
            1,
            column.childCount,
        )
        val pageText = (column.getChildAt(0) as SelectionAwareTextView).text.toString()
        assertTrue(pageText.contains(paragraphs[0]))
        assertTrue(pageText.contains(paragraphs[1]))
        page.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 720, 1280)
        assertTrue(
            "composited text must stay inside the page geometry used by pagination",
            column.height <= page.height - page.paddingTop - page.paddingBottom,
        )
    }

    @Test
    fun `paged composite selection maps across paragraph boundaries`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val first = "First paragraph starts with alpha and stays selectable."
        val second = "Second paragraph ends with omega for the cross-boundary range."
        val third = "Third paragraph keeps the page populated."
        val source = listOf(first, second, third).joinToString("\n\n")
        val file = kotlin.io.path.createTempFile(
            prefix = "readflow-txt-composite-selection-",
            suffix = ".txt",
        ).toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(720, 1280)
        engine.setMode(ReadingMode.PAGED)

        val page = engine.createPageView(0) as FrameLayout
        val column = page.getChildAt(0) as LinearLayout
        val textView = column.getChildAt(0) as SelectionAwareTextView
        val visible = textView.text.toString()
        val selectionStart = visible.indexOf("alpha")
        val selectionEnd = visible.indexOf("omega") + "omega".length
        assertTrue(selectionStart >= 0 && selectionEnd > selectionStart)
        textView.onSelectionRangeChanged?.invoke(selectionStart, selectionEnd)

        val selection = checkNotNull(engine.currentTextSelection.value)
        val start = selection.start.strategy as LocatorStrategy.ByteOffset
        val end = selection.end.strategy as LocatorStrategy.ByteOffset
        val expectedStart = source.indexOf("alpha")
        val expectedEnd = source.indexOf("omega") + "omega".length
        assertEquals(expectedStart.toLong(), start.offset)
        assertEquals(expectedEnd.toLong(), end.offset)
        assertEquals(expectedEnd - expectedStart, start.length)
        assertEquals(visible.substring(selectionStart, selectionEnd), selection.selectedText)
    }

    @Test
    fun `paged mode splits one visually long paragraph without clipping content`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = "长段落内容".repeat(1_200)
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-long-page-", suffix = ".txt").toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(420, 640)
        engine.setMode(ReadingMode.PAGED)

        assertTrue("a long visual paragraph must span multiple readable pages", engine.pageCount.value > 1)
        engine.goTo(
            Locator(
                LocatorStrategy.Page(index = 1, total = engine.pageCount.value),
            ),
        )
        val secondPageAnchor = engine.currentLocator.value.strategy as LocatorStrategy.ByteOffset
        assertTrue("a later fragment must publish a paragraph-internal byte anchor", secondPageAnchor.offset > 0L)
        assertEquals(
            "the paragraph-internal anchor must map back to the same visual page",
            1,
            engine.pageIndexForLocator(engine.currentLocator.value),
        )
        val rendered = buildString {
            repeat(engine.pageCount.value) { pageIndex ->
                val page = engine.createPageView(pageIndex) as FrameLayout
                val column = page.getChildAt(0) as LinearLayout
                repeat(column.childCount) { childIndex ->
                    append((column.getChildAt(childIndex) as TextView).text)
                }
                page.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, 420, 640)
                assertTrue(
                    "page $pageIndex content must fit inside the viewport",
                    column.height <= page.height - page.paddingTop - page.paddingBottom,
                )
            }
        }
        assertEquals(source, rendered)
    }

    @Test
    fun `paged to scroll preserves position inside one long paragraph`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = (0 until 80).joinToString("\n") { index ->
            "Long line %03d anchors scroll.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-long-scroll-", suffix = ".txt").toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(420, 640)
        engine.setMode(ReadingMode.PAGED)
        assertTrue("fixture must split the single paragraph", engine.pageCount.value > 4)

        val targetPage = engine.pageCount.value / 2
        engine.goTo(Locator(LocatorStrategy.Page(targetPage, engine.pageCount.value)))
        val before = engine.currentLocator.value
        val beforeOffset = (before.strategy as LocatorStrategy.ByteOffset).offset
        assertTrue("fixture must use a paragraph-internal anchor", beforeOffset > 0L)

        engine.setMode(ReadingMode.SCROLL)
        val view = engine.createView() as RecyclerView
        attachToActivityWindow(view)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 420, 640)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 420, 640)
        engine.reportProgressionForTest(view)

        val after = engine.currentLocator.value
        val afterOffset = (after.strategy as LocatorStrategy.ByteOffset).offset
        val holder = view.findViewHolderForAdapterPosition(0) as? TxtParagraphAdapter.ParagraphHolder
        assertTrue(
            "continuous row must not reset the middle-page anchor to byte zero; " +
                "first=${(view.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()} " +
                "rowTop=${holder?.itemView?.top} textTop=${holder?.textView?.top} " +
                "padding=${holder?.textView?.totalPaddingTop} layoutHeight=${holder?.textView?.layout?.height}",
            afterOffset > 0L,
        )
        assertEquals(before.totalProgression ?: 0f, after.totalProgression ?: 0f, 0.02f)
        assertTrue(
            "continuous anchor must remain near the paged fragment; before=$beforeOffset after=$afterOffset",
            abs(afterOffset - beforeOffset) <= 512L,
        )
    }

    @Test
    fun `unchanged typography is a no-op in paged mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-noop-", suffix = ".txt").toFile()
        file.writeText((0 until 20).joinToString("\n\n") { "段落 $it" })
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(420, 640)
        engine.setMode(ReadingMode.PAGED)
        val requests = mutableListOf<Int>()
        engine.setPageRequestCallback(requests::add)

        engine.setFontSize(ReaderTypographyRange.DEFAULT_FONT_SIZE.toFloat())
        engine.setLineSpacing(ReaderTypographyRange.DEFAULT_LINE_SPACING)
        engine.setFont("source_han")

        assertTrue("no-op settings must not repaginate or move the visible page", requests.isEmpty())
    }

    @Test
    fun `typography repagination preserves the visible page start instead of an inner locator`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication()
            val source = (0 until 5_000).joinToString(" ") { index ->
                "token%05d".format(index)
            }
            val file = kotlin.io.path.createTempFile(
                prefix = "readflow-txt-typography-anchor-",
                suffix = ".txt",
            ).toFile()
            file.writeText(source, charset = StandardCharsets.UTF_8)
            val behaviorFailures = mutableListOf<String>()

            suspend fun verifyChange(
                label: String,
                changeTypography: suspend (TxtVirtualPagerEngine) -> Unit,
            ) {
                val engine = TxtVirtualPagerEngine(context)
                engine.setFontSize(12f)
                engine.setLineSpacing(1f)
                engine.openBook(Uri.fromFile(file))
                engine.setViewportSize(420, 640)
                engine.setMode(ReadingMode.PAGED)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("$label fixture must span several pages", engine.pageCount.value > 4)

                val targetPage = 2
                engine.goTo(Locator(LocatorStrategy.Page(targetPage, engine.pageCount.value)))
                shadowOf(Looper.getMainLooper()).idle()
                val pageStartOffset =
                    (engine.currentLocator.value.strategy as LocatorStrategy.ByteOffset).offset.toInt()
                engine.goTo(Locator(LocatorStrategy.Page(targetPage + 1, engine.pageCount.value)))
                shadowOf(Looper.getMainLooper()).idle()
                val nextPageStartOffset =
                    (engine.currentLocator.value.strategy as LocatorStrategy.ByteOffset).offset.toInt()
                val innerOffset = pageStartOffset + (nextPageStartOffset - pageStartOffset) * 3 / 4
                val visiblePageStart = Locator(
                    LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = pageStartOffset),
                )
                val innerLocator = Locator(
                    LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = innerOffset),
                )
                assertTrue(
                    "$label fixture locator must be strictly inside the old visual page",
                    innerOffset > pageStartOffset && innerOffset < nextPageStartOffset,
                )
                assertEquals(targetPage, engine.pageIndexForLocator(visiblePageStart))
                assertEquals(targetPage, engine.pageIndexForLocator(innerLocator))
                engine.goTo(innerLocator)
                shadowOf(Looper.getMainLooper()).idle()

                val requestedPages = mutableListOf<Int>()
                engine.setPageRequestCallback(requestedPages::add)
                changeTypography(engine)
                shadowOf(Looper.getMainLooper()).idle()

                val expectedPage = engine.pageIndexForLocator(visiblePageStart)
                val promotedInnerPage = engine.pageIndexForLocator(innerLocator)
                assertTrue(
                    "$label fixture must distinguish the old page start from the inner locator after repagination",
                    expectedPage != promotedInnerPage,
                )
                assertTrue("$label change must request a repaginated page", requestedPages.isNotEmpty())
                val requestedPage = requestedPages.last()
                val page = engine.createPageView(requestedPage) as FrameLayout
                val column = page.getChildAt(0) as LinearLayout
                val visibleText = buildString {
                    repeat(column.childCount) { childIndex ->
                        append((column.getChildAt(childIndex) as TextView).text)
                    }
                }
                val oldPageStartPrefix = source.substring(
                    pageStartOffset,
                    (pageStartOffset + 48).coerceAtMost(source.length),
                )
                if (requestedPage != expectedPage) {
                    behaviorFailures +=
                        "$label page request expected=$expectedPage actual=$requestedPage " +
                            "oldStart=(paragraph=0,charOffset=$pageStartOffset) innerCharOffset=$innerOffset"
                }
                if (!visibleText.startsWith(oldPageStartPrefix)) {
                    behaviorFailures +=
                        "$label requested page text must start at old visual page start " +
                            "(paragraph=0,charOffset=$pageStartOffset); " +
                            "expectedPrefix=${oldPageStartPrefix.take(32)} " +
                            "actualPrefix=${visibleText.take(32)}"
                }
            }

            verifyChange("font size") { it.setFontSize(24f) }
            verifyChange("line spacing") { it.setLineSpacing(2f) }
            assertTrue(
                behaviorFailures.joinToString(separator = "\n"),
                behaviorFailures.isEmpty(),
            )
        }

    @Test
    fun `continuous typography keeps an inner paragraph anchor visible`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = buildString {
            repeat(700) { index ->
                append("prefix%04d ".format(index))
            }
            append("TARGET_ANCHOR ")
            repeat(700) { index ->
                append("suffix%04d ".format(index))
            }
        }
        val targetOffset = source.indexOf("TARGET_ANCHOR")
        val file = kotlin.io.path.createTempFile(
            prefix = "readflow-txt-scroll-typography-",
            suffix = ".txt",
        ).toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.setInitialLocator(Locator(LocatorStrategy.Section(0, 0, targetOffset)))
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        attachToActivityWindow(view)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 420, 640)
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

        val holder = view.findViewHolderForAdapterPosition(0) as TxtParagraphAdapter.ParagraphHolder
        val textView = holder.textView
        val targetInText = textView.text.toString().indexOf("TARGET_ANCHOR")
        assertTrue("fixture must bind the target anchor", targetInText >= 0)
        fun targetIsVisible(): Boolean {
            val layout = textView.layout ?: return false
            val line = layout.getLineForOffset(targetInText)
            val top = holder.itemView.top + textView.top + textView.totalPaddingTop + layout.getLineTop(line)
            val bottom = holder.itemView.top + textView.top + textView.totalPaddingTop + layout.getLineBottom(line)
            return bottom > view.paddingTop && top < view.height - view.paddingBottom
        }
        assertTrue("initial inner anchor must be visible", targetIsVisible())

        engine.setFontSize(30f)
        engine.setLineSpacing(0.9f)
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 420, 640)
        }
        assertTrue(
            "continuous typography changes must keep the inner source anchor visible",
            targetIsVisible(),
        )
    }

    @Test
    fun `createView remount invalidates pending continuous typography anchor`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 120).map { index ->
            "Remount anchor paragraph %03d keeps its own surface position.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-remount-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val first = engine.createView() as RecyclerView
        first.measureLayout(1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        first.measureLayout(1080, 2400)
        (first.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(90, 0)
        first.measureLayout(1080, 2400)

        // Arm a pending continuous typography restore on the first surface; do not settle it.
        engine.setFontSize(30f)
        assertNotNull(
            "fixture must arm a pending typography anchor",
            engine.privateField("pendingContinuousTypographyAnchor"),
        )

        // Remount before the restore applies: the stale pending anchor must be invalidated.
        val second = engine.createView() as RecyclerView
        second.measureLayout(1080, 2400)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            second.measureLayout(1080, 2400)
        }
        assertNull(
            "createView remount must clear the stale pending anchor",
            engine.privateField("pendingContinuousTypographyAnchor"),
        )

        // User scrolls the new surface to a different paragraph, then applies typography again.
        (second.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(3, 0)
        second.measureLayout(1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        engine.setLineSpacing(1.2f)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            second.measureLayout(1080, 2400)
        }

        val firstVisible = (second.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        assertTrue(
            "typography after remount must anchor to the new surface, not the stale paragraph; " +
                "firstVisible=$firstVisible",
            firstVisible <= 3,
        )
        assertTrue(
            "locator must track the new surface anchor",
            engine.currentParagraphIndexForTest() <= 3,
        )
    }

    @Test
    fun `continuous goTo honors the inner charOffset after the holder layout exists`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 60).map { index ->
            buildString {
                repeat(40) { row -> append("Line %02d ".format(row)) }
                append("par%03d ".format(index))
                if (index == 45) append("GO_TO_INNER_TARGET ")
                repeat(40) { row -> append("tail %02d ".format(row)) }
            }
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-goto-inner-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        attachToActivityWindow(view)
        view.measureLayout(420, 640)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }

        val targetParagraph = 45
        val targetCharOffset = lines[targetParagraph].indexOf("GO_TO_INNER_TARGET")
        assertTrue("fixture must contain the inner target", targetCharOffset >= 0)
        engine.goTo(
            Locator(
                strategy = LocatorStrategy.Section(0, targetParagraph, targetCharOffset),
                totalProgression = targetParagraph.toFloat() / lines.size,
            ),
        )
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }

        val holder = view.findViewHolderForAdapterPosition(targetParagraph)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: error("target paragraph must be bound after goTo")
        val textView = holder.textView
        val renderedOffset = textView.text.toString().indexOf("GO_TO_INNER_TARGET")
        assertTrue("fixture must render the target token", renderedOffset >= 0)
        val layout = requireNotNull(textView.layout)
        val line = layout.getLineForOffset(renderedOffset)
        val lineTopInRecycler = holder.itemView.top + textView.top + textView.totalPaddingTop + layout.getLineTop(line)
        assertTrue(
            "continuous goTo must align the inner charOffset line at the viewport top; " +
                "lineTopInRecycler=$lineTopInRecycler paddingTop=${view.paddingTop}",
            abs(lineTopInRecycler - view.paddingTop) <= 2,
        )
        assertEquals(targetParagraph, engine.currentParagraphIndexForTest())
        val afterOffset = (engine.currentLocator.value.strategy as LocatorStrategy.ByteOffset).offset
        assertTrue("goTo must keep a paragraph-internal anchor", afterOffset > 0L)
    }

    @Test
    fun `later zero-offset goTo invalidates the earlier inner-offset navigation restore`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 80).map { index ->
            buildString {
                repeat(60) { row -> append("Line %02d ".format(row)) }
                append("par%03d ".format(index))
                if (index == 45) append("GO_TO_A_INNER ")
                if (index == 46) append("GO_TO_B ")
                repeat(60) { row -> append("tail %02d ".format(row)) }
            }
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-nav-generation-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        view.measureLayout(420, 640)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }

        val aParagraph = 45
        val aCharOffset = lines[aParagraph].indexOf("GO_TO_A_INNER")
        val bParagraph = 46
        assertTrue("fixture must arm an inner-offset goTo", aCharOffset > 0)
        // Rapid navigation: goTo(A, inner offset) is immediately superseded by goTo(B, offset 0)
        // before A's holder is ever attached.
        engine.goTo(
            Locator(
                strategy = LocatorStrategy.Section(0, aParagraph, aCharOffset),
                totalProgression = aParagraph.toFloat() / lines.size,
            ),
        )
        engine.goTo(
            Locator(
                strategy = LocatorStrategy.Section(0, bParagraph, 0),
                totalProgression = bParagraph.toFloat() / lines.size,
            ),
        )
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }

        val bHolder = view.findViewHolderForAdapterPosition(bParagraph)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: error("B holder must be bound after the final goTo settles")
        assertTrue(
            "the later goTo must own the settled viewport; bTop=${bHolder.itemView.top} paddingTop=${view.paddingTop}",
            abs(bHolder.itemView.top - view.paddingTop) <= 2,
        )

        // Force A's holder to attach again (explicit scroll to A with offset 0). The stale A
        // restore must be gone: the viewport must stay exactly at A's paragraph top and must not
        // be pulled back to A's inner charOffset line.
        (view.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(aParagraph, 0)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }
        val aHolder = view.findViewHolderForAdapterPosition(aParagraph)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: error("A holder must be bound after the explicit scroll")
        assertTrue(
            "the stale A restore must not pull the viewport to A's inner line; " +
                "aTop=${aHolder.itemView.top} paddingTop=${view.paddingTop}",
            abs(aHolder.itemView.top - view.paddingTop) <= 2,
        )
    }

    @Test
    fun `mode switch to paged preserves the pending typography anchor`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = buildString {
            repeat(120) { index -> append("Wide paragraph row %04d\n".format(index)) }
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-mode-pending-", suffix = ".txt").toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.setViewportSize(420, 640)
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        attachToActivityWindow(view)
        view.measureLayout(420, 640)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }
        // Establish the same paragraph-internal continuous position through the public locator
        // path. A detached Robolectric RecyclerView does not reliably consume raw scrollBy()
        // deltas for a single oversized child.
        val requestedOffset = source.length / 3
        engine.goTo(Locator(LocatorStrategy.Section(0, 0, requestedOffset)))
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }

        val holder = view.findViewHolderForAdapterPosition(0)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: error("fixture paragraph must be bound")
        val textView = holder.textView
        val layout = requireNotNull(textView.layout)
        val textTopInRecycler = holder.itemView.top + textView.top + textView.totalPaddingTop
        val topLineCharOffset = layout.getLineStart(layout.getLineForVertical(view.paddingTop - textTopInRecycler))
        val expectedLineStart = layout.getLineStart(layout.getLineForOffset(requestedOffset))
        val centerY = view.paddingTop + (view.height - view.paddingTop - view.paddingBottom) / 2
        val centerLineCharOffset = layout.getLineStart(layout.getLineForVertical(centerY - textTopInRecycler))
        assertEquals(
            "public goTo must place its requested line at the viewport top",
            expectedLineStart,
            topLineCharOffset,
        )
        assertTrue(
            "fixture must establish a non-zero public goTo top anchor; top=$topLineCharOffset",
            topLineCharOffset > 0,
        )
        assertTrue(
            "fixture must separate the top anchor from the viewport center; top=$topLineCharOffset center=$centerLineCharOffset",
            centerLineCharOffset > topLineCharOffset,
        )

        val requestedPages = mutableListOf<Int>()
        engine.setPageRequestCallback(requestedPages::add)
        // Arm a typography restore without settling the reflow, then switch modes immediately.
        engine.setFontSize(30f)
        assertNotNull(
            "fixture must arm a pending typography anchor",
            engine.privateField("pendingContinuousTypographyAnchor"),
        )
        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()

        // The PAGED anchor must be the pre-reflow captured source position (top visible line),
        // not a recomputation from the current pixel geometry (viewport center).
        val actualOffset = (engine.currentLocator.value.strategy as LocatorStrategy.ByteOffset).offset
        assertEquals(
            "PAGED anchor must preserve the pending typography anchor's byte offset",
            topLineCharOffset.toLong(),
            actualOffset,
        )
        assertTrue("mode switch must request exactly one page", requestedPages.size == 1)
        assertEquals(
            "the requested page must map to the preserved anchor",
            engine.pageIndexForLocator(engine.currentLocator.value),
            requestedPages.single(),
        )
    }

    @Test
    fun `setMode paged captures the centered inner line offset in continuous`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = buildString {
            repeat(100) { index -> append("Centered paragraph row %03d\n".format(index)) }
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-mode-center-", suffix = ".txt").toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        attachToActivityWindow(view)
        view.measureLayout(420, 640)
        shadowOf(Looper.getMainLooper()).idle()
        view.measureLayout(420, 640)
        // Use the public locator path to establish an in-paragraph continuous position.
        val requestedOffset = source.length / 3
        engine.goTo(Locator(LocatorStrategy.Section(0, 0, requestedOffset)))
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }
        val holder = view.findViewHolderForAdapterPosition(0)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: error("fixture paragraph must be bound after public goTo")
        val layout = requireNotNull(holder.textView.layout)
        val textTopInRecycler = holder.itemView.top + holder.textView.top + holder.textView.totalPaddingTop
        val topLineCharOffset = layout.getLineStart(
            layout.getLineForVertical(view.paddingTop - textTopInRecycler),
        )
        val expectedLineStart = layout.getLineStart(layout.getLineForOffset(requestedOffset))
        assertTrue("fixture request must be a paragraph-internal line", expectedLineStart > 0)
        assertEquals(
            "public goTo must place its requested line at the viewport top",
            expectedLineStart,
            topLineCharOffset,
        )
        assertEquals(0, (view.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())

        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()

        val offset = (engine.currentLocator.value.strategy as LocatorStrategy.ByteOffset).offset
        assertTrue(
            "SCROLL->PAGED must keep the centered inner line offset instead of paragraph start; offset=$offset",
            offset > 0L,
        )
        assertEquals(0, engine.currentParagraphIndexForTest())
    }

    @Test
    fun `continuous goTo at document end settles at the physical scroll boundary`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = buildString {
            repeat(1_200) { index -> append("terminal paragraph row %04d ".format(index)) }
            append("END_ANCHOR")
        }
        val targetOffset = source.indexOf("END_ANCHOR")
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-goto-end-", suffix = ".txt").toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        view.measureLayout(420, 640)
        engine.goTo(Locator(LocatorStrategy.Section(0, 0, targetOffset)))
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }

        val holder = view.findViewHolderForAdapterPosition(0)
            as? TxtParagraphAdapter.ParagraphHolder
            ?: error("end-anchor paragraph must remain bound")
        val layout = requireNotNull(holder.textView.layout)
        val endLine = layout.getLineForOffset(targetOffset)
        val endLineTop = holder.itemView.top + holder.textView.top +
            holder.textView.totalPaddingTop + layout.getLineTop(endLine)
        val endLineBottom = holder.itemView.top + holder.textView.top +
            holder.textView.totalPaddingTop + layout.getLineBottom(endLine)
        val layoutManager = view.layoutManager as LinearLayoutManager
        val lastItem = requireNotNull(layoutManager.findViewByPosition(requireNotNull(view.adapter).itemCount - 1))
        assertTrue(
            "fixture must navigate to the document's lower scroll boundary",
            layoutManager.getDecoratedBottom(lastItem) <= view.height - view.paddingBottom,
        )
        assertTrue(
            "the final line is visible at the lower boundary instead of being forced beyond it; " +
                "top=$endLineTop bottom=$endLineBottom",
            endLineTop >= view.paddingTop && endLineBottom <= view.height - view.paddingBottom,
        )
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("a boundary anchor must not leave a perpetual layout request", view.isLayoutRequested)
    }

    @Test
    fun `rapid typography restore waits for child reflow before completing`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = buildString {
            repeat(700) { index -> append("prefix%04d ".format(index)) }
            append("TARGET_ANCHOR ")
            repeat(700) { index -> append("suffix%04d ".format(index)) }
        }
        val targetOffset = source.indexOf("TARGET_ANCHOR")
        fun textViewState(
            phase: String,
            textView: SelectionAwareTextView,
            rowHeight: Int,
        ): String {
            fun textViewField(name: String): String {
                var type: Class<*>? = textView.javaClass
                while (type != null) {
                    val currentType = type
                    val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
                    if (field != null) {
                        return runCatching {
                            field.isAccessible = true
                            field.get(textView)?.toString() ?: "null"
                        }.getOrElse { "unreadable:${it.javaClass.simpleName}" }
                    }
                    type = currentType.superclass
                }
                return "absent"
            }
            val layout = textView.layout
            val firstLineWidth = layout?.takeIf { it.lineCount > 0 }?.getLineWidth(0)
            val firstLineMax = layout?.takeIf { it.lineCount > 0 }?.getLineMax(0)
            val boringMetricsWidth = (layout as? android.text.BoringLayout)
                ?.let { android.text.BoringLayout.isBoring(textView.text, textView.paint)?.width }
            return "$phase{" +
                "lineCount=${layout?.lineCount} rowHeight=$rowHeight " +
                "textSize=${textView.width}x${textView.height} " +
                "layoutSize=${layout?.width}x${layout?.height} " +
                "layoutClass=${layout?.javaClass?.name} " +
                "lineWidth0=${firstLineWidth ?: "n/a"} " +
                "lineMax0=${firstLineMax ?: "n/a"} " +
                "desiredWidth=${android.text.Layout.getDesiredWidth(textView.text, textView.paint)} " +
                "boringMetricsWidth=${boringMetricsWidth ?: "n/a"} " +
                "padding=${textView.totalPaddingLeft},${textView.totalPaddingTop}," +
                "${textView.totalPaddingRight},${textView.totalPaddingBottom} " +
                "maxLines=${textView.maxLines} minLines=${textView.minLines} " +
                "inputType=${textView.inputType} " +
                "transformation=${textView.transformationMethod?.javaClass?.name ?: "null"} " +
                "selectable=${textView.isTextSelectable} " +
                "mHorizontallyScrolling=${textViewField("mHorizontallyScrolling")} " +
                "mSingleLine=${textViewField("mSingleLine")}" +
                "}"
        }
        val textViewStates = mutableListOf<String>()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-rapid-typography-", suffix = ".txt").toFile()
        file.writeText(source, charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.setInitialLocator(Locator(LocatorStrategy.Section(0, 0, targetOffset)))
        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        fun recordEngineState(phase: String) {
            val holder = view.findViewHolderForAdapterPosition(0) as? TxtParagraphAdapter.ParagraphHolder
            textViewStates += if (holder == null) "$phase{holder=unbound}" else {
                textViewState(phase, holder.textView, holder.itemView.height)
            }
        }
        attachToActivityWindow(view)
        view.measureLayout(420, 640)
        recordEngineState("engine-after-first-measure")
        repeat(5) { pass ->
            shadowOf(Looper.getMainLooper()).idle()
            view.measureLayout(420, 640)
            recordEngineState("engine-after-idle-measure-$pass")
        }

        fun tokenLineTop(): Int {
            val holder = view.findViewHolderForAdapterPosition(0)
                as? TxtParagraphAdapter.ParagraphHolder ?: return -1
            val textView = holder.textView
            val layout = textView.layout ?: return -1
            val renderedOffset = textView.text.toString().indexOf("TARGET_ANCHOR")
            if (renderedOffset < 0) return -1
            val line = layout.getLineForOffset(renderedOffset)
            return holder.itemView.top + textView.top + textView.totalPaddingTop + layout.getLineTop(line)
        }
        val initialHolder = view.findViewHolderForAdapterPosition(0)
            as? TxtParagraphAdapter.ParagraphHolder ?: error("initial holder must remain bound")
        assertTrue(
            "fixture must exercise one vertically wrapped oversized row; ${textViewStates.joinToString(" | ")}",
            requireNotNull(initialHolder.textView.layout).lineCount > 1 &&
                initialHolder.itemView.height > view.height,
        )
        assertTrue(
            "initial restore must put the target line at the viewport top; " +
                "top=${tokenLineTop()} padding=${view.paddingTop}",
            abs(tokenLineTop() - view.paddingTop) <= 2,
        )

        engine.setFontSize(30f)
        engine.setLineSpacing(0.9f)
        // Drain only the one-shot posts: the child has not reflowed yet, so the restore must stay
        // pending instead of completing against the stale pre-reflow holder layout.
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(
            "restore must not complete on the stale pre-reflow holder layout",
            engine.privateField("pendingContinuousTypographyAnchor"),
        )
        // One real layout pass rebinds/reflows the child; the content listener must converge.
        view.measureLayout(420, 640)
        shadowOf(Looper.getMainLooper()).idle()
        view.measureLayout(420, 640)
        assertNull(
            "child reflow must complete the restore",
            engine.privateField("pendingContinuousTypographyAnchor"),
        )
        assertTrue(
            "typography restore must keep the captured source line at the viewport top; " +
                "top=${tokenLineTop()} padding=${view.paddingTop}",
            abs(tokenLineTop() - view.paddingTop) <= 2,
        )
    }

    @Test
    fun `cold paged open starts exactly at an initial locator inside a standard page`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication()
            val source = (0 until 5_000).joinToString(" ") { index ->
                "cold%05d".format(index)
            }
            val file = kotlin.io.path.createTempFile(
                prefix = "readflow-txt-cold-inner-anchor-",
                suffix = ".txt",
            ).toFile()
            file.writeText(source, charset = StandardCharsets.UTF_8)
            val uri = Uri.fromFile(file)
            val fontSizeSp = 12f
            val lineSpacing = 1f
            val viewportWidth = 420
            val viewportHeight = 640

            val baseline = TxtVirtualPagerEngine(context)
            baseline.setFontSize(fontSizeSp)
            baseline.setLineSpacing(lineSpacing)
            baseline.setViewportSize(viewportWidth, viewportHeight)
            baseline.openBook(uri)
            baseline.setMode(ReadingMode.PAGED)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("fixture must produce several standard pages", baseline.pageCount.value > 4)

            val targetPage = 2
            baseline.goTo(Locator(LocatorStrategy.Page(targetPage, baseline.pageCount.value)))
            val pageStart = baseline.currentLocator.value.strategy as LocatorStrategy.ByteOffset
            baseline.goTo(Locator(LocatorStrategy.Page(targetPage + 1, baseline.pageCount.value)))
            val nextPageStart = baseline.currentLocator.value.strategy as LocatorStrategy.ByteOffset
            val innerOffset = pageStart.offset + (nextPageStart.offset - pageStart.offset) * 3 / 4
            val innerLocator = Locator(
                strategy = LocatorStrategy.ByteOffset(offset = innerOffset, length = 0),
                totalProgression = innerOffset.toFloat() / source.length,
            )
            assertTrue(
                "fixture locator must be strictly inside standard page $targetPage",
                innerOffset > pageStart.offset && innerOffset < nextPageStart.offset,
            )
            assertEquals(targetPage, baseline.pageIndexForLocator(innerLocator))
            baseline.close()

            val cold = TxtVirtualPagerEngine(context)
            val initialLocatorAware = (cold as Any) as? InitialLocatorAwareReaderEngine
            assertNotNull(
                "TXT engine must expose InitialLocatorAwareReaderEngine for exact cold-open restoration",
                initialLocatorAware,
            )
            if (initialLocatorAware == null) return@runTest
            initialLocatorAware.setInitialLocator(innerLocator)
            cold.setFontSize(fontSizeSp)
            cold.setLineSpacing(lineSpacing)
            cold.setViewportSize(viewportWidth, viewportHeight)
            cold.setMode(ReadingMode.PAGED)
            val requestedPages = mutableListOf<Int>()
            cold.setPageRequestCallback(requestedPages::add)

            cold.openBook(uri)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue("cold PAGED open must request its first visible page", requestedPages.isNotEmpty())
            val requestedPage = requestedPages.first()
            val page = cold.createPageView(requestedPage) as FrameLayout
            val column = page.getChildAt(0) as LinearLayout
            val visibleText = buildString {
                repeat(column.childCount) { childIndex ->
                    append((column.getChildAt(childIndex) as TextView).text)
                }
            }
            val expectedPrefix = source.substring(
                innerOffset.toInt(),
                (innerOffset.toInt() + 48).coerceAtMost(source.length),
            )
            assertTrue(
                "first cold-open page must start exactly at byteOffset=$innerOffset; " +
                    "expectedPrefix=${expectedPrefix.take(32)} actualPrefix=${visibleText.take(32)}",
                visibleText.startsWith(expectedPrefix),
            )
            assertEquals(innerLocator.strategy, cold.currentLocator.value.strategy)
            assertEquals(requestedPage, cold.pageIndexForLocator(cold.currentLocator.value))
        }

    @Test
    fun `stale background pagination cannot restore paged mode after scroll wins`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication()
            val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-stale-page-", suffix = ".txt")
                .toFile()
            val paragraphs = (0 until 40).map { "Queued paragraph $it remains readable." }
            file.writeText(paragraphs.joinToString("\n\n"), StandardCharsets.UTF_8)
            val paginationDispatcher = QueuedCoroutineDispatcher()
            val engine = TxtVirtualPagerEngine(context, paginationDispatcher)
            engine.openBook(Uri.fromFile(file))
            engine.setViewportSize(420, 640)
            val requests = mutableListOf<Int>()
            engine.setPageRequestCallback(requests::add)

            val paged = launch { engine.setMode(ReadingMode.PAGED) }
            testScheduler.runCurrent()
            assertTrue("pagination must be waiting on its background dispatcher", !paged.isCompleted)

            engine.setMode(ReadingMode.SCROLL)
            paginationDispatcher.runAll()
            testScheduler.runCurrent()
            paged.join()

            assertEquals(PagingKind.CONTINUOUS, engine.pagingKind.value)
            assertEquals(paragraphs.size, engine.pageCount.value)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `paged mode stays unpublished until pagination installs its windows`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val source = "Queued pagination must replace the temporary blank page."
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-queued-bind-", suffix = ".txt").toFile()
        file.writeText(source, StandardCharsets.UTF_8)
        val paginationDispatcher = QueuedCoroutineDispatcher()
        val engine = TxtVirtualPagerEngine(context, paginationDispatcher)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(420, 640)

        val paged = launch { engine.setMode(ReadingMode.PAGED) }
        testScheduler.runCurrent()
        assertTrue("pagination must still be queued", !paged.isCompleted)
        assertEquals(
            "a host must never observe PAGED while the windows are still being packed",
            PagingKind.CONTINUOUS,
            engine.pagingKind.value,
        )
        assertEquals("the previous continuous pageCount must stay published", 1, engine.pageCount.value)
        val page = engine.createPageView(0) as FrameLayout
        val column = page.getChildAt(0) as LinearLayout
        assertEquals(0, column.childCount)

        paginationDispatcher.runAll()
        testScheduler.runCurrent()
        paged.join()

        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        @Suppress("UNCHECKED_CAST")
        val installedWindows = engine.privateField("pagedPageWindows") as List<*>
        assertTrue(
            "PAGED must only be published with an installed window set",
            installedWindows.isNotEmpty(),
        )
        assertEquals(installedWindows.size, engine.pageCount.value)
        assertEquals(1, engine.pageCount.value)
        assertEquals(1, column.childCount)
        assertEquals(source, (column.getChildAt(0) as TextView).text.toString())
    }

    @Test
    fun `openBook reuses app private file uri without creating a temp txt copy`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("readflow-txt-") }
            ?.forEach(File::delete)
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(booksDir, "local-open.txt").apply {
            writeText("Readflow local file", charset = StandardCharsets.UTF_8)
        }
        val engine = TxtVirtualPagerEngine(context)

        engine.openBook(Uri.fromFile(file))

        val tempCopies = context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("readflow-txt-") }
            .orEmpty()
        assertTrue(tempCopies.isEmpty())
    }

    @Test
    fun `createView scrolls to locator restored before view exists`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 160).map { index ->
            "Readflow performance corpus line %06d: long hard-wrapped content".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-engine-restore-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n"), charset = StandardCharsets.UTF_8)
        val targetOffset = lines.take(4).sumOf { it.toByteArray(StandardCharsets.UTF_8).size + 1 }.toLong()
        val engine = TxtVirtualPagerEngine(context)

        engine.openBook(Uri.fromFile(file))
        engine.goTo(
            Locator(
                strategy = LocatorStrategy.ByteOffset(offset = targetOffset, length = lines[4].toByteArray().size),
                totalProgression = 4f / lines.size,
            ),
        )
        val view = engine.createView() as RecyclerView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)

        assertEquals(
            4,
            (view.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition(),
        )
    }

    @Test
    fun `setMode paged anchors to visible scroll paragraph instead of stale locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 80).map { index ->
            "Readflow mode switch paragraph %03d keeps the visible anchor stable.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-engine-mode-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        val requestedPages = mutableListOf<Int>()

        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        val layoutManager = view.layoutManager as LinearLayoutManager
        layoutManager.scrollToPositionWithOffset(24, 0)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        assertEquals(24, layoutManager.findFirstVisibleItemPosition())
        val expectedAnchor = view.centeredAdapterPosition(layoutManager)
        assertTrue("expected centered paragraph to move beyond the stale first page", expectedAnchor > 0)
        engine.forceCurrentLocatorForTest(
            Locator(
                strategy = LocatorStrategy.Page(0, lines.size),
                progression = 0f,
                totalProgression = 0f,
            ),
        )
        engine.setPageRequestCallback(requestedPages::add)

        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        // 段落贪心装箱后 页号≠段落号；验证“锚定到可见段落而非陈旧 page-0 locator”：
        // 请求页 == 当前 locator 映射页，且都对应可见的居中段落（非陈旧首页）。
        val expectedPage = engine.pageIndexForLocator(engine.currentLocator.value)
        assertEquals(expectedPage, requestedPages.single())
        assertEquals(expectedAnchor, engine.currentParagraphIndexForTest())
    }

    @Test
    fun `goTo in PAGED requests ViewPager page index not paragraph index`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        // Long paragraphs force multi-paragraph packing so page index != paragraph index.
        val lines = (0 until 80).map { index ->
            "Readflow paged goTo paragraph %03d packs with neighbors on the same page.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-engine-paged-goto-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        val requestedPages = mutableListOf<Int>()

        engine.openBook(Uri.fromFile(file))
        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        assertTrue(
            "fixture must pack multiple paragraphs per page so page≠paragraph",
            engine.pageCount.value < lines.size,
        )
        engine.setPageRequestCallback(requestedPages::add)

        val targetParagraph = 24
        val targetLocator = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = targetParagraph, charOffset = 0),
            totalProgression = targetParagraph.toFloat() / lines.size,
        )
        val expectedPage = engine.pageIndexForLocator(targetLocator)
        assertTrue(
            "expected packing so paragraph $targetParagraph maps to page ≠ paragraph index",
            expectedPage != targetParagraph,
        )

        engine.goTo(targetLocator)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "pageRequestCallback must receive ViewPager page index (pageIndexForLocator), not paragraph index",
            expectedPage,
            requestedPages.single(),
        )
        assertEquals(targetParagraph, engine.currentParagraphIndexForTest())
        assertEquals(expectedPage, engine.pageIndexForLocator(engine.currentLocator.value))

        // Host ViewPager settle emits LocatorStrategy.Page with page index, not paragraph index.
        requestedPages.clear()
        val settlePage = expectedPage
        engine.goTo(
            Locator(
                strategy = LocatorStrategy.Page(index = settlePage, total = engine.pageCount.value),
                progression = settlePage.toFloat() / engine.pageCount.value.coerceAtLeast(1),
                totalProgression = settlePage.toFloat() / engine.pageCount.value.coerceAtLeast(1),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(settlePage, requestedPages.single())
        assertEquals(settlePage, engine.pageIndexForLocator(engine.currentLocator.value))
    }

    @Test
    fun `goTo ignores stale pre-scroll position reports while target scroll settles`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 40).map { index ->
            "Readflow explicit navigation paragraph %03d keeps bookmark anchors stable.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-engine-goto-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)

        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        val layoutManager = view.layoutManager as LinearLayoutManager
        assertEquals(0, layoutManager.findFirstVisibleItemPosition())

        engine.goTo(Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 18, charOffset = 0)))
        assertEquals(18, engine.pageIndexForLocator(engine.currentLocator.value))

        layoutManager.scrollToPositionWithOffset(0, 0)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        engine.reportProgressionForTest(view)

        assertEquals(18, engine.pageIndexForLocator(engine.currentLocator.value))
    }

    @Test
    fun `dedicated surface report generation keeps the live surface and blocks stale reports`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 80).map { index ->
            "Stale report paragraph %03d keeps the remounted surface locator stable.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-stale-report-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))

        // Surface A establishes a locator deep in the document.
        val first = engine.createView() as RecyclerView
        val firstGeneration = engine.currentScrollReportGenerationForTest()
        first.measureLayout(1080, 2400)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            first.measureLayout(1080, 2400)
        }
        (first.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(10, 0)
        first.measureLayout(1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        engine.reportProgressionForTest(first)
        val firstProgression = engine.currentLocator.value.totalProgression ?: 0f

        // Remount surface B and navigate it to a different paragraph.
        val second = engine.createView() as RecyclerView
        val secondGeneration = engine.currentScrollReportGenerationForTest()
        second.measureLayout(1080, 2400)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            second.measureLayout(1080, 2400)
        }
        (second.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(20, 0)
        second.measureLayout(1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        engine.goTo(Locator(LocatorStrategy.Section(0, 30, 0), totalProgression = 30f / lines.size))
        second.measureLayout(1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        second.measureLayout(1080, 2400)
        val afterGoTo = engine.currentLocator.value.totalProgression ?: 0f
        assertTrue("goTo must move the locator past the stale surface", afterGoTo > firstProgression)

        // The CURRENT surface's normal scrolling must still publish after the navigation bump:
        // the report token is dedicated, so goTo/typography bumps must not silence the live view.
        second.scrollBy(0, 400)
        second.measureLayout(1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        val afterScroll = engine.currentLocator.value.totalProgression ?: 0f
        assertTrue(
            "current-surface scrolling must still publish after a navigation bump; afterScroll=$afterScroll",
            afterScroll > afterGoTo,
        )

        // A report from the old surface identity must not publish into B.
        engine.reportProgressionForTest(first)
        assertEquals(
            "a stale surface report must not republish into the remounted surface",
            afterScroll,
            engine.currentLocator.value.totalProgression ?: 0f,
            0f,
        )

        // The same live surface with an older captured generation must also be ignored.
        engine.reportProgressionForTest(second, firstGeneration)
        assertEquals(afterScroll, engine.currentLocator.value.totalProgression ?: 0f, 0f)

        // Mode transitions invalidate the old surface token before the host installs the next
        // view. A queued callback from the continuous surface must not overwrite either mode's
        // preserved anchor during that handoff.
        engine.setMode(ReadingMode.PAGED)
        val pagedModeLocator = engine.currentLocator.value
        engine.reportProgressionForTest(second, secondGeneration)
        assertEquals(pagedModeLocator, engine.currentLocator.value)
        engine.setMode(ReadingMode.SCROLL)
        val scrollModeLocator = engine.currentLocator.value
        engine.reportProgressionForTest(second, secondGeneration)
        assertEquals(scrollModeLocator, engine.currentLocator.value)

        // Reopening invalidates the old surface token even before the host remounts the new book.
        val replacement = kotlin.io.path.createTempFile(prefix = "readflow-txt-stale-open-", suffix = ".txt").toFile()
        replacement.writeText(
            "A replacement book with a fresh head.\n\nA second paragraph makes stale progress observable.",
            charset = StandardCharsets.UTF_8,
        )
        engine.openBook(Uri.fromFile(replacement))
        val afterOpen = engine.currentLocator.value.totalProgression ?: 0f
        engine.reportProgressionForTest(second, secondGeneration)
        assertEquals(afterOpen, engine.currentLocator.value.totalProgression ?: 0f, 0f)
    }

    @Test
    fun `PageText is not treated as paragraph index falls back to total progression`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 20).map { index ->
            "Readflow PageText isolation paragraph %03d.".format(index)
        }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-pagetext-", suffix = ".txt").toFile()
        file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))

        // Foreign PDF PageText.index=15 must not become TXT paragraph 15.
        val foreign = Locator(
            strategy = LocatorStrategy.PageText(index = 15, total = 20, charOffset = 999),
            totalProgression = 0.1f,
        )
        engine.goTo(foreign)
        // totalProgression 0.1 of 20 paragraphs → index 2; not 15.
        assertEquals(2, engine.pageIndexForLocator(engine.currentLocator.value))
        assertTrue(engine.currentLocator.value.strategy !is LocatorStrategy.PageText)

        // Without progression, PageText must not jump to index 15 either.
        engine.goTo(Locator(LocatorStrategy.PageText(index = 15, total = 20, charOffset = 999)))
        assertEquals(0, engine.pageIndexForLocator(engine.currentLocator.value))
    }

    private fun TxtVirtualPagerEngine.privateField(name: String): Any? =
        TxtVirtualPagerEngine::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)

    private fun TxtVirtualPagerEngine.forceCurrentLocatorForTest(locator: Locator) {
        @Suppress("UNCHECKED_CAST")
        val currentLocator = TxtVirtualPagerEngine::class.java
            .getDeclaredField("_currentLocator")
            .apply { isAccessible = true }
            .get(this) as MutableStateFlow<Locator>
        currentLocator.value = locator
    }

    private fun TxtVirtualPagerEngine.currentParagraphIndexForTest(): Int =
        TxtVirtualPagerEngine::class.java
            .getDeclaredMethod("currentParagraphIndex")
            .apply { isAccessible = true }
            .invoke(this) as Int

    private fun TxtVirtualPagerEngine.reportProgressionForTest(
        view: RecyclerView,
        surfaceGeneration: Long? = null,
    ) {
        if (surfaceGeneration == null) {
            TxtVirtualPagerEngine::class.java
                .getDeclaredMethod("reportProgression", RecyclerView::class.java)
                .apply { isAccessible = true }
                .invoke(this, view)
        } else {
            TxtVirtualPagerEngine::class.java
                .getDeclaredMethod(
                    "reportProgression",
                    RecyclerView::class.java,
                    Long::class.javaPrimitiveType!!,
                )
                .apply { isAccessible = true }
                .invoke(this, view, surfaceGeneration)
        }
    }

    private fun TxtVirtualPagerEngine.currentScrollReportGenerationForTest(): Long =
        (TxtVirtualPagerEngine::class.java
            .getDeclaredField("scrollReportGeneration")
            .apply { isAccessible = true }
            .get(this) as AtomicLong).get()

    private fun RecyclerView.centeredAdapterPosition(layoutManager: LinearLayoutManager): Int {
        val viewportCenter = paddingTop + (height - paddingTop - paddingBottom) / 2
        return (0 until childCount).mapNotNull { index ->
            val child = getChildAt(index) ?: return@mapNotNull null
            val position = layoutManager.getPosition(child).takeIf { it != RecyclerView.NO_POSITION }
                ?: return@mapNotNull null
            val childCenter = (child.top + child.bottom) / 2
            position to abs(childCenter - viewportCenter)
        }.minByOrNull { it.second }?.first ?: error("no visible RecyclerView children")
    }

    private fun View.measureLayout(width: Int, height: Int) {
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }

    @Test
    fun `paragraph adapter keeps bound text selectable`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(1080, 2400)
        }
        val adapter = TxtParagraphAdapter(
            paragraphCount = 1,
            paragraphProvider = { "Readflow selectable text" },
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.75f,
        )

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        val textView = holder.textView as SelectionAwareTextView
        assertTrue(textView.isTextSelectable)
        assertTrue(textView.isClickable)
        assertTrue(textView.isFocusable)
        assertTrue(textView.isFocusableInTouchMode)
        assertTrue(textView.isLongClickable)
    }

    @Test
    fun `fallback text selection is not cleared by a collapsed native selection callback`() {
        val context = RuntimeEnvironment.getApplication()
        val textView = SelectionAwareTextView(context).apply {
            text = "Readflow selectable text"
        }
        val reportedSelections = mutableListOf<Pair<Int, Int>>()
        textView.onSelectionRangeChanged = { start, end ->
            reportedSelections += start to end
        }

        SelectionAwareTextView::class.java
            .getDeclaredMethod("reportFallbackSelection", Integer.TYPE)
            .apply { isAccessible = true }
            .invoke(textView, 0)

        val fallbackSelection = reportedSelections.last()
        assertTrue(fallbackSelection.first < fallbackSelection.second)

        SelectionAwareTextView::class.java
            .getDeclaredMethod("onSelectionChanged", Integer.TYPE, Integer.TYPE)
            .apply { isAccessible = true }
            .invoke(textView, fallbackSelection.first, fallbackSelection.first)

        assertEquals(fallbackSelection, reportedSelections.last())
    }

    @Test
    fun `clearTextSelection clears native selection from visible recycler paragraphs`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-engine-selection-", suffix = ".txt").toFile()
        file.writeText(
            sequenceOf(
                "Readflow performance corpus line 000000: selectable paragraph",
                "Readflow performance corpus line 000001: another paragraph",
                "Readflow performance corpus line 000002: third paragraph",
            ).joinToString("\n"),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)

        engine.openBook(Uri.fromFile(file))
        val view = engine.createView() as RecyclerView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val holder = view.findViewHolderForAdapterPosition(0) as TxtParagraphAdapter.ParagraphHolder
        val textView = holder.textView
        val selectedText = "Readflow"
        Selection.setSelection(textView.text as Spannable, 0, selectedText.length)
        textView.onSelectionRangeChanged?.invoke(0, selectedText.length)

        assertEquals("Readflow", textView.text.substring(textView.selectionStart, textView.selectionEnd))
        assertEquals("Readflow", engine.currentTextSelection.value?.selectedText)

        engine.clearTextSelection()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(engine.currentTextSelection.value)
        assertTrue(
            "expected visible paragraph native selection to collapse after clearTextSelection; " +
                "start=${textView.selectionStart} end=${textView.selectionEnd}",
            textView.selectionStart == textView.selectionEnd,
        )
    }

    @Test
    fun `headingless open publishes empty toc and document chapterInfo`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-headingless-", suffix = ".txt").toFile()
        file.writeText(
            """
            Plain paragraph one with no chapter markers.
            Plain paragraph two continues the body.
            Plain paragraph three is still body text.
            """.trimIndent(),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)

        engine.openBook(Uri.fromFile(file))

        assertTrue(engine.tableOfContents.value.isEmpty())
        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.DOCUMENT, info.kind)
        assertEquals(0, info.currentIndex)
        assertEquals(0, info.totalChapters)
        assertEquals("正文", info.currentTitle)
        assertEquals(0f, info.progressInChapter)
    }

    @Test
    fun `real headings publish chapter kind with truthful count and title`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-headings-", suffix = ".txt").toFile()
        file.writeText(
            """
            第1章 开篇

            Body of chapter one.

            第2章 中途

            Body of chapter two.

            第3章 收束

            Body of chapter three.
            """.trimIndent(),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)

        engine.openBook(Uri.fromFile(file))

        val toc = engine.tableOfContents.value
        assertEquals(3, toc.size)
        assertEquals("第1章 开篇", toc[0].title)
        assertEquals("第2章 中途", toc[1].title)
        assertEquals("第3章 收束", toc[2].title)

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(0, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第1章 开篇", info.currentTitle)
    }

    @Test
    fun `goTo updates chapterInfo for real heading toc`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-goto-chapter-", suffix = ".txt").toFile()
        file.writeText(
            """
            第1章 开篇

            Body of chapter one with enough lines to keep progression distinct.

            第2章 中途

            Body of chapter two sits after the second heading.

            第3章 收束

            Body of chapter three is last.
            """.trimIndent(),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val toc = engine.tableOfContents.value
        assertEquals(3, toc.size)

        engine.goTo(toc[1].locator)

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(1, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第2章 中途", info.currentTitle)
    }

    @Test
    fun `adjacent chapter navigation uses real headings and respects boundaries`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-adjacent-", suffix = ".txt").toFile()
        file.writeText(
            """
            第1章 开篇

            Body one.

            第2章 中途

            Body two.

            第3章 收束

            Body three.
            """.trimIndent(),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))

        // At first chapter: previous is no-op.
        engine.goToAdjacentChapter(-1)
        assertEquals(0, engine.chapterInfo.value.currentIndex)
        assertEquals("第1章 开篇", engine.chapterInfo.value.currentTitle)

        engine.goToAdjacentChapter(+1)
        assertEquals(1, engine.chapterInfo.value.currentIndex)
        assertEquals("第2章 中途", engine.chapterInfo.value.currentTitle)

        engine.goToAdjacentChapter(+1)
        assertEquals(2, engine.chapterInfo.value.currentIndex)
        assertEquals("第3章 收束", engine.chapterInfo.value.currentTitle)

        // At last chapter: next is no-op.
        engine.goToAdjacentChapter(+1)
        assertEquals(2, engine.chapterInfo.value.currentIndex)
        assertEquals("第3章 收束", engine.chapterInfo.value.currentTitle)
    }

    @Test
    fun `headingless adjacent chapter navigation is a no-op document state`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-adj-doc-", suffix = ".txt").toFile()
        file.writeText("Only body text without chapter headings.\nSecond paragraph.", StandardCharsets.UTF_8)
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))

        engine.goToAdjacentChapter(+1)
        engine.goToAdjacentChapter(-1)

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.DOCUMENT, info.kind)
        assertEquals(0, info.totalChapters)
        assertEquals("正文", info.currentTitle)
        assertTrue(engine.tableOfContents.value.isEmpty())
    }

    @Test
    fun `setMode keeps chapterInfo in sync with updated locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-mode-chapter-", suffix = ".txt").toFile()
        file.writeText(
            """
            第1章 开篇

            Body of chapter one.

            第2章 中途

            Body of chapter two after the second heading marker.

            第3章 收束

            Body of chapter three.
            """.trimIndent(),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        val chapter2 = engine.tableOfContents.value[1]
        engine.goTo(chapter2.locator)
        assertEquals("第2章 中途", engine.chapterInfo.value.currentTitle)

        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(1, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第2章 中途", info.currentTitle)
        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
    }

    @Test
    fun `encoding reopen refreshes chapterInfo without inventing fake toc`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-reopen-", suffix = ".txt").toFile()
        file.writeText(
            """
            第1章 开篇

            Body one.

            第2章 中途

            Body two.
            """.trimIndent(),
            charset = StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.goTo(engine.tableOfContents.value[1].locator)
        assertEquals(1, engine.chapterInfo.value.currentIndex)

        engine.setTxtEncodingOverride("UTF-8")

        val toc = engine.tableOfContents.value
        assertEquals(2, toc.size)
        assertTrue(toc.none { it.title == "正文" && toc.size == 1 })
        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(2, info.totalChapters)
        // Restored progression should land back on chapter 2 when offsets remain stable.
        assertEquals(1, info.currentIndex)
        assertEquals("第2章 中途", info.currentTitle)
    }

    @Test
    fun `openBook clears search highlight state when engine is reused for another book`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val first = kotlin.io.path.createTempFile(prefix = "readflow-txt-search-a-", suffix = ".txt").toFile()
        first.writeText(
            "Needle appears in the first plain text book body.\nSecond paragraph keeps the document long enough.",
            StandardCharsets.UTF_8,
        )
        val second = kotlin.io.path.createTempFile(prefix = "readflow-txt-search-b-", suffix = ".txt").toFile()
        second.writeText(
            "Completely different second document without the prior match token.",
            StandardCharsets.UTF_8,
        )
        val engine = TxtVirtualPagerEngine(context)
        engine.openBook(Uri.fromFile(first))
        val hit = engine.search("Needle").first()
        engine.setSearchHighlight(hit)
        assertNotNull(engine.privateField("searchHighlightHit"))

        engine.openBook(Uri.fromFile(second))
        assertNull(
            "openBook must clear transient searchHighlightHit when reusing the engine",
            engine.privateField("searchHighlightHit"),
        )
        // Fresh search on book B must not inherit book A's paint.
        engine.setSearchHighlight(null)
        assertNull(engine.privateField("searchHighlightHit"))
    }

    @Test
    fun `setViewportSize stores host size and repacks PAGED using it not displayMetrics`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication()
            // Short single-line paragraphs so linesPerPage (viewport height) drives packing.
            val lines = (0 until 36).map { index ->
                "Viewport pack paragraph %02d.".format(index)
            }
            val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-viewport-", suffix = ".txt").toFile()
            file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
            val engine = TxtVirtualPagerEngine(context)
            engine.openBook(Uri.fromFile(file))
            engine.setMode(ReadingMode.PAGED)
            shadowOf(Looper.getMainLooper()).idle()

            // Large host viewport first — stores positive size and packs loosely.
            engine.setViewportSize(1080, 2400)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1080, engine.privateField("viewportWidthPx") as Int)
            assertEquals(2400, engine.privateField("viewportHeightPx") as Int)
            val countLarge = engine.pageCount.value
            assertTrue(
                "large viewport must pack multiple paragraphs per page; count=$countLarge paras=${lines.size}",
                countLarge in 1 until lines.size,
            )
            @Suppress("UNCHECKED_CAST")
            val startsLarge = engine.privateField("pagedParagraphStarts") as List<Int>

            val targetParagraph = 12
            // Prefer Section for goTo so paragraph anchor is explicit; engine normalizes to ByteOffset.
            val sectionLocator = Locator(
                strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = targetParagraph, charOffset = 0),
                totalProgression = targetParagraph.toFloat() / lines.size,
            )
            engine.goTo(sectionLocator)
            shadowOf(Looper.getMainLooper()).idle()
            val paragraphBefore = engine.currentParagraphIndexForTest()
            assertEquals(targetParagraph, paragraphBefore)
            val strategyBefore = engine.currentLocator.value.strategy
            assertTrue(
                "canonical locator must remain ByteOffset/paragraph-based, not bare Page",
                strategyBefore is LocatorStrategy.ByteOffset || strategyBefore is LocatorStrategy.Section,
            )

            val requested = mutableListOf<Int>()
            engine.setPageRequestCallback(requested::add)

            // Invalid sizes must be ignored (stored host size unchanged).
            engine.setViewportSize(0, 800)
            engine.setViewportSize(400, -1)
            assertEquals(1080, engine.privateField("viewportWidthPx") as Int)
            assertEquals(2400, engine.privateField("viewportHeightPx") as Int)
            assertTrue(requested.isEmpty())

            // No-op same size must not re-request pages.
            engine.setViewportSize(1080, 2400)
            assertTrue(requested.isEmpty())

            // Narrower/shorter host viewport must repack (typically more pages than displayMetrics alone
            // would imply for a large phone — use explicit host size vs previous host size).
            engine.setViewportSize(480, 700)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(480, engine.privateField("viewportWidthPx") as Int)
            assertEquals(700, engine.privateField("viewportHeightPx") as Int)
            assertTrue(
                "viewport shrink must change packed pageCount; large=$countLarge small=${engine.pageCount.value}",
                engine.pageCount.value != countLarge,
            )
            @Suppress("UNCHECKED_CAST")
            val startsSmall = engine.privateField("pagedParagraphStarts") as List<Int>
            assertTrue(
                "host viewport repack must change page starts vs large host packing",
                startsSmall != startsLarge,
            )
            assertTrue(requested.isNotEmpty())
            assertEquals(
                "pageRequestCallback must receive packed page for preserved paragraph anchor",
                engine.pageIndexForLocator(engine.currentLocator.value),
                requested.last(),
            )
            assertEquals(
                "paragraph/source anchor must survive viewport resize",
                targetParagraph,
                engine.currentParagraphIndexForTest(),
            )
            val strategyAfter = engine.currentLocator.value.strategy
            assertTrue(
                "repack must not publish bare Page as canonical TXT locator",
                strategyAfter !is LocatorStrategy.Page,
            )
            // Prove packing used host height (not fixed displayMetrics): without override, setViewportSize
            // is a no-op and pageCount/starts stay at the large-pack values.
            assertTrue(engine.pageCount.value >= 1)
            assertEquals(engine.pageCount.value, startsSmall.size)
        }

    @Test
    fun `cancelled viewport rebuild leaves the size retryable`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val lines = (0 until 48).map { index -> "Cancelled viewport paragraph %02d.".format(index) }
        val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-cancel-viewport-", suffix = ".txt")
            .toFile()
        file.writeText(lines.joinToString("\n\n"), StandardCharsets.UTF_8)
        val paginationDispatcher = QueuedCoroutineDispatcher()
        val engine = TxtVirtualPagerEngine(context, paginationDispatcher)
        engine.openBook(Uri.fromFile(file))
        engine.setViewportSize(1080, 2400)

        val enterPaged = launch { engine.setMode(ReadingMode.PAGED) }
        testScheduler.runCurrent()
        paginationDispatcher.runAll()
        testScheduler.runCurrent()
        enterPaged.join()
        val largePageCount = engine.pageCount.value

        val cancelledResize = launch { engine.setViewportSize(480, 700) }
        testScheduler.runCurrent()
        assertTrue("resize pagination must still be queued", !cancelledResize.isCompleted)
        cancelledResize.cancel()
        paginationDispatcher.runAll()
        testScheduler.runCurrent()
        cancelledResize.join()

        assertEquals(1080, engine.privateField("viewportWidthPx") as Int)
        assertEquals(2400, engine.privateField("viewportHeightPx") as Int)
        assertEquals(largePageCount, engine.pageCount.value)

        val retry = launch { engine.setViewportSize(480, 700) }
        testScheduler.runCurrent()
        assertTrue("same-size retry must schedule fresh pagination", !retry.isCompleted)
        paginationDispatcher.runAll()
        testScheduler.runCurrent()
        retry.join()

        assertEquals(480, engine.privateField("viewportWidthPx") as Int)
        assertEquals(700, engine.privateField("viewportHeightPx") as Int)
        assertTrue("retry must install the smaller-viewport packing", engine.pageCount.value != largePageCount)
    }

    @Test
    fun `setViewportSize rebinds active PAGED page content when pageCount stays equal`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication()
            // 9 one-line paragraphs. Pack cost with inter-paragraph gap: first=1 line, later=2 each
            // → n paras need 2n-1 lines. Capacity 5 → 3/page starts [0,3,6]; capacity 7 → 4/page [0,4,8].
            // Both pageCount=3; equal-count rebind must refresh tags without pageCount change.
            val lines = (0 until 9).map { index ->
                "EqualCount pack paragraph %02d token.".format(index)
            }
            val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-rebind-", suffix = ".txt").toFile()
            file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
            val engine = TxtVirtualPagerEngine(context)
            engine.openBook(Uri.fromFile(file))

            // Fixed typography; choose two real measured heights that keep pageCount equal while
            // changing page-0 grouping. This remains valid when font metrics or row padding change.
            val fontSizeSp = 16f
            val lineSpacing = 1.5f
            engine.setFontSize(fontSizeSp)
            engine.setLineSpacing(lineSpacing)
            engine.setMode(ReadingMode.PAGED)
            shadowOf(Looper.getMainLooper()).idle()

            suspend fun packAt(w: Int, h: Int): Pair<Int, List<Int>> {
                engine.setViewportSize(w, h)
                shadowOf(Looper.getMainLooper()).idle()
                @Suppress("UNCHECKED_CAST")
                val starts = engine.privateField("pagedParagraphStarts") as List<Int>
                return engine.pageCount.value to starts
            }

            val candidates = (140..720 step 4).map { height ->
                val (count, starts) = packAt(720, height)
                Triple(height, count, starts)
            }
            val selected = candidates.firstNotNullOfOrNull { left ->
                candidates.firstOrNull { right ->
                    right.first > left.first &&
                        left.second > 1 && left.second == right.second && left.third != right.third
                }?.let { right -> left to right }
            }
            assertNotNull("fixture must find equal-count viewports with different grouping", selected)
            val (left, right) = requireNotNull(selected)
            val heightA = left.first
            val countA = left.second
            val startsA = left.third
            val heightB = right.first
            val countB = right.second
            val startsB = right.third
            val expectedPageCount = countA
            val expectedTagsA = (startsA.first() until startsA.getOrElse(1) { lines.size }).toList()
            val expectedTagsB = (startsB.first() until startsB.getOrElse(1) { lines.size }).toList()
            assertTrue(expectedTagsA != expectedTagsB)

            // Restore A, bind page 0, then switch to B without destroying the page view.
            packAt(720, heightA)
            val pageView = engine.createPageView(0) as FrameLayout
            val columnBefore = pageView.getChildAt(0) as android.widget.LinearLayout
            assertEquals(1, columnBefore.childCount)
            val textBefore = (columnBefore.getChildAt(0) as TextView).text.toString()
            val descBefore = pageView.contentDescription?.toString()
            assertEquals(expectedTagsA.map(lines::get).joinToString("\n"), textBefore)
            assertEquals(
                "第 1 页，共 $expectedPageCount 页",
                descBefore,
            )

            // Active page stays attached — rebind must not depend on host destruction.
            engine.setViewportSize(720, heightB)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "test requires equal pageCount viewport path; A=$countA B=${engine.pageCount.value}",
                expectedPageCount,
                engine.pageCount.value,
            )
            @Suppress("UNCHECKED_CAST")
            val startsAfter = engine.privateField("pagedParagraphStarts") as List<Int>
            assertEquals(startsB, startsAfter)

            val columnAfter = pageView.getChildAt(0) as android.widget.LinearLayout
            assertEquals(1, columnAfter.childCount)
            val textAfter = (columnAfter.getChildAt(0) as TextView).text.toString()
            assertEquals(
                "active page paragraph grouping must match rebuilt packing even when pageCount unchanged",
                expectedTagsB.map(lines::get).joinToString("\n"),
                textAfter,
            )
            assertTrue(
                "grouping must differ from pre-resize page-0 tags when packing changes",
                textAfter != textBefore,
            )
            assertEquals(
                "contentDescription must refresh for current packing",
                "第 1 页，共 $expectedPageCount 页",
                pageView.contentDescription?.toString(),
            )
        }

    @Test
    fun `setTxtEncodingOverride while PAGED repacks and restores paragraph anchor`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication()
            // 22 paragraphs / target 13: Float32 progression 13f/22f * 22f → 12.999999f → toInt() 12.
            // Progression-first restore must RED at paragraph 12; ByteOffset-first restores 13.
            val paragraphCount = 22
            val targetParagraph = 13
            val lines = (0 until paragraphCount).map { index ->
                "Encoding reopen paragraph %02d keeps logical progress.".format(index)
            }
            val file = kotlin.io.path.createTempFile(prefix = "readflow-txt-enc-paged-", suffix = ".txt").toFile()
            file.writeText(lines.joinToString("\n\n"), charset = StandardCharsets.UTF_8)
            val engine = TxtVirtualPagerEngine(context)
            engine.setViewportSize(720, 1280)
            engine.openBook(Uri.fromFile(file))
            engine.setMode(ReadingMode.PAGED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PagingKind.PAGED, engine.pagingKind.value)
            val packedCountBefore = engine.pageCount.value
            assertTrue(
                "fixture must pack below paragraphCount; packed=$packedCountBefore paras=${lines.size}",
                packedCountBefore in 1 until lines.size,
            )

            engine.goTo(
                Locator(
                    strategy = LocatorStrategy.Section(0, targetParagraph, 0),
                    totalProgression = targetParagraph.toFloat() / paragraphCount,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(targetParagraph, engine.currentParagraphIndexForTest())
            // Capture canonical anchor before reopen (ByteOffset is strongest across encoding reindex).
            val strategyBefore = engine.currentLocator.value.strategy
            assertTrue(
                "canonical locator before encoding reopen must be ByteOffset",
                strategyBefore is LocatorStrategy.ByteOffset,
            )
            val savedByteOffset = (strategyBefore as LocatorStrategy.ByteOffset).offset
            val savedParagraphCount = paragraphCount
            val savedParagraphIndex = engine.currentParagraphIndexForTest()
            val savedProgression = engine.currentLocator.value.totalProgression
            assertEquals(targetParagraph, savedParagraphIndex)
            // Document the Float32 trap that progression-first restore hits.
            val progressionTrapIndex = ((savedProgression ?: 0f) * paragraphCount).toInt()
            assertEquals(
                "fixture requires Float32 progression trap: 13/22 → floor 12",
                12,
                progressionTrapIndex,
            )
            val pageBefore = engine.pageIndexForLocator(engine.currentLocator.value)

            val requested = mutableListOf<Int>()
            engine.setPageRequestCallback(requested::add)

            engine.setTxtEncodingOverride("UTF-8")
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PagingKind.PAGED, engine.pagingKind.value)
            assertTrue(
                "encoding reopen must report packed pageCount, not paragraphCount fallback",
                engine.pageCount.value < lines.size,
            )
            assertTrue(
                "encoding reopen must rebuild pagedParagraphStarts",
                (engine.privateField("pagedParagraphStarts") as List<*>).isNotEmpty(),
            )
            assertEquals(
                "packed pageCount after encoding reopen",
                (engine.privateField("pagedParagraphStarts") as List<*>).size,
                engine.pageCount.value,
            )
            // Same UTF-8 file → packing geometry should match pre-reopen host viewport pack.
            assertEquals(packedCountBefore, engine.pageCount.value)
            assertEquals(
                "encoding reopen must restore exact paragraph 13 via ByteOffset, not progression floor 12 " +
                    "(savedOffset=$savedByteOffset savedCount=$savedParagraphCount " +
                    "savedProgression=$savedProgression trapIndex=$progressionTrapIndex)",
                targetParagraph,
                engine.currentParagraphIndexForTest(),
            )
            val rebuiltPage = engine.pageIndexForLocator(engine.currentLocator.value)
            assertEquals(
                "restored paragraph must map to its containing packed page after reindex",
                rebuiltPage,
                engine.pageIndexForLocator(engine.currentLocator.value),
            )
            assertTrue(requested.isNotEmpty())
            assertEquals(
                "page request must use rebuilt packing for restored paragraph 13",
                rebuiltPage,
                requested.last(),
            )
            // Same source bytes + same viewport → containing page should match pre-reopen.
            assertEquals(pageBefore, rebuiltPage)
            assertTrue(
                engine.currentLocator.value.strategy !is LocatorStrategy.Page,
            )
            assertTrue(
                "post-reopen canonical locator must remain ByteOffset/paragraph-based",
                engine.currentLocator.value.strategy is LocatorStrategy.ByteOffset ||
                    engine.currentLocator.value.strategy is LocatorStrategy.Section,
            )
        }
}

private class QueuedCoroutineDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
