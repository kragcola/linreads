package dev.readflow.render.txt

import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReaderTypographyRange
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelectionAwareTextView
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TxtVirtualPagerEngineTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
        val columnParams = column.layoutParams as FrameLayout.LayoutParams
        assertEquals(
            "the paragraph column must fill the fixed ViewPager page so Android measures its rows",
            FrameLayout.LayoutParams.MATCH_PARENT,
            columnParams.height,
        )
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

    private fun TxtVirtualPagerEngine.reportProgressionForTest(view: RecyclerView) {
        TxtVirtualPagerEngine::class.java
            .getDeclaredMethod("reportProgression", RecyclerView::class.java)
            .apply { isAccessible = true }
            .invoke(this, view)
    }

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

            // Fixed typography; viewport heights derived from density so floor(linesPerPage) is exact.
            val fontSizeSp = 16f
            val lineSpacing = 1.5f
            engine.setFontSize(fontSizeSp)
            engine.setLineSpacing(lineSpacing)
            engine.setMode(ReadingMode.PAGED)
            shadowOf(Looper.getMainLooper()).idle()

            val metrics = context.resources.displayMetrics
            val density = metrics.density
            // Match production: textSizePx = applyDimension(SP), lineHeight = textSize * spacing,
            // contentHeight = height - 48*density, linesPerPage = floor(contentHeight / lineHeight).
            val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, metrics)
            val lineHeightPx = (textSizePx * lineSpacing).coerceAtLeast(1f)
            fun heightForLinesPerPage(targetLines: Int): Int {
                // Half-line safety margin so integer floor yields exactly targetLines.
                val contentHeightPx = (targetLines + 0.5f) * lineHeightPx
                return (contentHeightPx + 48f * density).toInt().coerceAtLeast(1)
            }
            val heightA = heightForLinesPerPage(5)
            val heightB = heightForLinesPerPage(7)
            val expectedStartsA = listOf(0, 3, 6)
            val expectedStartsB = listOf(0, 4, 8)
            val expectedPageCount = 3

            fun packAt(w: Int, h: Int): Pair<Int, List<Int>> {
                engine.setViewportSize(w, h)
                shadowOf(Looper.getMainLooper()).idle()
                @Suppress("UNCHECKED_CAST")
                val starts = engine.privateField("pagedParagraphStarts") as List<Int>
                return engine.pageCount.value to starts
            }

            val (countA, startsA) = packAt(720, heightA)
            assertEquals(
                "capacity-5 height must pack pageCount=3 (density=$density " +
                    "hA=$heightA lineH=$lineHeightPx)",
                expectedPageCount,
                countA,
            )
            assertEquals(
                "capacity-5 starts must be [0,3,6]",
                expectedStartsA,
                startsA,
            )

            val (countB, startsB) = packAt(720, heightB)
            assertEquals(
                "capacity-7 height must pack pageCount=3 (density=$density " +
                    "hB=$heightB lineH=$lineHeightPx)",
                expectedPageCount,
                countB,
            )
            assertEquals(
                "capacity-7 starts must be [0,4,8]",
                expectedStartsB,
                startsB,
            )
            assertTrue(
                "equal pageCount with different packing is required for rebind path",
                countA == countB && startsA != startsB,
            )

            // Restore A, bind page 0, then switch to B without destroying the page view.
            packAt(720, heightA)
            val pageView = engine.createPageView(0) as FrameLayout
            val columnBefore = pageView.getChildAt(0) as android.widget.LinearLayout
            val tagsBefore = (0 until columnBefore.childCount).map { i ->
                columnBefore.getChildAt(i).tag as Int
            }
            val descBefore = pageView.contentDescription?.toString()
            assertEquals(listOf(0, 1, 2), tagsBefore)
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
            assertEquals(expectedStartsB, startsAfter)

            val columnAfter = pageView.getChildAt(0) as android.widget.LinearLayout
            val tagsAfter = (0 until columnAfter.childCount).map { i ->
                columnAfter.getChildAt(i).tag as Int
            }
            assertEquals(
                "active page paragraph grouping must match rebuilt packing even when pageCount unchanged",
                listOf(0, 1, 2, 3),
                tagsAfter,
            )
            assertTrue(
                "grouping must differ from pre-resize page-0 tags when packing changes",
                tagsAfter != tagsBefore,
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
