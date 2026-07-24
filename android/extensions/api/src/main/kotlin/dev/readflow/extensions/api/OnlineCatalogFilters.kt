package dev.readflow.extensions.api

/** Client-side filter matching for online catalog entries. */
fun List<OnlineCatalogEntry>.applyCatalogFilter(filter: OnlineCatalogFilter): List<OnlineCatalogEntry> {
    if (filter.isEmpty) return this
    return filter { entry ->
        matchesAuthor(entry, filter.author) &&
            matchesSeries(entry, filter.series) &&
            matchesFormat(entry, filter.format) &&
            matchesTag(entry, filter.tag)
    }
}

private fun matchesAuthor(entry: OnlineCatalogEntry, author: String): Boolean {
    if (author.isBlank()) return true
    if (entry.authors.any { it.contains(author, ignoreCase = true) }) return true
    return entry.meta.author.contains(author, ignoreCase = true)
}

private fun matchesSeries(entry: OnlineCatalogEntry, series: String): Boolean {
    if (series.isBlank()) return true
    return entry.series?.contains(series, ignoreCase = true) == true
}

private fun matchesFormat(entry: OnlineCatalogEntry, format: String): Boolean {
    if (format.isBlank()) return true
    val needle = format.trim()
    if (entry.meta.format.name.equals(needle, ignoreCase = true)) return true
    return entry.availableFormats.any { it.equals(needle, ignoreCase = true) }
}

private fun matchesTag(entry: OnlineCatalogEntry, tag: String): Boolean {
    if (tag.isBlank()) return true
    return entry.tags.any { it.contains(tag, ignoreCase = true) }
}
