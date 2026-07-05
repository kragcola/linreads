package dev.readflow.render.epub

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubDataUriImageDecoderTest {

    @Test
    fun `data uri image decodes bounds and bitmap without zip entry`() {
        val epub = File.createTempFile("readflow-empty", ".epub")
        try {
            assertEquals(EpubImageBounds(width = 1, height = 1), decodeEpubImageBounds(epub, onePixelPngDataUri))

            val bitmap = decodeEpubImage(epub, onePixelPngDataUri)

            assertNotNull("data URI image must decode to a bitmap", bitmap)
            assertEquals(1, bitmap!!.width)
            assertEquals(1, bitmap.height)
        } finally {
            epub.delete()
        }
    }

    private companion object {
        const val onePixelPngDataUri =
            "data:image/png;base64," +
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }
}
