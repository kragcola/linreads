package dev.readflow.render.epub

/**
 * UI-facing status for one CSS `font-family` name declared via `@font-face` or
 * referenced in content styles for the current open book.
 *
 * Priority when resolving typefaces (see [resolveEpubCssTypeface] / merge helpers):
 * book custom map > global map > embedded CSS face > user/system default.
 */
enum class EpubCssFontMappingStatus {
    /** Book-scoped user mapping is present (may still soft-fallback if font file missing). */
    BOOK_MAPPED,
    /** Global EPUB replacement is present for this family. */
    GLOBAL_MAPPED,
    /** CSS `@font-face` embeds a loadable local face for this family. */
    EMBEDDED,
    /** Family is known from CSS but has no mapping and no usable embedded face. */
    UNRESOLVED,
}

/**
 * One CSS family entry for book-font settings UI.
 *
 * [family] is the canonical key (lowercase, unquoted). [displayName] prefers the
 * first raw family token as seen in CSS when available.
 */
data class EpubCssFontFamilyEntry(
    val family: String,
    val displayName: String,
    val status: EpubCssFontMappingStatus,
    val mappedFontId: String? = null,
    val embeddedSrcPath: String? = null,
)

/**
 * Builds a deterministic CSS family catalog for the current book.
 *
 * [embeddedFaces] supplies families from `@font-face`. [referencedFamilies] may
 * add CSS families seen in content (without faces). Mapping maps must already use
 * canonical family keys (or will be re-normalized).
 */
internal fun buildEpubCssFontCatalog(
    embeddedFaces: Map<String, EpubFontFace>,
    bookReplacements: Map<String, String>,
    globalReplacements: Map<String, String>,
    referencedFamilies: Collection<String> = emptyList(),
): List<EpubCssFontFamilyEntry> {
    val book = normalizeReplacementMap(bookReplacements)
    val global = normalizeReplacementMap(globalReplacements)
    val embedded = LinkedHashMap<String, EpubFontFace>()
    for ((rawKey, face) in embeddedFaces) {
        val key = normalizeFontFamilyKey(rawKey) ?: normalizeFontFamilyKey(face.family) ?: continue
        if (key in GENERIC_FONT_FAMILIES) continue
        if (key !in embedded) embedded[key] = face
    }
    val orderedKeys = LinkedHashSet<String>()
    embedded.keys.forEach { orderedKeys += it }
    referencedFamilies.forEach { raw ->
        val key = normalizeFontFamilyKey(raw) ?: return@forEach
        if (key !in GENERIC_FONT_FAMILIES) orderedKeys += key
    }
    book.keys.forEach { orderedKeys += it }
    // Do not list global-only families unless the book CSS references them —
    // catalog is book-scoped for the reader UI.

    return orderedKeys.sorted().map { key ->
        val face = embedded[key]
        val bookMapped = book[key]
        val globalMapped = global[key]
        val (status, mappedId) = when {
            bookMapped != null -> EpubCssFontMappingStatus.BOOK_MAPPED to bookMapped
            globalMapped != null -> EpubCssFontMappingStatus.GLOBAL_MAPPED to globalMapped
            face != null -> EpubCssFontMappingStatus.EMBEDDED to null
            else -> EpubCssFontMappingStatus.UNRESOLVED to null
        }
        EpubCssFontFamilyEntry(
            family = key,
            displayName = face?.family?.takeIf { it.isNotBlank() } ?: key,
            status = status,
            mappedFontId = mappedId,
            embeddedSrcPath = face?.srcPath,
        )
    }
}

/**
 * Union of all `@font-face` maps across spines (first face per family wins).
 */
internal fun mergeEpubBookFontMaps(maps: Collection<EpubBookFontMap>): EpubBookFontMap {
    if (maps.isEmpty()) return EpubBookFontMap.EMPTY
    val out = LinkedHashMap<String, EpubFontFace>()
    for (map in maps) {
        for ((key, face) in map.facesByFamily) {
            if (key !in out) out[key] = face
        }
    }
    return if (out.isEmpty()) EpubBookFontMap.EMPTY else EpubBookFontMap(out)
}

private fun normalizeReplacementMap(raw: Map<String, String>): Map<String, String> =
    buildMap {
        raw.forEach { (family, fontId) ->
            val key = normalizeFontFamilyKey(family) ?: return@forEach
            if (key in GENERIC_FONT_FAMILIES) return@forEach
            val id = fontId.trim()
            if (id.isEmpty()) return@forEach
            put(key, id)
        }
    }
