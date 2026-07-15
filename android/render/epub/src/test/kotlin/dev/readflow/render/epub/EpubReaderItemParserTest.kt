package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
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
                  <p><img alt="image" src="../Images/image.jpeg"/><br/><span style="display:none">Hidden</span></p>
                </body></html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/Text",
        )

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items.single())
        assertEquals("OEBPS/Images/image.jpeg", image.href)
        assertEquals("image", image.altText)
        assertFalse(image.isInlineContent)
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
        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items[1])
        assertEquals("OEBPS/Images/mid.png", image.href)
        assertTrue(image.isInlineContent)
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
    fun `external embedded and inline css cascade by importance specificity and source order`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html>
                  <head>
                    <link rel="stylesheet" href="../styles/book.css"/>
                    <style>
                      p.lead { color: #224466; font-style: italic; text-decoration: underline; }
                      #target {
                        text-align: right;
                        text-indent: 2em;
                        margin: 1em 2em;
                        padding: 0.5em;
                        background-color: #ffeecc;
                        border-bottom: 1px solid #333333;
                      }
                      .accent { color: #008000 !important; text-transform: uppercase; }
                    </style>
                  </head>
                  <body>
                    <p id="target" class="lead"><span class="accent" style="color: #ff0000">Hello world</span></p>
                  </body>
                </html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/text",
            resourceTextLoader = { path ->
                if (path == "OEBPS/styles/book.css") {
                    "p { color: #111111; font-weight: bold; } .lead { text-align: center; }"
                } else {
                    null
                }
            },
        )

        val text = assertInstanceOf(EpubReaderItem.Text::class.java, items.single())
        assertEquals("HELLO WORLD", text.text)
        assertTrue(text.styleSpans.any { it.style == EpubTextStyle.Bold })
        assertTrue(text.styleSpans.any { it.style == EpubTextStyle.Italic })
        assertTrue(text.styleSpans.any { it.style == EpubTextStyle.Underline })
        assertTrue(
            text.styleSpans.any {
                it.style == EpubTextStyle.ForegroundColor && it.color == 0xFF008000.toInt()
            },
        )
        assertFalse(
            text.styleSpans.any {
                it.style == EpubTextStyle.ForegroundColor && it.color == 0xFFFF0000.toInt()
            },
        )
        assertEquals(
            EpubBlockStyle(
                textAlign = EpubTextAlign.End,
                textIndent = EpubCssLength(2f, EpubCssUnit.Em),
                margin = EpubCssInsets(
                    top = EpubCssLength(1f, EpubCssUnit.Em),
                    right = EpubCssLength(2f, EpubCssUnit.Em),
                    bottom = EpubCssLength(1f, EpubCssUnit.Em),
                    left = EpubCssLength(2f, EpubCssUnit.Em),
                ),
                padding = EpubCssInsets.all(EpubCssLength(0.5f, EpubCssUnit.Em)),
                backgroundColor = 0xFFFFEECC.toInt(),
                borders = EpubCssBorders(
                    bottom = EpubCssBorder(
                        width = EpubCssLength(1f, EpubCssUnit.Px),
                        style = EpubCssBorderStyle.Solid,
                        color = 0xFF333333.toInt(),
                    ),
                ),
            ),
            text.blockStyle,
        )
    }

    @Test
    fun `css inheritance semantic overrides whitespace and hidden content are respected`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html>
                  <head>
                    <style>
                      body { color: rgb(17, 34, 51); font-style: italic; }
                      .preline { white-space: pre-line; text-transform: uppercase; }
                      .hidden { display: none; }
                    </style>
                  </head>
                  <body>
                    <p class="preline">alpha   beta
                      gamma</p>
                    <p><strong style="font-weight: normal; text-decoration: line-through">Plain</strong> <u>Under</u></p>
                    <p class="hidden">Must not render</p>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(2, items.size)
        val preLine = assertInstanceOf(EpubReaderItem.Text::class.java, items[0])
        assertEquals("ALPHA BETA\nGAMMA", preLine.text)
        assertTrue(preLine.styleSpans.any { it.style == EpubTextStyle.Italic })
        assertTrue(
            preLine.styleSpans.any {
                it.style == EpubTextStyle.ForegroundColor && it.color == 0xFF112233.toInt()
            },
        )

        val overrides = assertInstanceOf(EpubReaderItem.Text::class.java, items[1])
        assertEquals("Plain Under", overrides.text)
        assertFalse(overrides.styleSpans.any { it.style == EpubTextStyle.Bold && it.start == 0 && it.end == 5 })
        assertTrue(overrides.styleSpans.any { it.style == EpubTextStyle.Strikethrough && it.start == 0 && it.end == 5 })
        assertTrue(overrides.styleSpans.any { it.style == EpubTextStyle.Underline && it.start == 6 && it.end == 11 })
    }

    @Test
    fun `css image dimensions and centered block alignment are retained`() {
        val items = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html>
                  <head>
                    <style>
                      img.hero {
                        display: block;
                        width: 50%;
                        max-height: 12em;
                        margin-left: auto;
                        margin-right: auto;
                      }
                    </style>
                  </head>
                  <body><img class="hero" src="../images/plate.jpg" alt="Plate"/></body>
                </html>
            """.trimIndent(),
            resourceBaseDir = "OEBPS/text",
        )

        val image = assertInstanceOf(EpubReaderItem.Image::class.java, items.single())
        assertEquals(
            EpubImageStyle(
                width = EpubCssLength(50f, EpubCssUnit.Percent),
                maxHeight = EpubCssLength(12f, EpubCssUnit.Em),
                alignment = EpubTextAlign.Center,
            ),
            image.style,
        )
    }

    @Test
    fun `nested inline content inherits one relative font size and css alpha hex uses rgba order`() {
        val item = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = "<html><body><p><small><span>Small</span></small></p></body></html>",
        ).single() as EpubReaderItem.Text

        assertTrue(
            item.styleSpans.any {
                it.style == EpubTextStyle.RelativeSize && it.scale == 0.8f && it.start == 0 && it.end == 5
            },
        )
        assertFalse(item.styleSpans.any { it.style == EpubTextStyle.RelativeSize && it.scale == 0.64f })
        assertEquals(0xDDAABBCC.toInt(), parseCssColor("#abcd"))
        assertEquals(0x44112233, parseCssColor("#11223344"))
    }

    @Test
    fun `relative font size inherits the computed scale without compounding through descendants`() {
        val item = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = "<html><body style=\"font-size: 1.2em\"><p><span><em>Deep</em></span></p></body></html>",
        ).single() as EpubReaderItem.Text

        assertTrue(item.styleSpans.any { it.style == EpubTextStyle.RelativeSize && it.scale == 1.2f })
        assertFalse(item.styleSpans.any { it.style == EpubTextStyle.RelativeSize && it.scale == 1.44f })
    }

    @Test
    fun `author css can override heading user agent size weight and alignment`() {
        val heading = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><head><style>h1 { font-size: 1em; font-weight: normal !important; text-align: left; }</style></head>
                <body><h1>Plain heading</h1></body></html>
            """.trimIndent(),
        ).single() as EpubReaderItem.Heading

        assertFalse(heading.styleSpans.any { it.style == EpubTextStyle.Bold })
        assertFalse(heading.styleSpans.any { it.style == EpubTextStyle.RelativeSize })
        assertEquals(EpubTextAlign.Start, heading.blockStyle.textAlign)
        assertTrue(heading.blockStyle.headingStyleResolved)
    }

    @Test
    fun `heading user agent style preserves the existing default without author css`() {
        val heading = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = "<html><body><h1>Default heading</h1></body></html>",
        ).single() as EpubReaderItem.Heading

        assertTrue(heading.styleSpans.any { it.style == EpubTextStyle.Bold })
        assertTrue(heading.styleSpans.any { it.style == EpubTextStyle.RelativeSize && it.scale == 1.5f })
        assertEquals(EpubTextAlign.Center, heading.blockStyle.textAlign)
    }

    @Test
    fun `display none suppresses body and block images without splitting visible paragraph text`() {
        val visible = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = """
                <html><body><p>Before<img style="display:none" src="hidden.png"/>After</p>
                <div><img class="hidden" src="also-hidden.png"/></div>
                <style>.hidden { display: none; }</style></body></html>
            """.trimIndent(),
        )
        assertEquals(listOf("BeforeAfter"), visible.filterIsInstance<EpubReaderItem.Text>().map { it.text })
        assertEquals(0, visible.count { it is EpubReaderItem.Image })

        val hiddenBody = parseReaderItemsFromHtml(
            spineIndex = 0,
            html = "<html><head><style>body { display: none; }</style></head><body><p>Hidden</p></body></html>",
        )
        assertEquals(emptyList<EpubReaderItem>(), hiddenBody)
    }

    @Test
    fun `stylesheet loading stops once the global rule limit is full`() {
        val loaded = mutableListOf<String>()
        val fullSheet = (0 until 4_096).joinToString("\n") { index -> ".r$index { color: black; }" }

        parseReaderItemsFromHtml(
            spineIndex = 0,
            resourceBaseDir = "OEBPS/text",
            html = """
                <html><head>
                  <link rel="stylesheet" href="../styles/full.css"/>
                  <link rel="stylesheet" href="../styles/unused.css"/>
                </head><body><p>Body</p></body></html>
            """.trimIndent(),
            resourceTextLoader = { path ->
                loaded += path
                when (path) {
                    "OEBPS/styles/full.css" -> fullSheet
                    "OEBPS/styles/unused.css" -> ".unused { color: red; }"
                    else -> null
                }
            },
        )

        assertEquals(listOf("OEBPS/styles/full.css"), loaded)
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
