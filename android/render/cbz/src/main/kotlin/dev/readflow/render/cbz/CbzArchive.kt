package dev.readflow.render.cbz

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import dev.readflow.core.archive.ZipArchiveLimits
import dev.readflow.core.archive.ZipArchivePreflight
import dev.readflow.core.archive.ZipArchiveProblem
import dev.readflow.core.archive.ZipArchiveSafetyException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal enum class CbzReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

internal data class CbzSafetyLimits(
    val maxSourceBytes: Long = ZipArchiveLimits.DEFAULT_MAX_SOURCE_BYTES,
    val maxEntries: Int = ZipArchiveLimits.DEFAULT_MAX_ENTRIES,
    val maxCentralDirectoryBytes: Long = ZipArchiveLimits.DEFAULT_MAX_CENTRAL_DIRECTORY_BYTES,
    val maxPages: Int = 10_000,
    val maxEntryBytes: Long = 64L * 1024L * 1024L,
    val maxTotalBytes: Long = 256L * 1024L * 1024L,
    val maxCompressionRatio: Long = 100L,
    val maxMetadataBytes: Long = 1L * 1024L * 1024L,
    val maxImageDimension: Int = 65_535,
    val maxImagePixels: Long = 268_435_456L,
) {
    init {
        require(maxSourceBytes >= 22L)
        require(maxEntries > 0)
        require(maxCentralDirectoryBytes > 0L)
        require(maxPages > 0)
        require(maxEntryBytes > 0L)
        require(maxTotalBytes > 0L)
        require(maxCompressionRatio > 0L)
        require(maxMetadataBytes > 0L)
        require(maxImageDimension > 0)
        require(maxImagePixels > 0L)
    }
}

internal data class CbzPageEntry(
    val archiveName: String,
    val size: Long,
    val compressedSize: Long,
)

internal data class CbzArchiveManifest(
    val pages: List<CbzPageEntry>,
    val readingDirection: CbzReadingDirection,
)

internal class CbzArchiveIndexer(
    private val limits: CbzSafetyLimits = CbzSafetyLimits(),
) {
    fun index(file: File): CbzArchiveManifest {
        preflightCbzArchive(file, limits)
        return ZipFile(file).use(::index)
    }

    fun index(zip: ZipFile): CbzArchiveManifest {
        val imageEntries = ArrayList<ZipEntry>(limits.maxPages.coerceAtMost(1_024))
        val imageNames = HashSet<String>()
        var comicInfo: ZipEntry? = null
        var entryCount = 0
        var declaredTotal = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount++
            if (entryCount > limits.maxEntries) {
                throw IOException("CBZ 条目数超过安全上限 ${limits.maxEntries}")
            }
            if (entry.isDirectory) continue
            if (
                comicInfo == null &&
                entry.name.substringAfterLast('/').equals(COMIC_INFO_FILE, ignoreCase = true)
            ) {
                comicInfo = entry
            }
            if (!isPageCandidate(entry.name)) continue
            if (imageEntries.size >= limits.maxPages) {
                throw IOException("CBZ 页数超过安全上限 ${limits.maxPages}")
            }
            val canonicalName = entry.name.lowercase(Locale.ROOT)
            if (!imageNames.add(canonicalName)) {
                throw IOException("CBZ 包含重复图片路径: $canonicalName")
            }
            val size = entry.size
            val compressed = entry.compressedSize
            if (size > limits.maxEntryBytes) {
                throw IOException("CBZ 单页超过安全上限: ${entry.name}")
            }
            if (size >= 0L) {
                if (declaredTotal > limits.maxTotalBytes - size) {
                    throw IOException("CBZ 解压总量超过安全上限")
                }
                declaredTotal += size
            }
            if (exceedsCompressionRatio(size, compressed, limits.maxCompressionRatio)) {
                throw IOException("CBZ 压缩比异常: ${entry.name}")
            }
            imageEntries += entry
        }
        if (imageEntries.isEmpty()) throw IOException("CBZ 中没有可阅读的图片")
        val direction = comicInfo?.takeIf { it.size <= limits.maxMetadataBytes }?.let { entry ->
            try {
                zip.getInputStream(entry).use { input ->
                    comicInfoReadingDirection(
                        SizeLimitedInputStream(input, limits.maxMetadataBytes),
                    )
                }
            } catch (_: Exception) {
                CbzReadingDirection.LEFT_TO_RIGHT
            }
        } ?: CbzReadingDirection.LEFT_TO_RIGHT

        return CbzArchiveManifest(
            pages = imageEntries
                .sortedWith(compareByNaturalName { it.name })
                .map { CbzPageEntry(it.name, it.size, it.compressedSize) },
            readingDirection = direction,
        )
    }

    private fun isPageCandidate(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("__MACOSX/", ignoreCase = true)) return false
        val leaf = normalized.substringAfterLast('/')
        if (leaf.startsWith(".")) return false
        return leaf.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
    }

    private fun comicInfoReadingDirection(input: InputStream): CbzReadingDirection {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null)
        }
        var insideManga = false
        var mangaValue: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> insideManga = parser.name.equals("Manga", ignoreCase = true)
                XmlPullParser.TEXT -> if (insideManga) mangaValue = parser.text?.trim()
                XmlPullParser.END_TAG -> if (parser.name.equals("Manga", ignoreCase = true)) insideManga = false
            }
            parser.next()
        }
        return if (mangaValue.equals("YesAndRightToLeft", ignoreCase = true)) {
            CbzReadingDirection.RIGHT_TO_LEFT
        } else {
            CbzReadingDirection.LEFT_TO_RIGHT
        }
    }

    private companion object {
        const val COMIC_INFO_FILE = "ComicInfo.xml"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

internal data class PreparedCbzPage(
    val file: File,
    val width: Int,
    val height: Int,
    val byteCount: Long,
)

internal class CbzArchiveSession private constructor(
    private val zip: ZipFile,
    val manifest: CbzArchiveManifest,
    private val outputDirectory: File,
    private val limits: CbzSafetyLimits,
) : AutoCloseable {
    private val prepared = linkedMapOf<Int, PreparedCbzPage>()
    private var extractedTotalBytes = 0L

    @Synchronized
    fun preparePageBlocking(
        pageIndex: Int,
        shouldContinue: () -> Boolean = { true },
    ): PreparedCbzPage {
        val index = pageIndex.coerceIn(0, manifest.pages.lastIndex)
        prepared[index]?.let { return it }
        outputDirectory.mkdirs()
        val page = manifest.pages[index]
        val extension = page.archiveName.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
        val output = File(outputDirectory, "page-%05d.%s".format(index, extension))
        validPreparedPage(output)?.let { existing ->
            if (extractedTotalBytes <= limits.maxTotalBytes - existing.byteCount) {
                extractedTotalBytes += existing.byteCount
                prepared[index] = existing
                return existing
            }
            output.delete()
        }

        val entry = zip.getEntry(page.archiveName) ?: throw IOException("CBZ 页面缺失: ${page.archiveName}")
        val staging = File.createTempFile("page-%05d-".format(index), ".part", outputDirectory)
        try {
            var written = 0L
            zip.getInputStream(entry).use { input ->
                staging.outputStream().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (!shouldContinue()) throw CancellationException("CBZ page extraction cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > limits.maxEntryBytes) throw IOException("CBZ 单页实际大小超过安全上限")
                        if (
                            exceedsCompressionRatio(
                                uncompressedBytes = written,
                                compressedBytes = page.compressedSize,
                                maxCompressionRatio = limits.maxCompressionRatio,
                            )
                        ) {
                            throw IOException("CBZ 单页实际压缩比异常: ${page.archiveName}")
                        }
                        if (extractedTotalBytes > limits.maxTotalBytes - written) {
                            throw IOException("CBZ 实际解压总量超过安全上限")
                        }
                        destination.write(buffer, 0, count)
                    }
                }
            }
            if (!shouldContinue()) throw CancellationException("CBZ page extraction cancelled")
            val dimensions = validatedImageBounds(staging)
                ?: throw IOException("CBZ 页面不是可解码图片: ${page.archiveName}")
            moveReplacing(staging, output)
            extractedTotalBytes += written
            return PreparedCbzPage(output, dimensions.first, dimensions.second, written).also {
                prepared[index] = it
            }
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    fun preparedIndexes(): Set<Int> = prepared.keys.toSet()

    @Synchronized
    fun retainPreparedIndexes(indexes: Set<Int>) {
        val retained = indexes.filterTo(mutableSetOf()) { it in manifest.pages.indices }
        val iterator = prepared.iterator()
        while (iterator.hasNext()) {
            val (index, page) = iterator.next()
            if (index !in retained) {
                page.file.delete()
                extractedTotalBytes = (extractedTotalBytes - page.byteCount).coerceAtLeast(0L)
                iterator.remove()
            }
        }
    }

    @Synchronized
    override fun close() {
        prepared.clear()
        extractedTotalBytes = 0L
        zip.close()
        outputDirectory.deleteRecursively()
    }

    private fun validPreparedPage(file: File): PreparedCbzPage? {
        if (!file.isFile || file.length() <= 0L || file.length() > limits.maxEntryBytes) return null
        val bounds = runCatching { validatedImageBounds(file) }.getOrNull() ?: return null
        return PreparedCbzPage(file, bounds.first, bounds.second, file.length())
    }

    private fun validatedImageBounds(file: File): Pair<Int, Int>? =
        decodeImageBounds(file)?.also { (width, height) ->
            if (width > limits.maxImageDimension || height > limits.maxImageDimension) {
                throw IOException("CBZ 页面尺寸超过安全上限")
            }
            if (width.toLong() * height.toLong() > limits.maxImagePixels) {
                throw IOException("CBZ 页面像素数超过安全上限")
            }
        }

    companion object {
        fun open(
            archive: File,
            outputDirectory: File,
            limits: CbzSafetyLimits = CbzSafetyLimits(),
        ): CbzArchiveSession {
            preflightCbzArchive(archive, limits)
            val zip = ZipFile(archive)
            return try {
                val manifest = CbzArchiveIndexer(limits).index(zip)
                CbzArchiveSession(zip, manifest, outputDirectory, limits)
            } catch (error: Throwable) {
                zip.close()
                throw error
            }
        }
    }
}

private fun preflightCbzArchive(file: File, limits: CbzSafetyLimits) {
    try {
        ZipArchivePreflight.inspect(
            file,
            ZipArchiveLimits(
                maxSourceBytes = limits.maxSourceBytes,
                maxEntries = limits.maxEntries,
                maxCentralDirectoryBytes = limits.maxCentralDirectoryBytes,
            ),
        )
    } catch (error: ZipArchiveSafetyException) {
        val message = when (error.problem) {
            ZipArchiveProblem.SOURCE_TOO_LARGE -> "CBZ 源文件超过安全上限"
            ZipArchiveProblem.TOO_MANY_ENTRIES -> "CBZ 条目数超过安全上限 ${limits.maxEntries}"
            ZipArchiveProblem.CENTRAL_DIRECTORY_TOO_LARGE -> "CBZ 中央目录超过安全上限"
            ZipArchiveProblem.MULTI_DISK_UNSUPPORTED -> "CBZ 不支持分卷 ZIP"
            ZipArchiveProblem.MALFORMED -> "CBZ ZIP 结构无效"
        }
        throw IOException(message, error)
    }
}

internal fun decodeImageBounds(file: File): Pair<Int, Int>? {
    if (!hasSupportedImageSignature(file)) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        val rotationDegrees = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        orientedImageDimensions(options.outWidth, options.outHeight, rotationDegrees)
    } else {
        null
    }
}

internal fun orientedImageDimensions(
    width: Int,
    height: Int,
    rotationDegrees: Int,
): Pair<Int, Int> = if (Math.floorMod(rotationDegrees, 180) == 90) {
    height to width
} else {
    width to height
}

internal fun exceedsCompressionRatio(
    uncompressedBytes: Long,
    compressedBytes: Long,
    maxCompressionRatio: Long,
): Boolean {
    if (uncompressedBytes <= 0L || compressedBytes <= 0L) return false
    if (maxCompressionRatio <= 0L) return true
    if (compressedBytes > Long.MAX_VALUE / maxCompressionRatio) return false
    return uncompressedBytes > compressedBytes * maxCompressionRatio
}

private fun hasSupportedImageSignature(file: File): Boolean {
    if (!file.isFile || file.length() < 4L) return false
    val header = ByteArray(12)
    val count = file.inputStream().use { it.read(header) }
    if (count >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { header[it] == PNG_SIGNATURE[it] }
    ) {
        return true
    }
    if (count >= 3 &&
        header[0] == 0xFF.toByte() &&
        header[1] == 0xD8.toByte() &&
        header[2] == 0xFF.toByte()
    ) {
        return true
    }
    return count >= 12 &&
        header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
        header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)
private val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)

private fun <T> compareByNaturalName(selector: (T) -> String): Comparator<T> =
    Comparator { left, right -> naturalNameCompare(selector(left), selector(right)) }

internal fun naturalNameCompare(left: String, right: String): Int {
    var li = 0
    var ri = 0
    while (li < left.length && ri < right.length) {
        val lc = left[li]
        val rc = right[ri]
        if (lc.isDigit() && rc.isDigit()) {
            val leftStart = li
            val rightStart = ri
            while (li < left.length && left[li].isDigit()) li++
            while (ri < right.length && right[ri].isDigit()) ri++
            val leftDigits = left.substring(leftStart, li)
            val rightDigits = right.substring(rightStart, ri)
            val leftSignificant = leftDigits.trimStart('0').ifEmpty { "0" }
            val rightSignificant = rightDigits.trimStart('0').ifEmpty { "0" }
            if (leftSignificant.length != rightSignificant.length) {
                return leftSignificant.length.compareTo(rightSignificant.length)
            }
            val numeric = leftSignificant.compareTo(rightSignificant)
            if (numeric != 0) return numeric
            if (leftDigits.length != rightDigits.length) return rightDigits.length.compareTo(leftDigits.length)
        } else {
            val lowerLeft = lc.lowercaseChar()
            val lowerRight = rc.lowercaseChar()
            if (lowerLeft != lowerRight) return lowerLeft.compareTo(lowerRight)
            li++
            ri++
        }
    }
    return when {
        li < left.length -> 1
        ri < right.length -> -1
        else -> left.compareTo(right)
    }
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
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) record(1L) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) record(it.toLong()) }

    private fun record(bytes: Long) {
        count += bytes
        if (count > maxBytes) throw IOException("CBZ metadata exceeds $maxBytes bytes")
    }
}
