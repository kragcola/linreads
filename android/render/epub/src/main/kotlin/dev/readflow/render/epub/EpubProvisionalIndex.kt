package dev.readflow.render.epub

internal data class EpubProvisionalSpine(
    val spineIndex: Int,
    val paras: List<EpubPara>,
    val blocks: List<EpubDisplayBlock>,
)

internal class EpubProvisionalIndex(spineEntryWeights: List<Long>) {
    private val weights = spineEntryWeights.map { it.coerceAtLeast(1L) }
    private val totalWeight = weights.sum().coerceAtLeast(1L)
    private val contentBySpine = mutableMapOf<Int, EpubProvisionalSpine>()
    private val baseBySpine = mutableMapOf<Int, Int>()

    @Synchronized
    fun installInitial(content: EpubProvisionalSpine, globalParagraphBase: Int): Boolean {
        if (content.spineIndex !in weights.indices || globalParagraphBase < 0) return false
        val existing = contentBySpine[content.spineIndex]
        if (existing != null) {
            return baseBySpine[content.spineIndex] == globalParagraphBase &&
                existing.paras.size == content.paras.size
        }
        val proposed = globalParagraphBase until (globalParagraphBase + content.paras.size)
        if (occupiedRanges().any { rangesOverlap(it, proposed) }) return false
        contentBySpine[content.spineIndex] = content
        baseBySpine[content.spineIndex] = globalParagraphBase
        return true
    }

    @Synchronized
    fun installAdjacent(content: EpubProvisionalSpine, sourceSpineIndex: Int): Int? {
        if (kotlin.math.abs(content.spineIndex - sourceSpineIndex) != 1) return null
        val source = contentBySpine[sourceSpineIndex] ?: return null
        val sourceBase = baseBySpine[sourceSpineIndex] ?: return null
        val targetBase = if (content.spineIndex > sourceSpineIndex) {
            sourceBase + source.paras.size
        } else {
            sourceBase - content.paras.size
        }
        if (targetBase < 0) return null
        return if (installInitial(content, targetBase)) targetBase else null
    }

    @Synchronized
    fun globalBase(spineIndex: Int): Int? = baseBySpine[spineIndex]

    @Synchronized
    fun globalParagraphIndex(spineIndex: Int, localParagraphIndex: Int): Int? {
        val content = contentBySpine[spineIndex] ?: return null
        if (localParagraphIndex !in content.paras.indices) return null
        return (baseBySpine[spineIndex] ?: return null) + localParagraphIndex
    }

    @Synchronized
    fun contentForSpine(spineIndex: Int): EpubProvisionalSpine? = contentBySpine[spineIndex]

    @Synchronized
    fun blocksForSpine(spineIndex: Int): List<EpubDisplayBlock> {
        val content = contentBySpine[spineIndex] ?: return emptyList()
        val base = baseBySpine[spineIndex] ?: return emptyList()
        return content.blocks.map { block -> block.withParagraphOffset(base) }
    }

    @Synchronized
    fun parasSnapshot(): List<EpubPara> {
        val size = contentBySpine.keys.maxOfOrNull { spineIndex ->
            (baseBySpine[spineIndex] ?: 0) + contentBySpine.getValue(spineIndex).paras.size
        } ?: return emptyList()
        val placeholder = EpubPara(spineIndex = 0, text = "")
        return MutableList(size) { placeholder }.also { result ->
            contentBySpine.forEach { (spineIndex, content) ->
                val base = baseBySpine.getValue(spineIndex)
                content.paras.forEachIndexed { localIndex, para ->
                    result[base + localIndex] = para.copy(text = "")
                }
            }
        }
    }

    fun approximateProgression(spineIndex: Int, spineFraction: Float): Float {
        if (spineIndex !in weights.indices) return 0f
        val prefix = weights.take(spineIndex).sum()
        val within = weights[spineIndex] * spineFraction.coerceIn(0f, 1f)
        return ((prefix + within) / totalWeight.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    @Synchronized
    private fun occupiedRanges(): List<IntRange> = contentBySpine.keys.map { spineIndex ->
        val base = baseBySpine.getValue(spineIndex)
        base until (base + contentBySpine.getValue(spineIndex).paras.size)
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first < second.last + 1 && second.first < first.last + 1

    private fun EpubDisplayBlock.withParagraphOffset(offset: Int): EpubDisplayBlock =
        when (this) {
            is EpubDisplayBlock.Text -> copy(paragraphIndex = paragraphIndex + offset)
            is EpubDisplayBlock.Image -> copy(paragraphIndex = paragraphIndex + offset)
            is EpubDisplayBlock.Break -> copy(paragraphIndex = paragraphIndex + offset)
        }
}
