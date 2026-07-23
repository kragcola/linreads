package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubFontUsageCatalogTest {

    @Test
    fun `real spans aggregate twelve primary families without fallback inflation`() {
        val firstChapter = parsedChapter(
            spineIndex = 0,
            familyRange = 1..12,
            repeatedFamily = 1,
        )
        val secondChapter = parsedChapter(
            spineIndex = 1,
            familyRange = 1..12,
            repeatedFamily = 1,
        )

        val usages = buildEpubCssFontUsages(
            listOf(
                0 to firstChapter.items,
                1 to secondChapter.items,
            ),
        )

        assertEquals(12, usages.size)
        assertEquals((1..12).map { "story font $it" }.toSet(), usages.map { it.family }.toSet())
        assertFalse(usages.any { "fallback" in it.family || it.family == "serif" })
        assertEquals("story font 1", usages.first().family)
        assertTrue(usages.first().occurrenceCount > usages.last().occurrenceCount)
        assertTrue(usages.first().coveredChars > usages.last().coveredChars)

        val expected = expectedPrimaryFamilyCoverage(firstChapter.items + secondChapter.items)

        usages.forEach { usage ->
            assertTrue(usage.displayName.startsWith("Story Font "))
            assertTrue(usage.occurrenceCount > 0)
            assertTrue(usage.coveredChars > 0)
            assertTrue(usage.excerpt.isNotBlank())
            assertTrue(usage.excerptMatchStart in 0 until usage.excerpt.length)
            assertTrue(usage.excerptMatchEnd in 1..usage.excerpt.length)
            assertTrue(usage.excerptMatchStart < usage.excerptMatchEnd)
            assertEquals(expected.getValue(usage.family).first, usage.occurrenceCount)
            assertEquals(expected.getValue(usage.family).second, usage.coveredChars)
            val sourceText = (firstChapter.items + secondChapter.items)
                .mapNotNull {
                    when (it) {
                        is EpubReaderItem.Text -> it.text
                        is EpubReaderItem.Heading -> it.text
                        else -> null
                    }
                }
                .joinToString("\n")
            assertTrue(sourceText.contains(usage.excerpt))
        }
    }

    @Test
    fun `catalog excludes unused font faces and preserves usage sample fields`() {
        val content = parseReaderItemsContent(
            spineIndex = 0,
            html = """
                <html><head><style>
                  @font-face { font-family: UsedFace; src: url("used.ttf"); }
                  @font-face { font-family: UnusedFace; src: url("unused.ttf"); }
                  p { font-family: UsedFace, UnusedFace, serif; }
                </style></head><body>
                  <p>这是真实书中文字，用来检查字体预览例句。</p>
                </body></html>
            """.trimIndent(),
        )
        val usage = buildEpubCssFontUsages(listOf(0 to content.items)).single()

        val catalog = buildEpubCssFontCatalog(
            embeddedFaces = content.bookFontMap.facesByFamily,
            bookReplacements = emptyMap(),
            globalReplacements = emptyMap(),
            fontUsages = listOf(usage),
        )

        assertEquals(listOf("usedface"), catalog.map { it.family })
        assertEquals(usage.occurrenceCount, catalog.single().occurrenceCount)
        assertEquals(usage.coveredChars, catalog.single().coveredChars)
        assertEquals(usage.excerpt, catalog.single().excerpt)
        assertEquals(usage.excerptMatchStart, catalog.single().excerptMatchStart)
        assertEquals(usage.excerptMatchEnd, catalog.single().excerptMatchEnd)
    }

    @Test
    fun `usage keeps the complete fallback chain while primary family remains the row key`() {
        val content = parseReaderItemsContent(
            spineIndex = 0,
            html = """
                <html><head><style>
                  p { font-family: Unknown, "Mincho Display", serif; }
                </style></head><body><p>完整 fallback 链仍然使用一个稳定字体入口。</p></body></html>
            """.trimIndent(),
        )

        val usage = buildEpubCssFontUsages(listOf(0 to content.items)).single()

        assertEquals("unknown", usage.family)
        assertEquals(listOf("unknown", "mincho display", "serif"), usage.fontFamilyChain)
    }

    @Test
    fun `excerpt truncation never splits supplementary code points`() {
        val startsOnLowSurrogate = "a".repeat(39) + "\uD840\uDC00" + "b".repeat(200)
        val endsAfterHighSurrogate = "c".repeat(159) + "\uD83D\uDE00" + "d".repeat(100)
        val items = listOf(
            styledText("Start Boundary", startsOnLowSurrogate, 80, 81),
            styledText("End Boundary", endsAfterHighSurrogate, 80, 81),
        )

        val usages = buildEpubCssFontUsages(listOf(0 to items))

        assertEquals(2, usages.size)
        usages.forEach { usage ->
            assertUnicodeWellFormed(usage.excerpt)
            assertTrue(usage.excerptMatchStart in 0 until usage.excerpt.length)
            assertTrue(usage.excerptMatchEnd in 1..usage.excerpt.length)
        }
        assertNotNull(usages.single { it.family == "start boundary" }.excerpt.codePoints().toArray())
    }

    private fun parsedChapter(
        spineIndex: Int,
        familyRange: IntRange,
        repeatedFamily: Int,
    ): EpubParsedHtmlContent {
        val rules = buildString {
            appendLine("@font-face { font-family: UnusedFace; src: url('unused.ttf'); }")
            familyRange.forEach { index ->
                appendLine(
                    ".f$index { font-family: 'Story Font $index', 'Fallback $index', serif; }",
                )
            }
        }
        val paragraphs = buildString {
            familyRange.forEach { index ->
                appendLine(
                    "<p class='f$index'>第${index}种字体的真实正文，包含<strong>粗体</strong>和<em>斜体</em>。</p>",
                )
            }
            appendLine(
                "<p class='f$repeatedFamily'>重复出现的正文让跨章节聚合和稳定排序都可验证。</p>",
            )
        }
        return parseReaderItemsContent(
            spineIndex = spineIndex,
            html = "<html><head><style>$rules</style></head><body>$paragraphs</body></html>",
        )
    }

    private fun expectedPrimaryFamilyCoverage(
        items: List<EpubReaderItem>,
    ): Map<String, Pair<Int, Int>> {
        val counts = linkedMapOf<String, Pair<Int, Int>>()
        items.forEach { item ->
            val text: String
            val spans: List<EpubTextStyleSpan>
            when (item) {
                is EpubReaderItem.Text -> {
                    text = item.text
                    spans = item.styleSpans
                }
                is EpubReaderItem.Heading -> {
                    text = item.text
                    spans = item.styleSpans
                }
                else -> return@forEach
            }
            spans.filter { it.style == EpubTextStyle.FontFamily }.forEach { span ->
                val family = splitCssFontFamilyList(span.fontFamily.orEmpty())
                    .mapNotNull(::normalizeFontFamilyKey)
                    .firstOrNull { it !in GENERIC_FONT_FAMILIES }
                    ?: return@forEach
                val start = span.start.coerceIn(0, text.length)
                val end = span.end.coerceIn(start, text.length)
                if (start >= end || text.substring(start, end).isBlank()) return@forEach
                val current = counts[family] ?: (0 to 0)
                counts[family] = current.first + 1 to current.second + (end - start)
            }
        }
        return counts
    }

    private fun styledText(
        family: String,
        text: String,
        start: Int,
        end: Int,
    ) = EpubReaderItem.Text(
        locator = EpubItemLocator(spineIndex = 0, elementIndex = 0),
        text = text,
        styleSpans = listOf(
            EpubTextStyleSpan(
                start = start,
                end = end,
                style = EpubTextStyle.FontFamily,
                fontFamily = family,
            ),
        ),
    )

    private fun assertUnicodeWellFormed(text: String) {
        text.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) {
                assertTrue(index + 1 < text.length && text[index + 1].isLowSurrogate())
            }
            if (char.isLowSurrogate()) {
                assertTrue(index > 0 && text[index - 1].isHighSurrogate())
            }
        }
    }
}
