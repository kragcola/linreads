package dev.readflow.render.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PdfPageCachePolicyTest {

    @Test
    fun `uses two page prefetch radius when five rendered pages fit memory budget`() {
        val policy = pdfPageCachePolicy(
            pageWidthPx = 1080,
            pageHeightPx = 1620,
            memoryBudgetBytes = 40L * 1024L * 1024L,
        )

        assertEquals(2, policy.radius)
        assertEquals(5, policy.maxPages)
    }

    @Test
    fun `falls back to one page radius when five rendered pages would exceed memory budget`() {
        val policy = pdfPageCachePolicy(
            pageWidthPx = 2400,
            pageHeightPx = 3600,
            memoryBudgetBytes = 120L * 1024L * 1024L,
        )

        assertEquals(1, policy.radius)
        assertEquals(3, policy.maxPages)
    }

    @Test
    fun `keeps only the current page when even three rendered pages exceed memory budget`() {
        val policy = pdfPageCachePolicy(
            pageWidthPx = 2400,
            pageHeightPx = 3600,
            memoryBudgetBytes = 40L * 1024L * 1024L,
        )

        assertEquals(0, policy.radius)
        assertEquals(1, policy.maxPages)
    }
}
