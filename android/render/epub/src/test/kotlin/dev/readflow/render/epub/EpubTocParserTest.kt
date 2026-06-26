package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubTocParserTest {

    @Test
    fun `epub navigation document yields toc entries with nesting levels`() {
        val entries = parseNavTocEntries(
            """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                  <body>
                    <nav epub:type="landmarks">
                      <ol><li><a href="cover.xhtml">Cover</a></li></ol>
                    </nav>
                    <nav epub:type="toc">
                      <ol>
                        <li>
                          <a href="text/ch1.xhtml#title">Chapter One</a>
                          <ol>
                            <li><a href="text/ch1.xhtml#scene">Opening Scene</a></li>
                          </ol>
                        </li>
                        <li><a href="text/ch2.xhtml">Chapter Two</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                EpubParsedTocEntry("Chapter One", "text/ch1.xhtml#title", level = 0),
                EpubParsedTocEntry("Opening Scene", "text/ch1.xhtml#scene", level = 1),
                EpubParsedTocEntry("Chapter Two", "text/ch2.xhtml", level = 0),
            ),
            entries,
        )
    }

    @Test
    fun `epub two ncx yields toc entries with nesting levels`() {
        val entries = parseNcxTocEntries(
            """
                <ncx>
                  <navMap>
                    <navPoint id="n1" playOrder="1">
                      <navLabel><text>Chapter One</text></navLabel>
                      <content src="chapter1.xhtml#start"/>
                      <navPoint id="n1-1" playOrder="2">
                        <navLabel><text>Nested Section</text></navLabel>
                        <content src="chapter1.xhtml#nested"/>
                      </navPoint>
                    </navPoint>
                    <navPoint id="n2" playOrder="3">
                      <navLabel><text>Chapter Two</text></navLabel>
                      <content src="chapter2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                EpubParsedTocEntry("Chapter One", "chapter1.xhtml#start", level = 0),
                EpubParsedTocEntry("Nested Section", "chapter1.xhtml#nested", level = 1),
                EpubParsedTocEntry("Chapter Two", "chapter2.xhtml", level = 0),
            ),
            entries,
        )
    }
}
