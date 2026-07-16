package dev.readflow.features.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.graphics.vector.ImageVector
import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig

/**
 * UI metadata for a stable reader command. Lives in features:reader only —
 * render engines must not depend on command types.
 */
data class ReaderCommandDescriptor(
    val id: ReaderCommandId,
    val label: String,
    val icon: ImageVector,
    val panel: ReaderPanel?,
    val feature: ReaderFeature,
)

/**
 * Catalog of Stage-0 bottom commands with Material icons.
 * Order matches the historical hard-coded row and [ReaderCommandCatalog].
 */
object ReaderCommandRegistry {
    private val icons: Map<ReaderCommandId, ImageVector> = mapOf(
        ReaderCommandId.TOC to Icons.Default.Menu,
        ReaderCommandId.SEARCH to Icons.Default.Search,
        ReaderCommandId.BOOKMARKS to Icons.Default.Bookmark,
        ReaderCommandId.ANNOTATIONS to Icons.Default.Edit,
        ReaderCommandId.FONT to Icons.Outlined.TextFields,
        ReaderCommandId.THEME to Icons.Outlined.Palette,
    )

    val catalog: List<ReaderCommandDescriptor> =
        ReaderCommandCatalog.specs.map { spec ->
            ReaderCommandDescriptor(
                id = spec.id,
                label = spec.label,
                icon = icons.getValue(spec.id),
                panel = spec.panel,
                feature = spec.feature,
            )
        }

    private val byId: Map<ReaderCommandId, ReaderCommandDescriptor> =
        catalog.associateBy { it.id }

    fun descriptor(id: ReaderCommandId): ReaderCommandDescriptor? = byId[id]

    /**
     * Intersect persisted menu config with the caller/test [features] filter.
     * Hidden entries and entries whose feature is disabled are excluded;
     * remaining entries keep the resolved config order.
     */
    fun visibleCommands(
        config: ReaderMenuConfig,
        features: Set<ReaderFeature>,
    ): List<ReaderCommandDescriptor> =
        ReaderCommandCatalog.visibleSpecs(config, features).map { spec ->
            byId.getValue(spec.id)
        }
}
