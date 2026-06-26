package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubLazyBookTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `spine content is loaded lazily and evicted by lru`() {
        val epub = tempDir.resolve("lazy.epub").toFile()
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two</p></body></html>",
            "OEBPS/ch3.xhtml" to "<html><body><p>Chapter three</p></body></html>",
            "OEBPS/ch4.xhtml" to "<html><body><p>Chapter four</p></body></html>",
        )

        val book = EpubParser().parseLazyBook(epub, maxCachedSpines = 2)

        assertEquals(emptySet<Int>(), book.cachedSpineIndexes())
        assertEquals(4, book.paras.size)
        assertEquals("Chapter one", book.paragraphAt(0)?.text)
        assertEquals(setOf(0), book.cachedSpineIndexes())
        assertEquals("Chapter three", book.paragraphAt(2)?.text)
        assertEquals(setOf(0, 2), book.cachedSpineIndexes())
        assertEquals("Chapter four", book.paragraphAt(3)?.text)
        assertEquals(setOf(2, 3), book.cachedSpineIndexes())
        assertEquals(1, book.loadCount(0))

        assertEquals("Chapter one", book.paragraphAt(0)?.text)

        assertEquals(2, book.loadCount(0))
        assertEquals(setOf(3, 0), book.cachedSpineIndexes())
    }

    @Test
    fun `paragraph to block mapping accounts for non text blocks`() {
        val epub = tempDir.resolve("blocks.epub").toFile()
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>First paragraph</p>
                  <img src="image.png" alt="Cover"/>
                  <p>Second paragraph</p>
                </body></html>
            """.trimIndent(),
        )

        val book = EpubParser().parseLazyBook(epub, maxCachedSpines = 1)
        val imageBlock = book.blockAt(1)

        assertEquals(3, book.blockCount)
        assertTrue(imageBlock is EpubDisplayBlock.Image)
        assertEquals(0, imageBlock?.paragraphIndex)
        assertEquals(2, book.blockIndexForParagraph(1))
        assertEquals("Second paragraph", book.paragraphAt(1)?.text)
    }

    @Test
    fun `prefetch warms current and neighboring spines within cache limit`() {
        val epub = tempDir.resolve("prefetch.epub").toFile()
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two</p></body></html>",
            "OEBPS/ch3.xhtml" to "<html><body><p>Chapter three</p></body></html>",
            "OEBPS/ch4.xhtml" to "<html><body><p>Chapter four</p></body></html>",
        )

        val book = EpubParser().parseLazyBook(epub, maxCachedSpines = 3)

        book.prefetchAroundParagraph(1)

        assertEquals(setOf(0, 1, 2), book.cachedSpineIndexes())
        assertEquals(1, book.loadCount(0))
        assertEquals(1, book.loadCount(1))
        assertEquals(1, book.loadCount(2))
        assertEquals(0, book.loadCount(3))

        book.prefetchAroundParagraph(3)

        assertEquals(3, book.cachedSpineIndexes().size)
        assertTrue(3 in book.cachedSpineIndexes())
        assertTrue(2 in book.cachedSpineIndexes())
        assertEquals(1, book.loadCount(2))
        assertEquals(1, book.loadCount(3))
    }

    @Test
    fun `cached paragraph lookup does not load cold spines for paged boundaries`() {
        val epub = tempDir.resolve("cached-boundaries.epub").toFile()
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>alpha beta gamma</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>delta epsilon zeta</p></body></html>",
        )
        val book = EpubParser().parseLazyBook(epub, maxCachedSpines = 1)

        assertEquals("alpha beta gamma", book.paragraphAt(0)?.text)

        val pages = epubViewportPagedLayout(
            paras = book.paras,
            textProvider = { index -> book.cachedParagraphAt(index)?.text.orEmpty() },
            metrics = EpubPageMetrics(
                viewportWidthPx = 120,
                viewportHeightPx = 80,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 60,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 20f,
            ),
        )

        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 10), pages[0])
        assertEquals(1, book.loadCount(0))
        assertEquals(0, book.loadCount(1))
    }

    @Test
    fun `cached block lookup does not load cold spines for paged image slices`() {
        val epub = tempDir.resolve("cached-blocks.epub").toFile()
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>alpha</p><img src=\"cover.png\" alt=\"Cover\"/></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>beta</p><img src=\"cold.png\" alt=\"Cold\"/></body></html>",
        )
        val book = EpubParser().parseLazyBook(epub, maxCachedSpines = 1)

        assertEquals("alpha", book.paragraphAt(0)?.text)

        val pages = epubPagedLayoutWithBlocks(
            paras = book.paras,
            textProvider = { index -> book.cachedParagraphAt(index)?.text.orEmpty() },
            blockProvider = { book.cachedBlocks() },
            metrics = EpubPageMetrics(
                viewportWidthPx = 120,
                viewportHeightPx = 80,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 60,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 20f,
            ),
            lineBreaker = { text, _, _ -> listOf(0 to text.length) },
        )

        assertTrue(
            pages.any { it.kind == EpubPageSliceKind.Image("OEBPS/cover.png", altText = "Cover") },
        )
        assertTrue(
            pages.none { it.kind == EpubPageSliceKind.Image("OEBPS/cold.png", altText = "Cold") },
        )
        assertEquals(1, book.loadCount(0))
        assertEquals(0, book.loadCount(1))
    }

    @Test
    fun `close clears lazy cache and load counters`() {
        val epub = tempDir.resolve("close.epub").toFile()
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two</p></body></html>",
        )

        val book = EpubParser().parseLazyBook(epub, maxCachedSpines = 2)

        assertEquals("Chapter one", book.paragraphAt(0)?.text)
        assertEquals(setOf(0), book.cachedSpineIndexes())
        assertEquals(1, book.loadCount(0))

        book.close()

        assertEquals(emptySet<Int>(), book.cachedSpineIndexes())
        assertEquals(0, book.loadCount(0))
    }

    private fun writeEpub(file: File, vararg spineEntries: Pair<String, String>) {
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
                buildString {
                    appendLine("<package version=\"3.0\">")
                    appendLine("  <manifest>")
                    spineEntries.forEachIndexed { index, (path, _) ->
                        appendLine("    <item id=\"c$index\" href=\"${path.removePrefix("OEBPS/")}\" media-type=\"application/xhtml+xml\"/>")
                    }
                    appendLine("  </manifest>")
                    appendLine("  <spine>")
                    spineEntries.forEachIndexed { index, _ ->
                        appendLine("    <itemref idref=\"c$index\"/>")
                    }
                    appendLine("  </spine>")
                    appendLine("</package>")
                },
            )
            spineEntries.forEach { (path, content) -> add(path, content) }
        }
    }
}
