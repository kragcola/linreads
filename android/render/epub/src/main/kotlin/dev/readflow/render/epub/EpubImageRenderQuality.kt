package dev.readflow.render.epub

internal enum class EpubImageRenderQuality(
    val linearScale: Float,
) {
    RAPID(0.5f),
    MOTION(0.75f),
    DISPLAY(1f),
}

internal data class EpubImageDecodeBudget(
    val maxSide: Int,
    val maxPixels: Int,
)

internal fun epubImageDecodeBudget(
    targetWidth: Int,
    targetHeight: Int,
    quality: EpubImageRenderQuality,
): EpubImageDecodeBudget {
    val width = targetWidth.coerceAtLeast(1)
    val height = targetHeight.coerceAtLeast(1)
    val scale = quality.linearScale
    val scaledPixels = (width.toLong() * height.toLong() * scale * scale)
        .toLong()
        .coerceIn(1L, EPUB_MAX_IMAGE_PIXELS.toLong())
    return EpubImageDecodeBudget(
        maxSide = (maxOf(width, height) * scale).toInt().coerceAtLeast(1),
        maxPixels = scaledPixels.toInt(),
    )
}

internal fun epubImageRenderQualityForOccurrence(
    layoutStart: Int,
    currentPageRanges: Collection<IntRange>,
    isCurrentChapter: Boolean,
    visualMotionActive: Boolean,
): EpubImageRenderQuality =
    if (visualMotionActive) EpubImageRenderQuality.RAPID else EpubImageRenderQuality.MOTION
