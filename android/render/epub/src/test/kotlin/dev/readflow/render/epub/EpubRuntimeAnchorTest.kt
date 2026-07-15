package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpubRuntimeAnchorTest {

    @Test
    fun `section locator resolves local paragraph and canonical global base`() {
        val paras = listOf(
            para(spine = 4, start = 0, end = 12),
            para(spine = 4, start = 12, end = 30),
            para(spine = 4, start = 30, end = 45),
        )

        val resolved = resolveProvisionalSection(
            LocatorStrategy.Section(spineIndex = 4, elementIndex = 91, charOffset = 17),
            paras,
        )

        assertEquals(EpubAnchor(spineIndex = 4, localParagraphIndex = 1, paragraphOffset = 5), resolved?.anchor)
        assertEquals(90, resolved?.globalParagraphBase)
    }

    @Test
    fun `new book anchor starts at global base zero`() {
        val paras = listOf(para(spine = 0, start = 0, end = 20))

        val resolved = resolveProvisionalSection(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 0),
            paras,
        )

        assertEquals(EpubAnchor(0, 0, 0), resolved?.anchor)
        assertEquals(0, resolved?.globalParagraphBase)
    }

    @Test
    fun `character offset at final text end resolves the last paragraph`() {
        val paras = listOf(
            para(spine = 2, start = 0, end = 10),
            para(spine = 2, start = 10, end = 25),
        )

        val resolved = resolveProvisionalSection(
            LocatorStrategy.Section(spineIndex = 2, elementIndex = 41, charOffset = 25),
            paras,
        )

        assertEquals(EpubAnchor(2, 1, 15), resolved?.anchor)
        assertEquals(40, resolved?.globalParagraphBase)
    }

    @Test
    fun `ambiguous zero length paragraph boundary refuses provisional guessing`() {
        val paras = listOf(
            para(spine = 3, start = 0, end = 0),
            para(spine = 3, start = 0, end = 0),
            para(spine = 3, start = 0, end = 12),
        )

        val resolved = resolveProvisionalSection(
            LocatorStrategy.Section(spineIndex = 3, elementIndex = 52, charOffset = 0),
            paras,
        )

        assertNull(resolved)
    }

    private fun para(spine: Int, start: Int, end: Int): EpubPara =
        EpubPara(
            spineIndex = spine,
            text = "",
            spineCharStart = start,
            spineCharEnd = end,
            documentCharStart = start,
            documentCharEnd = end,
        )
}
