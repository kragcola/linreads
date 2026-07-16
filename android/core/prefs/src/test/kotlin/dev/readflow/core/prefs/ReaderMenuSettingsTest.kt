package dev.readflow.core.prefs

import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderMenuEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderMenuSettingsTest {

    @Test
    fun `missing stored payload resolves to v1 defaults`() {
        assertEquals(ReaderMenuConfig.v1Defaults(), resolvedReaderMenuConfig(null))
        assertEquals(ReaderMenuConfig.v1Defaults(), resolvedReaderMenuConfig(""))
        assertEquals(ReaderMenuConfig.v1Defaults(), resolvedReaderMenuConfig("not-json"))
    }

    @Test
    fun `stored payload is decoded through model codec not ad-hoc parse`() {
        val stored = ReaderMenuConfig.encode(
            ReaderMenuConfig(
                version = 1,
                entries = listOf(
                    ReaderMenuEntry(ReaderCommandId.THEME, visible = false),
                    ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ),
            ),
        )
        val resolved = resolvedReaderMenuConfig(stored)
        assertEquals(ReaderCommandId.THEME, resolved.entries.first().id)
        assertFalse(resolved.entries.first().visible)
        assertTrue(resolved.entries.any { it.id == ReaderCommandId.SEARCH && it.visible })
    }

    @Test
    fun `repository flow exposes resolved defaults when unset`() = runBlocking {
        val repo = InMemoryReaderMenuSettingsRepository()
        assertEquals(ReaderMenuConfig.v1Defaults(), repo.readerMenuConfig.first())
    }

    @Test
    fun `repository setter round-trips resolved order and visibility`() = runBlocking {
        val repo = InMemoryReaderMenuSettingsRepository()
        val desired = ReaderMenuConfig.resolve(
            ReaderMenuConfig(
                version = 1,
                entries = listOf(
                    ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                    ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                    ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ),
            ),
        )
        repo.setReaderMenuConfig(desired)
        val loaded = repo.readerMenuConfig.first()
        assertEquals(desired.entries, loaded.entries)
        assertEquals(ReaderMenuConfig.VERSION_V1, loaded.version)
        // Raw payload is model-encoded structured JSON under the prefs key surface.
        assertTrue(repo.rawStored!!.contains("\"FONT\""))
        assertTrue(repo.rawStored!!.contains("\"visible\":false"))
    }

    @Test
    fun `setter always persists resolved canonical config`() = runBlocking {
        val repo = InMemoryReaderMenuSettingsRepository()
        repo.setReaderMenuConfig(
            ReaderMenuConfig(
                version = 7,
                entries = listOf(
                    ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = false),
                    ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ),
            ),
        )
        val loaded = repo.readerMenuConfig.first()
        assertEquals(ReaderMenuConfig.VERSION_V1, loaded.version)
        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = false),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
            loaded.entries,
        )
    }
}

/**
 * Small in-memory stand-in for DataStore-backed prefs when Android harness is absent.
 * Mirrors store-as-string + resolve-on-read behavior of [DataStoreSettingsRepository].
 */
internal class InMemoryReaderMenuSettingsRepository {
    private val stored = MutableStateFlow<String?>(null)

    val rawStored: String? get() = stored.value

    val readerMenuConfig = kotlinx.coroutines.flow.flow {
        // Same resolve-on-read path as DataStoreSettingsRepository.readerMenuConfig.
        emit(resolvedReaderMenuConfig(stored.value))
        stored.collect { emit(resolvedReaderMenuConfig(it)) }
    }

    suspend fun setReaderMenuConfig(config: ReaderMenuConfig) {
        val canonical = ReaderMenuConfig.resolve(config)
        stored.value = ReaderMenuConfig.encode(canonical)
    }
}
