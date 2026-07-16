package dev.readflow.render.txt

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextSelection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TxtDocumentFingerprint(
    val byteLength: Long,
    val crc32: Long,
) {
    companion object {
        fun fromFile(file: File): TxtDocumentFingerprint {
            val crc = CRC32()
            file.inputStream().use { input ->
                val buffer = ByteArray(BLOCK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                }
            }
            return TxtDocumentFingerprint(file.length(), crc.value)
        }

        private const val BLOCK_BYTES: Int = 64 * 1024
    }
}

internal data class TxtParagraphRange(
    val startByte: Long,
    val endByte: Long,
) {
    val length: Long
        get() = (endByte - startByte).coerceAtLeast(0)
}

internal class TxtDocument private constructor(
    private val file: File,
    val charsetDetection: TxtCharsetDetection,
    private val deleteOnClose: Boolean,
    internal val ranges: List<TxtParagraphRange>,
) {
    private val paragraphCache = object : LinkedHashMap<Int, String>(MAX_CACHED_PARAGRAPHS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > MAX_CACHED_PARAGRAPHS
    }

    val byteLength: Long = file.length()

    val paragraphCount: Int
        get() = ranges.size

    @Synchronized
    fun readParagraph(index: Int): String {
        check(index in ranges.indices)
        paragraphCache[index]?.let { return it }
        val range = ranges[index]
        val text = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            channel.position(range.startByte)
            channel.readTextRange(range.length, charsetDetection.charset)
        }.removePrefix(UNICODE_BOM).trim()
        paragraphCache[index] = text
        return text
    }

    fun rangeAt(index: Int): TxtParagraphRange? = ranges.getOrNull(index)

    fun engineState(fingerprint: TxtDocumentFingerprint): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TXT_ENGINE_STATE_MAGIC)
                output.writeInt(TXT_ENGINE_STATE_VERSION)
                output.writeLong(fingerprint.byteLength)
                output.writeLong(fingerprint.crc32)
                output.writeUTF(charsetDetection.charset.name())
                output.writeUTF(charsetDetection.source.name)
                output.writeUTF(charsetDetection.detectedName.orEmpty())
                output.writeUTF(charsetDetection.fallbackReason.orEmpty())
                output.writeInt(ranges.size)
                ranges.forEach { range ->
                    output.writeLong(range.startByte)
                    output.writeLong(range.endByte)
                }
            }
            bytes.toByteArray()
        }

    fun fingerprint(): TxtDocumentFingerprint = TxtDocumentFingerprint.fromFile(file)

    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Locator> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0 || ranges.isEmpty()) return emptyList()
        val totalBytes = byteLength.coerceAtLeast(1L).toFloat()
        val results = mutableListOf<Locator>()
        for (index in ranges.indices) {
            currentCoroutineContext().ensureActive()
            val paragraph = readParagraph(index)
            var fromIndex = 0
            while (results.size < limit) {
                currentCoroutineContext().ensureActive()
                val matchIndex = paragraph.indexOf(needle, startIndex = fromIndex, ignoreCase = true)
                if (matchIndex < 0) break
                val range = ranges[index]
                val prefixBytes = paragraph.substring(0, matchIndex).toByteArray(charsetDetection.charset).size
                val matchBytes = paragraph.substring(matchIndex, matchIndex + needle.length)
                    .toByteArray(charsetDetection.charset)
                    .size
                val byteOffset = range.startByte + prefixBytes
                val totalProgression = (byteOffset.toFloat() / totalBytes).coerceIn(0f, 1f)
                results += Locator(
                    strategy = LocatorStrategy.ByteOffset(byteOffset, matchBytes),
                    progression = index.toFloat() / ranges.size.coerceAtLeast(1),
                    totalProgression = totalProgression,
                )
                fromIndex = matchIndex + needle.length
            }
            if (results.size >= limit) break
        }
        return results
    }

    fun selectionForParagraphRange(
        paragraphIndex: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): ReaderTextSelection? {
        val range = ranges.getOrNull(paragraphIndex) ?: return null
        val paragraph = readParagraph(paragraphIndex)
        val start = selectionStart.coerceIn(0, paragraph.length)
        val end = selectionEnd.coerceIn(0, paragraph.length)
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (first == last) return null
        val selectedText = paragraph.substring(first, last)
        if (selectedText.isBlank()) return null

        val startByte = range.startByte + paragraph.substring(0, first).toByteArray(charsetDetection.charset).size
        val endByte = range.startByte + paragraph.substring(0, last).toByteArray(charsetDetection.charset).size
        val selectedBytes = paragraph.substring(first, last).toByteArray(charsetDetection.charset).size
        val totalBytes = byteLength.coerceAtLeast(1L).toFloat()
        val totalItems = ranges.size.coerceAtLeast(1)
        return ReaderTextSelection(
            start = Locator(
                strategy = LocatorStrategy.ByteOffset(startByte, selectedBytes),
                progression = paragraphIndex.toFloat() / totalItems,
                totalProgression = (startByte.toFloat() / totalBytes).coerceIn(0f, 1f),
            ),
            end = Locator(
                strategy = LocatorStrategy.ByteOffset(endByte, 0),
                progression = paragraphIndex.toFloat() / totalItems,
                totalProgression = (endByte.toFloat() / totalBytes).coerceIn(0f, 1f),
            ),
            selectedText = selectedText,
        )
    }

    fun normalizeOffsetToCharacterStart(offset: Long): Long {
        if (byteLength <= 0L) return 0L
        val clamped = offset.coerceIn(0L, byteLength)
        if (clamped == 0L || clamped == byteLength || ranges.isEmpty()) return clamped
        val range = ranges[indexForRawOffset(clamped)]
        val target = clamped.coerceIn(range.startByte, range.endByte)
        if (target == range.startByte || target == range.endByte) return target
        return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            channel.position(range.startByte)
            channel.normalizeOffsetToCharacterStart(
                startByte = range.startByte,
                targetByte = target,
                kind = charsetDetection.charset.boundaryKind(),
            )
        }
    }

    fun isCharacterStartOffset(offset: Long): Boolean =
        offset in 0L..byteLength && normalizeOffsetToCharacterStart(offset) == offset

    fun indexForOffset(offset: Long): Int {
        if (ranges.isEmpty()) return 0
        return indexForRawOffset(normalizeOffsetToCharacterStart(offset))
    }

    private fun indexForRawOffset(offset: Long): Int {
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val range = ranges[mid]
            when {
                offset < range.startByte -> high = mid - 1
                offset >= range.endByte -> low = mid + 1
                else -> return mid
            }
        }
        return low.coerceIn(0, ranges.lastIndex)
    }

    @Synchronized
    fun close() {
        paragraphCache.clear()
        if (deleteOnClose) {
            file.delete()
        }
    }

    companion object {
        const val BLOCK_BYTES: Int = 64 * 1024
        private const val MAX_CACHED_PARAGRAPHS: Int = 128
        private const val DEFAULT_SEARCH_LIMIT: Int = 100

        fun index(
            file: File,
            blockBytes: Int = BLOCK_BYTES,
            charsetDetection: TxtCharsetDetection? = null,
            deleteOnClose: Boolean = false,
            fingerprint: TxtDocumentFingerprint? = null,
            cachedEngineState: ByteArray? = null,
        ): TxtDocument {
            require(blockBytes > 0)
            val cached = if (fingerprint == null || cachedEngineState == null) {
                null
            } else {
                decodeEngineState(cachedEngineState, fingerprint)
            }
            if (cached != null) {
                return TxtDocument(file, cached.charsetDetection, deleteOnClose, cached.ranges)
            }
            val ranges = buildParagraphIndex(file, blockBytes)
            return TxtDocument(
                file = file,
                charsetDetection = charsetDetection ?: TxtCharsetDetector.detect(file),
                deleteOnClose = deleteOnClose,
                ranges = splitLongHardWrappedRanges(file, ranges, blockBytes),
            )
        }

        private fun decodeEngineState(
            state: ByteArray,
            fingerprint: TxtDocumentFingerprint,
        ): TxtCachedEngineState? = runCatching {
            DataInputStream(ByteArrayInputStream(state)).use { input ->
                if (input.readInt() != TXT_ENGINE_STATE_MAGIC) return@runCatching null
                if (input.readInt() != TXT_ENGINE_STATE_VERSION) return@runCatching null
                if (input.readLong() != fingerprint.byteLength) return@runCatching null
                if (input.readLong() != fingerprint.crc32) return@runCatching null
                val charset = Charset.forName(input.readUTF())
                val source = runCatching {
                    TxtCharsetDetectionSource.valueOf(input.readUTF())
                }.getOrDefault(TxtCharsetDetectionSource.Detector)
                val detectedName = input.readUTF().ifEmpty { null }
                val fallbackReason = input.readUTF().ifEmpty { null }
                val rangeCount = input.readInt()
                if (rangeCount < 0 || rangeCount > MAX_CACHED_PARAGRAPH_RANGES) return@runCatching null
                val ranges = mutableListOf<TxtParagraphRange>()
                repeat(rangeCount) {
                    val start = input.readLong()
                    val end = input.readLong()
                    if (start < 0 || end < start || end > fingerprint.byteLength) return@runCatching null
                    val previous = ranges.lastOrNull()
                    if (previous != null && previous.endByte > start) return@runCatching null
                    ranges += TxtParagraphRange(start, end)
                }
                TxtCachedEngineState(
                    charsetDetection = TxtCharsetDetection(
                        charset = charset,
                        source = source,
                        detectedName = detectedName,
                        fallbackReason = fallbackReason,
                    ),
                    ranges = ranges,
                )
            }
        }.getOrNull()

        private fun buildParagraphIndex(file: File, blockBytes: Int): List<TxtParagraphRange> {
            if (file.length() <= 0L) return emptyList()
            val ranges = mutableListOf<TxtParagraphRange>()
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(blockBytes)
                var absoluteOffset = 0L
                var lineStartOffset = 0L
                var paragraphStartOffset = 0L
                var lineHasContent = false
                var paragraphHasContent = false

                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        val byte = buffer.get()
                        val byteOffset = absoluteOffset
                        absoluteOffset += 1
                        if (byte == NEWLINE) {
                            if (lineHasContent) {
                                paragraphHasContent = true
                            } else if (paragraphHasContent) {
                                ranges += TxtParagraphRange(paragraphStartOffset, lineStartOffset)
                                paragraphHasContent = false
                                paragraphStartOffset = byteOffset + 1
                            } else {
                                paragraphStartOffset = byteOffset + 1
                            }
                            lineHasContent = false
                            lineStartOffset = byteOffset + 1
                        } else if (!byte.isAsciiLineWhitespace()) {
                            if (!paragraphHasContent && !lineHasContent) {
                                paragraphStartOffset = lineStartOffset
                            }
                            lineHasContent = true
                        }
                    }
                }

                if (lineHasContent || paragraphHasContent) {
                    ranges += TxtParagraphRange(paragraphStartOffset, absoluteOffset)
                }
            }
            return ranges
        }

        private fun splitLongHardWrappedRanges(
            file: File,
            ranges: List<TxtParagraphRange>,
            blockBytes: Int,
        ): List<TxtParagraphRange> {
            if (ranges.none { it.length > MAX_UNSPLIT_PARAGRAPH_BYTES }) return ranges
            val split = mutableListOf<TxtParagraphRange>()
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(blockBytes)
                ranges.forEach { range ->
                    if (range.length <= MAX_UNSPLIT_PARAGRAPH_BYTES) {
                        split += range
                    } else {
                        split += channel.splitRangeOnLineBoundaries(range, buffer)
                    }
                }
            }
            return split
        }
    }
}

private data class TxtCachedEngineState(
    val charsetDetection: TxtCharsetDetection,
    val ranges: List<TxtParagraphRange>,
)

private val NEWLINE: Byte = '\n'.code.toByte()
private val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
private val SPACE: Byte = ' '.code.toByte()
private val TAB: Byte = '\t'.code.toByte()
private const val UNICODE_BOM: String = "\uFEFF"
private const val TXT_ENGINE_STATE_MAGIC: Int = 0x52465458 // RFTX
private const val TXT_ENGINE_STATE_VERSION: Int = 1
private const val MAX_CACHED_PARAGRAPH_RANGES: Int = 1_000_000
private const val MAX_UNSPLIT_PARAGRAPH_BYTES: Long = 4 * 1024

private fun Byte.isAsciiLineWhitespace(): Boolean =
    this == CARRIAGE_RETURN || this == SPACE || this == TAB

private fun FileChannel.splitRangeOnLineBoundaries(
    range: TxtParagraphRange,
    buffer: ByteBuffer,
): List<TxtParagraphRange> {
    val lines = mutableListOf<TxtParagraphRange>()
    position(range.startByte)
    var absoluteOffset = range.startByte
    var lineStartOffset = range.startByte
    var lineHasContent = false
    while (absoluteOffset < range.endByte) {
        buffer.clear()
        buffer.limit((range.endByte - absoluteOffset).coerceAtMost(buffer.capacity().toLong()).toInt())
        val read = read(buffer)
        if (read < 0) break
        buffer.flip()
        while (buffer.hasRemaining()) {
            val byte = buffer.get()
            val byteOffset = absoluteOffset
            absoluteOffset += 1
            if (byte == NEWLINE) {
                if (lineHasContent) {
                    lines += TxtParagraphRange(lineStartOffset, byteOffset)
                }
                lineStartOffset = byteOffset + 1
                lineHasContent = false
            } else if (!byte.isAsciiLineWhitespace()) {
                lineHasContent = true
            }
        }
    }
    if (lineHasContent) {
        lines += TxtParagraphRange(lineStartOffset, range.endByte)
    }
    return lines.ifEmpty { listOf(range) }
}

private fun FileChannel.readTextRange(length: Long, charset: Charset): String {
    val output = ByteArrayOutputStream(length.coerceAtMost(TxtDocument.BLOCK_BYTES.toLong()).toInt())
    val buffer = ByteBuffer.allocate(TxtDocument.BLOCK_BYTES)
    var remaining = length
    while (remaining > 0L) {
        buffer.clear()
        buffer.limit(remaining.coerceAtMost(TxtDocument.BLOCK_BYTES.toLong()).toInt())
        val read = read(buffer)
        if (read < 0) break
        remaining -= read.toLong()
        output.write(buffer.array(), 0, read)
    }
    return output.toString(charset.name())
}

private enum class TxtBoundaryKind {
    Utf8,
    Gbk,
    Gb18030,
    ShiftJis,
    SingleByte,
}

private fun Charset.boundaryKind(): TxtBoundaryKind {
    val name = name().uppercase(Locale.ROOT)
    return when {
        name == "UTF-8" -> TxtBoundaryKind.Utf8
        name == "GB18030" -> TxtBoundaryKind.Gb18030
        name == "GBK" || name == "GB2312" -> TxtBoundaryKind.Gbk
        name == "SHIFT_JIS" || name == "SHIFT-JIS" || name == "SJIS" || name == "WINDOWS-31J" ->
            TxtBoundaryKind.ShiftJis
        else -> TxtBoundaryKind.SingleByte
    }
}

private fun FileChannel.normalizeOffsetToCharacterStart(
    startByte: Long,
    targetByte: Long,
    kind: TxtBoundaryKind,
): Long {
    val buffer = ByteBuffer.allocate(TxtDocument.BLOCK_BYTES)
    var absolute = startByte
    var currentCharStart = startByte
    var remainingTrailBytes = 0
    var waitingGb18030SecondByte = false

    while (absolute < targetByte) {
        buffer.clear()
        buffer.limit((targetByte - absolute).coerceAtMost(TxtDocument.BLOCK_BYTES.toLong()).toInt())
        val read = read(buffer)
        if (read < 0) break
        buffer.flip()
        while (buffer.hasRemaining() && absolute < targetByte) {
            val value = buffer.get().toInt() and 0xFF
            if (waitingGb18030SecondByte) {
                remainingTrailBytes = if (value in 0x30..0x39) 2 else 0
                waitingGb18030SecondByte = false
            } else if (remainingTrailBytes > 0) {
                remainingTrailBytes -= 1
            } else {
                currentCharStart = absolute
                when (kind) {
                    TxtBoundaryKind.Utf8 -> remainingTrailBytes = utf8TrailBytes(value)
                    TxtBoundaryKind.Gbk -> remainingTrailBytes = if (value.isGbkLeadByte()) 1 else 0
                    TxtBoundaryKind.Gb18030 -> waitingGb18030SecondByte = value.isGbkLeadByte()
                    TxtBoundaryKind.ShiftJis -> remainingTrailBytes = if (value.isShiftJisLeadByte()) 1 else 0
                    TxtBoundaryKind.SingleByte -> remainingTrailBytes = 0
                }
            }
            absolute += 1
        }
    }

    return if (remainingTrailBytes > 0 || waitingGb18030SecondByte) currentCharStart else targetByte
}

private fun utf8TrailBytes(value: Int): Int =
    when {
        value and 0b1000_0000 == 0 -> 0
        value and 0b1110_0000 == 0b1100_0000 -> 1
        value and 0b1111_0000 == 0b1110_0000 -> 2
        value and 0b1111_1000 == 0b1111_0000 -> 3
        else -> 0
    }

private fun Int.isGbkLeadByte(): Boolean = this in 0x81..0xFE

private fun Int.isShiftJisLeadByte(): Boolean =
    this in 0x81..0x9F || this in 0xE0..0xFC
