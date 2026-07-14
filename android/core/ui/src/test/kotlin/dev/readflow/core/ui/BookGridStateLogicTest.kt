package dev.readflow.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookGridStateLogicTest {

    @Test
    fun contextMenuAnchorSurvivesReorderAndClearsWhenItemDisappears() {
        val anchor = "bundle:stable-id"

        assertEquals(
            anchor,
            retainContextMenuAnchor(
                anchorKey = anchor,
                currentItemKeys = listOf("book:second", anchor, "book:first"),
            ),
        )
        assertNull(
            retainContextMenuAnchor(
                anchorKey = anchor,
                currentItemKeys = listOf("book:second", "book:first"),
            ),
        )
    }
}
