package dev.readflow.render.api

/**
 * UI-facing CSS `@font-face` / content family catalog entry for the open EPUB.
 * Lives in render/api so reader/settings can observe status without depending on render/epub.
 *
 * Resolution priority (engine): book-scoped map > global map > embedded face > user/system default.
 */
enum class EpubCssFontMappingStatus {
    /** Book-scoped user mapping is present for this family. */
    BOOK_MAPPED,
    /** Global EPUB replacement is present for this family. */
    GLOBAL_MAPPED,
    /** CSS embeds a loadable local face; no user mapping. */
    EMBEDDED,
    /** Family known from CSS but unmapped and without a usable embedded face. */
    UNRESOLVED,
}

enum class EpubCssFontEffectiveSource {
    BOOK_MAPPING,
    GLOBAL_MAPPING,
    EMBEDDED,
    DEFAULT,
}

/**
 * One CSS family row for book-font settings UI.
 *
 * [family] is the canonical primary family key (lowercase, unquoted). Only the first
 * non-generic family in each real text span is counted. [excerptMatchStart] and
 * [excerptMatchEnd] are an end-exclusive UTF-16 range inside [excerpt].
 */
data class EpubCssFontFamilyInfo(
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
