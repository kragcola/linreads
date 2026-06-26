package dev.readflow.render.md

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextAnnotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownEngineTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setTextAnnotations keeps markdown scroll position and locator near current section`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-scroll-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val targetLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        val renderedOffset = textView.text.toString().indexOf(targetText)
        val targetLine = textView.layout.getLineForOffset(renderedOffset)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) -
                scrollView.height / 2
            ).coerceAtLeast(0)

        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)

        val beforeScrollY = scrollView.scrollY
        val beforeLocator = engine.currentLocator.value

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "annotation-1",
                    start = targetLocator,
                    end = targetLocator.copy(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = targetLineIndex,
                            charOffset = targetSourceOffset + targetText.length,
                        ),
                        totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
                    ),
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        idleMainLooper()
        relayout(parent)

        val afterScrollY = scrollView.scrollY
        val afterLocator = engine.currentLocator.value

        assertTrue("expected to scroll away from top before applying annotations", beforeScrollY > 0)
        assertTrue(
            "expected locator to be near tail before applying annotations; before=$beforeLocator",
            (beforeLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected annotation refresh to preserve near-tail scroll; before=$beforeScrollY after=$afterScrollY",
            afterScrollY >= beforeScrollY / 2,
        )
        assertTrue(
            "expected locator to stay near current section; before=$beforeLocator after=$afterLocator",
            (afterLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected rendered text to keep target visible after annotation refresh",
            textView.text.toString().contains(targetText),
        )
    }

    @Test
    fun `setTextAnnotations keeps markdown scroll position when native selection is active`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-selection-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val renderedStart = textView.text.toString().indexOf(targetText)
        val renderedEnd = renderedStart + targetText.length
        val targetLine = textView.layout.getLineForOffset(renderedStart)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) -
                scrollView.height / 2
            ).coerceAtLeast(0)
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }

        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)
        Selection.setSelection(textView.text as Spannable, renderedStart, renderedEnd)

        val beforeScrollY = scrollView.scrollY
        val beforeLocator = engine.currentLocator.value

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "annotation-1",
                    start = Locator(
                        strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
                        totalProgression = targetSourceOffset.toFloat() / markdown.length,
                    ),
                    end = Locator(
                        strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset + targetText.length),
                        totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
                    ),
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        idleMainLooper()
        relayout(parent)

        val afterScrollY = scrollView.scrollY
        val afterLocator = engine.currentLocator.value

        assertTrue("expected to scroll away from top before applying annotations", beforeScrollY > 0)
        assertTrue(
            "expected locator to be near tail before applying annotations; before=$beforeLocator",
            (beforeLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected annotation refresh to preserve active-selection scroll; before=$beforeScrollY after=$afterScrollY",
            afterScrollY >= beforeScrollY / 2,
        )
        assertTrue(
            "expected active-selection locator to stay near current section; before=$beforeLocator after=$afterLocator",
            (afterLocator.totalProgression ?: 0f) > 0.5f,
        )
    }

    @Test
    fun `setTextAnnotations restores markdown scroll and locator after internal text refresh resets viewport`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-internal-reset-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))

        val targetText = "TargetToken064"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val nearTailLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        val headLocator = Locator(
            strategy = LocatorStrategy.Section(0, 0, 0),
            totalProgression = 0f,
        )
        val currentLocatorFlow = currentLocatorFlow(engine)
        currentLocatorFlow.value = nearTailLocator

        val textView = ResettingTextView(context).apply {
            textSize = 18f
            setPadding(48, 48, 48, 48)
            text = markdown
        }
        val scrollView = ScrollView(context).apply {
            addView(textView)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                currentLocatorFlow.value = if (scrollY <= 0) headLocator else nearTailLocator
            }
        }
        val parent = attachMeasured(scrollView, context)
        scrollView.scrollTo(0, 1200)
        idleMainLooper()
        relayout(parent)

        setPrivateField(engine, "scrollView", scrollView)
        setPrivateField(engine, "textView", textView)

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "annotation-1",
                    start = nearTailLocator,
                    end = Locator(
                        strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset + targetText.length),
                        totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
                    ),
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        idleMainLooper()
        relayout(parent)

        assertTrue(
            "expected internal text refresh to preserve scroll position instead of snapping to top; actual=${scrollView.scrollY}",
            scrollView.scrollY > 0,
        )
        assertTrue(
            "expected locator to stay near tail after internal text refresh; actual=${engine.currentLocator.value}",
            (engine.currentLocator.value.totalProgression ?: 0f) > 0.5f,
        )
    }

    @Test
    fun `clearTextSelection preserves markdown scroll and locator near current section`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-clear-selection-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val renderedStart = textView.text.toString().indexOf(targetText)
        val renderedEnd = renderedStart + targetText.length
        val targetLine = textView.layout.getLineForOffset(renderedStart)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) -
                scrollView.height / 2
            ).coerceAtLeast(0)

        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)
        Selection.setSelection(textView.text as Spannable, renderedStart, renderedEnd)

        val beforeScrollY = scrollView.scrollY
        val beforeLocator = engine.currentLocator.value

        engine.clearTextSelection()
        idleMainLooper()
        relayout(parent)

        val afterScrollY = scrollView.scrollY
        val afterLocator = engine.currentLocator.value

        assertTrue("expected to scroll away from top before clearing selection", beforeScrollY > 0)
        assertTrue(
            "expected locator to be near tail before clearing selection; before=$beforeLocator",
            (beforeLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected native selection to collapse after clearTextSelection; " +
                "start=${textView.selectionStart} end=${textView.selectionEnd}",
            textView.selectionStart == textView.selectionEnd,
        )
        assertTrue(
            "expected clearTextSelection to preserve near-tail scroll; before=$beforeScrollY after=$afterScrollY",
            afterScrollY >= beforeScrollY / 2,
        )
        assertTrue(
            "expected clearTextSelection to keep locator near current section; before=$beforeLocator after=$afterLocator",
            (afterLocator.totalProgression ?: 0f) > 0.5f,
        )
    }

    private fun buildMarkdownDocument(): String = buildString {
        append("# Title\n\n")
        repeat(96) { index ->
            append("## Section %03d\n".format(index))
            append(
                "Paragraph %03d carries stable markdown content for scroll preservation checks and ".format(index),
            )
            append(
                "TargetToken%03d keeps the near-tail viewport anchored during annotation refresh.\n\n".format(index),
            )
        }
    }

    private fun attachMeasured(view: View, context: Application): FrameLayout =
        FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(view)
            relayout(this)
        }

    private fun relayout(parent: FrameLayout) {
        parent.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(2400, MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 1080, 2400)
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentLocatorFlow(engine: MarkdownEngine): MutableStateFlow<Locator> =
        MarkdownEngine::class.java.getDeclaredField("_currentLocator").apply {
            isAccessible = true
        }.get(engine) as MutableStateFlow<Locator>

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        target::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private class ResettingTextView(context: Context) : TextView(context) {
        override fun setText(text: CharSequence?, type: BufferType?) {
            super.setText(text, type)
            (parent as? ScrollView)?.scrollTo(0, 0)
        }
    }
}
