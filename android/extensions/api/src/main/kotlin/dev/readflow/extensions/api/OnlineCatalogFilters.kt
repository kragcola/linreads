package dev.readflow.extensions.api

import java.text.Normalizer

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
    val expected = onlineAuthorIdentityKey(author)
    return entry.individualAuthors().any { onlineAuthorIdentityKey(it) == expected }
}

/** Author values used consistently by facets, filtering, and source-wide batch selection. */
fun OnlineCatalogEntry.individualAuthors(): List<String> {
    val fields = authors
        .map(String::trim)
        .filter(String::isNotBlank)
        .ifEmpty { listOf(meta.author.trim()).filter(String::isNotBlank) }
    return fields
        .flatMap { field -> field.split(AUTHOR_AMPERSAND_SEPARATOR) }
        .map(::onlineAuthorDisplayName)
        .filter(String::isNotBlank)
        .distinctBy(::onlineAuthorIdentityKey)
}

fun onlineAuthorDisplayName(value: String): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    val key = StringBuilder(normalized.length)
    var index = 0
    while (index < normalized.length) {
        val char = normalized[index]
        if (!char.isWhitespace()) {
            key.append(char)
            index += 1
            continue
        }
        var next = index + 1
        while (next < normalized.length && normalized[next].isWhitespace()) next += 1
        val previousChar = key.lastOrNull()
        val nextChar = normalized.getOrNull(next)
        if (
            previousChar != null &&
            nextChar != null &&
            !(previousChar.isEastAsianAuthorCharacter() && nextChar.isEastAsianAuthorCharacter()) &&
            key.lastOrNull() != ' '
        ) {
            key.append(' ')
        }
        index = next
    }
    return key.toString()
}

fun onlineAuthorIdentityKey(value: String): String = onlineAuthorDisplayName(value).lowercase()

private fun Char.isEastAsianAuthorCharacter(): Boolean = when (Character.UnicodeScript.of(code)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    -> true
    else -> false
}

private val AUTHOR_AMPERSAND_SEPARATOR = Regex("\\s*[&＆]\\s*")

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
