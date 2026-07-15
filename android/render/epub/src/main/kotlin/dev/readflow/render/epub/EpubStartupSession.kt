package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import java.util.concurrent.FutureTask

internal data class EpubStartupSpine(
    val parsed: EpubParsedSpine,
    val globalParagraphBase: Int,
    val globalParas: List<EpubPara>,
    val globalBlocks: List<EpubDisplayBlock>,
)

internal data class EpubStartupResolvedSection(
    val anchor: EpubAnchor,
    val globalParagraphIndex: Int,
)

internal class EpubStartupSession(
    val packageIndex: EpubPackageIndex,
    private val spineLoader: (Int) -> EpubParsedSpine,
) {
    private val provisionalIndex = EpubProvisionalIndex(packageIndex.spineEntryWeights)
    private val parsedSpines = mutableMapOf<Int, EpubParsedSpine>()
    private val parseTasks = mutableMapOf<Int, FutureTask<EpubParsedSpine>>()
    @Volatile
    private var closed = false

    fun installInitial(parsed: EpubParsedSpine, globalParagraphBase: Int): Boolean {
        if (closed) return false
        rememberParsed(parsed)
        if (closed) return false
        return provisionalIndex.installInitial(parsed.toProvisionalSpine(), globalParagraphBase)
    }

    fun ensureAdjacent(sourceSpineIndex: Int, targetSpineIndex: Int): EpubStartupSpine? {
        if (closed) return null
        val parsed = ensureParsed(targetSpineIndex) ?: return null
        if (closed) return null
        val base = provisionalIndex.installAdjacent(parsed.toProvisionalSpine(), sourceSpineIndex) ?: return null
        return parsed.toStartupSpine(base)
    }

    fun ensureSection(section: LocatorStrategy.Section): EpubStartupResolvedSection? {
        if (closed) return null
        val parsed = ensureParsed(section.spineIndex) ?: return null
        if (closed) return null
        val resolved = resolveProvisionalSection(section, parsed.paras) ?: return null
        val existingBase = provisionalIndex.globalBase(section.spineIndex)
        if (existingBase == null) {
            if (!installInitial(parsed, resolved.globalParagraphBase)) return null
        } else if (existingBase != resolved.globalParagraphBase) {
            return null
        }
        return EpubStartupResolvedSection(
            anchor = resolved.anchor,
            globalParagraphIndex = resolved.globalParagraphBase + resolved.anchor.localParagraphIndex,
        )
    }

    fun startupSpine(spineIndex: Int): EpubStartupSpine? {
        val parsed = synchronized(this) { parsedSpines[spineIndex] } ?: return null
        val base = provisionalIndex.globalBase(spineIndex) ?: return null
        return parsed.toStartupSpine(base)
    }

    fun globalBase(spineIndex: Int): Int? = provisionalIndex.globalBase(spineIndex)

    fun globalParagraphIndex(spineIndex: Int, localParagraphIndex: Int): Int? =
        provisionalIndex.globalParagraphIndex(spineIndex, localParagraphIndex)

    fun parasSnapshot(): List<EpubPara> = provisionalIndex.parasSnapshot()

    fun blocksForSpine(spineIndex: Int): List<EpubDisplayBlock> = provisionalIndex.blocksForSpine(spineIndex)

    fun approximateProgression(spineIndex: Int, spineFraction: Float): Float =
        provisionalIndex.approximateProgression(spineIndex, spineFraction)

    fun fragmentTargetsForSpine(spineIndex: Int): Map<String, EpubTargetPosition> {
        val parsed = synchronized(this) { parsedSpines[spineIndex] } ?: return emptyMap()
        val base = provisionalIndex.globalBase(spineIndex) ?: return emptyMap()
        return parsed.fragmentTargetIndexes.mapValues { (_, target) ->
            target.copy(paragraphIndex = base + target.paragraphIndex)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        parseTasks.values.forEach { it.cancel(true) }
        parseTasks.clear()
        parsedSpines.clear()
    }

    private fun ensureParsed(spineIndex: Int): EpubParsedSpine? {
        synchronized(this) { parsedSpines[spineIndex] }?.let { return it }
        val task = synchronized(this) {
            if (closed) return null
            parseTasks.getOrPut(spineIndex) {
                FutureTask { spineLoader(spineIndex) }
            }
        }
        task.run()
        val parsed = runCatching { task.get() }.getOrElse { EpubParsedSpine.empty(spineIndex) }
        return synchronized(this) {
            parseTasks.remove(spineIndex, task)
            if (closed) return@synchronized null
            parsedSpines.putIfAbsent(spineIndex, parsed)
            parsedSpines.getValue(spineIndex)
        }
    }

    @Synchronized
    private fun rememberParsed(parsed: EpubParsedSpine) {
        if (closed) return
        parsedSpines.putIfAbsent(parsed.spineIndex, parsed)
    }

    private fun EpubParsedSpine.toProvisionalSpine(): EpubProvisionalSpine =
        EpubProvisionalSpine(spineIndex = spineIndex, paras = paras, blocks = blocks)

    private fun EpubParsedSpine.toStartupSpine(base: Int): EpubStartupSpine =
        EpubStartupSpine(
            parsed = this,
            globalParagraphBase = base,
            globalParas = paras.map { para -> para.copy(text = "") },
            globalBlocks = provisionalIndex.blocksForSpine(spineIndex),
        )
}
