package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpubSelectionTest {

    @Test
    fun `selection maps paragraph character range to section anchors`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("Alpha beta", "Needle text"),
                listOf("Other spine"),
            ),
        )
        val start = "Needle text".indexOf("Needle")
        val end = "Needle text".indexOf("text") + "text".length

        val selection = epubTextSelection(paras, paragraphIndex = 1, start, end) { paras[it] }!!

        assertEquals("Needle text", selection.selectedText)
        assertEquals(
            LocatorStrategy.Section(0, 1, "Alpha beta".length + start),
            selection.start.strategy,
        )
        assertEquals(
            LocatorStrategy.Section(0, 1, "Alpha beta".length + end),
            selection.end.strategy,
        )
    }

    @Test
    fun `selection trims edge whitespace and standalone word connectors`() {
        val text = "Alpha - beta"
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))
        val start = text.indexOf("-")
        val end = text.length

        val selection = epubTextSelection(paras, paragraphIndex = 0, start, end) { paras[it] }!!

        assertEquals("beta", selection.selectedText)
        assertEquals(
            LocatorStrategy.Section(0, 0, text.indexOf("beta")),
            selection.start.strategy,
        )
        assertEquals(
            LocatorStrategy.Section(0, 0, text.length),
            selection.end.strategy,
        )
    }

    @Test
    fun `compose initial selection expands long press offset to word range`() {
        val text = "Alpha Compose beta"
        val start = text.indexOf("Compose")

        val range = epubComposeInitialSelectionRange(text, start + 2)

        assertEquals(start to start + "Compose".length, range)
    }

    @Test
    fun `compose initial selection keeps combining mark with word range`() {
        val word = "Cafe\u0301"
        val text = "$word selection"
        val baseOffset = text.indexOf("e")
        val markOffset = baseOffset + 1

        val baseRange = epubComposeInitialSelectionRange(text, baseOffset)
        val markRange = epubComposeInitialSelectionRange(text, markOffset)

        assertEquals(0 to word.length, baseRange)
        assertEquals(0 to word.length, markRange)
    }

    @Test
    fun `compose initial selection expands typographic apostrophe word`() {
        val word = "reader\u2019s"
        val text = "$word selection"
        val offset = text.indexOf("s")

        val range = epubComposeInitialSelectionRange(text, offset)

        assertEquals(0 to word.length, range)
    }

    @Test
    fun `compose initial selection expands unicode hyphen word`() {
        listOf("well\u2010being", "well\u2011being").forEach { word ->
            val text = "$word selection"
            val offset = text.indexOf("being")

            val range = epubComposeInitialSelectionRange(text, offset)

            assertEquals(0 to word.length, range)
        }
    }

    @Test
    fun `compose initial selection ignores standalone word connector punctuation`() {
        val hyphenText = "Alpha - beta"
        val apostropheText = "Alpha \u2019 beta"

        val hyphenRange = epubComposeInitialSelectionRange(hyphenText, hyphenText.indexOf("-"))
        val apostropheRange = epubComposeInitialSelectionRange(apostropheText, apostropheText.indexOf("\u2019"))

        assertNull(hyphenRange)
        assertNull(apostropheRange)
    }

    @Test
    fun `compose initial selection keeps cjk long press to one character`() {
        val text = "中文选择测试"
        val offset = text.indexOf("选")

        val range = epubComposeInitialSelectionRange(text, offset)

        assertEquals(offset to offset + 1, range)
    }

    @Test
    fun `compose initial selection keeps cjk surrogate pair to one code point`() {
        val text = "\uD840\uDC00文"

        val range = epubComposeInitialSelectionRange(text, 0)

        assertEquals(0 to 2, range)
    }

    @Test
    fun `compose initial selection keeps emoji zwj sequence to one text element`() {
        val emoji = "\uD83D\uDC69\u200D\uD83D\uDCBB"
        val text = "Alpha $emoji beta"
        val start = text.indexOf(emoji)

        val range = epubComposeInitialSelectionRange(text, start)

        assertEquals(start to start + emoji.length, range)
    }

    @Test
    fun `compose initial selection ignores whitespace offsets`() {
        val text = "Alpha Compose beta"

        val range = epubComposeInitialSelectionRange(text, text.indexOf(" "))

        assertNull(range)
    }

    @Test
    fun `compose initial selection event reports word range with anchor`() {
        val text = "Alpha Compose beta"
        val start = text.indexOf("Compose")

        val event = epubComposeInitialSelectionAt(text, start + 2)

        assertEquals(start to start + "Compose".length, event.range)
        assertEquals(start, event.anchor)
    }

    @Test
    fun `compose initial selection event reports collapsed range for whitespace`() {
        val text = "Alpha Compose beta"
        val whitespace = text.indexOf(" ")

        val event = epubComposeInitialSelectionAt(text, whitespace)

        assertEquals(whitespace to whitespace, event.range)
        assertNull(event.anchor)
    }

    @Test
    fun `compose initial selection event reports collapsed range outside text`() {
        val text = "Alpha Compose beta"

        val beforeText = epubComposeInitialSelectionAt(text, -1)
        val afterText = epubComposeInitialSelectionAt(text, text.length)

        assertEquals(0 to 0, beforeText.range)
        assertNull(beforeText.anchor)
        assertEquals(text.length to text.length, afterText.range)
        assertNull(afterText.anchor)
    }

    @Test
    fun `compose drag selection keeps initial word when dragging before it`() {
        val text = "Alpha Compose beta"
        val wordStart = text.indexOf("Compose")
        val wordEnd = wordStart + "Compose".length
        val focus = text.indexOf("Alpha")
        val initialSelection = epubComposeInitialSelectionAt(text, wordStart + 2)

        val range = epubComposeDragSelectionRange(initialSelection, focus)

        assertEquals(focus to wordEnd, range)
    }

    @Test
    fun `compose drag selection keeps initial word when dragging after it`() {
        val text = "Alpha Compose beta"
        val wordStart = text.indexOf("Compose")
        val focus = text.length
        val initialSelection = epubComposeInitialSelectionAt(text, wordStart + 2)

        val range = epubComposeDragSelectionRange(initialSelection, focus)

        assertEquals(wordStart to focus, range)
    }

    @Test
    fun `compose drag selection keeps emoji focus on text element boundary`() {
        val emoji = "\uD83D\uDC69\u200D\uD83D\uDCBB"
        val text = "Alpha $emoji beta"
        val emojiStart = text.indexOf(emoji)
        val initialSelection = epubComposeInitialSelectionAt(text, text.indexOf("Alpha") + 2)

        val range = epubComposeDragSelectionRange(initialSelection, emojiStart + 1)

        assertEquals(text.indexOf("Alpha") to emojiStart + emoji.length, range)
    }

    @Test
    fun `compose drag selection keeps emoji modifier focus on text element boundary`() {
        val emoji = "\uD83D\uDC4D\uD83C\uDFFD"
        val text = "Alpha $emoji beta"
        val emojiStart = text.indexOf(emoji)
        val initialSelection = epubComposeInitialSelectionAt(text, text.indexOf("Alpha") + 2)

        val range = epubComposeDragSelectionRange(initialSelection, emojiStart + emoji.length - 1)

        assertEquals(text.indexOf("Alpha") to emojiStart + emoji.length, range)
    }

    @Test
    fun `compose drag selection ignores standalone word connector focus`() {
        val text = "Alpha - beta"
        val wordStart = text.indexOf("Alpha")
        val wordEnd = wordStart + "Alpha".length
        val initialSelection = epubComposeInitialSelectionAt(text, wordStart + 2)

        val range = epubComposeDragSelectionRange(initialSelection, text.indexOf("-"))

        assertEquals(wordStart to wordEnd, range)
    }

    @Test
    fun `compose drag selection keeps initial word while focus stays inside it`() {
        val text = "Alpha Compose beta"
        val wordStart = text.indexOf("Compose")
        val wordEnd = wordStart + "Compose".length
        val focus = wordStart + 3
        val initialSelection = epubComposeInitialSelectionAt(text, wordStart + 2)

        val range = epubComposeDragSelectionRange(initialSelection, focus)

        assertEquals(wordStart to wordEnd, range)
    }

    @Test
    fun `compose drag selection ignores collapsed initial selection`() {
        val text = "Alpha Compose beta"
        val whitespace = text.indexOf(" ")
        val initialSelection = epubComposeInitialSelectionAt(text, whitespace)

        val range = epubComposeDragSelectionRange(initialSelection, text.length)

        assertNull(range)
    }
}
