package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** Anchor for ink strokes / annotations (§6.3). Sealed for pure-data polymorphism. */
@Serializable
sealed interface InkAnchor {
    /** Fixed-layout (PDF/CBZ/DOCX): anchor to immutable page coordinates. */
    @Serializable
    data class Page(val pageIndex: Int, val pageWidth: Float, val pageHeight: Float) : InkAnchor

    /**
     * Reflow (EPUB/TXT/MD): anchor to text, re-resolved on font-size change.
     * Uses the SAME spine+element+charOffset coordinate system as Locator (§7.1, F7).
     */
    @Serializable
    data class Text(
        val spineIndex: Int,          // which spine item (chapter)
        val elementIndex: Int,        // index into the parsed ReaderItem sequence
        val textStartOffset: Int,     // char offset within the element (code points)
        val textEndOffset: Int,
        val offsetXPx: Float,         // capture-time pixel hint, re-resolved on reflow
        val offsetYPx: Float,
        val fontSizeAtCapture: Float,
    ) : InkAnchor
}
