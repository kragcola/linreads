package dev.readflow.ink

import android.graphics.Color

/** Brush types for ink strokes (§6.2). Uses android.graphics.Color int — no Compose dependency. */
sealed interface InkBrush {
    val colorInt: Int
    val sizePx: Float

    data class Pen(
        override val colorInt: Int = Color.BLACK,
        override val sizePx: Float = 3f,
    ) : InkBrush

    data class FountainPen(
        override val colorInt: Int = Color.BLACK,
        override val sizePx: Float = 4f,
        val pressureSensitivity: Float = 1f,  // [0,2]; 1 = linear
    ) : InkBrush

    data class Highlighter(
        override val colorInt: Int = Color.argb(100, 255, 235, 59),
        override val sizePx: Float = 20f,
    ) : InkBrush

    data object Eraser : InkBrush {
        override val colorInt: Int = Color.TRANSPARENT
        override val sizePx: Float = 24f
    }
}
