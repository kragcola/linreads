package dev.readflow.render.pdf

/**
 * Immutable open-document text session: framework API binding + search/selection capability.
 *
 * Published as one atomic snapshot from [PdfRendererEngine] so search / selection /
 * annotation never observe a split (api, capable) pair.
 *
 * Invariant — **API identity preservation independent of page content**:
 * [api] is always the exact [resolvedApi] instance passed to [resolvePdfOpenTextSession].
 * Capability may be false when the book is closed or the API reports unavailable, but the
 * helper never swaps a working API to [PdfFrameworkTextApi.Unavailable] based on page content.
 * (Production teardown still publishes an explicit unavailable session separately.)
 */
internal data class PdfOpenTextSession(
    val api: PdfFrameworkTextApi,
    val searchCapable: Boolean,
    /**
     * Selection/annotation capability for this open session.
     * Derived atomically from bookOpen && api.isAvailable && api.supportsSelection.
     * Content-independent — never probes page text.
     */
    val selectionCapable: Boolean,
)

/**
 * Builds a session from a resolved API binding and whether the book is open.
 * Preserves [resolvedApi] object identity; never probes page content.
 */
internal fun resolvePdfOpenTextSession(
    resolvedApi: PdfFrameworkTextApi,
    bookOpen: Boolean,
): PdfOpenTextSession = PdfOpenTextSession(
    api = resolvedApi,
    searchCapable = PdfSearchLifecycle.supportsSearch(
        apiAvailable = resolvedApi.isAvailable,
        bookOpen = bookOpen,
    ),
    selectionCapable = PdfSearchLifecycle.supportsSelection(
        apiAvailable = resolvedApi.isAvailable,
        supportsSelection = resolvedApi.supportsSelection,
        bookOpen = bookOpen,
    ),
)

/** Explicit unavailable session used by production invalidate/close (not content-driven). */
internal val PdfOpenTextSessionUnavailable: PdfOpenTextSession = PdfOpenTextSession(
    api = PdfFrameworkTextApi.Unavailable,
    searchCapable = false,
    selectionCapable = false,
)

/**
 * Pure lifecycle helpers for PDF search generation invalidation.
 * Open/close bump the generation so in-flight results must be discarded.
 */
internal object PdfSearchLifecycle {

    /**
     * Returns true when [resultGeneration] still matches [currentGeneration]
     * (same open session; not invalidated by close/reopen).
     */
    fun isResultFresh(currentGeneration: Int, resultGeneration: Int): Boolean =
        currentGeneration == resultGeneration && currentGeneration >= 0 && resultGeneration >= 0

    /**
     * Next generation after open or close. Starts from 0; first invalidate → 1.
     */
    fun nextGeneration(current: Int): Int = current + 1

    /**
     * Whether the engine should advertise search for the current document session.
     * Capability is open-session + framework API binding only — never document content probing.
     * Image-only PDFs stay searchable when the API exists; user-triggered search then returns empty.
     */
    fun supportsSearch(
        apiAvailable: Boolean,
        bookOpen: Boolean,
    ): Boolean = apiAvailable && bookOpen

    /**
     * Whether the engine should advertise text selection/annotations for the current session.
     * Same boundary as search (API binding + open book) plus [supportsSelection] reflection flag.
     * Never probes page content; image-only pages do not flip capability.
     */
    fun supportsSelection(
        apiAvailable: Boolean,
        supportsSelection: Boolean,
        bookOpen: Boolean,
    ): Boolean = apiAvailable && supportsSelection && bookOpen
}