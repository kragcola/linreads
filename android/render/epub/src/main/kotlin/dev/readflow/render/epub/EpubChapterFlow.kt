package dev.readflow.render.epub

/**
 * Continuous-flow chapter model (方案 C, Moon+ Reader style).
 *
 * One spine (chapter) → one canonical plain-text [CharSequence] that the render layer turns into a
 * single SpannableStringBuilder / StaticLayout. [EpubFlowSegment]s map every layout character offset
 * back to a `(paragraphIndex, paragraphOffset)` pair so the existing `Locator.Section` contract keeps
 * working (charOffset = para.spineCharStart + paragraphOffset). The render layer MUST build its
 * Spannable from [EpubChapterFlow.text] verbatim and apply spans over [EpubChapterFlow.segments],
 * otherwise the offset map and the laid-out text disagree.
 *
 * Images become a single U+FFFC object-replacement char (the standard ImageSpan anchor). Break blocks
 * are zero-length segments; the uniform paragraph separator below already provides the blank-line gap.
 */
internal const val EPUB_FLOW_IMAGE_CHAR = '￼'

/** Blank-line gap inserted between adjacent blocks (paragraph spacing). */
internal const val EPUB_FLOW_PARAGRAPH_SEPARATOR = "\n\n"

internal data class EpubFlowSegment(
    val layoutStart: Int,
    val layoutEnd: Int,
    val paragraphIndex: Int,
    val block: EpubDisplayBlock,
) {
    val isText: Boolean get() = block is EpubDisplayBlock.Text
    val isImage: Boolean get() = block is EpubDisplayBlock.Image
}

internal data class EpubChapterFlow(
    val spineIndex: Int,
    val text: String,
    val segments: List<EpubFlowSegment>,
)

internal fun EpubChapterFlow.nextContentSegmentAfter(index: Int): EpubFlowSegment? {
    for (candidateIndex in (index + 1) until segments.size) {
        val candidate = segments[candidateIndex]
        if (candidate.layoutEnd > candidate.layoutStart) return candidate
    }
    return null
}

/** Builds the canonical flow text + offset map for one chapter's [blocks] (already in source order). */
internal fun epubBuildChapterFlow(
    spineIndex: Int,
    blocks: List<EpubDisplayBlock>,
): EpubChapterFlow {
    val sb = StringBuilder()
    val segments = ArrayList<EpubFlowSegment>(blocks.size)
    blocks.forEachIndexed { i, block ->
        if (i > 0) sb.append(EPUB_FLOW_PARAGRAPH_SEPARATOR)
        val start = sb.length
        when (block) {
            is EpubDisplayBlock.Text -> sb.append(block.text)
            is EpubDisplayBlock.Image -> sb.append(EPUB_FLOW_IMAGE_CHAR)
            is EpubDisplayBlock.Break -> Unit // zero-length; separator gap above/below renders the break
        }
        segments += EpubFlowSegment(start, sb.length, block.paragraphIndex, block)
    }
    return EpubChapterFlow(spineIndex, sb.toString(), segments)
}

/**
 * Maps a layout character [offset] back to `(paragraphIndex, paragraphOffset)`.
 * Offsets that land in a separator gap snap to the next content segment (offset 0), or the last
 * segment's end when past the final content. Returns null only for an empty flow.
 */
internal fun EpubChapterFlow.paragraphAtOffset(offset: Int): Pair<Int, Int>? {
    if (segments.isEmpty()) return null
    val clamped = offset.coerceIn(0, text.length)
    // Segment containing the offset (prefer a non-empty/content segment).
    segments.firstOrNull { clamped in it.layoutStart until it.layoutEnd }?.let {
        return it.paragraphIndex to (clamped - it.layoutStart)
    }
    // In a gap (or exactly at a boundary): snap to the next content segment.
    segments.firstOrNull { it.layoutStart >= clamped && it.layoutEnd > it.layoutStart }?.let {
        return it.paragraphIndex to 0
    }
    // Past the last content: end of the last non-empty segment.
    val last = segments.lastOrNull { it.layoutEnd > it.layoutStart } ?: segments.last()
    return last.paragraphIndex to (last.layoutEnd - last.layoutStart)
}

/**
 * Inverse: the layout offset where a given `(paragraphIndex, paragraphOffset)` begins, for restoring a
 * saved Locator. Picks the content segment of that paragraph (image/text over a trailing Break).
 */
internal fun EpubChapterFlow.offsetForParagraph(paragraphIndex: Int, paragraphOffset: Int): Int {
    val candidates = segments.filter { it.paragraphIndex == paragraphIndex }
    if (candidates.isEmpty()) return 0
    val seg = candidates.firstOrNull { it.block !is EpubDisplayBlock.Break } ?: candidates.first()
    val span = (seg.layoutEnd - seg.layoutStart).coerceAtLeast(0)
    return seg.layoutStart + paragraphOffset.coerceIn(0, span)
}
