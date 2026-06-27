package dev.readflow.features.reader

import android.view.KeyEvent

internal enum class ReaderTapZone {
    PreviousPage,
    ToggleChrome,
    NextPage,
}

internal fun classifyReaderTapZone(xRatio: Float): ReaderTapZone {
    val clamped = xRatio.coerceIn(0f, 1f)
    return when {
        clamped < ONE_THIRD -> ReaderTapZone.PreviousPage
        clamped <= TWO_THIRDS -> ReaderTapZone.ToggleChrome
        else -> ReaderTapZone.NextPage
    }
}

internal fun readerTapZoneForTap(
    xRatio: Float,
    interactiveChildConsumedTap: Boolean = false,
    pagedTapZonesEnabled: Boolean = true,
): ReaderTapZone? {
    if (interactiveChildConsumedTap) return null
    val zone = classifyReaderTapZone(xRatio)
    return when {
        pagedTapZonesEnabled -> zone
        zone == ReaderTapZone.ToggleChrome -> zone
        else -> null
    }
}

internal fun readerTapZoneForKey(keyCode: Int, shiftPressed: Boolean = false): ReaderTapZone? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_VOLUME_UP,
    -> ReaderTapZone.PreviousPage
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    -> ReaderTapZone.NextPage
    KeyEvent.KEYCODE_SPACE -> if (shiftPressed) ReaderTapZone.PreviousPage else ReaderTapZone.NextPage
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    -> ReaderTapZone.ToggleChrome
    else -> null
}

private const val ONE_THIRD = 1f / 3f
private const val TWO_THIRDS = 2f / 3f
