package dev.readflow.render.epub

internal data class EpubParagraphPlan(
    val itemParagraphIndexes: List<Int>,
    val paragraphSpineIndexes: List<Int>,
    val paragraphTexts: List<String>,
)

internal fun epubParagraphPlan(items: List<EpubReaderItem>): EpubParagraphPlan {
    if (items.isEmpty()) {
        return EpubParagraphPlan(
            itemParagraphIndexes = emptyList(),
            paragraphSpineIndexes = emptyList(),
            paragraphTexts = emptyList(),
        )
    }

    val itemParagraphIndexes = MutableList(items.size) { 0 }
    val paragraphSpineIndexes = mutableListOf<Int>()
    val paragraphTexts = mutableListOf<String>()
    var nextParagraphIndex = 0
    var currentSpineIndex = items.first().locator.spineIndex
    var currentSpineParagraphIndex: Int? = null
    val pendingBreakIndexes = mutableListOf<Int>()

    fun ensureSyntheticParagraph(spineIndex: Int): Int {
        currentSpineParagraphIndex?.let { return it }
        val paragraphIndex = nextParagraphIndex++
        currentSpineParagraphIndex = paragraphIndex
        paragraphSpineIndexes += spineIndex
        paragraphTexts += ""
        return paragraphIndex
    }

    fun addTextParagraph(spineIndex: Int, text: String): Int {
        val paragraphIndex = nextParagraphIndex++
        currentSpineParagraphIndex = paragraphIndex
        paragraphSpineIndexes += spineIndex
        paragraphTexts += text
        return paragraphIndex
    }

    fun bindPendingBreaks(paragraphIndex: Int) {
        pendingBreakIndexes.forEach { breakIndex ->
            itemParagraphIndexes[breakIndex] = paragraphIndex
        }
        pendingBreakIndexes.clear()
    }

    fun flushPendingBreaks(spineIndex: Int) {
        if (pendingBreakIndexes.isEmpty()) return
        val paragraphIndex = currentSpineParagraphIndex ?: ensureSyntheticParagraph(spineIndex)
        bindPendingBreaks(paragraphIndex)
    }

    items.forEachIndexed { itemIndex, item ->
        val spineIndex = item.locator.spineIndex
        if (spineIndex != currentSpineIndex) {
            flushPendingBreaks(currentSpineIndex)
            currentSpineIndex = spineIndex
            currentSpineParagraphIndex = null
        }
        itemParagraphIndexes[itemIndex] = when (item) {
            is EpubReaderItem.Heading -> addTextParagraph(spineIndex, item.text).also(::bindPendingBreaks)
            is EpubReaderItem.Text -> addTextParagraph(spineIndex, item.text).also(::bindPendingBreaks)
            is EpubReaderItem.Image -> ensureSyntheticParagraph(spineIndex).also(::bindPendingBreaks)
            is EpubReaderItem.Break -> {
                val paragraphIndex = currentSpineParagraphIndex
                if (paragraphIndex == null) {
                    pendingBreakIndexes += itemIndex
                    0
                } else {
                    paragraphIndex
                }
            }
        }
    }
    flushPendingBreaks(currentSpineIndex)

    return EpubParagraphPlan(
        itemParagraphIndexes = itemParagraphIndexes,
        paragraphSpineIndexes = paragraphSpineIndexes,
        paragraphTexts = paragraphTexts,
    )
}
