package dev.readflow.extensions.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import dev.readflow.core.archive.ZipArchiveLimits
import dev.readflow.core.archive.ZipArchivePreflight
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Extracts a cover image for local imports (EPUB: OPF cover-image item; PDF: page 0 render).
 * TXT/MD have no image — returns null so BookCover falls back to the cloth stamp.
 */
internal object CoverExtractor {

    suspend fun extract(
        context: Context,
        srcFile: File,
        format: BookFormat,
        bookId: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val out = File(coversDir, "$bookId.jpg")
            val staging = File.createTempFile("$bookId-", ".jpg.part", coversDir)
            try {
                val extracted = when (format) {
                    BookFormat.EPUB -> epubCover(srcFile, staging)
                    BookFormat.PDF -> pdfCover(srcFile, staging)
                    BookFormat.CBZ -> cbzCover(srcFile, staging)
                    else -> null
                } ?: return@withContext null
                if (extracted.length() <= 0L) return@withContext null
                moveReplacing(staging, out)
                Uri.fromFile(out).toString()
            } finally {
                staging.delete()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    // ── EPUB ─────────────────────────────────────────────────────────────────

    private fun epubCover(epub: File, out: File): File? = ZipFile(epub).use { zip ->
        val opfPath = opfPath(zip) ?: return null
        val baseDir = opfPath.substringBeforeLast('/', "")
        val opfEntry = zip.getEntry(opfPath) ?: return null
        if (opfEntry.size > MAX_PACKAGE_XML_BYTES) return null

        val coverHref = zip.getInputStream(opfEntry).use { input ->
            coverItemHref(SizeLimitedInputStream(input, MAX_PACKAGE_XML_BYTES))
        } ?: return null
        val imgPath = resolveZipPath(baseDir, coverHref) ?: return null
        val entry = zip.getEntry(imgPath)
            ?: resolveZipPath("", coverHref)?.let(zip::getEntry)
            ?: return null
        if (entry.size > MAX_COVER_BYTES) return null
        out.outputStream().use { output ->
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_BYTES) return null
                    output.write(buffer, 0, read)
                }
            }
        }
        out
    }

    /** Returns the href of the cover image item in the OPF, or null. */
    private fun coverItemHref(input: InputStream): String? {
        val parser = securePullParser(input)
        val itemHrefs = mutableMapOf<String, String>()
        var epub3CoverHref: String? = null
        var epub2CoverId: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val href = parser.getAttributeValue(null, "href")
                        val id = parser.getAttributeValue(null, "id")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            itemHrefs[id] = href
                        }
                        val properties = parser.getAttributeValue(null, "properties").orEmpty()
                        if (
                            !href.isNullOrBlank() &&
                            properties.split(WHITESPACE).any { it == "cover-image" }
                        ) {
                            epub3CoverHref = href
                        }
                    }
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        epub2CoverId = parser.getAttributeValue(null, "content")
                    }
                }
            }
            parser.next()
        }
        return epub3CoverHref ?: epub2CoverId?.let(itemHrefs::get)
    }

    private fun opfPath(zip: ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return "OEBPS/content.opf"
        if (entry.size > MAX_PACKAGE_XML_BYTES) return null
        val fullPath = zip.getInputStream(entry).use { input ->
            val parser = securePullParser(
                SizeLimitedInputStream(input, MAX_PACKAGE_XML_BYTES),
            )
            var packagePath: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (
                    packagePath == null &&
                    parser.eventType == XmlPullParser.START_TAG &&
                    parser.name == "rootfile" &&
                    parser.getAttributeValue(null, "media-type") == "application/oebps-package+xml"
                ) {
                    packagePath = parser.getAttributeValue(null, "full-path")
                }
                parser.next()
            }
            packagePath
        }
        return fullPath?.let { resolveZipPath("", it) } ?: "OEBPS/content.opf"
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private fun pdfCover(pdf: File, out: File): File? {
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val scale = 200f / page.width.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                out
            }
        }
    }

    // ── CBZ ──────────────────────────────────────────────────────────────────

    private fun cbzCover(cbz: File, out: File): File? {
        ZipArchivePreflight.inspect(cbz, CBZ_ZIP_LIMITS)
        return ZipFile(cbz).use { zip ->
            val candidates = mutableListOf<java.util.zip.ZipEntry>()
            val entries = zip.entries()
            var scanned = 0
            while (entries.hasMoreElements()) {
                if (++scanned > MAX_CBZ_ARCHIVE_ENTRIES) return null
                val entry = entries.nextElement()
                if (!entry.isDirectory && isCbzImageCandidate(entry.name)) candidates += entry
            }
            candidates.sortWith { left, right -> naturalNameCompare(left.name, right.name) }
            for (entry in candidates) {
                if (entry.size > MAX_COVER_BYTES) continue
                if (entry.size > 0L && entry.compressedSize > 0L &&
                    entry.size > entry.compressedSize * MAX_CBZ_COMPRESSION_RATIO
                ) {
                    continue
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(
                        SizeLimitedInputStream(input, MAX_COVER_BYTES),
                        null,
                        bounds,
                    )
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                if (
                    bounds.outWidth > MAX_CBZ_IMAGE_DIMENSION ||
                    bounds.outHeight > MAX_CBZ_IMAGE_DIMENSION ||
                    bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_CBZ_IMAGE_PIXELS
                ) {
                    continue
                }
                var sampleSize = 1
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                while (longEdge / sampleSize > MAX_COVER_LONG_EDGE_PX * 2) sampleSize *= 2
                val bitmap = zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(
                        SizeLimitedInputStream(input, MAX_COVER_BYTES),
                        null,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        },
                    )
                } ?: continue
                val rotationDegrees = runCatching {
                    zip.getInputStream(entry).use { input ->
                        ExifInterface(SizeLimitedInputStream(input, MAX_COVER_BYTES)).rotationDegrees
                    }
                }.getOrDefault(0)
                val oriented = if (rotationDegrees % 360 == 0) {
                    bitmap
                } else {
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                        true,
                    )
                }
                try {
                    out.outputStream().use { output ->
                        if (!oriented.compress(Bitmap.CompressFormat.JPEG, 85, output)) return null
                    }
                    return out
                } finally {
                    if (oriented !== bitmap) oriented.recycle()
                    bitmap.recycle()
                }
            }
            null
        }
    }

    private fun isCbzImageCandidate(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("__MACOSX/", ignoreCase = true)) return false
        val leaf = normalized.substringAfterLast('/')
        if (leaf.startsWith('.')) return false
        return leaf.substringAfterLast('.', "").lowercase() in CBZ_IMAGE_EXTENSIONS
    }

    private fun naturalNameCompare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftStart = leftIndex
                val rightStart = rightIndex
                while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
                while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++
                val leftDigits = left.substring(leftStart, leftIndex)
                val rightDigits = right.substring(rightStart, rightIndex)
                val leftNumber = leftDigits.trimStart('0').ifEmpty { "0" }
                val rightNumber = rightDigits.trimStart('0').ifEmpty { "0" }
                if (leftNumber.length != rightNumber.length) return leftNumber.length.compareTo(rightNumber.length)
                val numeric = leftNumber.compareTo(rightNumber)
                if (numeric != 0) return numeric
                if (leftDigits.length != rightDigits.length) return rightDigits.length.compareTo(leftDigits.length)
            } else {
                val foldedLeft = leftChar.lowercaseChar()
                val foldedRight = rightChar.lowercaseChar()
                if (foldedLeft != foldedRight) return foldedLeft.compareTo(foldedRight)
                leftIndex++
                rightIndex++
            }
        }
        return when {
            leftIndex < left.length -> 1
            rightIndex < right.length -> -1
            else -> left.compareTo(right)
        }
    }

    private fun securePullParser(input: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null)
        }

    private fun resolveZipPath(baseDir: String, href: String): String? {
        val decoded = Uri.decode(href.substringBefore('#').substringBefore('?'))
        val combined = when {
            decoded.startsWith('/') -> decoded.removePrefix("/")
            baseDir.isEmpty() -> decoded
            else -> "$baseDir/$decoded"
        }
        val segments = mutableListOf<String>()
        for (segment in combined.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int {
            enforceLimit()
            return `in`.read().also { value ->
                if (value >= 0) recordRead(1)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            enforceLimit()
            val allowed = minOf(length.toLong(), maxBytes - bytesRead + 1).toInt()
            return `in`.read(buffer, offset, allowed).also { count ->
                if (count > 0) recordRead(count.toLong())
            }
        }

        override fun skip(byteCount: Long): Long {
            if (byteCount <= 0) return 0
            enforceLimit()
            val allowed = minOf(byteCount, maxBytes - bytesRead + 1)
            return `in`.skip(allowed).also(::recordRead)
        }

        private fun recordRead(count: Long) {
            bytesRead += count
            enforceLimit()
        }

        private fun enforceLimit() {
            if (bytesRead > maxBytes) {
                throw IOException("XML entry exceeds $maxBytes bytes")
            }
        }
    }

    private const val MAX_COVER_BYTES = 32L * 1024 * 1024
    private const val MAX_PACKAGE_XML_BYTES = 2L * 1024 * 1024
    private const val MAX_CBZ_ARCHIVE_ENTRIES = ZipArchiveLimits.DEFAULT_MAX_ENTRIES
    private const val MAX_CBZ_COMPRESSION_RATIO = 100L
    private const val MAX_CBZ_IMAGE_DIMENSION = 65_535
    private const val MAX_CBZ_IMAGE_PIXELS = 268_435_456L
    private const val MAX_COVER_LONG_EDGE_PX = 800
    private val CBZ_ZIP_LIMITS = ZipArchiveLimits()
    private val CBZ_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val WHITESPACE = Regex("\\s+")
}
