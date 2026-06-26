package dev.readflow.features.reader

import android.text.SpannableString
import android.text.Selection
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.Layout
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.readflow.render.api.SelectionAwareTextView
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectionAwareTextViewTest {

    @Test
    fun `touch cancel keeps recovered long press anchor for later fallback`() {
        val context = RuntimeEnvironment.getApplication()
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setText("Alpha line\n\nBravo target word\nCharlie tail")
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(800, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(view)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, 800, parent.measuredHeight)

        val reportedSelections = mutableListOf<Pair<Int, Int>>()
        view.onSelectionRangeChanged = { start, end ->
            reportedSelections += start to end
        }

        val wordStart = view.text.indexOf("Bravo")
        setPendingLongPressTextOffset(view, wordStart)
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)

        view.onTouchEvent(cancel)
        assertEquals(wordStart, pendingLongPressTextOffset(view))
        cancel.recycle()
    }

    @Test
    fun `fallback selection uses preserved anchor after touch cancel`() {
        val context = RuntimeEnvironment.getApplication()
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setText("Alpha line\n\nBravo target word\nCharlie tail")
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(800, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(view)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, 800, parent.measuredHeight)

        val reportedSelections = mutableListOf<Pair<Int, Int>>()
        view.onSelectionRangeChanged = { start, end ->
            reportedSelections += start to end
        }

        val wordStart = view.text.indexOf("Bravo")
        setPendingLongPressTextOffset(view, wordStart)
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)

        view.onTouchEvent(cancel)
        reportFallbackSelection(view, pendingLongPressTextOffset(view))

        val selection = reportedSelections.last()
        assertEquals("Bravo", view.text.substring(selection.first, selection.second))
        cancel.recycle()
    }

    @Test
    fun `fallback selection also updates native selection range`() {
        val context = RuntimeEnvironment.getApplication()
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setText(
                SpannableString("Alpha line\n\nBravo target word\nCharlie tail"),
                android.widget.TextView.BufferType.SPANNABLE,
            )
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(800, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(view)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, 800, parent.measuredHeight)

        val reportedSelections = mutableListOf<Pair<Int, Int>>()
        view.onSelectionRangeChanged = { start, end ->
            reportedSelections += start to end
        }

        val wordStart = view.text.indexOf("Bravo")
        reportFallbackSelection(view, wordStart)

        val selection = reportedSelections.last()
        assertEquals(selection.first, view.selectionStart)
        assertEquals(selection.second, view.selectionEnd)
    }

    @Test
    fun `perform long click keeps recovered anchor when native selection stays collapsed`() {
        val context = RuntimeEnvironment.getApplication()
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setText("Alpha line\n\nBravo target word\nCharlie tail")
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(800, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(view)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, 800, parent.measuredHeight)

        val reportedSelections = mutableListOf<Pair<Int, Int>>()
        view.onSelectionRangeChanged = { start, end ->
            reportedSelections += start to end
        }

        val wordStart = view.text.indexOf("Bravo")
        setPendingLongPressTextOffset(view, wordStart)

        view.performLongClick()

        val selection = reportedSelections.last()
        assertEquals("Bravo", view.text.substring(selection.first, selection.second))
    }

    @Test
    fun `touch cancel keeps far anchor after scroll for later fallback`() {
        val context = RuntimeEnvironment.getApplication()
        val text = buildString {
            repeat(90) { index ->
                append("Paragraph %03d marker Target%03d keeps scroll selection stable.\n\n".format(index, index))
            }
        }
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setText(text)
        }
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(view)
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(1080, 2400)
            addView(scrollView)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(2400, MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 1080, 2400)

        val reportedSelections = mutableListOf<Pair<Int, Int>>()
        view.onSelectionRangeChanged = { start, end ->
            reportedSelections += start to end
        }

        val word = "Paragraph"
        val targetParagraphStart = view.text.indexOf("Paragraph 064")
        val wordStart = targetParagraphStart
        val layout = view.layout
        val contentY = view.totalPaddingTop + ((layout.getLineTop(layout.getLineForOffset(wordStart)) +
            layout.getLineBottom(layout.getLineForOffset(wordStart))) / 2f)
        scrollView.scrollTo(0, (contentY - (scrollView.height / 2f)).toInt().coerceAtLeast(0))

        setPendingLongPressTextOffset(view, wordStart)
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)

        view.onTouchEvent(cancel)
        assertEquals(wordStart, pendingLongPressTextOffset(view))
        cancel.recycle()
    }

    @Test
    fun `clear native text selection preserves scroll and removes native selection`() {
        val context = RuntimeEnvironment.getApplication()
        val text = buildString {
            repeat(90) { index ->
                append("Paragraph %03d marker Target%03d keeps scroll selection stable.\n\n".format(index, index))
            }
        }
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setText(SpannableString(text), android.widget.TextView.BufferType.SPANNABLE)
        }
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(view)
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(1080, 2400)
            addView(scrollView)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(2400, MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 1080, 2400)

        val selectedText = "Paragraph"
        val wordStart = view.text.indexOf("Paragraph 064")
        val layout = view.layout
        val contentY = view.totalPaddingTop + ((layout.getLineTop(layout.getLineForOffset(wordStart)) +
            layout.getLineBottom(layout.getLineForOffset(wordStart))) / 2f)
        scrollView.scrollTo(0, (contentY - (scrollView.height / 2f)).toInt().coerceAtLeast(0))
        Selection.setSelection(view.text as SpannableString, wordStart, wordStart + selectedText.length)

        val beforeScrollY = scrollView.scrollY

        view.clearNativeTextSelection()
        idleMainLooper()

        assertTrue("expected to scroll away from top before clearing selection", beforeScrollY > 0)
        assertTrue(
            "expected native selection to collapse after clearNativeTextSelection; " +
                "start=${view.selectionStart} end=${view.selectionEnd}",
            view.selectionStart == view.selectionEnd,
        )
        assertTrue(
            "expected scroll position to stay near the active paragraph after clearing selection; " +
                "before=$beforeScrollY after=${scrollView.scrollY}",
            scrollView.scrollY >= beforeScrollY / 2,
        )
    }

    @Test
    fun `clickable span tap invokes span and reports interactive consumption`() {
        val context = RuntimeEnvironment.getApplication()
        var clicked = 0
        val span = object : ClickableSpan() {
            override fun onClick(widget: View) {
                clicked += 1
            }
        }
        val view = SelectionAwareTextView(context).apply {
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            val text = SpannableString("hello link world")
            text.setSpan(span, 6, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setText(text, android.widget.TextView.BufferType.SPANNABLE)
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(800, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(view)
        }

        parent.measure(
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, 800, parent.measuredHeight)

        val layout = view.layout
        val spanned = view.text as Spanned
        assertEquals(1, spanned.getSpans(0, spanned.length, ClickableSpan::class.java).size)
        val tapOffset = 7
        val line = layout.getLineForOffset(tapOffset)
        val charStartX = layout.getPrimaryHorizontal(tapOffset)
        val charEndX = layout.getPrimaryHorizontal(tapOffset + 1)
        val tapX = view.totalPaddingLeft + charStartX + ((charEndX - charStartX) / 2f)
        val tapY = view.totalPaddingTop + ((layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        val up = MotionEvent.obtain(0L, 30L, MotionEvent.ACTION_UP, tapX, tapY, 0)

        assertEquals(span, clickableSpanAt(view, down))
        assertTrue(view.dispatchTouchEvent(down))
        assertEquals(span, pendingClickableSpan(view))
        assertTrue(view.dispatchTouchEvent(up))
        assertEquals(1, clicked)
        assertEquals(
            true,
            view.getTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed),
        )

        down.recycle()
        up.recycle()
    }

    @Test
    fun `multi line clickable span line hit test includes first visual line right edge`() {
        val context = RuntimeEnvironment.getApplication()
        val scenario = createMultilineSpanScenario(context)

        assertTrue(scenario.endLine > scenario.startLine)
        val tapXLocal = scenario.layout.getLineRight(scenario.startLine) - 2f

        assertEquals(
            scenario.span,
            clickableSpanAtLineX(
                scenario.view,
                scenario.spanned,
                scenario.layout,
                scenario.startLine,
                tapXLocal,
            ),
        )
    }

    @Test
    fun `near boundary tap on first visual line of multiline clickable span still invokes span`() {
        val context = RuntimeEnvironment.getApplication()
        val scenario = createMultilineSpanScenario(context)
        val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val tapX = scenario.view.totalPaddingLeft +
            scenario.layout.getLineRight(scenario.startLine) +
            minOf(1f, tapSlop / 2f)
        val tapY = scenario.view.totalPaddingTop +
            ((scenario.layout.getLineTop(scenario.startLine) + scenario.layout.getLineBottom(scenario.startLine)) / 2f)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        val up = MotionEvent.obtain(0L, 30L, MotionEvent.ACTION_UP, tapX, tapY, 0)

        assertEquals(scenario.span, clickableSpanAt(scenario.view, down))
        assertTrue(scenario.view.dispatchTouchEvent(down))
        assertEquals(scenario.span, pendingClickableSpan(scenario.view))
        assertTrue(scenario.view.dispatchTouchEvent(up))
        assertEquals(1, scenario.clicked())
        assertEquals(
            true,
            scenario.view.getTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed),
        )

        down.recycle()
        up.recycle()
    }

    private fun createMultilineSpanScenario(context: android.content.Context): MultilineSpanScenario {
        var clicked = 0
        val linkLabel = "www.competitiveautoparts.com/spreadsheets/\nacdelcosparkplugs.xls"
        val paragraph = "For example, see $linkLabel in this case oil filters and spark plugs."
        val linkStart = paragraph.indexOf(linkLabel)
        val linkEnd = linkStart + linkLabel.length
        val span = object : ClickableSpan() {
            override fun onClick(widget: View) {
                clicked += 1
            }
        }
        val view = SelectionAwareTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(24, 10, 24, 10)
            setLineSpacing(0f, 1.75f)
            val text = SpannableString(paragraph)
            text.setSpan(span, linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setText(text, android.widget.TextView.BufferType.SPANNABLE)
            setHorizontallyScrolling(false)
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
        }
        val parent = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(1080, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(view)
        }
        parent.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, 1080, parent.measuredHeight)
        val layout = view.layout
        val startLine = layout.getLineForOffset(linkStart)
        val endLine = layout.getLineForOffset(linkEnd - 1)
        return MultilineSpanScenario(
            view = view,
            spanned = view.text as Spanned,
            layout = layout,
            span = span,
            startLine = startLine,
            endLine = endLine,
            clicked = { clicked },
        )
    }

    private fun pendingClickableSpan(view: SelectionAwareTextView): ClickableSpan? {
        val field = SelectionAwareTextView::class.java.getDeclaredField("pendingClickableSpan")
        field.isAccessible = true
        return field.get(view) as? ClickableSpan
    }

    private fun pendingLongPressTextOffset(view: SelectionAwareTextView): Int {
        val field = SelectionAwareTextView::class.java.getDeclaredField("pendingLongPressTextOffset")
        field.isAccessible = true
        return field.getInt(view)
    }

    private fun setPendingLongPressTextOffset(view: SelectionAwareTextView, offset: Int) {
        val field = SelectionAwareTextView::class.java.getDeclaredField("pendingLongPressTextOffset")
        field.isAccessible = true
        field.setInt(view, offset)
    }

    private fun reportFallbackSelection(view: SelectionAwareTextView, anchor: Int) {
        val method = SelectionAwareTextView::class.java.getDeclaredMethod("reportFallbackSelection", Integer.TYPE)
        method.isAccessible = true
        method.invoke(view, anchor)
    }

    private fun clickableSpanAt(view: SelectionAwareTextView, event: MotionEvent): ClickableSpan? {
        val method = SelectionAwareTextView::class.java.getDeclaredMethod("clickableSpanAt", MotionEvent::class.java)
        method.isAccessible = true
        return method.invoke(view, event) as? ClickableSpan
    }

    private fun clickableSpanAtLineX(
        view: SelectionAwareTextView,
        spanned: Spanned,
        layout: Layout,
        line: Int,
        x: Float,
    ): ClickableSpan? {
        val method = SelectionAwareTextView::class.java.getDeclaredMethod(
            "clickableSpanAtLineX",
            Spanned::class.java,
            Layout::class.java,
            Integer.TYPE,
            java.lang.Float.TYPE,
        )
        method.isAccessible = true
        return method.invoke(view, spanned, layout, line, x) as? ClickableSpan
    }

    private data class MultilineSpanScenario(
        val view: SelectionAwareTextView,
        val spanned: Spanned,
        val layout: Layout,
        val span: ClickableSpan,
        val startLine: Int,
        val endLine: Int,
        val clicked: () -> Int,
    )

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

}
