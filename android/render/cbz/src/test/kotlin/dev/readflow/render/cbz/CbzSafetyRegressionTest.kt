package dev.readflow.render.cbz

import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CbzSafetyRegressionTest {

    @Test
    fun `default archive limits stay within a device safe budget`() {
        val limits = CbzSafetyLimits()

        assertTrue(
            "default CBZ limits exceed the device-safe policy: $limits",
            limits.maxEntries <= 10_000 &&
                limits.maxPages <= 10_000 &&
                limits.maxEntryBytes <= 64L * MEBIBYTE &&
                limits.maxTotalBytes <= 256L * MEBIBYTE &&
                limits.maxCompressionRatio <= 100L,
        )
    }

    @Test
    fun `file URI larger than two GiB is rejected before ZIP parsing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val archive = kotlin.io.path.createTempFile("readflow-cbz-oversized-", ".cbz").toFile()
        RandomAccessFile(archive, "rw").use { file ->
            file.setLength(TWO_GIBIBYTES + 1L)
        }
        val engine = CbzReaderEngine(RuntimeEnvironment.getApplication(), ioDispatcher = dispatcher)

        try {
            val error = runCatching {
                engine.openBook(Uri.fromFile(archive))
            }.exceptionOrNull()

            assertEquals("CBZ source size must be checked before ZIP parsing", SOURCE_SIZE_ERROR, error?.message)
        } finally {
            engine.close()
            archive.delete()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `EOCD entry count above limit is rejected before ZipFile opens`() {
        val limits = CbzSafetyLimits()
        val archive = tempCbz("1.jpg" to byteArrayOf(1))
        val advertisedEntryCount = limits.maxEntries + 1
        require(advertisedEntryCount in 1..U16_MAX)
        overwriteEocdEntryCount(archive, advertisedEntryCount)
        val output = kotlin.io.path.createTempDirectory("readflow-cbz-pages-").toFile()

        val result = runCatching {
            CbzArchiveSession.open(archive, output, limits)
        }
        result.getOrNull()?.close()

        assertTrue(
            "EOCD entry count must fail with the CBZ entry-count safety error, but was ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IOException &&
                result.exceptionOrNull()?.message.orEmpty().contains(ENTRY_COUNT_ERROR),
        )
    }

    private fun tempCbz(vararg entries: Pair<String, ByteArray>): File =
        kotlin.io.path.createTempFile("readflow-cbz-eocd-", ".cbz").toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun overwriteEocdEntryCount(archive: File, entryCount: Int) {
        RandomAccessFile(archive, "rw").use { file ->
            val eocdOffset = file.length() - EOCD_SIZE
            file.seek(eocdOffset)
            val signature = ByteArray(EOCD_SIGNATURE.size)
            file.readFully(signature)
            check(signature.contentEquals(EOCD_SIGNATURE))

            file.seek(eocdOffset + EOCD_ENTRIES_ON_DISK_OFFSET)
            repeat(2) {
                file.write(entryCount and 0xFF)
                file.write(entryCount ushr 8 and 0xFF)
            }
        }
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
        const val TWO_GIBIBYTES = 2L * 1024L * 1024L * 1024L
        const val U16_MAX = 0xFFFF
        const val EOCD_SIZE = 22L
        const val EOCD_ENTRIES_ON_DISK_OFFSET = 8L
        const val SOURCE_SIZE_ERROR = "CBZ 源文件超过安全上限"
        const val ENTRY_COUNT_ERROR = "CBZ 条目数超过安全上限"
        val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    }
}
