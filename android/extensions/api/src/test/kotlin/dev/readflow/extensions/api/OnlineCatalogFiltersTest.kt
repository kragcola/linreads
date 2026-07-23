package dev.readflow.extensions.api

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineCatalogFiltersTest {

    @Test
    fun filtersByAuthorSeriesFormatAndTag() {
        val entries = listOf(
            entry("1", author = "Alice", series = "Saga", format = BookFormat.EPUB, tags = listOf("scifi")),
            entry("2", author = "Bob", series = "Saga", format = BookFormat.PDF, tags = listOf("history")),
            entry("3", author = "Alice", series = "Other", format = BookFormat.EPUB, tags = listOf("scifi", "space")),
        )

        assertEquals(
            listOf("1", "3"),
            entries.applyCatalogFilter(OnlineCatalogFilter(author = "alice")).map { it.meta.id },
        )
        assertEquals(
            listOf("1", "2"),
            entries.applyCatalogFilter(OnlineCatalogFilter(series = "saga")).map { it.meta.id },
        )
        assertEquals(
            listOf("2"),
            entries.applyCatalogFilter(OnlineCatalogFilter(format = "pdf")).map { it.meta.id },
        )
        assertEquals(
            listOf("1", "3"),
            entries.applyCatalogFilter(OnlineCatalogFilter(tag = "scifi")).map { it.meta.id },
        )
        assertEquals(
            listOf("1"),
            entries.applyCatalogFilter(
                OnlineCatalogFilter(author = "Alice", series = "Saga", format = "EPUB", tag = "scifi"),
            ).map { it.meta.id },
        )
    }

    @Test
    fun emptyFilterReturnsAllEntries() {
        val entries = listOf(entry("1"), entry("2"))
        assertEquals(entries, entries.applyCatalogFilter(OnlineCatalogFilter()))
    }

    private fun entry(
        id: String,
        author: String = "Author",
        series: String? = null,
        format: BookFormat = BookFormat.EPUB,
        tags: List<String> = emptyList(),
    ) = OnlineCatalogEntry(
        meta = BookMeta(id = id, title = "Title $id", author = author, format = format),
        series = series,
        tags = tags,
        availableFormats = listOf(format.name),
    )
}
