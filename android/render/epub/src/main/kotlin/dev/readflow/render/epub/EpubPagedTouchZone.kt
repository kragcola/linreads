package dev.readflow.render.epub

internal enum class EpubPagedTouchZone {
    PageTurn,
    CenterDead,
    TemporaryScroll,
}

internal object EpubPagedTouchZones {
    fun classify(width: Int, height: Int, downX: Float, downY: Float): EpubPagedTouchZone {
        if (width <= 0 || height <= 0) return EpubPagedTouchZone.PageTurn
        if (isInsideCenteredBox(width, height, downX, downY, divisor = 5f)) {
            return EpubPagedTouchZone.TemporaryScroll
        }
        if (isInsideCenteredBox(width, height, downX, downY, divisor = 3f)) {
            return EpubPagedTouchZone.CenterDead
        }
        return EpubPagedTouchZone.PageTurn
    }

    private fun isInsideCenteredBox(
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        divisor: Float,
    ): Boolean {
        val boxWidth = width / divisor
        val boxHeight = height / divisor
        val left = (width - boxWidth) / 2f
        val right = (width + boxWidth) / 2f
        val top = (height - boxHeight) / 2f
        val bottom = (height + boxHeight) / 2f
        return x >= left - EDGE_EPSILON_PX &&
            x <= right + EDGE_EPSILON_PX &&
            y >= top - EDGE_EPSILON_PX &&
            y <= bottom + EDGE_EPSILON_PX
    }

    private const val EDGE_EPSILON_PX = 0.5f
}
