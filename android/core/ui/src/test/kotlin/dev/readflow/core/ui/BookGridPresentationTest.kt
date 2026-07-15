package dev.readflow.core.ui

import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookGridPresentationTest {

    @Test
    fun `book covers are square while controls keep accessible touch targets`() {
        assertEquals(0.dp, Dimens.coverCorner)
        assertEquals(48.dp, Dimens.touchTarget)
    }

    @Test
    fun `bundle layers keep the front cover visually dominant`() {
        assertEquals(1f, bundleCoverFraction(1))
        assertEquals(0.94f, bundleCoverFraction(2))
        assertEquals(0.90f, bundleCoverFraction(3))
        assertEquals(0.86f, bundleCoverFraction(4))
    }

    @Test
    fun `single books omit labels while bundles show only their group name`() {
        val book = BookMeta(
            id = "book-1",
            title = "Single title",
            author = "Author",
            format = BookFormat.EPUB,
        )

        assertNull(libraryItemLabel(LibraryItem.Single(book)))
        assertEquals(
            "Reading list",
            libraryItemLabel(
                LibraryItem.Bundle(
                    BookBundle(
                        id = "bundle-1",
                        name = "Reading list",
                        books = listOf(book),
                    ),
                ),
            ),
        )
    }
}
