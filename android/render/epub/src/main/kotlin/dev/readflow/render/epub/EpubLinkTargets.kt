package dev.readflow.render.epub

internal fun epubInternalLinkTargetIndexes(
    spinePaths: List<String>,
    paras: List<EpubPara>,
    fragmentTargets: Map<String, EpubTargetPosition> = emptyMap(),
): Map<String, EpubTargetPosition> {
    if (spinePaths.isEmpty() || paras.isEmpty()) return emptyMap()
    val firstParaIndexBySpine = mutableMapOf<Int, Int>()
    paras.forEachIndexed { index, para ->
        firstParaIndexBySpine.putIfAbsent(para.spineIndex, index)
    }
    val spineTargets = spinePaths.mapIndexedNotNull { spineIndex, path ->
        val paraIndex = firstParaIndexBySpine[spineIndex] ?: return@mapIndexedNotNull null
        epubNormalizePath(path) to EpubTargetPosition(paraIndex)
    }.toMap()
    return spineTargets + fragmentTargets
}

internal fun epubInternalLinkTargetKey(href: String): String {
    val path = epubHrefPath(href)
    if (path.isEmpty()) return ""
    val fragment = href.substringAfter('#', missingDelimiterValue = "").substringBefore('?').trim()
    val normalizedPath = epubNormalizePath(path)
    return if (fragment.isEmpty()) normalizedPath else "$normalizedPath#$fragment"
}

internal fun epubFragmentTargetIndexes(
    spinePaths: List<String>,
    items: List<EpubReaderItem>,
): Map<String, EpubTargetPosition> {
    if (spinePaths.isEmpty() || items.isEmpty()) return emptyMap()
    val paragraphPlan = epubParagraphPlan(items)
    val paragraphTextLengths = paragraphPlan.paragraphTexts
        .mapIndexed { paragraphIndex, text -> paragraphIndex to text.length }
        .toMap()
    val nextImageAnchorOffsets = mutableMapOf<Int, Int>()
    return buildMap {
        items.forEachIndexed { itemIndex, item ->
            val spinePath = spinePaths.getOrNull(item.locator.spineIndex)
            val paragraphIndex = paragraphPlan.itemParagraphIndexes[itemIndex]
            val target = when (item) {
                is EpubReaderItem.Heading,
                is EpubReaderItem.Text,
                -> EpubTargetPosition(paragraphIndex)
                is EpubReaderItem.Image -> {
                    val imageAnchorOffset = nextImageAnchorOffsets.getOrPut(paragraphIndex) {
                        paragraphTextLengths[paragraphIndex] ?: 0
                    }
                    nextImageAnchorOffsets[paragraphIndex] = imageAnchorOffset + 1
                    EpubTargetPosition(
                        paragraphIndex = paragraphIndex,
                        paragraphOffset = imageAnchorOffset,
                    )
                }
                is EpubReaderItem.Break -> EpubTargetPosition(paragraphIndex)
            }
            val fragmentIds = when (item) {
                is EpubReaderItem.Heading -> item.fragmentIds
                is EpubReaderItem.Text -> item.fragmentIds
                is EpubReaderItem.Image -> item.fragmentIds
                is EpubReaderItem.Break -> emptyList()
            }
            if (spinePath == null) return@forEachIndexed
            fragmentIds.forEach { id ->
                put("${epubNormalizePath(spinePath)}#$id", target)
            }
        }
    }
}
