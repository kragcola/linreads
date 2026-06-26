package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserHardeningTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `opf with dtd or entity declarations is rejected`() {
        val epub = tempDir.resolve("xxe.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <!DOCTYPE package [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <package version="3.0">
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to "<html><body><p>Should not load</p></body></html>",
        )

        val book = EpubParser().parseBook(epub)

        assertEquals(emptyList<String>(), book.paras.map { it.text })
        assertEquals(emptyList<String>(), book.spinePaths)
    }

    @Test
    fun `manifest href that escapes zip root is not read`() {
        val epub = tempDir.resolve("zip-slip.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="c1" href="../../evil.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "evil.xhtml" to "<html><body><p>Escaped content</p></body></html>",
        )

        val book = EpubParser().parseBook(epub)

        assertEquals(emptyList<String>(), book.paras.map { it.text })
    }

    @Test
    fun `oversized spine entry is skipped without reading into parser`() {
        val epub = tempDir.resolve("huge-spine.epub").toFile()
        val largeText = "x".repeat(2_100_000)
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="c1" href="huge.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/huge.xhtml" to "<html><body><p>$largeText</p></body></html>",
        )

        val book = EpubParser().parseBook(epub)

        assertEquals(emptyList<String>(), book.paras.map { it.text })
    }

    @Test
    fun `deeply nested xhtml does not overflow parser recursion`() {
        val deepBody = buildString {
            repeat(5_000) { append("<div>") }
            append("<p>Very deep</p>")
            repeat(5_000) { append("</div>") }
        }

        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = "<html><body>$deepBody</body></html>",
        )

        assertEquals(emptyList<EpubReaderItem>(), items)
    }

    @Test
    fun `xhtml entity declarations are rejected before reader item parsing`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <!DOCTYPE html [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <html><body><p>&xxe;</p></body></html>
            """.trimIndent(),
        )

        assertEquals(emptyList<EpubReaderItem>(), items)
    }

    @Test
    fun `lazy parse also skips oversized spine entries`() {
        val epub = tempDir.resolve("lazy-huge-spine.epub").toFile()
        val largeText = "x".repeat(2_100_000)
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="c1" href="huge.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/huge.xhtml" to "<html><body><p>$largeText</p></body></html>",
        )

        val book = EpubParser().parseLazyBook(epub)

        assertEquals(emptyList<String>(), book.paras.map { it.text })
        assertEquals(0, book.blockCount)
        assertEquals(null, book.paragraphAt(0))
    }

    @Test
    fun `zip entry count cap returns empty books`() {
        val epub = tempDir.resolve("many-entries.epub").toFile()
        ZipOutputStream(epub.outputStream()).use { zip ->
            repeat(EPUB_MAX_ZIP_ENTRIES + 1) { index ->
                zip.putNextEntry(ZipEntry("entry-$index.txt"))
                zip.write("x".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        val fullBook = EpubParser().parseBook(epub)
        val lazyBook = EpubParser().parseLazyBook(epub)

        assertEquals(emptyList<String>(), fullBook.paras.map { it.text })
        assertEquals(emptyList<String>(), fullBook.spinePaths)
        assertEquals(emptyList<EpubPara>(), lazyBook.paras)
        assertEquals(emptyList<String>(), lazyBook.spinePaths)
    }

    @Test
    fun `corrupt zip is degraded to empty books`() {
        val epub = tempDir.resolve("corrupt.epub").toFile()
        epub.writeText("not a zip")

        val fullBook = EpubParser().parseBook(epub)
        val lazyBook = EpubParser().parseLazyBook(epub)

        assertEquals(emptyList<String>(), fullBook.paras.map { it.text })
        assertEquals(emptyList<EpubPara>(), lazyBook.paras)
    }

    @Test
    fun `safe zip paths reject absolute backslash null and escaping segments`() {
        assertEquals("OEBPS/ch1.xhtml", epubSafeZipPath("/OEBPS/./text/../ch1.xhtml"))
        assertEquals(null, epubSafeZipPath("../evil.xhtml"))
        assertEquals(null, epubSafeZipPath("OEBPS\\evil.xhtml"))
        assertEquals(null, epubSafeZipPath("OEBPS/evil\u0000.xhtml"))
        assertEquals(null, epubSafeZipPath(""))
    }

    private fun containerXml(): String =
        """
            <container>
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

    private fun writeEpub(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
