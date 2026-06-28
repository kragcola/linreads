package dev.readflow.render.txt

import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelectionAwareTextView
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
}
