package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.zip.ZipFile

internal const val EPUB_MAX_IMAGE_SIDE = 1600
internal const val EPUB_MAX_IMAGE_PIXELS = 4_000_000
internal const val EPUB_MAX_ENCODED_IMAGE_BYTES = 20L * 1024L * 1024L

internal fun decodeEpubImage(
    epubFile: File,
    entryPath: String,
    maxSide: Int = EPUB_MAX_IMAGE_SIDE,
    maxPixels: Int = EPUB_MAX_IMAGE_PIXELS,
    maxEncodedBytes: Long = EPUB_MAX_ENCODED_IMAGE_BYTES,
): Bitmap? = try {
    ZipFile(epubFile).use { zip ->
        val entry = zip.getEntry(entryPath) ?: return null
        if (entry.size > maxEncodedBytes) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = epubImageSampleSize(width, height, maxSide, maxPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        zip.getInputStream(entry).use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }
} catch (_: OutOfMemoryError) {
    null
} catch (_: Exception) {
    null
}

internal fun epubImageSampleSize(
    width: Int,
    height: Int,
    maxSide: Int,
    maxPixels: Int,
): Int {
    if (width <= 0 || height <= 0 || maxSide <= 0 || maxPixels <= 0) return 1
    var sample = 1
    while (true) {
        val scaledWidth = width.toLong() / sample
        val scaledHeight = height.toLong() / sample
        if (
            scaledWidth <= maxSide &&
            scaledHeight <= maxSide &&
            scaledWidth * scaledHeight <= maxPixels
        ) {
            return sample
        }
        sample *= 2
    }
}
