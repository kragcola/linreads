package dev.readflow.features.reader

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderTextSelectionStateTest {

    @Test
    fun `reader ui state exposes current text selection`() {
        val selection = ReaderTextSelection(
            start = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 1, charOffset = 4)),
            end = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 1, charOffset = 8)),
            selectedText = "选中文字",
        )

        val state = ReaderUiState(textSelection = selection)

        assertEquals(selection, state.textSelection)
    }

    @Test
    fun `default reader ui state has no text selection`() {
        assertNull(ReaderUiState().textSelection)
    }
}
