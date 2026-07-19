package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.Base64
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

internal const val EPUB_MAX_IMAGE_SIDE = 1600
internal const val EPUB_MAX_IMAGE_PIXELS = 4_000_000
internal const val EPUB_MAX_ENCODED_IMAGE_BYTES = 20L * 1024L * 1024L

internal data class EpubImageBounds(val width: Int, val height: Int)

/**
 * Test/diagnostic counters for full-pixel [decodeEpubImage] vs bounds-only probes.
 * Bounds decode is allowed on the main thread (placeholder sizing); full-pixel decode must stay
 * off the interactive gesture path (Flow async loader / background executor).
 */
internal object EpubImageDecodeProbe {
    private val fullDecodeTotal = AtomicInteger(0)
    private val fullDecodeMainThread = AtomicInteger(0)
    private val boundsDecodeTotal = AtomicInteger(0)
    @Volatile private var enabled = false

    fun reset() {
        fullDecodeTotal.set(0)
        fullDecodeMainThread.set(0)
        boundsDecodeTotal.set(0)
        enabled = true
    }

    fun stop() {
        enabled = false
        fullDecodeTotal.set(0)
        fullDecodeMainThread.set(0)
        boundsDecodeTotal.set(0)
    }

    fun noteFullDecode() {
        if (!enabled) return
        fullDecodeTotal.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fullDecodeMainThread.incrementAndGet()
        }
    }

    fun noteBoundsDecode() {
        if (!enabled) return
        boundsDecodeTotal.incrementAndGet()
    }

    fun fullDecodeTotal(): Int = fullDecodeTotal.get()
    fun fullDecodeMainThread(): Int = fullDecodeMainThread.get()
    fun boundsDecodeTotal(): Int = boundsDecodeTotal.get()
}

// Decode only the intrinsic pixel dimensions (no pixel data) so placement can classify an image
// as a full-page illustration vs a small inline marker/avatar without loading the full bitmap.
internal fun decodeEpubImageBounds(
    epubFile: File,
    entryPath: String,
): EpubImageBounds? = try {
    EpubImageDecodeProbe.noteBoundsDecode()
    decodeEpubDataImageBytes(entryPath, EPUB_MAX_ENCODED_IMAGE_BYTES)?.let { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            EpubImageBounds(bounds.outWidth, bounds.outHeight)
        }
    }
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
    EpubImageDecodeProbe.noteFullDecode()
    decodeEpubDataImageBytes(entryPath, maxEncodedBytes)?.let { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = epubImageSampleSize(width, height, maxSide, maxPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }
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

private fun decodeEpubDataImageBytes(
    value: String,
    maxEncodedBytes: Long,
): ByteArray? {
    if (!value.startsWith("data:", ignoreCase = true)) return null
    val comma = value.indexOf(',')
    if (comma <= "data:".length) return null
    val header = value.substring("data:".length, comma)
    val mediaType = header.substringBefore(';').trim()
    if (!mediaType.startsWith("image/", ignoreCase = true)) return null
    if (header.split(';').none { it.equals("base64", ignoreCase = true) }) return null

    val encoded = value.substring(comma + 1).filterNot(Char::isWhitespace)
    val maxBase64Chars = ((maxEncodedBytes + 2L) / 3L) * 4L + 128L
    if (encoded.length.toLong() > maxBase64Chars) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT).takeIf { it.size.toLong() <= maxEncodedBytes }
    } catch (_: IllegalArgumentException) {
        null
    }
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
