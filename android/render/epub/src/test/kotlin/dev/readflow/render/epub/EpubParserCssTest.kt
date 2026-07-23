package dev.readflow.render.epub

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EpubParserCssTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `css rule regex escapes both braces for Android runtime`() {
        val field = Class.forName("dev.readflow.render.epub.EpubCssKt")
            .getDeclaredField("CSS_RULE")
            .apply { isAccessible = true }
        val pattern = (field.get(null) as Regex).pattern

        assertEquals("([^{}]+)\\{([^{}]*)\\}", pattern)
    }

    @Test
    fun `legacy short multi-spine epub remains readable after css initialization`() {
        val epub = tempDir.resolve("legacy-short-boundary.epub").toFile()
        writeLegacyShortBoundaryEpub(epub)

        val parser = EpubParser()
        val eager = parser.parseBook(epub)
        val lazy = parser.parseLazyBook(epub)

        assertTrue(epub.length() < 1024L, "fixture should stay representative of the sub-1 KiB legacy EPUB")
        assertEquals(listOf("Chapter one end.", "Chapter two start."), eager.paras.map(EpubPara::text))
        assertEquals("Chapter one end.", lazy.paragraphAt(0)?.text)
        assertEquals("Chapter two start.", lazy.paragraphAt(1)?.text)
    }

    @Test
    fun `eager and lazy parsing load linked stylesheets relative to the spine document`() {
        val epub = tempDir.resolve("css.epub").toFile()
        writeEpub(epub)

        val parser = EpubParser()
        val eagerItem = parser.parseBook(epub).items.single() as EpubReaderItem.Text
        val lazyBook = parser.parseLazyBook(epub)
        val lazyBlock = lazyBook.blockAt(0) as EpubDisplayBlock.Text

        listOf(eagerItem.styleSpans, lazyBlock.styleSpans).forEach { spans ->
            assertTrue(spans.any { it.style == EpubTextStyle.Bold })
            assertTrue(
                spans.any {
                    it.style == EpubTextStyle.ForegroundColor && it.color == 0xFF13579B.toInt()
                },
            )
        }
        assertEquals(EpubTextAlign.Center, eagerItem.blockStyle.textAlign)
        assertEquals(EpubTextAlign.Center, lazyBlock.blockStyle.textAlign)
        assertEquals(
            EpubCssFontMappingStatus.UNRESOLVED,
            lazyBook.cssFontCatalog().single { it.family == "missing face" }.status,
        )
    }

    private fun writeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            add(
                "META-INF/container.xml",
                """
                    <container><rootfiles>
                      <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles></container>
                """.trimIndent(),
            )
            add(
                "OEBPS/content.opf",
                """
                    <package version="3.0">
                      <manifest>
                        <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="css" href="styles/book.css" media-type="text/css"/>
                      </manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>
                """.trimIndent(),
            )
            add(
                "OEBPS/text/ch1.xhtml",
                """
                    <html><head><link rel="stylesheet" href="../styles/book.css"/></head>
                    <body><p class="chapter">Styled chapter</p></body></html>
                """.trimIndent(),
            )
            add(
                "OEBPS/styles/book.css",
                ".chapter { color: #13579b; font-weight: 700; text-align: center; font-family: 'Missing Face', serif; }",
            )
        }
    }

    private fun writeLegacyShortBoundaryEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            add(
                "META-INF/container.xml",
                """
                    <container>
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent(),
            )
            add(
                "OEBPS/content.opf",
                """
                    <package version="3.0">
                      <manifest>
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                      </spine>
                    </package>
                """.trimIndent(),
            )
            add(
                "OEBPS/ch1.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><p>Chapter one end.</p></body>
                    </html>
                """.trimIndent(),
            )
            add(
                "OEBPS/ch2.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><p>Chapter two start.</p></body>
                    </html>
                """.trimIndent(),
            )
        }
    }
}
