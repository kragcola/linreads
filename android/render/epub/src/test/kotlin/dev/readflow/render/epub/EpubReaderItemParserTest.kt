package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class EpubReaderItemParserTest {

    @Test
    fun `html is parsed into typed reader items in document order`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 2,
            html = """
                <html><body>
                  <h2>Chapter One</h2>
                  <p>Hello <strong>world</strong></p>
                  <img src="images/cover.jpg" alt="Cover art"/>
                  <hr/>
                </body></html>
            """.trimIndent(),
        )

        assertEquals(4, items.size)
        val heading = assertInstanceOf(EpubReaderItem.Heading::class.java, items[0])
        assertEquals(EpubItemLocator(spineIndex = 2, elementIndex = 0), heading.locator)
        assertEquals(2, heading.level)
        assertEquals("Chapter One", heading.text)

        val text = assertInstanceOf(EpubReaderItem.Text::class.java, items[1])
        assertEquals(EpubItemLocator(spineIndex = 2, elementIndex = 1), text.locator)
        assertEquals("Hello world", text.text)

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items[2])
        assertEquals(EpubItemLocator(spineIndex = 2, elementIndex = 2), image.locator)
        assertEquals("images/cover.jpg", image.href)
        assertEquals("Cover art", image.altText)

        val br = assertInstanceOf(EpubReaderItem.Break::class.java, items[3])
        assertEquals(EpubItemLocator(spineIndex = 2, elementIndex = 3), br.locator)
    }

    @Test
    fun `svg image cover is parsed into an image item via xlink href`() {
        // EPUB 3 fixed-layout covers commonly wrap the bitmap in <svg><image xlink:href=".."/></svg>
        // instead of <img> — the flow parser must still surface it as an Image item (else the cover
        // is dropped to text/unknown and never renders).
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <div style="text-align: center;">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1434 2048">
                      <image width="1434" height="2048" xlink:href="../Images/cover.jpg"/>
                    </svg>
                  </div>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/Text",
        )

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items.single())
        assertEquals("OEBPS/Images/cover.jpg", image.href)
    }

    @Test
    fun `text links keep ranges and distinguish internal from external hrefs`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p>Read the <a href="../notes.xhtml#n1">note</a> and <a href="https://example.com">site</a>.</p>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/text",
        )

        val text = assertInstanceOf(EpubReaderItem.Text::class.java, items.single())
        assertEquals("Read the note and site.", text.text)
        assertEquals(
            listOf(
                EpubTextLink(start = 9, end = 13, href = "OEBPS/notes.xhtml#n1", isExternal = false),
                EpubTextLink(start = 18, end = 22, href = "https://example.com", isExternal = true),
            ),
            text.links,
        )
    }

    @Test
    fun `same document fragment links resolve through the current spine path`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p>See <a href="#note">note</a>.</p>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/text",
            documentPath = "OEBPS/text/ch1.xhtml",
        )

        val text = assertInstanceOf(EpubReaderItem.Text::class.java, items.single())
        assertEquals(
            listOf(EpubTextLink(start = 4, end = 8, href = "OEBPS/text/ch1.xhtml#note", isExternal = false)),
            text.links,
        )
    }

    @Test
    fun `image hrefs can be resolved relative to the spine document`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <img src="../images/cover.jpg" alt="Cover"/>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/text",
        )

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items.single())
        assertEquals("OEBPS/images/cover.jpg", image.href)
    }

    @Test
    fun `image wrapped in a paragraph is emitted as a block image`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p><img alt="image" src="../Images/image.jpeg"/><br/></p>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/Text",
        )

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items.single())
        assertEquals("OEBPS/Images/image.jpeg", image.href)
        assertEquals("image", image.altText)
    }

    @Test
    fun `paragraph mixing text and image preserves document order`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p>Lead text<img alt="mid" src="../Images/mid.png"/>tail text</p>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/Text",
        )

        assertEquals(3, items.size)
        assertEquals("Lead text", assertInstanceOf(EpubReaderItem.Text::class.java, items[0]).text)
        assertEquals("OEBPS/Images/mid.png", assertInstanceOf(EpubReaderItem.Image::class.java, items[1]).href)
        assertEquals("tail text", assertInstanceOf(EpubReaderItem.Text::class.java, items[2]).text)
    }

    @Test
    fun `image wrapped in a pure link container is emitted as a block image`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <div class="illus"><a href="../Images/plate.jpg"><img alt="plate" src="../Images/plate.jpg"/></a></div>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/Text",
        )

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items.single())
        assertEquals("OEBPS/Images/plate.jpg", image.href)
        assertEquals("plate", image.altText)
    }

    @Test
    fun `footnote marker image nested in a link stays inline text not a block image`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p>胆小鬼<a class="duokan-footnote" href="#note1"><sup><img alt="note" src="../Images/note.png"/></sup></a>？</p>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/Text",
        )

        val text = assertInstanceOf(EpubReaderItem.Text::class.java, items.single())
        assertEquals("胆小鬼？", text.text)
    }

    @Test
    fun `table pre blockquote nested lists and unknown blocks degrade to readable text`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <style>p { color: red; }</style>
                  <script>alert('x')</script>
                  <table>
                    <tr><td>A</td><td>B</td></tr>
                    <tr><td>C</td><td>D</td></tr>
                  </table>
                  <pre>line one
                    line two</pre>
                  <blockquote><p>Quoted text</p></blockquote>
                  <ul>
                    <li>First<ul><li>Nested</li></ul></li>
                  </ul>
                  <aside>Unknown body</aside>
                </body></html>
            """.trimIndent(),
        )

        assertEquals(6, items.size)
        val table = assertInstanceOf(EpubReaderItem.Text::class.java, items[0])
        assertEquals(EpubTextKind.Table, table.kind)
        assertEquals("A, B\nC, D", table.text)

        val pre = assertInstanceOf(EpubReaderItem.Text::class.java, items[1])
        assertEquals(EpubTextKind.Preformatted, pre.kind)
        assertEquals("line one\n    line two", pre.text)

        val quote = assertInstanceOf(EpubReaderItem.Text::class.java, items[2])
        assertEquals(EpubTextKind.Blockquote, quote.kind)
        assertEquals("Quoted text", quote.text)

        val first = assertInstanceOf(EpubReaderItem.Text::class.java, items[3])
        assertEquals(EpubTextKind.ListItem, first.kind)
        assertEquals(0, first.indentLevel)
        assertEquals("• First", first.text)

        val nested = assertInstanceOf(EpubReaderItem.Text::class.java, items[4])
        assertEquals(EpubTextKind.ListItem, nested.kind)
        assertEquals(1, nested.indentLevel)
        assertEquals("• Nested", nested.text)

        val unknown = assertInstanceOf(EpubReaderItem.Text::class.java, items[5])
        assertEquals(EpubTextKind.Body, unknown.kind)
        assertEquals("Unknown body", unknown.text)
    }

    @Test
    fun `inline strong emphasis code sub sup and ruby degrade to styled spans`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p><strong>Bold</strong> <em>Italic</em> <code>x</code> H<sub>2</sub> O<sup>2</sup> <ruby>漢<rt>han</rt></ruby></p>
                </body></html>
            """.trimIndent(),
        )

        val text = assertInstanceOf(EpubReaderItem.Text::class.java, items.single())
        assertEquals("Bold Italic x H2 O2 漢(han)", text.text)
        assertEquals(
            listOf(
                EpubTextStyleSpan(0, 4, EpubTextStyle.Bold),
                EpubTextStyleSpan(5, 11, EpubTextStyle.Italic),
                EpubTextStyleSpan(12, 13, EpubTextStyle.Code),
                EpubTextStyleSpan(15, 16, EpubTextStyle.Subscript),
                EpubTextStyleSpan(18, 19, EpubTextStyle.Superscript),
            ),
            text.styleSpans,
        )
    }

    @Test
    fun `invisible spacer paragraphs are ignored`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body>
                  <p>&nbsp;</p>
                  <p>　</p>
                  <p>&#8203;</p>
                  <p>&#65279;</p>
                  <p>&#8288;</p>
                  <p>&#8204;&#8205;</p>
                  <p>第一句很短。</p>
                  <p><span>&nbsp;&#8203;&#65279;&#8288;</span></p>
                  <p>第二句也短。</p>
                </body></html>
            """.trimIndent(),
        )

        assertEquals(2, items.size)
        assertEquals("第一句很短。", assertInstanceOf(EpubReaderItem.Text::class.java, items[0]).text)
        assertEquals("第二句也短。", assertInstanceOf(EpubReaderItem.Text::class.java, items[1]).text)
    }

    @Test
    fun `text and heading reader items can feed existing paragraph renderer`() {
        val paras = epubParasFromReaderItems(
            listOf(
                EpubReaderItem.Heading(EpubItemLocator(0, 0), level = 1, text = "Title"),
                EpubReaderItem.Text(EpubItemLocator(0, 1), text = "Body"),
                EpubReaderItem.Image(EpubItemLocator(0, 2), href = "cover.jpg", altText = null),
                EpubReaderItem.Break(EpubItemLocator(1, 0)),
                EpubReaderItem.Text(EpubItemLocator(1, 1), text = "Next"),
            ),
        )

        assertEquals(listOf("Title", "Body", "Next"), paras.map { it.text })
        assertEquals(1, paras[2].spineIndex)
        assertEquals(9, paras[2].documentCharStart)
    }
}
