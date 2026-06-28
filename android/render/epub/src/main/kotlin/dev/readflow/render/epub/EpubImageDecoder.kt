package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.zip.ZipFile

internal const val EPUB_MAX_IMAGE_SIDE = 1600
internal const val EPUB_MAX_IMAGE_PIXELS = 4_000_000
internal const val EPUB_MAX_ENCODED_IMAGE_BYTES = 20L * 1024L * 1024L

internal data class EpubImageBounds(val width: Int, val height: Int)

// Decode only the intrinsic pixel dimensions (no pixel data) so placement can classify an image
// as a full-page illustration vs a small inline marker/avatar without loading the full bitmap.
internal fun decodeEpubImageBounds(
    epubFile: File,
    entryPath: String,
): EpubImageBounds? = try {
    ZipFile(epubFile).use { zip ->
        val entry = zip.getEntry(entryPath) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            EpubImageBounds(bounds.outWidth, bounds.outHeight)
        }
    }
} catch (_: OutOfMemoryError) {
    null
} catch (_: Exception) {
    null
}

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
