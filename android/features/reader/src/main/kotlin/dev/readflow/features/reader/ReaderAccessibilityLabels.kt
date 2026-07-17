package dev.readflow.features.reader

import dev.readflow.core.model.TocEntry
import kotlin.math.roundToInt

internal fun readerProgressPercentText(totalProgression: Float?): String =
    "${((totalProgression ?: 0f).coerceIn(0f, 1f) * 100f).roundToInt()}%"

internal fun readerChapterProgressDescription(
    title: String,
    currentIndex: Int,
    totalChapters: Int,
    progressInChapter: Float,
): String {
    val safeTitle = title.trim().ifEmpty { "未命名章节" }
    val safeCurrent = (currentIndex + 1).coerceAtLeast(1)
    val safeTotal = totalChapters.coerceAtLeast(1)
    val percent = ((progressInChapter.coerceIn(0f, 1f)) * 100f).roundToInt()
    return "$safeTitle，第 $safeCurrent / $safeTotal 章，本章进度 $percent%"
}

internal fun ReaderSearchResult.readerAccessibilityLabel(
    query: String = "",
    selected: Boolean = false,
): String {
    val normalizedQuery = query.trim()
    val head = if (normalizedQuery.isEmpty()) {
        "搜索结果 ${index + 1}"
    } else {
        "搜索「$normalizedQuery」结果 ${index + 1}"
    }
    val withPosition = locator.totalProgression?.coerceIn(0f, 1f)?.let {
        "$head，位置 ${(it * 100f).roundToInt()}%"
    } ?: head
    return if (selected) "$withPosition，已选中" else withPosition
}

internal fun ReaderBookmarkItem.accessibilityLabel(isCurrent: Boolean = false): String {
    val base = "跳转到$label"
    return if (isCurrent) "$base，当前" else base
}

internal fun ReaderAnnotationItem.accessibilityLabel(): String {
    val snippet = selectedText.singleLineSnippet()
    val noteSnippet = note?.singleLineSnippet()
    return if (noteSnippet.isNullOrEmpty()) {
        "跳转到标注：$snippet"
    } else {
        "跳转到标注：$snippet，笔记：$noteSnippet"
    }
}

internal fun readerTocAccessibilityLabel(entry: TocEntry): String =
    entry.title.trim().ifEmpty { "未命名目录" }.let { title ->
        val level = (entry.level + 1).coerceAtLeast(1)
        "$level 级目录，$title"
    }

private fun String.singleLineSnippet(maxChars: Int = 40): String =
    trim()
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .let { normalized ->
            if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 1) + "…"
        }
