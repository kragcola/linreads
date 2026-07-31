package dev.readflow.features.reader

import android.view.KeyEvent
import dev.readflow.render.api.PageReadingDirection

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
    pageReadingDirection: PageReadingDirection = PageReadingDirection.LEFT_TO_RIGHT,
): ReaderTapZone? {
    if (interactiveChildConsumedTap) return null
    val physicalZone = classifyReaderTapZone(xRatio)
    val zone = when {
        pageReadingDirection != PageReadingDirection.RIGHT_TO_LEFT -> physicalZone
        physicalZone == ReaderTapZone.PreviousPage -> ReaderTapZone.NextPage
        physicalZone == ReaderTapZone.NextPage -> ReaderTapZone.PreviousPage
        else -> physicalZone
    }
    return when {
        pagedTapZonesEnabled -> zone
        zone == ReaderTapZone.ToggleChrome -> zone
        else -> null
    }
}

internal fun readerTapZoneForKey(
    keyCode: Int,
    shiftPressed: Boolean = false,
    pageReadingDirection: PageReadingDirection = PageReadingDirection.LEFT_TO_RIGHT,
): ReaderTapZone? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> if (pageReadingDirection == PageReadingDirection.RIGHT_TO_LEFT) {
        ReaderTapZone.NextPage
    } else {
        ReaderTapZone.PreviousPage
    }
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (pageReadingDirection == PageReadingDirection.RIGHT_TO_LEFT) {
        ReaderTapZone.PreviousPage
    } else {
        ReaderTapZone.NextPage
    }
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_VOLUME_UP,
    -> ReaderTapZone.PreviousPage
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
