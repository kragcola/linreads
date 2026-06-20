package dev.readflow.ink

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.readflow.core.model.InkAnchor

/**
 * Overlay that sits above the document view and handles stylus input (§6.2).
 * Attach/detach lifecycle mirrors the reader session; page changes flush in-progress strokes.
 * All methods must be called on the main thread.
 */
interface InkOverlay {

    /** True while a stroke is being drawn. */
    val isDrawing: Boolean

    /** True if any committed strokes exist on the current page/anchor. */
    val hasStrokes: Boolean

    /**
     * Inflate the overlay Views and add them above [documentView] inside [parent].
     * Must be called before any other method.
     */
    fun attach(parent: ViewGroup, documentView: View)

    /** Remove overlay Views from [parent] and release resources. */
    fun detach()

    /**
     * Route a stylus [MotionEvent] into the ink pipeline.
     * Returns true if the event was consumed (caller should not forward it further).
     * Caller is responsible for tool-type routing — only pass STYLUS/ERASER events here.
     */
    fun handleStylusEvent(event: MotionEvent): Boolean

    /** Abort the stroke currently in progress without committing it. */
    fun cancelCurrentStroke()

    fun setBrush(brush: InkBrush)
    fun undo()
    fun redo()

    /** Erase all strokes on the current page / anchor. */
    fun clearPage()

    /** Called before a page turn begins — flushes and saves any in-progress stroke. */
    fun onPageWillChange()

    /**
     * Called after page turn completes. [anchor] describes the new position so the
     * overlay can load strokes for the new page.
     */
    fun onPageChanged(anchor: InkAnchor)
}
