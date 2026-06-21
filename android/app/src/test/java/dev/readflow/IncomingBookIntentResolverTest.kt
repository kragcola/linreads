package dev.readflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IncomingBookIntentResolverTest {

    @Test
    fun `ACTION_VIEW resolves supported data uri`() {
        val uri = IncomingBookIntentResolver.resolve(
            action = "android.intent.action.VIEW",
            mimeType = "application/epub+zip",
            dataUri = "content://books/sample.epub",
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )

        assertEquals("content://books/sample.epub", uri)
    }

    @Test
    fun `ACTION_SEND resolves stream uri`() {
        val uri = IncomingBookIntentResolver.resolve(
            action = "android.intent.action.SEND",
            mimeType = "application/pdf",
            dataUri = null,
            streamUris = listOf("content://books/paper.pdf"),
            clipDataUris = emptyList(),
        )

        assertEquals("content://books/paper.pdf", uri)
    }

    @Test
    fun `ACTION_SEND_MULTIPLE picks first supported book uri`() {
        val uri = IncomingBookIntentResolver.resolve(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = null,
            dataUri = null,
            streamUris = listOf("content://share/cover.jpg", "content://share/notes.txt"),
            clipDataUris = emptyList(),
        )

        assertEquals("content://share/notes.txt", uri)
    }

    @Test
    fun `supported mime type accepts opaque content uri`() {
        val uri = IncomingBookIntentResolver.resolve(
            action = "android.intent.action.VIEW",
            mimeType = "text/plain",
            dataUri = "content://provider/document/42",
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )

        assertEquals("content://provider/document/42", uri)
    }

    @Test
    fun `unsupported action is ignored`() {
        val uri = IncomingBookIntentResolver.resolve(
            action = "android.intent.action.EDIT",
            mimeType = "application/pdf",
            dataUri = "content://books/paper.pdf",
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )

        assertNull(uri)
    }
}
