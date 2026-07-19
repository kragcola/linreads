package dev.readflow.core.model

import kotlinx.serialization.Serializable

/**
 * Cross-platform reading position. `progression` is the in-section ratio [0,1];
 * `totalProgression` is the whole-book ratio [0,1] (sync primary key, v4 §7.1 E2).
 * `strategy` carries the engine-specific precise locator payload.
 */
@Serializable
data class Locator(
    val strategy: LocatorStrategy,
    val progression: Float? = null,
    val totalProgression: Float? = null,
)

/**
 * Engine-specific locating payload as pure-data polymorphism (sealed interface
 * whitelisted in §3.3 model-purity rule).
 *
 * **Page vs PageText identity (v4 §7.1):**
 * - [LocatorStrategy.Page] is fixed-layout **progress identity** (PDF progress, TOC,
 *   page turns, bookmarks). Engines publish bare Page for current position.
 * - [LocatorStrategy.PageText] is a fixed-page **text point** (page index + charOffset)
 *   for annotation anchors / future annotation jump. Range = two PageText points via
 *   ReaderTextSelection/Annotation start+end. Not progress identity; do not treat
 *   PageText as a substitute for Page in progress, TOC, or page-turn paths.
 * - Non-PDF engines must never reinterpret [LocatorStrategy.PageText.index] as an
 *   EPUB paragraph, Markdown slice, or TXT paragraph; use totalProgression fallback.
 */
@Serializable
sealed interface LocatorStrategy {
    /** Fixed-layout page (PDF, paged engines). Progress identity for fixed layout. */
    @Serializable
    data class Page(val index: Int, val total: Int) : LocatorStrategy

    /**
     * Fixed-page text point (PDF annotation anchors / jump targets).
     * [index]/[total] identify the page; [charOffset] is the text-layer character
     * offset on that page. Not progress identity — see Page vs PageText rule above.
     */
    @Serializable
    data class PageText(
        val index: Int,
        val total: Int,
        val charOffset: Int,
    ) : LocatorStrategy

    /** Reflowable section position (EPUB native reflow): spine + element + char offset. */
    @Serializable
    data class Section(
        val spineIndex: Int,
        val elementIndex: Int,
        val charOffset: Int,
    ) : LocatorStrategy

    /** Byte-range position (TXT virtual paging). */
    @Serializable
    data class ByteOffset(val offset: Long, val length: Int) : LocatorStrategy

    /** Position unknown / not yet resolved. */
    @Serializable
    data object Unknown : LocatorStrategy
}

/**
 * Fixed-layout page index for [LocatorStrategy.Page] and [LocatorStrategy.PageText].
 *
 * Use this only when a fixed page number is required (e.g. PDF goTo / page jump).
 * Do **not** use as progress identity: engines should still publish bare [LocatorStrategy.Page]
 * for current position, TOC, and bookmarks. Returns null for non-fixed strategies.
 */
fun fixedPageIndex(strategy: LocatorStrategy): Int? =
    when (strategy) {
        is LocatorStrategy.Page -> strategy.index
        is LocatorStrategy.PageText -> strategy.index
        is LocatorStrategy.Section,
        is LocatorStrategy.ByteOffset,
        LocatorStrategy.Unknown,
        -> null
    }

/** Convenience: fixed page index from a full [Locator], or null. */
fun fixedPageIndex(locator: Locator): Int? = fixedPageIndex(locator.strategy)
