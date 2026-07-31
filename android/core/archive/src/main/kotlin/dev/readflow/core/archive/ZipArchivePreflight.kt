package dev.readflow.core.archive

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class ZipArchiveLimits(
    val maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxCentralDirectoryBytes: Long = DEFAULT_MAX_CENTRAL_DIRECTORY_BYTES,
) {
    init {
        require(maxSourceBytes >= 22L)
        require(maxEntries >= 0)
        require(maxCentralDirectoryBytes >= 0L)
    }

    companion object {
        const val DEFAULT_MAX_SOURCE_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRIES = 10_000
        const val DEFAULT_MAX_CENTRAL_DIRECTORY_BYTES = 16L * 1024L * 1024L
    }
}

data class ZipArchiveSummary(
    val sourceBytes: Long,
    val entryCount: Int,
    val centralDirectoryBytes: Long,
)

enum class ZipArchiveProblem {
    SOURCE_TOO_LARGE,
    TOO_MANY_ENTRIES,
    CENTRAL_DIRECTORY_TOO_LARGE,
    MULTI_DISK_UNSUPPORTED,
    MALFORMED,
}

class ZipArchiveSafetyException(
    val problem: ZipArchiveProblem,
    message: String,
) : IOException(message)

/**
 * Bounds and validates a ZIP central directory before java.util.zip.ZipFile parses it.
 * This intentionally does not inspect or extract local-file payloads.
 */
object ZipArchivePreflight {
    fun inspect(
        file: File,
        limits: ZipArchiveLimits = ZipArchiveLimits(),
    ): ZipArchiveSummary {
        val sourceBytes = file.length()
        if (!file.isFile || sourceBytes < MIN_EOCD_BYTES) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP end record not found")
        }
        if (sourceBytes > limits.maxSourceBytes) {
            fail(ZipArchiveProblem.SOURCE_TOO_LARGE, "ZIP source exceeds ${limits.maxSourceBytes} bytes")
        }

        return RandomAccessFile(file, "r").use { archive ->
            val end = findEndRecord(archive, sourceBytes)
            val directory = readDirectoryDescriptor(archive, end)
            if (directory.entryCount > limits.maxEntries.toLong()) {
                fail(ZipArchiveProblem.TOO_MANY_ENTRIES, "ZIP has too many entries")
            }
            if (directory.byteCount > limits.maxCentralDirectoryBytes) {
                fail(
                    ZipArchiveProblem.CENTRAL_DIRECTORY_TOO_LARGE,
                    "ZIP central directory exceeds ${limits.maxCentralDirectoryBytes} bytes",
                )
            }
            val directoryEnd = checkedAdd(directory.offset, directory.byteCount)
            if (
                directory.offset < 0L || directoryEnd > end.offset ||
                directoryEnd > sourceBytes
            ) {
                fail(ZipArchiveProblem.MALFORMED, "ZIP central directory is outside the archive")
            }
            val actualEntries = scanCentralDirectory(
                archive = archive,
                offset = directory.offset,
                byteCount = directory.byteCount,
                maxEntries = limits.maxEntries,
            )
            if (actualEntries.toLong() != directory.entryCount) {
                fail(ZipArchiveProblem.MALFORMED, "ZIP central-directory entry count is inconsistent")
            }
            ZipArchiveSummary(sourceBytes, actualEntries, directory.byteCount)
        }
    }

    private fun findEndRecord(archive: RandomAccessFile, sourceBytes: Long): EndRecord {
        val tailBytes = minOf(sourceBytes, MIN_EOCD_BYTES + MAX_ZIP_COMMENT_BYTES).toInt()
        val tailOffset = sourceBytes - tailBytes
        val tail = ByteArray(tailBytes)
        archive.seek(tailOffset)
        archive.readFully(tail)
        for (index in tail.size - MIN_EOCD_BYTES.toInt() downTo 0) {
            if (littleU32(tail, index) != END_SIGNATURE) continue
            val commentBytes = littleU16(tail, index + END_COMMENT_LENGTH_OFFSET)
            val recordEnd = tailOffset + index + MIN_EOCD_BYTES + commentBytes
            if (recordEnd == sourceBytes) {
                return EndRecord(
                    offset = tailOffset + index,
                    bytes = tail.copyOfRange(index, index + MIN_EOCD_BYTES.toInt()),
                )
            }
        }
        fail(ZipArchiveProblem.MALFORMED, "ZIP end record not found")
    }

    private fun readDirectoryDescriptor(
        archive: RandomAccessFile,
        end: EndRecord,
    ): DirectoryDescriptor {
        val diskNumber = littleU16(end.bytes, END_DISK_NUMBER_OFFSET)
        val directoryDisk = littleU16(end.bytes, END_DIRECTORY_DISK_OFFSET)
        val entriesOnDisk = littleU16(end.bytes, END_ENTRIES_ON_DISK_OFFSET)
        val totalEntries = littleU16(end.bytes, END_TOTAL_ENTRIES_OFFSET)
        val directoryBytes = littleU32(end.bytes, END_DIRECTORY_SIZE_OFFSET)
        val directoryOffset = littleU32(end.bytes, END_DIRECTORY_OFFSET_OFFSET)
        val usesZip64 = entriesOnDisk == U16_MAX || totalEntries == U16_MAX ||
            directoryBytes == U32_MAX || directoryOffset == U32_MAX

        if (!usesZip64) {
            if (diskNumber != 0 || directoryDisk != 0 || entriesOnDisk != totalEntries) {
                fail(ZipArchiveProblem.MULTI_DISK_UNSUPPORTED, "Multi-disk ZIP archives are unsupported")
            }
            return DirectoryDescriptor(totalEntries.toLong(), directoryOffset, directoryBytes)
        }

        val locatorOffset = end.offset - ZIP64_LOCATOR_BYTES
        if (locatorOffset < 0L) fail(ZipArchiveProblem.MALFORMED, "ZIP64 locator is missing")
        val locator = readAt(archive, locatorOffset, ZIP64_LOCATOR_BYTES.toInt())
        if (littleU32(locator, 0) != ZIP64_LOCATOR_SIGNATURE) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP64 locator is missing")
        }
        if (littleU32(locator, 4) != 0L || littleU32(locator, 16) != 1L) {
            fail(ZipArchiveProblem.MULTI_DISK_UNSUPPORTED, "Multi-disk ZIP64 archives are unsupported")
        }
        val zip64Offset = littleU64(locator, 8)
        val zip64 = readAt(archive, zip64Offset, MIN_ZIP64_END_BYTES.toInt())
        if (littleU32(zip64, 0) != ZIP64_END_SIGNATURE) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP64 end record is missing")
        }
        val recordPayloadBytes = littleU64(zip64, 4)
        if (
            recordPayloadBytes < MIN_ZIP64_END_PAYLOAD_BYTES ||
            checkedAdd(zip64Offset, checkedAdd(12L, recordPayloadBytes)) > locatorOffset
        ) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP64 end record is invalid")
        }
        val zip64Disk = littleU32(zip64, 16)
        val zip64DirectoryDisk = littleU32(zip64, 20)
        val zip64EntriesOnDisk = littleU64(zip64, 24)
        val zip64TotalEntries = littleU64(zip64, 32)
        if (
            zip64Disk != 0L || zip64DirectoryDisk != 0L ||
            zip64EntriesOnDisk != zip64TotalEntries
        ) {
            fail(ZipArchiveProblem.MULTI_DISK_UNSUPPORTED, "Multi-disk ZIP64 archives are unsupported")
        }
        return DirectoryDescriptor(
            entryCount = zip64TotalEntries,
            offset = littleU64(zip64, 48),
            byteCount = littleU64(zip64, 40),
        )
    }

    private fun scanCentralDirectory(
        archive: RandomAccessFile,
        offset: Long,
        byteCount: Long,
        maxEntries: Int,
    ): Int {
        archive.seek(offset)
        var consumed = 0L
        var entries = 0
        while (consumed < byteCount) {
            val remaining = byteCount - consumed
            if (remaining < 4L) fail(ZipArchiveProblem.MALFORMED, "Truncated ZIP central directory")
            val signature = readLittleU32(archive)
            when (signature) {
                CENTRAL_FILE_SIGNATURE -> {
                    if (remaining < CENTRAL_FILE_HEADER_BYTES) {
                        fail(ZipArchiveProblem.MALFORMED, "Truncated ZIP central-file header")
                    }
                    val rest = ByteArray(CENTRAL_FILE_HEADER_BYTES.toInt() - 4)
                    archive.readFully(rest)
                    val nameBytes = littleU16(rest, CENTRAL_NAME_LENGTH_OFFSET - 4)
                    val extraBytes = littleU16(rest, CENTRAL_EXTRA_LENGTH_OFFSET - 4)
                    val commentBytes = littleU16(rest, CENTRAL_COMMENT_LENGTH_OFFSET - 4)
                    val recordBytes = CENTRAL_FILE_HEADER_BYTES + nameBytes + extraBytes + commentBytes
                    if (recordBytes > remaining) {
                        fail(ZipArchiveProblem.MALFORMED, "ZIP central-file record exceeds its directory")
                    }
                    archive.seek(archive.filePointer + recordBytes - CENTRAL_FILE_HEADER_BYTES)
                    consumed += recordBytes
                    entries++
                    if (entries > maxEntries) {
                        fail(ZipArchiveProblem.TOO_MANY_ENTRIES, "ZIP has too many entries")
                    }
                }

                CENTRAL_DIGITAL_SIGNATURE -> {
                    if (remaining < CENTRAL_DIGITAL_HEADER_BYTES) {
                        fail(ZipArchiveProblem.MALFORMED, "Truncated ZIP central-directory signature")
                    }
                    val signatureBytes = readLittleU16(archive)
                    val recordBytes = CENTRAL_DIGITAL_HEADER_BYTES + signatureBytes
                    if (recordBytes != remaining) {
                        fail(ZipArchiveProblem.MALFORMED, "Invalid ZIP central-directory signature")
                    }
                    archive.seek(archive.filePointer + signatureBytes)
                    consumed += recordBytes
                }

                else -> fail(ZipArchiveProblem.MALFORMED, "Invalid ZIP central-directory signature")
            }
        }
        return entries
    }

    private fun readAt(archive: RandomAccessFile, offset: Long, byteCount: Int): ByteArray {
        if (offset < 0L || checkedAdd(offset, byteCount.toLong()) > archive.length()) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP structure points outside the archive")
        }
        return ByteArray(byteCount).also { bytes ->
            archive.seek(offset)
            archive.readFully(bytes)
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP structure size overflow")
        }
        return left + right
    }

    private fun littleU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun littleU32(bytes: ByteArray, offset: Int): Long =
        littleU16(bytes, offset).toLong() or (littleU16(bytes, offset + 2).toLong() shl 16)

    private fun littleU64(bytes: ByteArray, offset: Int): Long {
        if (bytes[offset + 7].toInt() and 0x80 != 0) {
            fail(ZipArchiveProblem.MALFORMED, "ZIP64 value exceeds signed range")
        }
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        return value
    }

    private fun readLittleU16(archive: RandomAccessFile): Int =
        archive.readUnsignedByte() or (archive.readUnsignedByte() shl 8)

    private fun readLittleU32(archive: RandomAccessFile): Long =
        readLittleU16(archive).toLong() or (readLittleU16(archive).toLong() shl 16)

    private fun fail(problem: ZipArchiveProblem, message: String): Nothing =
        throw ZipArchiveSafetyException(problem, message)

    private data class EndRecord(val offset: Long, val bytes: ByteArray)

    private data class DirectoryDescriptor(
        val entryCount: Long,
        val offset: Long,
        val byteCount: Long,
    )

    private const val U16_MAX = 0xFFFF
    private const val U32_MAX = 0xFFFF_FFFFL
    private const val MAX_ZIP_COMMENT_BYTES = 0xFFFFL
    private const val MIN_EOCD_BYTES = 22L
    private const val END_SIGNATURE = 0x06054B50L
    private const val END_DISK_NUMBER_OFFSET = 4
    private const val END_DIRECTORY_DISK_OFFSET = 6
    private const val END_ENTRIES_ON_DISK_OFFSET = 8
    private const val END_TOTAL_ENTRIES_OFFSET = 10
    private const val END_DIRECTORY_SIZE_OFFSET = 12
    private const val END_DIRECTORY_OFFSET_OFFSET = 16
    private const val END_COMMENT_LENGTH_OFFSET = 20
    private const val ZIP64_LOCATOR_BYTES = 20L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
    private const val ZIP64_END_SIGNATURE = 0x06064B50L
    private const val MIN_ZIP64_END_BYTES = 56L
    private const val MIN_ZIP64_END_PAYLOAD_BYTES = 44L
    private const val CENTRAL_FILE_SIGNATURE = 0x02014B50L
    private const val CENTRAL_FILE_HEADER_BYTES = 46L
    private const val CENTRAL_NAME_LENGTH_OFFSET = 28
    private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
    private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
    private const val CENTRAL_DIGITAL_SIGNATURE = 0x05054B50L
    private const val CENTRAL_DIGITAL_HEADER_BYTES = 6L
}
