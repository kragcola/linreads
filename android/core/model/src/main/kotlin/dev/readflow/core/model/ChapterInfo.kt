package dev.readflow.core.model

/**
 * Chapter-level progress snapshot for the reader chrome.
 * Engines that support chapters (EPUB via spine, etc.) update this
 * on each scroll/page-turn; engines that don't (TXT) return single-chapter.
 */
data class ChapterInfo(
    /** 0-based index of the current chapter. */
    val currentIndex: Int,
    /** Total number of chapters. */
    val totalChapters: Int,
    /** Display name of the current chapter (e.g. TOC label or "第3章"). */
    val currentTitle: String,
    /** Progress within the current chapter, 0..1. */
    val progressInChapter: Float,
)
