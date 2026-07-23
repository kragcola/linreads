package dev.readflow.core.calibre

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceAdapterCapabilitiesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun capabilitiesMatchImplementedFilteringDownloadAndPagination() {
        val calibre = CalibreSourceAdapterFactory(tempFolder.root)
            .capabilities(1, calibreSourceConfigJson("http://192.168.1.5:8080"))
        val json = JsonHttpSourceAdapterFactory(tempFolder.root)
            .capabilities(1, httpCatalogSourceConfigJson("https://books.example/catalog.json"))
        val opds = OpdsSourceAdapterFactory(tempFolder.root)
            .capabilities(1, httpCatalogSourceConfigJson("https://books.example/opds"))
        val htmlFactory = HtmlRulesV1SourceAdapterFactory(tempFolder.root)
        val htmlWithoutSeriesOrPages = htmlFactory.capabilities(
            1,
            htmlRulesV1ConfigJson(htmlConfig("https://books.example/search?q={query}")),
        )
        val pagedHtmlWithSeries = htmlFactory.capabilities(
            1,
            htmlRulesV1ConfigJson(
                htmlConfig("https://books.example/search?q={query}&page={page}").copy(
                    search = htmlConfig("https://books.example/search?q={query}").search.copy(
                        seriesSelector = ".series",
                    ),
                ),
            ),
        )

        assertTrue(calibre.canBatchAcrossSource)
        assertTrue(json.canBatchAcrossSource)
        assertFalse(opds.canBatchAcrossSource)
        assertFalse(opds.canFilterBySeries)
        assertTrue(htmlWithoutSeriesOrPages.canDownload)
        assertFalse(htmlWithoutSeriesOrPages.canFilterBySeries)
        assertFalse(htmlWithoutSeriesOrPages.canBatchAcrossSource)
        assertTrue(pagedHtmlWithSeries.canFilterBySeries)
        assertTrue(pagedHtmlWithSeries.canBatchAcrossSource)
    }

    private fun htmlConfig(searchUrlTemplate: String) = HtmlRulesV1Config(
        searchUrlTemplate = searchUrlTemplate,
        allowedHosts = listOf("books.example"),
        search = HtmlSearchRules(
            itemSelector = ".book",
            titleSelector = ".title",
            authorSelector = ".author",
            detailLinkSelector = ".detail",
        ),
        detail = HtmlDetailRules(
            chapterItemSelector = ".chapter",
            chapterLinkSelector = "a",
        ),
        chapter = HtmlChapterRules(bodySelector = ".content"),
    )
}
