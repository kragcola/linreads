package dev.readflow.features.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderMenuEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderCommandRegistryTest {

    @Test
    fun `registry catalog order matches historical bottom row`() {
        assertEquals(
            listOf(
                ReaderCommandId.TOC,
                ReaderCommandId.SEARCH,
                ReaderCommandId.BOOKMARKS,
                ReaderCommandId.ANNOTATIONS,
                ReaderCommandId.FONT,
                ReaderCommandId.THEME,
            ),
            ReaderCommandCatalog.specs.map { it.id },
        )
    }

    @Test
    fun `default labels and panels match previous hard-coded row`() {
        val labels = ReaderCommandCatalog.specs.map { it.label }
        assertEquals(listOf("目录", "搜索", "书签", "标注", "排版", "主题"), labels)
        val byId = ReaderCommandCatalog.specs.associateBy { it.id }
        assertEquals(ReaderPanel.TOC, byId[ReaderCommandId.TOC]!!.panel)
        assertEquals(ReaderPanel.SEARCH, byId[ReaderCommandId.SEARCH]!!.panel)
        assertEquals(ReaderPanel.BOOKMARKS, byId[ReaderCommandId.BOOKMARKS]!!.panel)
        assertEquals(ReaderPanel.ANNOTATIONS, byId[ReaderCommandId.ANNOTATIONS]!!.panel)
        assertEquals(ReaderPanel.FONT, byId[ReaderCommandId.FONT]!!.panel)
        assertEquals(ReaderPanel.THEME, byId[ReaderCommandId.THEME]!!.panel)
    }

    @Test
    fun `default icons match previous hard-coded row`() {
        val byId = ReaderCommandRegistry.catalog.associateBy { it.id }
        assertEquals(Icons.Default.Menu, byId[ReaderCommandId.TOC]!!.icon)
        assertEquals(Icons.Default.Search, byId[ReaderCommandId.SEARCH]!!.icon)
        assertEquals(Icons.Default.Edit, byId[ReaderCommandId.BOOKMARKS]!!.icon)
        assertEquals(Icons.Default.Edit, byId[ReaderCommandId.ANNOTATIONS]!!.icon)
        assertEquals(Icons.Default.Edit, byId[ReaderCommandId.FONT]!!.icon)
        assertEquals(Icons.Default.MoreVert, byId[ReaderCommandId.THEME]!!.icon)
    }

    @Test
    fun `command to intent mapping preserves existing panel and font theme intents`() {
        assertEquals(ReaderIntent.OpenPanel(ReaderPanel.TOC), readerCommandIntent(ReaderCommandId.TOC))
        assertEquals(ReaderIntent.OpenPanel(ReaderPanel.SEARCH), readerCommandIntent(ReaderCommandId.SEARCH))
        assertEquals(
            ReaderIntent.OpenPanel(ReaderPanel.BOOKMARKS),
            readerCommandIntent(ReaderCommandId.BOOKMARKS),
        )
        assertEquals(
            ReaderIntent.OpenPanel(ReaderPanel.ANNOTATIONS),
            readerCommandIntent(ReaderCommandId.ANNOTATIONS),
        )
        assertEquals(ReaderIntent.FontPanel, readerCommandIntent(ReaderCommandId.FONT))
        assertEquals(ReaderIntent.ThemePanel, readerCommandIntent(ReaderCommandId.THEME))
    }

    @Test
    fun `feature filter intersection hides disabled features while keeping order`() {
        val features = setOf(ReaderFeature.TOC, ReaderFeature.FONT, ReaderFeature.THEME)
        val visible = ReaderCommandCatalog.visibleSpecs(
            config = ReaderMenuConfig.v1Defaults(),
            features = features,
        )
        assertEquals(
            listOf(ReaderCommandId.TOC, ReaderCommandId.FONT, ReaderCommandId.THEME),
            visible.map { it.id },
        )
    }

    @Test
    fun `hidden menu entries are excluded even when feature is enabled`() {
        val config = ReaderMenuConfig(
            version = 1,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = false),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
        )
        val visible = ReaderCommandCatalog.visibleSpecs(
            config = config,
            features = ReaderFeature.entries.toSet(),
        )
        assertEquals(
            listOf(
                ReaderCommandId.TOC,
                ReaderCommandId.BOOKMARKS,
                ReaderCommandId.ANNOTATIONS,
                ReaderCommandId.THEME,
            ),
            visible.map { it.id },
        )
        assertFalse(visible.any { it.id == ReaderCommandId.SEARCH })
        assertFalse(visible.any { it.id == ReaderCommandId.FONT })
    }

    @Test
    fun `persisted order is respected after feature filter`() {
        val config = ReaderMenuConfig(
            version = 1,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
            ),
        )
        val visible = ReaderCommandCatalog.visibleSpecs(
            config = config,
            features = setOf(ReaderFeature.THEME, ReaderFeature.TOC, ReaderFeature.SEARCH),
        )
        assertEquals(
            listOf(ReaderCommandId.THEME, ReaderCommandId.TOC, ReaderCommandId.SEARCH),
            visible.map { it.id },
        )
    }

    @Test
    fun `default visible commands match previous hard-coded six when all features enabled`() {
        val visible = defaultVisibleReaderCommands()
        assertEquals(6, visible.size)
        assertEquals(
            listOf("目录", "搜索", "书签", "标注", "排版", "主题"),
            visible.map { it.label },
        )
        assertTrue(readerCommandSelected(ReaderCommandId.TOC, ReaderPanel.TOC))
        assertFalse(readerCommandSelected(ReaderCommandId.TOC, ReaderPanel.SEARCH))
        assertTrue(readerCommandSelected(ReaderCommandId.FONT, ReaderPanel.FONT))
        assertTrue(readerCommandSelected(ReaderCommandId.THEME, ReaderPanel.THEME))
    }
}
