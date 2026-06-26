package dev.readflow.render.animate

import kotlin.math.abs

internal data class PageTransformValues(
    val alpha: Float = 1f,
    val pivotXFraction: Float = 0.5f,
    val pivotYFraction: Float = 0.5f,
    val rotationY: Float = 0f,
    val cameraDistance: Float = 20_000f,
)

internal fun curlTransformFor(position: Float): PageTransformValues {
    val clamped = position.coerceIn(-1f, 1f)
    val pivotX = if (clamped < 0f) 1f else 0f
    if (position <= -1f || position >= 1f) {
        return PageTransformValues(
            alpha = 0f,
            pivotXFraction = pivotX,
            rotationY = -MAX_CURL_ROTATION * clamped,
        )
    }
    return PageTransformValues(
        alpha = (1f - abs(clamped) * CURL_EDGE_FADE).coerceIn(0f, 1f),
        pivotXFraction = pivotX,
        rotationY = -MAX_CURL_ROTATION * clamped,
    )
}

private const val MAX_CURL_ROTATION = 45f
private const val CURL_EDGE_FADE = 0.15f
