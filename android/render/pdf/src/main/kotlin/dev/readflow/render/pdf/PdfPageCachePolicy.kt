package dev.readflow.render.pdf

internal data class PdfPageCachePolicy(
    val radius: Int,
    val maxPages: Int,
)

internal fun pdfPageCachePolicy(
    pageWidthPx: Int,
    pageHeightPx: Int,
    memoryBudgetBytes: Long = PDF_PAGE_BITMAP_MEMORY_BUDGET_BYTES,
): PdfPageCachePolicy {
    val pageBytes = pageWidthPx.coerceAtLeast(1).toLong() *
        pageHeightPx.coerceAtLeast(1).toLong() *
        ARGB_8888_BYTES_PER_PIXEL
    return when {
        pageBytes * pagesForRadius(PDF_TARGET_PAGE_CACHE_RADIUS) <= memoryBudgetBytes ->
            cachePolicyForRadius(PDF_TARGET_PAGE_CACHE_RADIUS)
        pageBytes * pagesForRadius(PDF_FALLBACK_PAGE_CACHE_RADIUS) <= memoryBudgetBytes ->
            cachePolicyForRadius(PDF_FALLBACK_PAGE_CACHE_RADIUS)
        else -> cachePolicyForRadius(PDF_MIN_PAGE_CACHE_RADIUS)
    }
}

private fun cachePolicyForRadius(radius: Int): PdfPageCachePolicy =
    PdfPageCachePolicy(radius = radius, maxPages = pagesForRadius(radius))

private fun pagesForRadius(radius: Int): Int = radius * 2 + 1

private const val ARGB_8888_BYTES_PER_PIXEL = 4
private const val PDF_TARGET_PAGE_CACHE_RADIUS = 2
private const val PDF_FALLBACK_PAGE_CACHE_RADIUS = 1
private const val PDF_MIN_PAGE_CACHE_RADIUS = 0
private const val PDF_PAGE_BITMAP_MEMORY_BUDGET_BYTES = 128L * 1024L * 1024L
