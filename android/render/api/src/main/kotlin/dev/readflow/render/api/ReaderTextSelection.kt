package dev.readflow.render.api

import dev.readflow.core.model.Locator
import kotlinx.coroutines.flow.StateFlow

data class ReaderTextSelection(
    val start: Locator,
    val end: Locator,
    val selectedText: String,
)

interface TextSelectableReaderEngine : ReaderEngine {
    val currentTextSelection: StateFlow<ReaderTextSelection?>
    fun clearTextSelection()
}
