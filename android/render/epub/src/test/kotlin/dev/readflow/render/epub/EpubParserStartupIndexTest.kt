package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserStartupIndexTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `package index reads metadata without reading spine xhtml`() {
        val epub = tempDir.resolve("package-index.epub").toFile()
        val firstChapter = "<html><body><p>First chapter</p></body></html>"
        val secondChapter = "<html><body><p>Second chapter</p></body></html>"
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <metadata>
                    <meta property="rendition:layout">pre-paginated</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="toc"><ol><li><a href="text/ch1.xhtml#start">One</a></li></ol></nav>
                </body></html>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap><navPoint><navLabel><text>One</text></navLabel>
                  <content src="text/ch1.xhtml#start"/>
                </navPoint></navMap></ncx>
            """.trimIndent(),
            "OEBPS/text/ch1.xhtml" to firstChapter,
            "OEBPS/text/ch2.xhtml" to secondChapter,
        )
        val reads = mutableListOf<Int>()

        val index = EpubParser(onSpineRead = reads::add).parsePackageIndex(epub)

        assertEquals(emptyList<Int>(), reads)
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"), index.spinePaths)
        assertEquals(
            listOf(firstChapter.toByteArray().size.toLong(), secondChapter.toByteArray().size.toLong()),
            index.spineEntryWeights,
        )
        assertTrue(index.isFixedLayout)
        assertEquals(listOf("One"), index.navDocument?.entries?.map(EpubParsedTocEntry::title))
        assertEquals(listOf("One"), index.ncxDocument?.entries?.map(EpubParsedTocEntry::title))
    }

    @Test
    fun `parse spine reads only target with local offsets fragments and linked css`() {
        val epub = tempDir.resolve("single-spine.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="styles/book.css" media-type="text/css"/>
                    <item id="image" href="images/figure.png" media-type="image/png"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/text/ch1.xhtml" to "<html><body><p>Do not read me</p></body></html>",
            "OEBPS/text/ch2.xhtml" to """
                <html><head><link rel="stylesheet" href="../styles/book.css"/></head><body>
                  <h1 id="start">Styled chapter</h1>
                  <p id="body" class="accent">Second paragraph</p>
                  <img id="figure" src="../images/figure.png" alt="Figure"/>
                </body></html>
            """.trimIndent(),
            "OEBPS/styles/book.css" to """
                .accent { color: #13579b; font-weight: 700; text-align: center; }
            """.trimIndent(),
            "OEBPS/images/figure.png" to "not-decoded-by-parser",
        )
        val reads = mutableListOf<Int>()
        val parser = EpubParser(onSpineRead = reads::add)
        val packageIndex = parser.parsePackageIndex(epub)

        val spine = parser.parseSpine(epub, packageIndex, spineIndex = 1)

        assertEquals(listOf(1), reads)
        assertEquals("OEBPS/text/ch2.xhtml", spine.path)
        assertEquals(listOf("Styled chapter", "Second paragraph"), spine.paras.map(EpubPara::text))
        assertEquals(listOf(0, 14), spine.paras.map(EpubPara::documentCharStart))
        assertEquals(listOf(14, 30), spine.paras.map(EpubPara::documentCharEnd))
        assertEquals(listOf(0, 1, 1), spine.blocks.map(EpubDisplayBlock::paragraphIndex))
        assertEquals(30, spine.charCount)
        assertEquals(EpubTargetPosition(paragraphIndex = 0), spine.fragmentTargetIndexes["OEBPS/text/ch2.xhtml#start"])
        assertEquals(EpubTargetPosition(paragraphIndex = 1), spine.fragmentTargetIndexes["OEBPS/text/ch2.xhtml#body"])
        assertEquals(
            EpubTargetPosition(paragraphIndex = 1, paragraphOffset = 16),
            spine.fragmentTargetIndexes["OEBPS/text/ch2.xhtml#figure"],
        )
        val styledBlock = spine.blocks[1] as EpubDisplayBlock.Text
        assertEquals(EpubTextAlign.Center, styledBlock.blockStyle.textAlign)
        assertTrue(styledBlock.styleSpans.any { it.style == EpubTextStyle.Bold })
        assertTrue(
            styledBlock.styleSpans.any {
                it.style == EpubTextStyle.ForegroundColor && it.color == 0xFF13579B.toInt()
            },
        )
    }

    @Test
    fun `package and spine parsing reject malicious missing and oversized entries without reads`() {
        val epub = tempDir.resolve("guarded-index.epub").toFile()
        val oversizedChapter = "x".repeat(EPUB_MAX_SPINE_ENTRY_BYTES + 1)
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="escape" href="../../evil.xhtml" media-type="application/xhtml+xml"/>
                    <item id="missing" href="missing.xhtml" media-type="application/xhtml+xml"/>
                    <item id="large" href="large.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="escape"/>
                    <itemref idref="missing"/>
                    <itemref idref="large"/>
                  </spine>
                </package>
            """.trimIndent(),
            "evil.xhtml" to "<html><body><p>Escaped content</p></body></html>",
            "OEBPS/large.xhtml" to oversizedChapter,
        )
        val reads = mutableListOf<Int>()
        val parser = EpubParser(onSpineRead = reads::add)

        val packageIndex = parser.parsePackageIndex(epub)
        val missing = parser.parseSpine(epub, packageIndex, spineIndex = 0)
        val oversized = parser.parseSpine(epub, packageIndex, spineIndex = 1)
        val outOfRange = parser.parseSpine(epub, packageIndex, spineIndex = 2)

        assertEquals(listOf("OEBPS/missing.xhtml", "OEBPS/large.xhtml"), packageIndex.spinePaths)
        assertEquals(listOf(0L, oversizedChapter.toByteArray().size.toLong()), packageIndex.spineEntryWeights)
        assertEquals(emptyList<Int>(), reads)
        assertEquals(EpubParsedSpine.empty(0, "OEBPS/missing.xhtml"), missing)
        assertEquals(EpubParsedSpine.empty(1, "OEBPS/large.xhtml"), oversized)
        assertEquals(EpubParsedSpine.empty(2), outOfRange)
    }

    @Test
    fun `invalid package metadata returns an empty startup index`() {
        val epub = tempDir.resolve("invalid-package.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <!DOCTYPE package [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <package version="3.0">
                  <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to "<html><body><p>Should not load</p></body></html>",
        )
        val reads = mutableListOf<Int>()

        val index = EpubParser(onSpineRead = reads::add).parsePackageIndex(epub)

        assertEquals(EpubPackageIndex.empty(), index)
        assertEquals(emptyList<Int>(), reads)
    }

    private fun containerXml(): String =
        """
            <container><rootfiles>
              <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles></container>
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
