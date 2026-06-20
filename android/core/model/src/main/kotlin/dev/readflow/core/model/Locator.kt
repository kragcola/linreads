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
 */
@Serializable
sealed interface LocatorStrategy {
    /** Fixed-layout page (PDF, paged engines). */
    @Serializable
    data class Page(val index: Int, val total: Int) : LocatorStrategy

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
