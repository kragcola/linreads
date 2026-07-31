package dev.readflow.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalReadingCapabilitiesTest {

    @Test
    fun `local format contract matches registered readable engines`() {
        assertEquals(
            setOf(BookFormat.EPUB, BookFormat.PDF, BookFormat.TXT, BookFormat.MD, BookFormat.CBZ),
            LocalReadingCapabilities.formats,
        )
        assertTrue(LocalReadingCapabilities.supportsExtension("CBZ"))
        assertTrue(LocalReadingCapabilities.supportsMimeType("application/vnd.comicbook+zip"))
        assertFalse(LocalReadingCapabilities.supportsExtension("docx"))
        assertFalse(LocalReadingCapabilities.supportsMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }
}
