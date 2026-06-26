package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserFixedLayoutTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fixed layout epub is detected but still degrades to reflow text extraction`() {
        val epub = tempDir.resolve("fixed.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to """
                <container>
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent(),
            "OEBPS/content.opf" to """
                <package version="3.0" xmlns:rendition="http://www.idpf.org/vocab/rendition/#">
                  <metadata>
                    <meta property="rendition:layout">pre-paginated</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="page.xhtml" media-type="application/xhtml+xml" properties="rendition:layout-pre-paginated"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/page.xhtml" to "<html><body><p>Fixed page text</p></body></html>",
        )

        val book = EpubParser().parseBook(epub)

        assertTrue(book.isFixedLayout)
        assertEquals(listOf("Fixed page text"), book.paras.map { it.text })
    }

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
