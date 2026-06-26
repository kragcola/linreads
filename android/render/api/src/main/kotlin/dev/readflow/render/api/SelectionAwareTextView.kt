package dev.readflow.render.api

import android.content.Context
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView

class SelectionAwareTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {
    var onSelectionRangeChanged: ((start: Int, end: Int) -> Unit)? = null
    var hideFromAccessibility: Boolean = false
        set(value) {
            field = value
            applyAccessibilityVisibility()
        }
    var touchSelectionEnabled: Boolean = true
        set(value) {
            field = value
            keepTextSelectable()
        }
    var nativeTextSelectionEnabled: Boolean = true
        set(value) {
            field = value
            keepTextSelectable()
        }
    private var lastFallbackSelection: Pair<Int, Int>? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeoutMs = ViewConfiguration.getTapTimeout().toLong()
    private var pendingClickableSpan: ClickableSpan? = null
    private var pendingClickableSpanDownTime = 0L
    private var pendingClickableSpanDownX = 0f
    private var pendingClickableSpanDownY = 0f
    private var pendingLongPressTextOffset = NO_TEXT_OFFSET
    private var preservingInteractionStateForSelectableRefresh = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!preservingInteractionStateForSelectableRefresh) {
            lastFallbackSelection = null
            pendingLongPressTextOffset = NO_TEXT_OFFSET
            clearInteractiveTapReport()
        }
        super.setText(text, type)
        keepTextSelectable()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        keepTextSelectable()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        keepTextSelectable()
    }

    override fun performLongClick(): Boolean {
        if (!nativeTextSelectionEnabled) return false
        val scrollPositions = captureAncestorScrollPositions()
        refreshTextSelectable()
        val touchAnchor = pendingLongPressTextOffset
        val handled = super.performLongClick()
        if (touchAnchor != NO_TEXT_OFFSET && !selectionContainsAnchor(selectionStart, selectionEnd, touchAnchor)) {
            reportFallbackSelection(touchAnchor)
        } else if (selectionStart == selectionEnd) {
            val fallbackAnchor = touchAnchor.takeIf { it != NO_TEXT_OFFSET } ?: selectionStart
            reportFallbackSelection(fallbackAnchor)
        }
        restoreScrollPositions(scrollPositions)
        post { restoreScrollPositions(scrollPositions) }
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearInteractiveTapReport()
                pendingClickableSpan = clickableSpanAt(event)
                pendingClickableSpanDownTime = event.eventTime
                pendingClickableSpanDownX = event.x
                pendingClickableSpanDownY = event.y
                pendingLongPressTextOffset = selectionTextOffsetAt(event) ?: NO_TEXT_OFFSET
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingClickableSpan != null && touchMovedPastSlop(event)) {
                    pendingClickableSpan = null
                }
            }
            MotionEvent.ACTION_UP -> {
                val candidate = pendingClickableSpan
                val quickTap = (event.eventTime - pendingClickableSpanDownTime) <= tapTimeoutMs
                val stableTap = !touchMovedPastSlop(event)
                pendingClickableSpan = null
                if (candidate != null && quickTap && stableTap) {
                    reportInteractiveTapConsumed()
                    candidate.onClick(this)
                    pendingLongPressTextOffset = NO_TEXT_OFFSET
                    return true
                }
                pendingLongPressTextOffset = NO_TEXT_OFFSET
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingClickableSpan = null
                // Some long-press paths cancel the touch stream before performLongClick().
                // Clearing here drops the recovered anchor and makes fallback selection snap
                // back to the document start instead of the visible text.
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!nativeTextSelectionEnabled) return
        if (selStart >= 0 && selEnd >= 0) {
            val touchAnchor = pendingLongPressTextOffset
            if (touchAnchor != NO_TEXT_OFFSET && !selectionContainsAnchor(selStart, selEnd, touchAnchor)) {
                return
            }
            if (selStart == selEnd && lastFallbackSelection != null) {
                return
            }
            if (selStart != selEnd) {
                val normalizedSelection = minOf(selStart, selEnd) to maxOf(selStart, selEnd)
                if (lastFallbackSelection != normalizedSelection) {
                    lastFallbackSelection = null
                }
            }
            onSelectionRangeChanged?.invoke(selStart, selEnd)
        }
    }

    private fun keepTextSelectable() {
        if (nativeTextSelectionEnabled && !isTextSelectable) {
            setTextIsSelectable(true)
        } else if (!nativeTextSelectionEnabled && isTextSelectable) {
            setTextIsSelectable(false)
        }
        val inputEnabled = nativeTextSelectionEnabled && touchSelectionEnabled
        isFocusableInTouchMode = inputEnabled
        isFocusable = inputEnabled
        isClickable = inputEnabled
        isLongClickable = inputEnabled
        applyAccessibilityVisibility()
    }

    private fun applyAccessibilityVisibility() {
        importantForAccessibility = if (hideFromAccessibility) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private fun refreshTextSelectable() {
        if (!nativeTextSelectionEnabled) {
            keepTextSelectable()
            return
        }
        val scrollPositions = captureAncestorScrollPositions()
        preservingInteractionStateForSelectableRefresh = true
        try {
            setTextIsSelectable(false)
            setTextIsSelectable(true)
            restoreScrollPositions(scrollPositions)
        } finally {
            preservingInteractionStateForSelectableRefresh = false
        }
        post { restoreScrollPositions(scrollPositions) }
        keepTextSelectable()
    }

    fun clearNativeTextSelection() {
        val scrollPositions = captureAncestorScrollPositions()
        lastFallbackSelection = null
        pendingLongPressTextOffset = NO_TEXT_OFFSET
        clearInteractiveTapReport()
        preservingInteractionStateForSelectableRefresh = true
        try {
            (text as? Spannable)?.let(Selection::removeSelection)
            if (nativeTextSelectionEnabled) {
                setTextIsSelectable(false)
                setTextIsSelectable(true)
            } else {
                keepTextSelectable()
            }
            restoreScrollPositions(scrollPositions)
        } finally {
            preservingInteractionStateForSelectableRefresh = false
        }
        post { restoreScrollPositions(scrollPositions) }
        keepTextSelectable()
    }

    private fun captureAncestorScrollPositions(): List<ScrollPositionSnapshot> {
        val snapshots = mutableListOf(ScrollPositionSnapshot(this, scrollX, scrollY))
        var ancestor = parent
        while (ancestor is View) {
            snapshots += ScrollPositionSnapshot(ancestor, ancestor.scrollX, ancestor.scrollY)
            ancestor = ancestor.parent
        }
        return snapshots
    }

    private fun restoreScrollPositions(snapshots: List<ScrollPositionSnapshot>) {
        snapshots.forEach { snapshot ->
            if (snapshot.view.scrollX != snapshot.scrollX || snapshot.view.scrollY != snapshot.scrollY) {
                snapshot.view.scrollTo(snapshot.scrollX, snapshot.scrollY)
            }
        }
    }

    private fun reportFallbackSelection(anchor: Int) {
        val length = text.length
        if (length <= 0) return
        val safeAnchor = anchor.coerceIn(0, length - 1)
        var start = safeAnchor
        while (start > 0 && !text[start - 1].isWhitespace()) {
            start--
        }
        var end = safeAnchor
        while (end < length && !text[end].isWhitespace()) {
            end++
        }
        if (start == end) {
            end = (safeAnchor + FALLBACK_SELECTION_CHARS).coerceAtMost(length)
        }
        val fallback = start to end
        if (start < end && lastFallbackSelection != fallback) {
            lastFallbackSelection = fallback
            val spannable = text as? Spannable
            if (spannable != null) {
                Selection.setSelection(spannable, start, end)
            } else {
                onSelectionRangeChanged?.invoke(start, end)
            }
        }
    }

    private fun touchMovedPastSlop(event: MotionEvent): Boolean =
        kotlin.math.abs(event.x - pendingClickableSpanDownX) > touchSlop ||
            kotlin.math.abs(event.y - pendingClickableSpanDownY) > touchSlop

    private fun selectionContainsAnchor(selStart: Int, selEnd: Int, anchor: Int): Boolean {
        if (selStart < 0 || selEnd < 0 || selStart == selEnd) return false
        val first = minOf(selStart, selEnd)
        val lastExclusive = maxOf(selStart, selEnd)
        return anchor in first until lastExclusive
    }

    private fun clickableSpanAt(event: MotionEvent): ClickableSpan? {
        val textLayout = layout ?: return null
        val spannable = text as? Spanned ?: return null
        val localX = event.x - totalPaddingLeft + scrollX
        val offset = textOffsetAt(event) ?: return null
        val line = textLayout.getLineForOffset(offset)
        val lineLeft = minOf(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val lineRight = maxOf(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val clampedX = localX.coerceIn(lineLeft, lineRight)
        return spannable.clickableSpanNear(offset)
            ?: spannable.clickableSpanAtLineX(textLayout, line, clampedX)
    }

    private fun textOffsetAt(event: MotionEvent): Int? {
        val textLayout = layout ?: return null
        val localX = event.x - totalPaddingLeft + scrollX
        val localY = event.y - totalPaddingTop + scrollY
        if (localX < 0f || localY < 0f) return null
        val line = textLayout.getLineForVertical(localY.toInt())
        if (line !in 0 until textLayout.lineCount) return null
        val lineLeft = minOf(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val lineRight = maxOf(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val expandedLeft = lineLeft - touchSlop
        val expandedRight = lineRight + touchSlop
        if (localX < expandedLeft || localX > expandedRight) return null
        return getOffsetForPosition(event.x, event.y)
    }

    private fun selectionTextOffsetAt(event: MotionEvent): Int? {
        val textLayout = layout ?: return null
        val localX = event.x - totalPaddingLeft + scrollX
        val localY = event.y - totalPaddingTop + scrollY
        if (localX < 0f || localY < 0f) return null
        val line = nearestSelectionLine(localY.toInt()) ?: return null
        val lineLeft = minOf(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val lineRight = maxOf(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val clampedX = localX.coerceIn(lineLeft, lineRight)
        return textLayout.getOffsetForHorizontal(line, clampedX)
    }

    private fun nearestSelectionLine(localY: Int): Int? {
        val textLayout = layout ?: return null
        if (textLayout.lineCount == 0) return null
        val initialLine = textLayout.getLineForVertical(localY)
        if (initialLine !in 0 until textLayout.lineCount) return null
        if (lineHasSelectableText(textLayout, initialLine)) return initialLine
        val belowLine = findSelectableLine(textLayout, start = initialLine + 1, step = 1)
        val aboveLine = findSelectableLine(textLayout, start = initialLine - 1, step = -1)
        if (belowLine == null) return aboveLine
        if (aboveLine == null) return belowLine
        val belowDistance = lineVerticalDistance(textLayout, belowLine, localY)
        val aboveDistance = lineVerticalDistance(textLayout, aboveLine, localY)
        return when {
            belowDistance < aboveDistance -> belowLine
            aboveDistance < belowDistance -> aboveLine
            localY >= (textLayout.getLineTop(initialLine) + textLayout.getLineBottom(initialLine)) / 2 -> belowLine
            else -> aboveLine
        }
    }

    private fun findSelectableLine(textLayout: Layout, start: Int, step: Int): Int? {
        var line = start
        while (line in 0 until textLayout.lineCount) {
            if (lineHasSelectableText(textLayout, line)) return line
            line += step
        }
        return null
    }

    private fun lineHasSelectableText(textLayout: Layout, line: Int): Boolean {
        val lineStart = textLayout.getLineStart(line)
        val lineEnd = textLayout.getLineEnd(line)
        if (lineStart >= lineEnd) return false
        for (index in lineStart until lineEnd) {
            if (!text[index].isWhitespace()) return true
        }
        return false
    }

    private fun lineVerticalDistance(textLayout: Layout, line: Int, localY: Int): Int {
        val top = textLayout.getLineTop(line)
        val bottom = textLayout.getLineBottom(line)
        return when {
            localY < top -> top - localY
            localY > bottom -> localY - bottom
            else -> 0
        }
    }

    private fun Spanned.clickableSpanNear(offset: Int): ClickableSpan? {
        if (offset < 0 || offset > length) return null
        val queryStart = (offset - 1).coerceAtLeast(0)
        val queryEnd = (offset + 1).coerceAtMost(length)
        getSpans(queryStart, queryEnd, ClickableSpan::class.java).firstOrNull { span ->
            val spanStart = getSpanStart(span)
            val spanEnd = getSpanEnd(span)
            offset in spanStart until spanEnd || (offset == spanEnd && offset > spanStart)
        }?.let { return it }
        if (offset > 0) {
            getSpans(offset - 1, offset, ClickableSpan::class.java).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun Spanned.clickableSpanAtLineX(
        textLayout: Layout,
        line: Int,
        x: Float,
    ): ClickableSpan? {
        val lineStart = textLayout.getLineStart(line)
        val lineEnd = textLayout.getLineEnd(line)
        return getSpans(lineStart, lineEnd, ClickableSpan::class.java).firstOrNull { span ->
            spanContainsLineX(textLayout, span, line, x)
        }
    }

    private fun Spanned.spanContainsLineX(
        textLayout: Layout,
        span: ClickableSpan,
        line: Int,
        x: Float,
    ): Boolean {
        val spanStart = getSpanStart(span)
        val spanEnd = getSpanEnd(span)
        if (spanStart < 0 || spanEnd <= spanStart) return false
        val startLine = textLayout.getLineForOffset(spanStart)
        val endLine = textLayout.getLineForOffset((spanEnd - 1).coerceAtLeast(spanStart))
        if (line < startLine || line > endLine) return false
        val segmentStart = if (line == startLine) spanStart else textLayout.getLineStart(line)
        val segmentEnd = if (line == endLine) spanEnd else textLayout.getLineEnd(line)
        val lineEnd = textLayout.getLineEnd(line)
        val left = textLayout.getPrimaryHorizontal(segmentStart)
        val right = when {
            segmentEnd <= segmentStart -> left
            segmentEnd >= lineEnd -> textLayout.getLineRight(line)
            else -> textLayout.getPrimaryHorizontal(segmentEnd)
        }
        val minX = minOf(left, right)
        val maxX = maxOf(left, right)
        return x in minX..maxX
    }

    private fun reportInteractiveTapConsumed() {
        setTag(R.id.selection_aware_interactive_tap_consumed, true)
    }

    private fun clearInteractiveTapReport() {
        setTag(R.id.selection_aware_interactive_tap_consumed, false)
    }

    private companion object {
        const val FALLBACK_SELECTION_CHARS = 8
        const val NO_TEXT_OFFSET = -1
    }
}

private data class ScrollPositionSnapshot(
    val view: View,
    val scrollX: Int,
    val scrollY: Int,
)
