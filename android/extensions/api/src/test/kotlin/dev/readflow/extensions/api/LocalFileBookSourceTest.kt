package dev.readflow.extensions.api

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFileBookSourceTest {

    @Test
    fun `importing the same offline file twice keeps a stable book id`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = LocalFileBookSource(context)
        val sourceFile = File(context.cacheDir, "stable-offline-book.txt").apply {
            writeText("Stable offline import keeps progress bookmarks and annotations together.")
        }
        val uri = Uri.fromFile(sourceFile)

        val first = source.import(uri, "text/plain").successValue()
        val second = source.import(uri, "text/plain").successValue()

        assertEquals(first.first.id, second.first.id)
        assertEquals(first.first.localUri, second.first.localUri)
        assertEquals(first.second.bookId, second.second.bookId)
        assertTrue(first.first.id.startsWith("local-"))
    }

    @Test
    fun `stable local ids keep file formats isolated for identical bytes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = LocalFileBookSource(context)
        val text = "Identical bytes can still represent different reader formats."
        val txtFile = File(context.cacheDir, "same-bytes.txt").apply { writeText(text) }
        val mdFile = File(context.cacheDir, "same-bytes.md").apply { writeText(text) }

        val txt = source.import(Uri.fromFile(txtFile), "text/plain").successValue()
        val md = source.import(Uri.fromFile(mdFile), "text/markdown").successValue()

        assertTrue(txt.first.id.startsWith("local-txt-"))
        assertTrue(md.first.id.startsWith("local-md-"))
        assertTrue(txt.first.id != md.first.id)
    }

    @Test
    fun `a failed import leaves no incoming staging file behind`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = LocalFileBookSource(context)
        // A supported extension gets us past the format check so the staging
        // file is created, but the missing file makes openInputStream fail —
        // the exact shape of the scoped-storage read denial that produced
        // 0-byte incoming-* orphans in the books dir.
        val missing = File(context.cacheDir, "does-not-exist.epub")
        missing.delete()

        val result = source.import(Uri.fromFile(missing), "application/epub+zip")

        assertTrue(result is ReadflowResult.Failure)
        val booksDir = File(context.filesDir, "books")
        val orphans = booksDir.listFiles { file -> file.name.startsWith("incoming-") }
        assertTrue(
            "failed import must not leave incoming-* orphans, found: " +
                orphans?.joinToString { it.name },
            orphans.isNullOrEmpty(),
        )
    }

    @Test
    fun `oversized CBZ file URI is rejected before copying`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = LocalFileBookSource(context)
        val archive = File(context.cacheDir, "oversized.cbz")
        RandomAccessFile(archive, "rw").use { file ->
            file.setLength(2L * 1024L * 1024L * 1024L + 1L)
        }

        val result = source.import(Uri.fromFile(archive), "application/vnd.comicbook+zip")

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("CBZ 源文件"))
    }

    @Test
    fun `comic zip mime imports generic zip filename as CBZ`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = LocalFileBookSource(context)
        val archive = File(context.cacheDir, "comic-as-zip.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
        }

        val imported = source.import(Uri.fromFile(archive), "application/vnd.comicbook+zip").successValue()

        assertEquals("CBZ", imported.second.format)
        assertTrue(imported.first.id.startsWith("local-cbz-"))
        assertTrue(imported.first.localUri?.endsWith(".cbz") == true)
    }

    @Test
    fun `CBZ with forged excessive entry count is rejected before private import`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = LocalFileBookSource(context)
        val archive = File(context.cacheDir, "forged-count.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("1.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        RandomAccessFile(archive, "rw").use { file ->
            file.seek(file.length() - 22L + 8L)
            repeat(2) {
                file.write(10_001 and 0xFF)
                file.write(10_001 ushr 8 and 0xFF)
            }
        }

        val result = source.import(Uri.fromFile(archive), "application/vnd.comicbook+zip")

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("CBZ 条目数"))
    }

    private fun <T> ReadflowResult<T>.successValue(): T =
        when (this) {
            is ReadflowResult.Success -> value
            is ReadflowResult.Failure -> error("expected success, got $error")
        }
}
