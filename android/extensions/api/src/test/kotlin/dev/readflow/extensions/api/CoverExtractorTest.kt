package dev.readflow.extensions.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverExtractorTest {

    @Test
    fun `container actual stream larger than package limit is rejected when metadata underreports size`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = "oversized-container-cover"
        val epub = File(context.cacheDir, "$bookId.epub")
        val cover = File(context.filesDir, "covers/$bookId.jpg").apply { delete() }
        writeEpub(
            epub,
            linkedMapOf(
                "META-INF/container.xml" to oversizedContainerXml().toByteArray(),
                "OEBPS/content.opf" to epub3Opf("cover.jpg").toByteArray(),
                "OEBPS/cover.jpg" to FAKE_COVER,
            ),
        )
        underreportUncompressedSize(epub, "META-INF/container.xml", reportedSize = 1)

        ZipFile(epub).use { zip ->
            val entry = checkNotNull(zip.getEntry("META-INF/container.xml"))
            assertEquals(1L, entry.size)
            val actualSize = zip.getInputStream(entry).use { it.readBytes().size }
            assertTrue("fixture must decompress past the package XML limit", actualSize > PACKAGE_XML_LIMIT_BYTES)
        }

        val result = CoverExtractor.extract(context, epub, BookFormat.EPUB, bookId)

        assertNull(result)
        assertFalse("rejected EPUB must not publish a cover", cover.exists())
    }

    @Test
    fun `container larger than package limit is rejected when metadata reports actual size`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = "reported-oversized-container-cover"
        val epub = File(context.cacheDir, "$bookId.epub")
        val cover = File(context.filesDir, "covers/$bookId.jpg").apply { delete() }
        writeEpub(
            epub,
            linkedMapOf(
                "META-INF/container.xml" to oversizedContainerXml().toByteArray(),
                "OEBPS/content.opf" to epub3Opf("cover.jpg").toByteArray(),
                "OEBPS/cover.jpg" to FAKE_COVER,
            ),
        )
        ZipFile(epub).use { zip ->
            val entry = checkNotNull(zip.getEntry("META-INF/container.xml"))
            assertTrue("fixture must report a size past the package XML limit", entry.size > PACKAGE_XML_LIMIT_BYTES)
        }

        val result = CoverExtractor.extract(context, epub, BookFormat.EPUB, bookId)

        assertNull(result)
        assertFalse("rejected EPUB must not publish a cover", cover.exists())
    }

    @Test
    fun `opf actual stream larger than package limit is rejected when metadata underreports size`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = "oversized-opf-cover"
        val epub = File(context.cacheDir, "$bookId.epub")
        val cover = File(context.filesDir, "covers/$bookId.jpg").apply { delete() }
        val oversizedOpf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <padding>${"x".repeat(PACKAGE_XML_LIMIT_BYTES)}</padding>
            </package>
        """.trimIndent()
        writeEpub(
            epub,
            linkedMapOf(
                "META-INF/container.xml" to containerXml("OEBPS/content.opf").toByteArray(),
                "OEBPS/content.opf" to oversizedOpf.toByteArray(),
                "OEBPS/cover.jpg" to FAKE_COVER,
            ),
        )
        underreportUncompressedSize(epub, "OEBPS/content.opf", reportedSize = 1)

        ZipFile(epub).use { zip ->
            val entry = checkNotNull(zip.getEntry("OEBPS/content.opf"))
            assertEquals(1L, entry.size)
            val actualSize = zip.getInputStream(entry).use { it.readBytes().size }
            assertTrue("fixture must decompress past the package XML limit", actualSize > PACKAGE_XML_LIMIT_BYTES)
        }

        val result = CoverExtractor.extract(context, epub, BookFormat.EPUB, bookId)

        assertNull(result)
        assertFalse("rejected EPUB must not publish a cover", cover.exists())
    }

    @Test
    fun `cover path falls back to archive root for nonconforming epub`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = "archive-root-cover"
        val epub = File(context.cacheDir, "$bookId.epub")
        val cover = File(context.filesDir, "covers/$bookId.jpg").apply { delete() }
        writeEpub(
            epub,
            linkedMapOf(
                "META-INF/container.xml" to containerXml("OEBPS/content.opf").toByteArray(),
                "OEBPS/content.opf" to epub3Opf("images/cover.jpg").toByteArray(),
                "images/cover.jpg" to FAKE_COVER,
            ),
        )

        val result = CoverExtractor.extract(context, epub, BookFormat.EPUB, bookId)

        assertEquals(android.net.Uri.fromFile(cover).toString(), result)
        assertArrayEquals(FAKE_COVER, cover.readBytes())
    }

    private fun writeEpub(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun underreportUncompressedSize(file: File, entryName: String, reportedSize: Int) {
        val bytes = file.readBytes()
        val endOfCentralDirectory = (bytes.size - END_OF_CENTRAL_DIRECTORY_BYTES downTo 0)
            .firstOrNull { offset -> readLittleEndianInt(bytes, offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE }
            ?: error("missing ZIP end-of-central-directory record")
        var offset = readLittleEndianInt(
            bytes,
            endOfCentralDirectory + END_OF_CENTRAL_DIRECTORY_OFFSET_FIELD,
        )
        while (readLittleEndianInt(bytes, offset) == CENTRAL_DIRECTORY_ENTRY_SIGNATURE) {
            val nameLength = readLittleEndianShort(bytes, offset + CENTRAL_DIRECTORY_NAME_LENGTH_FIELD)
            val extraLength = readLittleEndianShort(bytes, offset + CENTRAL_DIRECTORY_EXTRA_LENGTH_FIELD)
            val commentLength = readLittleEndianShort(bytes, offset + CENTRAL_DIRECTORY_COMMENT_LENGTH_FIELD)
            val name = String(
                bytes,
                offset + CENTRAL_DIRECTORY_HEADER_BYTES,
                nameLength,
                StandardCharsets.UTF_8,
            )
            if (name == entryName) {
                writeLittleEndianInt(
                    bytes,
                    offset + CENTRAL_DIRECTORY_UNCOMPRESSED_SIZE_FIELD,
                    reportedSize,
                )
                file.writeBytes(bytes)
                return
            }
            offset += CENTRAL_DIRECTORY_HEADER_BYTES + nameLength + extraLength + commentLength
        }
        error("missing ZIP central-directory entry: $entryName")
    }

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        readLittleEndianShort(bytes, offset) or (readLittleEndianShort(bytes, offset + 2) shl 16)

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(Int.SIZE_BYTES) { index ->
            bytes[offset + index] = (value ushr (index * Byte.SIZE_BITS)).toByte()
        }
    }

    private fun epub3Opf(coverHref: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest>
            <item id="cover" href="$coverHref" media-type="image/jpeg" properties="cover-image"/>
          </manifest>
        </package>
    """.trimIndent()

    private fun containerXml(opfPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles>
            <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun oversizedContainerXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
          <padding>${"x".repeat(PACKAGE_XML_LIMIT_BYTES)}</padding>
        </container>
    """.trimIndent()

    private companion object {
        const val PACKAGE_XML_LIMIT_BYTES = 2 * 1024 * 1024
        const val END_OF_CENTRAL_DIRECTORY_BYTES = 22
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
        const val END_OF_CENTRAL_DIRECTORY_OFFSET_FIELD = 16
        const val CENTRAL_DIRECTORY_ENTRY_SIGNATURE = 0x02014b50
        const val CENTRAL_DIRECTORY_HEADER_BYTES = 46
        const val CENTRAL_DIRECTORY_UNCOMPRESSED_SIZE_FIELD = 24
        const val CENTRAL_DIRECTORY_NAME_LENGTH_FIELD = 28
        const val CENTRAL_DIRECTORY_EXTRA_LENGTH_FIELD = 30
        const val CENTRAL_DIRECTORY_COMMENT_LENGTH_FIELD = 32
        val FAKE_COVER = "fake-jpeg-cover".toByteArray()
    }
}
