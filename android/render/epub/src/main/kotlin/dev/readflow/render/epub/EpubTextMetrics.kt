package dev.readflow.render.epub

internal fun epubParasWithCharacterOffsets(spineParagraphs: List<List<String>>): List<EpubPara> =
    buildList {
        var documentOffset = 0
        spineParagraphs.forEachIndexed { spineIndex, paragraphs ->
            var spineOffset = 0
            paragraphs
                .forEach { text ->
                    val charCount = text.length
                    add(
                        EpubPara(
                            spineIndex = spineIndex,
                            text = text,
                            spineCharStart = spineOffset,
                            spineCharEnd = spineOffset + charCount,
                            documentCharStart = documentOffset,
                            documentCharEnd = documentOffset + charCount,
                        ),
                    )
                    spineOffset += charCount
                    documentOffset += charCount
                }
        }
    }

internal fun epubParasFromParagraphPlan(plan: EpubParagraphPlan): List<EpubPara> =
    buildList {
        if (plan.paragraphSpineIndexes.isEmpty()) return@buildList
        val spineOffsets = mutableMapOf<Int, Int>()
        var documentOffset = 0
        plan.paragraphSpineIndexes.forEachIndexed { paragraphIndex, spineIndex ->
            val text = plan.paragraphTexts[paragraphIndex]
            val charCount = text.length
            val spineOffset = spineOffsets[spineIndex] ?: 0
            add(
                EpubPara(
                    spineIndex = spineIndex,
                    text = text,
                    spineCharStart = spineOffset,
                    spineCharEnd = spineOffset + charCount,
                    documentCharStart = documentOffset,
                    documentCharEnd = documentOffset + charCount,
                ),
            )
            spineOffsets[spineIndex] = spineOffset + charCount
            documentOffset += charCount
        }
    }

internal fun epubSpineCharCounts(paras: List<EpubPara>): List<Int> {
    val maxSpine = paras.maxOfOrNull { it.spineIndex } ?: return emptyList()
    val counts = MutableList(maxSpine + 1) { 0 }
    paras.forEach { para ->
        counts[para.spineIndex] = maxOf(counts[para.spineIndex], para.spineCharEnd)
    }
    return counts
}

internal fun epubTotalChars(paras: List<EpubPara>): Int =
    paras.maxOfOrNull { it.documentCharEnd } ?: 0
