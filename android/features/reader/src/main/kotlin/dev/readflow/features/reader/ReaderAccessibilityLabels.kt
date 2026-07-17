package dev.readflow.features.reader

import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.TocEntry
import kotlin.math.roundToInt

/** Fixed width reserved for DOCUMENT chrome in place of adjacent nav buttons. */
internal const val readerDocumentNavSpacerDp: Int = 48

internal fun readerProgressValue(totalProgression: Float?): Float =
    finiteUnitProgression(totalProgression)

internal fun readerProgressPercentText(totalProgression: Float?): String =
    "${(readerProgressValue(totalProgression) * 100f).roundToInt()}%"

internal fun readerChapterProgressDescription(
    title: String,
    currentIndex: Int,
    totalChapters: Int,
    progressInChapter: Float,
    kind: ChapterInfo.Kind = ChapterInfo.Kind.CHAPTER,
): String {
    val safeTitle = title.trim().ifEmpty { "未命名章节" }
    return when (kind) {
        ChapterInfo.Kind.DOCUMENT -> safeTitle
        ChapterInfo.Kind.CHAPTER -> {
            val safeCurrent = (currentIndex + 1).coerceAtLeast(1)
            val safeTotal = totalChapters.coerceAtLeast(1)
            val percent = (finiteUnitProgression(progressInChapter) * 100f).roundToInt()
            "$safeTitle，第 $safeCurrent / $safeTotal 章，本章进度 $percent%"
        }
        ChapterInfo.Kind.PAGE -> {
            // PAGE never invents a 1/0 or 1/1 counter, and never speaks meaningless "本页进度".
            if (totalChapters <= 0) {
                safeTitle
            } else {
                val safeCurrent = (currentIndex + 1).coerceIn(1, totalChapters)
                "$safeTitle，第 $safeCurrent / $totalChapters 页"
            }
        }
    }
}

internal fun readerNavigationCounterText(info: ChapterInfo): String? =
    when (info.kind) {
        ChapterInfo.Kind.CHAPTER ->
            "${info.currentIndex + 1} / ${info.totalChapters} 章"
        ChapterInfo.Kind.PAGE ->
            if (info.totalChapters <= 0) {
                null
            } else {
                "${info.currentIndex + 1} / ${info.totalChapters} 页"
            }
        ChapterInfo.Kind.DOCUMENT -> null
    }

internal fun readerAdjacentNavLabel(kind: ChapterInfo.Kind, delta: Int): String? =
    when (kind) {
        ChapterInfo.Kind.CHAPTER -> if (delta < 0) "上一章" else "下一章"
        ChapterInfo.Kind.PAGE -> if (delta < 0) "上一页" else "下一页"
        ChapterInfo.Kind.DOCUMENT -> null
    }

internal fun readerShowsAdjacentNavButtons(kind: ChapterInfo.Kind): Boolean =
    kind != ChapterInfo.Kind.DOCUMENT

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
    val withPosition = finiteProgressionOrNull(locator.totalProgression)?.let {
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

/** Finite unit progression in [0, 1]; non-finite / null → 0. */
private fun finiteUnitProgression(value: Float?): Float {
    if (value == null || !value.isFinite()) return 0f
    return value.coerceIn(0f, 1f)
}

private fun finiteProgressionOrNull(value: Float?): Float? {
    if (value == null || !value.isFinite()) return null
    return value.coerceIn(0f, 1f)
}

private fun String.singleLineSnippet(maxChars: Int = 40): String =
    trim()
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .let { normalized ->
            if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 1) + "…"
        }
