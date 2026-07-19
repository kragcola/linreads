package dev.readflow.render.epub

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.buildSearchSnippet
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun epubSearchHits(
    indexedParas: List<EpubPara>,
    query: String,
    limit: Int = 100,
    paragraphProvider: (Int) -> EpubPara?,
): List<ReaderSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty() || limit <= 0 || indexedParas.isEmpty()) return emptyList()
    val totalChars = epubTotalChars(indexedParas).coerceAtLeast(1).toFloat()
    val results = mutableListOf<ReaderSearchHit>()
    for (index in indexedParas.indices) {
        currentCoroutineContext().ensureActive()
        val paragraph = paragraphProvider(index) ?: indexedParas[index]
        var fromIndex = 0
        while (results.size < limit) {
            currentCoroutineContext().ensureActive()
            val matchIndex = paragraph.text.indexOf(needle, startIndex = fromIndex, ignoreCase = true)
            if (matchIndex < 0) break
            val documentOffset = paragraph.documentCharStart + matchIndex
            val totalProgression = (documentOffset.toFloat() / totalChars).coerceIn(0f, 1f)
            val locator = Locator(
                strategy = LocatorStrategy.Section(
                    spineIndex = paragraph.spineIndex,
                    elementIndex = index,
                    charOffset = paragraph.spineCharStart + matchIndex,
                ),
                progression = totalProgression,
                totalProgression = totalProgression,
            )
            results += ReaderSearchHit(
                locator = locator,
                snippet = buildSearchSnippet(
                    source = paragraph.text,
                    matchStart = matchIndex,
                    matchLength = needle.length,
                ),
                matchLength = needle.length,
            )
            fromIndex = matchIndex + needle.length
        }
        if (results.size >= limit) break
    }
    return results
}
