package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy

internal data class EpubAnchor(
    val spineIndex: Int,
    val localParagraphIndex: Int,
    val paragraphOffset: Int,
)

internal data class EpubProvisionalSection(
    val anchor: EpubAnchor,
    val globalParagraphBase: Int,
)

internal fun resolveProvisionalSection(
    section: LocatorStrategy.Section,
    localParas: List<EpubPara>,
): EpubProvisionalSection? {
    if (localParas.isEmpty() || localParas.any { it.spineIndex != section.spineIndex }) return null
    val charOffset = section.charOffset.coerceAtLeast(0)
    val candidates = localParas.indices.filter { index ->
        val para = localParas[index]
        when {
            para.spineCharStart == para.spineCharEnd -> para.spineCharStart == charOffset
            charOffset in para.spineCharStart until para.spineCharEnd -> true
            else -> false
        }
    }.toMutableList()
    if (candidates.isEmpty()) {
        val maxEnd = localParas.maxOf(EpubPara::spineCharEnd)
        if (charOffset == maxEnd) {
            candidates += localParas.indices.filter { index ->
                val para = localParas[index]
                para.spineCharEnd == charOffset && para.spineCharEnd > para.spineCharStart
            }
        }
    }
    if (candidates.size != 1) return null
    val localIndex = candidates.single()
    val para = localParas[localIndex]
    val base = section.elementIndex - localIndex
    if (base < 0) return null
    return EpubProvisionalSection(
        anchor = EpubAnchor(
            spineIndex = section.spineIndex,
            localParagraphIndex = localIndex,
            paragraphOffset = (charOffset - para.spineCharStart)
                .coerceIn(0, (para.spineCharEnd - para.spineCharStart).coerceAtLeast(0)),
        ),
        globalParagraphBase = base,
    )
}
