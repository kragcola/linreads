package dev.readflow.render.pdf

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class PdfOutlineParserTest {

    @Test
    fun `parses document outline titles levels and page destinations`() {
        val file = createTempFile(prefix = "readflow-pdf-outline-", suffix = ".pdf")
        file.writeText(
            """
            %PDF-1.7
            1 0 obj
            << /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R >>
            endobj
            4 0 obj
            << /Type /Page /Parent 2 0 R >>
            endobj
            6 0 obj
            << /Type /Outlines /First 7 0 R /Last 9 0 R /Count 3 >>
            endobj
            7 0 obj
            << /Title (Intro \(setup\)) /Dest [3 0 R /Fit] /First 8 0 R /Next 9 0 R >>
            endobj
            8 0 obj
            << /Title <FEFF6DF1523B> /A << /S /GoTo /D [4 0 R /Fit] >> >>
            endobj
            9 0 obj
            << /Title (Second) /Dest [4 0 R /Fit] >>
            endobj
            trailer
            << /Root 1 0 R >>
            %%EOF
            """.trimIndent(),
            charset = Charsets.ISO_8859_1,
        )

        val entries = PdfOutlineParser.parse(file.toFile(), pageCount = 2)

        assertEquals(listOf("Intro (setup)", "深刻", "Second"), entries.map { it.title })
        assertEquals(listOf(0, 1, 0), entries.map { it.level })
        assertEquals(listOf(0, 1, 1), entries.map { (it.locator.strategy as LocatorStrategy.Page).index })
    }

    @Test
    fun `fallback toc titles are explicitly marked as page list`() {
        val entries = buildPdfFallbackToc(total = 2)

        assertEquals(listOf("第 1 页（页列表）", "第 2 页（页列表）"), entries.map { it.title })
    }

    @Test
    fun `resolves outline named destinations through catalog dest name tree`() {
        val file = createTempFile(prefix = "readflow-pdf-named-dest-", suffix = ".pdf")
        file.writeText(
            """
            %PDF-1.7
            1 0 obj
            << /Type /Catalog /Pages 2 0 R /Outlines 6 0 R /Names << /Dests 10 0 R >> >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R >>
            endobj
            4 0 obj
            << /Type /Page /Parent 2 0 R >>
            endobj
            6 0 obj
            << /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>
            endobj
            7 0 obj
            << /Title (Named literal) /Dest (chapter-two) /Next 8 0 R >>
            endobj
            8 0 obj
            << /Title (Named name) /A << /S /GoTo /D /chapter-one >> >>
            endobj
            10 0 obj
            << /Names [ (chapter-one) [3 0 R /Fit] (chapter-two) [4 0 R /Fit] ] >>
            endobj
            trailer
            << /Root 1 0 R >>
            %%EOF
            """.trimIndent(),
            charset = Charsets.ISO_8859_1,
        )

        val entries = PdfOutlineParser.parse(file.toFile(), pageCount = 2)

        assertEquals(listOf("Named literal", "Named name"), entries.map { it.title })
        assertEquals(listOf(1, 0), entries.map { (it.locator.strategy as LocatorStrategy.Page).index })
    }

    @Test
    fun `resolves outline named destinations through catalog dest dictionary`() {
        val file = createTempFile(prefix = "readflow-pdf-dest-dict-", suffix = ".pdf")
        file.writeText(
            """
            %PDF-1.7
            1 0 obj
            << /Type /Catalog /Pages 2 0 R /Outlines 6 0 R /Dests 10 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R >>
            endobj
            4 0 obj
            << /Type /Page /Parent 2 0 R >>
            endobj
            6 0 obj
            << /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>
            endobj
            7 0 obj
            << /Title (Dict literal) /Dest (chapter-two) /Next 8 0 R >>
            endobj
            8 0 obj
            << /Title (Dict name) /A << /S /GoTo /D /chapter-one >> >>
            endobj
            10 0 obj
            << /chapter-one [3 0 R /Fit] /chapter-two 11 0 R >>
            endobj
            11 0 obj
            << /D [4 0 R /Fit] >>
            endobj
            trailer
            << /Root 1 0 R >>
            %%EOF
            """.trimIndent(),
            charset = Charsets.ISO_8859_1,
        )

        val entries = PdfOutlineParser.parse(file.toFile(), pageCount = 2)

        assertEquals(listOf("Dict literal", "Dict name"), entries.map { it.title })
        assertEquals(listOf(1, 0), entries.map { (it.locator.strategy as LocatorStrategy.Page).index })
    }

    @Test
    fun `resolves indirect outline title action and catalog names tree destination refs`() {
        val file = createTempFile(prefix = "readflow-pdf-indirect-outline-", suffix = ".pdf")
        file.writeText(
            """
            %PDF-1.7
            1 0 obj
            << /D (section.1) /S /GoTo >>
            endobj
            2 0 obj
            << /Type /Catalog /Pages 3 0 R /Outlines 8 0 R /Names 13 0 R >>
            endobj
            3 0 obj
            << /Type /Pages /Kids [4 0 R 5 0 R] /Count 2 >>
            endobj
            4 0 obj
            << /Type /Page /Parent 3 0 R >>
            endobj
            5 0 obj
            << /Type /Page /Parent 3 0 R >>
            endobj
            8 0 obj
            << /Type /Outlines /First 9 0 R /Count 1 >>
            endobj
            9 0 obj
            << /Title 10 0 R /A 1 0 R >>
            endobj
            10 0 obj
            (1 Introduction)
            endobj
            13 0 obj
            << /Dests 14 0 R >>
            endobj
            14 0 obj
            << /Kids [15 0 R] >>
            endobj
            15 0 obj
            << /Names [ (section.1) 16 0 R ] >>
            endobj
            16 0 obj
            [5 0 R /Fit]
            endobj
            trailer
            << /Root 2 0 R >>
            %%EOF
            """.trimIndent(),
            charset = Charsets.ISO_8859_1,
        )

        val entries = PdfOutlineParser.parse(file.toFile(), pageCount = 2)

        assertEquals(listOf("1 Introduction"), entries.map { it.title })
        assertEquals(listOf(1), entries.map { (it.locator.strategy as LocatorStrategy.Page).index })
    }
}
