package dev.readflow.features.reader

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderTapZoneTest {

    @Test
    fun `left third goes to previous page`() {
        assertEquals(ReaderTapZone.PreviousPage, classifyReaderTapZone(0.1f))
        assertEquals(ReaderTapZone.PreviousPage, classifyReaderTapZone(0.32f))
    }

    @Test
    fun `middle third toggles chrome`() {
        assertEquals(ReaderTapZone.ToggleChrome, classifyReaderTapZone(0.34f))
        assertEquals(ReaderTapZone.ToggleChrome, classifyReaderTapZone(0.5f))
        assertEquals(ReaderTapZone.ToggleChrome, classifyReaderTapZone(0.66f))
    }

    @Test
    fun `right third goes to next page`() {
        assertEquals(ReaderTapZone.NextPage, classifyReaderTapZone(0.67f))
        assertEquals(ReaderTapZone.NextPage, classifyReaderTapZone(0.95f))
    }

    @Test
    fun `ratio outside view bounds is clamped`() {
        assertEquals(ReaderTapZone.PreviousPage, classifyReaderTapZone(-0.2f))
        assertEquals(ReaderTapZone.NextPage, classifyReaderTapZone(1.2f))
    }

    @Test
    fun `hardware page keys map to reader actions`() {
        assertEquals(ReaderTapZone.PreviousPage, readerTapZoneForKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ReaderTapZone.PreviousPage, readerTapZoneForKey(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ReaderTapZone.NextPage, readerTapZoneForKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReaderTapZone.NextPage, readerTapZoneForKey(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(ReaderTapZone.NextPage, readerTapZoneForKey(KeyEvent.KEYCODE_SPACE))
        assertEquals(ReaderTapZone.PreviousPage, readerTapZoneForKey(KeyEvent.KEYCODE_SPACE, shiftPressed = true))
        assertEquals(ReaderTapZone.ToggleChrome, readerTapZoneForKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(ReaderTapZone.ToggleChrome, readerTapZoneForKey(KeyEvent.KEYCODE_ENTER))
        assertNull(readerTapZoneForKey(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `interactive child tap suppresses tap zone action`() {
        assertNull(readerTapZoneForTap(0.5f, interactiveChildConsumedTap = true))
    }

    @Test
    fun `plain tap still maps to tap zone when child did not consume interactive click`() {
        assertEquals(ReaderTapZone.ToggleChrome, readerTapZoneForTap(0.5f))
    }

    @Test
    fun `continuous mode only keeps middle chrome tap`() {
        assertNull(readerTapZoneForTap(0.1f, pagedTapZonesEnabled = false))
        assertEquals(ReaderTapZone.ToggleChrome, readerTapZoneForTap(0.5f, pagedTapZonesEnabled = false))
        assertNull(readerTapZoneForTap(0.9f, pagedTapZonesEnabled = false))
    }
}
