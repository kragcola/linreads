package dev.readflow.render.epub

internal data class EpubParsedSpine(
    val spineIndex: Int,
    val path: String,
    val items: List<EpubReaderItem>,
    val paras: List<EpubPara>,
    val blocks: List<EpubDisplayBlock>,
    val fragmentTargetIndexes: Map<String, EpubTargetPosition>,
    val charCount: Int,
    /** Book-scoped @font-face map collected from this spine's stylesheets (first face per family). */
    val bookFontMap: EpubBookFontMap = EpubBookFontMap.EMPTY,
) {
    companion object {
        fun empty(spineIndex: Int, path: String = ""): EpubParsedSpine =
            EpubParsedSpine(
                spineIndex = spineIndex,
                path = path,
                items = emptyList(),
                paras = emptyList(),
                blocks = emptyList(),
                fragmentTargetIndexes = emptyMap(),
                charCount = 0,
                bookFontMap = EpubBookFontMap.EMPTY,
            )
    }
}
