package dev.readflow.core.archive

import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ZipArchivePreflightTest {
    @Test
    fun `valid archive reports bounded central directory before opening`() {
        val archive = tempZip("1.jpg", "2.jpg")

        val summary = ZipArchivePreflight.inspect(archive)

        assertEquals(2, summary.entryCount)
        assertEquals(archive.length(), summary.sourceBytes)
    }

    @Test
    fun `source byte limit wins over malformed zip parsing`() {
        val archive = kotlin.io.path.createTempFile("readflow-zip-source-", ".zip").toFile()
        RandomAccessFile(archive, "rw").use { it.setLength(65L) }

        val error = assertThrows(ZipArchiveSafetyException::class.java) {
            ZipArchivePreflight.inspect(
                archive,
                ZipArchiveLimits(maxSourceBytes = 64L, maxEntries = 1, maxCentralDirectoryBytes = 64L),
            )
        }

        assertEquals(ZipArchiveProblem.SOURCE_TOO_LARGE, error.problem)
    }

    @Test
    fun `actual central records cannot hide behind a forged low EOCD count`() {
        val archive = tempZip("1.jpg", "2.jpg", "3.jpg")
        overwriteEocdEntryCount(archive, 1)

        val error = assertThrows(ZipArchiveSafetyException::class.java) {
            ZipArchivePreflight.inspect(
                archive,
                ZipArchiveLimits(maxEntries = 2),
            )
        }

        assertEquals(ZipArchiveProblem.TOO_MANY_ENTRIES, error.problem)
    }

    private fun tempZip(vararg names: String) =
        kotlin.io.path.createTempFile("readflow-zip-preflight-", ".zip").toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                names.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
        }

    private fun overwriteEocdEntryCount(archive: java.io.File, entryCount: Int) {
        RandomAccessFile(archive, "rw").use { file ->
            file.seek(file.length() - 22L + 8L)
            repeat(2) {
                file.write(entryCount and 0xFF)
                file.write(entryCount ushr 8 and 0xFF)
            }
        }
    }
}
