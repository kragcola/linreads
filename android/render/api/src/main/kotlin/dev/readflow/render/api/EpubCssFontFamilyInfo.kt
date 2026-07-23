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

/**
 * One CSS family row for book-font settings UI.
 *
 * [family] is the canonical key (lowercase, unquoted). [displayName] prefers the
 * first raw family token as seen in CSS when available.
 */
data class EpubCssFontFamilyInfo(
    val family: String,
    val displayName: String,
    val status: EpubCssFontMappingStatus,
    val mappedFontId: String? = null,
    val embeddedSrcPath: String? = null,
)
