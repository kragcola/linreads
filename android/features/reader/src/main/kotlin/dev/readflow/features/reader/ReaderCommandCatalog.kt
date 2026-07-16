package dev.readflow.features.reader

import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig

/**
 * Pure Stage-0 command catalog row (no Compose / Material icons).
 * Labels and panel mapping must stay identical to the historical hard-coded bottom bar.
 */
data class ReaderCommandSpec(
    val id: ReaderCommandId,
    val label: String,
    val panel: ReaderPanel,
    val feature: ReaderFeature,
)

/** Ordered v1 catalog — identity is [ReaderCommandId], never position or label text. */
object ReaderCommandCatalog {
    val specs: List<ReaderCommandSpec> = listOf(
        ReaderCommandSpec(ReaderCommandId.TOC, "目录", ReaderPanel.TOC, ReaderFeature.TOC),
        ReaderCommandSpec(ReaderCommandId.SEARCH, "搜索", ReaderPanel.SEARCH, ReaderFeature.SEARCH),
        ReaderCommandSpec(ReaderCommandId.BOOKMARKS, "书签", ReaderPanel.BOOKMARKS, ReaderFeature.BOOKMARKS),
        ReaderCommandSpec(
            ReaderCommandId.ANNOTATIONS,
            "标注",
            ReaderPanel.ANNOTATIONS,
            ReaderFeature.ANNOTATIONS,
        ),
        ReaderCommandSpec(ReaderCommandId.FONT, "排版", ReaderPanel.FONT, ReaderFeature.FONT),
        ReaderCommandSpec(ReaderCommandId.THEME, "主题", ReaderPanel.THEME, ReaderFeature.THEME),
    )

    private val byId: Map<ReaderCommandId, ReaderCommandSpec> = specs.associateBy { it.id }

    fun spec(id: ReaderCommandId): ReaderCommandSpec? = byId[id]

    /**
     * Intersect persisted menu config with the caller/test [features] filter.
     * Hidden entries and entries whose feature is disabled are excluded;
     * remaining entries keep the resolved config order.
     */
    fun visibleSpecs(
        config: ReaderMenuConfig,
        features: Set<ReaderFeature>,
    ): List<ReaderCommandSpec> {
        val resolved = ReaderMenuConfig.resolve(config)
        return resolved.entries.mapNotNull { entry ->
            if (!entry.visible) return@mapNotNull null
            val spec = byId[entry.id] ?: return@mapNotNull null
            if (spec.feature !in features) return@mapNotNull null
            spec
        }
    }
}

/**
 * Pure command-id → [ReaderIntent] mapping. Mirrors the previous hard-coded
 * onClick intents: panels via OpenPanel, FONT/THEME via dedicated intents.
 */
fun readerCommandIntent(id: ReaderCommandId): ReaderIntent = when (id) {
    ReaderCommandId.TOC -> ReaderIntent.OpenPanel(ReaderPanel.TOC)
    ReaderCommandId.SEARCH -> ReaderIntent.OpenPanel(ReaderPanel.SEARCH)
    ReaderCommandId.BOOKMARKS -> ReaderIntent.OpenPanel(ReaderPanel.BOOKMARKS)
    ReaderCommandId.ANNOTATIONS -> ReaderIntent.OpenPanel(ReaderPanel.ANNOTATIONS)
    ReaderCommandId.FONT -> ReaderIntent.FontPanel
    ReaderCommandId.THEME -> ReaderIntent.ThemePanel
}

/** Active-panel highlight mapping used by the bottom row. */
fun readerCommandSelected(id: ReaderCommandId, activePanel: ReaderPanel?): Boolean {
    val panel = ReaderCommandCatalog.spec(id)?.panel ?: return false
    return panel == activePanel
}

/** Convenience: default v1 config with all features enabled matches old row. */
fun defaultVisibleReaderCommands(
    features: Set<ReaderFeature> = ReaderFeature.entries.toSet(),
): List<ReaderCommandSpec> =
    ReaderCommandCatalog.visibleSpecs(ReaderMenuConfig.v1Defaults(), features)
