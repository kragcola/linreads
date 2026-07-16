package dev.readflow.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stable reader bottom-command identity. Wire IDs never encode display position
 * or localized label text — only catalog membership.
 */
enum class ReaderCommandId(val wireId: String) {
    TOC("TOC"),
    SEARCH("SEARCH"),
    BOOKMARKS("BOOKMARKS"),
    ANNOTATIONS("ANNOTATIONS"),
    FONT("FONT"),
    THEME("THEME"),
    ;

    companion object {
        fun fromWireId(raw: String?): ReaderCommandId? =
            entries.firstOrNull { it.wireId == raw }
    }
}

data class ReaderMenuEntry(
    val id: ReaderCommandId,
    val visible: Boolean = true,
)

/**
 * Versioned persisted reader-menu configuration.
 * Catalog is ordered ID + visibility for every known command.
 */
data class ReaderMenuConfig(
    val version: Int = VERSION_V1,
    val entries: List<ReaderMenuEntry> = emptyList(),
) {
    fun encode(): String = Companion.encode(this)

    companion object {
        const val VERSION_V1: Int = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = false
        }

        fun v1Defaults(): ReaderMenuConfig = ReaderMenuConfig(
            version = VERSION_V1,
            entries = listOf(
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
            ),
        )

        fun encode(config: ReaderMenuConfig): String =
            json.encodeToString(config.toDto())

        /**
         * Decode raw payload then resolve to a complete, catalog-valid config.
         * Null / blank / corrupt payloads recover to [v1Defaults].
         */
        fun decodeOrDefaults(raw: String?): ReaderMenuConfig {
            if (raw.isNullOrBlank()) return v1Defaults()
            val dto = runCatching { json.decodeFromString<ReaderMenuConfigDto>(raw) }.getOrNull()
                ?: return v1Defaults()
            // Empty/missing entries after parse is treated as corrupt/empty payload.
            if (dto.entries.isNullOrEmpty()) return v1Defaults()
            val partial = ReaderMenuConfig(
                version = dto.version,
                entries = dto.entries.mapNotNull { entry ->
                    val id = ReaderCommandId.fromWireId(entry.id) ?: return@mapNotNull null
                    ReaderMenuEntry(id = id, visible = entry.visible)
                },
            )
            // All IDs unknown → deterministic recovery to defaults.
            if (partial.entries.isEmpty()) return v1Defaults()
            return resolve(partial)
        }

        /**
         * Drop unknown/duplicate IDs, keep first-seen order/visibility for known IDs,
         * append missing catalog IDs using current defaults, and normalize version.
         */
        fun resolve(config: ReaderMenuConfig): ReaderMenuConfig {
            val defaults = v1Defaults()
            val defaultById = defaults.entries.associateBy { it.id }
            val seen = linkedSetOf<ReaderCommandId>()
            val kept = ArrayList<ReaderMenuEntry>(defaults.entries.size)
            for (entry in config.entries) {
                if (entry.id !in defaultById) continue
                if (!seen.add(entry.id)) continue
                kept += entry
            }
            for (defaultEntry in defaults.entries) {
                if (defaultEntry.id !in seen) {
                    kept += defaultEntry
                }
            }
            return ReaderMenuConfig(version = VERSION_V1, entries = kept)
        }

        private fun ReaderMenuConfig.toDto(): ReaderMenuConfigDto = ReaderMenuConfigDto(
            version = version,
            entries = entries.map { ReaderMenuEntryDto(id = it.id.wireId, visible = it.visible) },
        )
    }
}

@Serializable
private data class ReaderMenuEntryDto(
    val id: String,
    val visible: Boolean = true,
)

@Serializable
private data class ReaderMenuConfigDto(
    val version: Int = ReaderMenuConfig.VERSION_V1,
    val entries: List<ReaderMenuEntryDto>? = null,
)
