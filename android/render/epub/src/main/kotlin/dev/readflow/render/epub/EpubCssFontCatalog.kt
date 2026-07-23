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
internal data class EpubCssFontFamilyEntry(
    val family: String,
    val displayName: String,
    val fontFamilyChain: List<String> = listOf(family),
    val status: EpubCssFontMappingStatus,
    val effectiveSource: EpubCssFontEffectiveSource = EpubCssFontEffectiveSource.DEFAULT,
    val effectiveFamily: String? = null,
    val mappedFontId: String? = null,
    val embeddedSrcPath: String? = null,
    val occurrenceCount: Int = 0,
    val coveredChars: Int = 0,
    val excerpt: String = "",
    val excerptMatchStart: Int = 0,
    val excerptMatchEnd: Int = 0,
    val excerptSpineIndex: Int = 0,
)

/**
 * Builds a deterministic CSS family catalog for the current book.
 *
 * [embeddedFaces] only supplies resolution status for families present in [fontUsages];
 * an unused `@font-face` never becomes a reader-facing row. Mapping maps are normalized here.
 */
internal fun buildEpubCssFontCatalog(
    embeddedFaces: Map<String, EpubFontFace>,
    bookReplacements: Map<String, String>,
    globalReplacements: Map<String, String>,
    fontUsages: Collection<EpubCssFontUsage> = emptyList(),
    availableReplacementIds: Set<String>? = null,
): List<EpubCssFontFamilyEntry> {
    val book = normalizeReplacementMap(bookReplacements)
    val global = normalizeReplacementMap(globalReplacements)
    val embedded = LinkedHashMap<String, EpubFontFace>()
    for ((rawKey, face) in embeddedFaces) {
        val key = normalizeFontFamilyKey(rawKey) ?: normalizeFontFamilyKey(face.family) ?: continue
        if (key in GENERIC_FONT_FAMILIES) continue
        if (key !in embedded) embedded[key] = face
    }
    return fontUsages.map { usage ->
        val resolution = resolveEpubCssFontEffectiveSource(
            fontFamilyChain = usage.fontFamilyChain,
            bookReplacements = book,
            globalReplacements = global,
            bookFonts = EpubBookFontMap(embedded),
            availableReplacementIds = availableReplacementIds,
        )
        val status = when (resolution.source) {
            EpubCssFontEffectiveSource.BOOK_MAPPING -> EpubCssFontMappingStatus.BOOK_MAPPED
            EpubCssFontEffectiveSource.GLOBAL_MAPPING -> EpubCssFontMappingStatus.GLOBAL_MAPPED
            EpubCssFontEffectiveSource.EMBEDDED -> EpubCssFontMappingStatus.EMBEDDED
            EpubCssFontEffectiveSource.DEFAULT -> EpubCssFontMappingStatus.UNRESOLVED
        }
        EpubCssFontFamilyEntry(
            family = usage.family,
            displayName = usage.displayName,
            fontFamilyChain = usage.fontFamilyChain,
            status = status,
            effectiveSource = resolution.source,
            effectiveFamily = resolution.effectiveFamily,
            mappedFontId = resolution.mappedFontId,
            embeddedSrcPath = resolution.embeddedFace?.srcPath,
            occurrenceCount = usage.occurrenceCount,
            coveredChars = usage.coveredChars,
            excerpt = usage.excerpt,
            excerptMatchStart = usage.excerptMatchStart,
            excerptMatchEnd = usage.excerptMatchEnd,
            excerptSpineIndex = usage.excerptSpineIndex,
        )
    }.sortedWith(
        compareByDescending<EpubCssFontFamilyEntry> { it.coveredChars }
            .thenByDescending { it.occurrenceCount }
            .thenBy { it.family },
    )
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
