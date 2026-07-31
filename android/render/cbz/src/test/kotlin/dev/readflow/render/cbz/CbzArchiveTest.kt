package dev.readflow.render.cbz

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CbzArchiveTest {

    @Test
    fun `index keeps only decodable image candidates in natural reading order`() {
        val archive = tempCbz(
            "chapter/10.jpg" to ONE_PIXEL_PNG,
            "__MACOSX/._2.jpg" to byteArrayOf(1),
            "chapter/2.jpg" to ONE_PIXEL_PNG,
            "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
            "chapter/001.jpg" to ONE_PIXEL_PNG,
            "notes.txt" to "ignore".encodeToByteArray(),
        )

        val manifest = CbzArchiveIndexer().index(archive)

        assertEquals(
            listOf("chapter/001.jpg", "chapter/2.jpg", "chapter/10.jpg"),
            manifest.pages.map(CbzPageEntry::archiveName),
        )
    }

    @Test
    fun `ComicInfo right to left metadata controls pager direction`() {
        val archive = tempCbz(
            "1.jpg" to ONE_PIXEL_PNG,
            "ComicInfo.xml" to """
                <?xml version="1.0"?>
                <ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>
            """.trimIndent().encodeToByteArray(),
        )

        val manifest = CbzArchiveIndexer().index(archive)

        assertEquals(CbzReadingDirection.RIGHT_TO_LEFT, manifest.readingDirection)
    }

    @Test
    fun `malformed ComicInfo falls back to left to right without rejecting pages`() {
        val archive = tempCbz(
            "1.jpg" to ONE_PIXEL_PNG,
            "ComicInfo.xml" to "<ComicInfo><Manga>".encodeToByteArray(),
        )

        val manifest = CbzArchiveIndexer().index(archive)

        assertEquals(CbzReadingDirection.LEFT_TO_RIGHT, manifest.readingDirection)
        assertEquals(listOf("1.jpg"), manifest.pages.map(CbzPageEntry::archiveName))
    }

    @Test
    fun `index rejects archives outside bounded page and byte budgets`() {
        val archive = tempCbz(
            "1.jpg" to ONE_PIXEL_PNG,
            "2.jpg" to ONE_PIXEL_PNG,
        )
        val limits = CbzSafetyLimits(
            maxPages = 1,
            maxEntryBytes = 1024,
            maxTotalBytes = 2048,
            maxCompressionRatio = 500,
        )

        assertThrows(IOException::class.java) {
            CbzArchiveIndexer(limits).index(archive)
        }
    }

    @Test
    fun `index rejects excessive total entries before filtering image pages`() {
        val archive = tempCbz(
            "1.jpg" to ONE_PIXEL_PNG,
            "metadata/one.txt" to byteArrayOf(1),
            "metadata/two.txt" to byteArrayOf(2),
        )
        val limits = CbzSafetyLimits(
            maxEntries = 2,
            maxPages = 10,
            maxEntryBytes = 1024,
            maxTotalBytes = 2048,
            maxCompressionRatio = 500,
        )

        val error = assertThrows(IOException::class.java) {
            CbzArchiveIndexer(limits).index(archive)
        }

        assertTrue(error.message.orEmpty().contains("条目数"))
    }

    @Test
    fun `extraction never uses archive paths and rejects fake images`() {
        val archive = tempCbz(
            "../../outside.jpg" to "not an image".encodeToByteArray(),
        )
        val output = kotlin.io.path.createTempDirectory("readflow-cbz-pages-").toFile()
        val session = CbzArchiveSession.open(archive, output)

        try {
            assertThrows(IOException::class.java) {
                session.preparePageBlocking(0)
            }
            assertTrue(output.canonicalFile.walkTopDown().all { file ->
                file.canonicalPath.startsWith(output.canonicalPath)
            })
        } finally {
            session.close()
        }
    }

    @Test
    fun `evicted pages can be extracted again without exhausting the live cache budget`() {
        val archive = tempCbz(
            "1.png" to ONE_PIXEL_PNG,
            "2.png" to ONE_PIXEL_PNG,
            "3.png" to ONE_PIXEL_PNG,
        )
        val output = kotlin.io.path.createTempDirectory("readflow-cbz-pages-").toFile()
        val session = CbzArchiveSession.open(
            archive = archive,
            outputDirectory = output,
            limits = CbzSafetyLimits(
                maxPages = 3,
                maxEntryBytes = 1024,
                maxTotalBytes = ONE_PIXEL_PNG.size.toLong() * 3,
                maxCompressionRatio = 500,
            ),
        )

        try {
            session.preparePageBlocking(0)
            session.retainPreparedIndexes(setOf(1))
            session.preparePageBlocking(1)
            session.retainPreparedIndexes(setOf(2))
            session.preparePageBlocking(2)
            session.retainPreparedIndexes(setOf(0))

            val extractedAgain = session.preparePageBlocking(0)

            assertTrue(extractedAgain.file.isFile)
            assertEquals(setOf(0), session.preparedIndexes())
        } finally {
            session.close()
        }
    }

    @Test
    fun `cancelled extraction leaves no prepared page or staging file`() {
        val archive = tempCbz("1.png" to ONE_PIXEL_PNG)
        val output = kotlin.io.path.createTempDirectory("readflow-cbz-pages-").toFile()
        val session = CbzArchiveSession.open(archive, output)

        try {
            assertThrows(CancellationException::class.java) {
                session.preparePageBlocking(0) { false }
            }
            assertTrue(session.preparedIndexes().isEmpty())
            assertTrue(output.listFiles().isNullOrEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun `quarter-turn EXIF orientation swaps the visible page geometry`() {
        assertEquals(1200 to 800, orientedImageDimensions(800, 1200, 90))
        assertEquals(1200 to 800, orientedImageDimensions(800, 1200, 270))
        assertEquals(800 to 1200, orientedImageDimensions(800, 1200, 0))
        assertEquals(800 to 1200, orientedImageDimensions(800, 1200, 180))
    }

    @Test
    fun `decoded page bounds follow EXIF rotation from a real image file`() {
        val image = kotlin.io.path.createTempFile("readflow-cbz-exif-", ".jpg").toFile()
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(image).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }

        assertEquals(12 to 8, decodeImageBounds(image))
    }

    @Test
    fun `extraction rejects image geometry above configured device budget`() {
        val archive = tempCbz("1.png" to solidPng(8, 12))
        val output = kotlin.io.path.createTempDirectory("readflow-cbz-geometry-").toFile()
        val session = CbzArchiveSession.open(
            archive,
            output,
            CbzSafetyLimits(maxImageDimension = 10),
        )

        try {
            val error = assertThrows(IOException::class.java) {
                session.preparePageBlocking(0)
            }
            assertTrue(error.message.orEmpty().contains("尺寸"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `actual extraction ratio rejects output beyond the compressed byte budget`() {
        assertTrue(exceedsCompressionRatio(251, 1, 250))
        assertTrue(!exceedsCompressionRatio(250, 1, 250))
        assertTrue(!exceedsCompressionRatio(1024, -1, 1))
    }

    private fun tempCbz(vararg entries: Pair<String, ByteArray>): File =
        kotlin.io.path.createTempFile("readflow-cbz-", ".cbz").toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun solidPng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val ONE_PIXEL_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
