package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTocTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parseBook prefers epub three nav over ncx and maps hrefs to spine locators`() {
        val epub = tempDir.resolve("nav-first.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="nav" href="nav/nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="../text/ch1.xhtml#top">Nav One</a></li>
                        <li><a href="../text/ch2.xhtml#top">Nav Two</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
            "OEBPS/toc.ncx" to ncxXml("NCX Should Not Win", "text/ch1.xhtml"),
            "OEBPS/text/ch1.xhtml" to "<html><body><p>One</p></body></html>",
            "OEBPS/text/ch2.xhtml" to "<html><body><p>Two</p></body></html>",
        )

        val book = EpubParser().parseBook(epub)

        assertEquals(listOf("Nav One", "Nav Two"), book.tableOfContents.map { it.title })
        val second = book.tableOfContents[1]
        val secondLocator = second.locator.strategy as LocatorStrategy.Section
        assertEquals(1, secondLocator.spineIndex)
        assertEquals(1, secondLocator.elementIndex)
        assertEquals(0, second.level)
        assertEquals(0.5f, second.locator.totalProgression)
    }

    @Test
    fun `parseBook uses ncx toc when navigation document is absent`() {
        val epub = tempDir.resolve("ncx.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="2.0">
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx>
                  <navMap>
                    <navPoint>
                      <navLabel><text>NCX One</text></navLabel>
                      <content src="text/ch1.xhtml#start"/>
                      <navPoint>
                        <navLabel><text>NCX Nested</text></navLabel>
                        <content src="text/ch2.xhtml#nested"/>
                      </navPoint>
                    </navPoint>
                  </navMap>
                </ncx>
            """.trimIndent(),
            "OEBPS/text/ch1.xhtml" to "<html><body><p>One</p></body></html>",
            "OEBPS/text/ch2.xhtml" to "<html><body><p>Two</p></body></html>",
        )

        val book = EpubParser().parseBook(epub)

        assertEquals(listOf("NCX One", "NCX Nested"), book.tableOfContents.map { it.title })
        assertEquals(listOf(0, 1), book.tableOfContents.map { it.level })
        val nestedLocator = book.tableOfContents[1].locator.strategy as LocatorStrategy.Section
        assertEquals(1, nestedLocator.spineIndex)
        assertEquals(1, nestedLocator.elementIndex)
    }

    @Test
    fun `parseBook maps toc fragments to the paragraph containing matching id`() {
        val epub = tempDir.resolve("fragment-target.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="ch1.xhtml#scene">Scene</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p id="start">Start paragraph</p>
                  <p id="scene">Scene paragraph</p>
                </body></html>
            """.trimIndent(),
        )

        val book = EpubParser().parseBook(epub)

        val scene = book.tableOfContents.single().locator.strategy as LocatorStrategy.Section
        assertEquals(0, scene.spineIndex)
        assertEquals(1, scene.elementIndex)
        assertEquals(15, scene.charOffset)
    }

    @Test
    fun `parseBook and lazy parse map toc image fragments to synthetic image anchors`() {
        val epub = tempDir.resolve("image-fragment-target.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="ch1.xhtml#scene">Scene</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Q</p>
                  <img id="scene" src="scene.png" alt="Scene"/>
                  <p>R</p>
                </body></html>
            """.trimIndent(),
        )

        val fullBook = EpubParser().parseBook(epub)
        val lazyBook = EpubParser().parseLazyBook(epub)

        listOf(fullBook.tableOfContents.single(), lazyBook.tableOfContents.single()).forEach { toc ->
            val scene = toc.locator.strategy as LocatorStrategy.Section
            assertEquals(0, scene.spineIndex)
            assertEquals(0, scene.elementIndex)
            assertEquals(1, scene.charOffset)
        }
    }

    @Test
    fun `parseBook keeps toc entry for image only cover spine before later text spine`() {
        val epub = tempDir.resolve("cover-spine-nav.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="cover"/>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="cover.xhtml">Cover</a></li>
                        <li><a href="ch1.xhtml">Chapter One</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
            "OEBPS/cover.xhtml" to """
                <html><body>
                  <img id="cover-art" src="cover.png" alt="Cover art"/>
                </body></html>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one</p></body></html>",
        )

        val fullBook = EpubParser().parseBook(epub)
        val lazyBook = EpubParser().parseLazyBook(epub)

        listOf(fullBook.tableOfContents, lazyBook.tableOfContents).forEach { tocEntries ->
            assertEquals(listOf("Cover", "Chapter One"), tocEntries.map { it.title })
            val cover = tocEntries[0].locator.strategy as LocatorStrategy.Section
            val chapter = tocEntries[1].locator.strategy as LocatorStrategy.Section
            assertEquals(0, cover.spineIndex)
            assertEquals(0, cover.elementIndex)
            assertEquals(0, cover.charOffset)
            assertEquals(1, chapter.spineIndex)
            assertEquals(1, chapter.elementIndex)
            assertEquals(0, chapter.charOffset)
        }
    }

    @Test
    fun `lazy parse maps toc fragments in later spine documents`() {
        val epub = tempDir.resolve("lazy-fragment-target.epub").toFile()
        writeEpub(
            epub,
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="ch2.xhtml#scene">Scene</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one</p></body></html>",
            "OEBPS/ch2.xhtml" to """
                <html><body>
                  <p id="start">Start paragraph</p>
                  <p id="scene">Scene paragraph</p>
                </body></html>
            """.trimIndent(),
        )

        val fullBook = EpubParser().parseBook(epub)
        val lazyBook = EpubParser().parseLazyBook(epub)

        listOf(fullBook.tableOfContents.single(), lazyBook.tableOfContents.single()).forEach { toc ->
            val scene = toc.locator.strategy as LocatorStrategy.Section
            assertEquals(1, scene.spineIndex)
            assertEquals(2, scene.elementIndex)
            assertEquals(15, scene.charOffset)
        }
    }

    private fun containerXml(): String =
        """
            <container>
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

    private fun ncxXml(title: String, href: String): String =
        """
            <ncx>
              <navMap>
                <navPoint>
                  <navLabel><text>$title</text></navLabel>
                  <content src="$href"/>
                </navPoint>
              </navMap>
            </ncx>
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
