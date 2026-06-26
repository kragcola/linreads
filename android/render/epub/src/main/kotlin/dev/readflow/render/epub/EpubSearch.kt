package dev.readflow.render.epub

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy

internal fun epubSearchLocators(
    indexedParas: List<EpubPara>,
    query: String,
    limit: Int = 100,
    paragraphProvider: (Int) -> EpubPara?,
): List<Locator> {
    val needle = query.trim()
    if (needle.isEmpty() || limit <= 0 || indexedParas.isEmpty()) return emptyList()
    val totalChars = epubTotalChars(indexedParas).coerceAtLeast(1).toFloat()
    val results = mutableListOf<Locator>()
    for (index in indexedParas.indices) {
        val paragraph = paragraphProvider(index) ?: indexedParas[index]
        var fromIndex = 0
        while (results.size < limit) {
            val matchIndex = paragraph.text.indexOf(needle, startIndex = fromIndex, ignoreCase = true)
            if (matchIndex < 0) break
            val documentOffset = paragraph.documentCharStart + matchIndex
            val totalProgression = (documentOffset.toFloat() / totalChars).coerceIn(0f, 1f)
            results += Locator(
                strategy = LocatorStrategy.Section(
                    spineIndex = paragraph.spineIndex,
                    elementIndex = index,
                    charOffset = paragraph.spineCharStart + matchIndex,
                ),
                progression = totalProgression,
                totalProgression = totalProgression,
            )
            fromIndex = matchIndex + needle.length
        }
        if (results.size >= limit) break
    }
    return results
}
