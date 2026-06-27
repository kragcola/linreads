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

    private fun <T> ReadflowResult<T>.successValue(): T =
        when (this) {
            is ReadflowResult.Success -> value
            is ReadflowResult.Failure -> error("expected success, got $error")
        }
}
