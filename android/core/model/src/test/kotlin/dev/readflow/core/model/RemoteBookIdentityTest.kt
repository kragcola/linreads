package dev.readflow.core.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteBookIdentityTest {

    @Test
    fun `recognizes legacy and source scoped remote ids without matching local imports`() {
        assertTrue("calibre-42".isRemoteBookId())
        assertTrue("remote-source-json-book-42-aaaaaaaa".isRemoteBookId())
        assertFalse("local-import".isRemoteBookId())
    }

    @Test
    fun `downloaded remote asset requires remote identity completed state and local uri`() {
        assertTrue(remoteBook("remote-source-opds-book-7-bbbbbbbb").hasDownloadedRemoteAsset)
        assertTrue(remoteBook("calibre-42").hasDownloadedRemoteAsset)
        assertFalse(remoteBook("local-import").hasDownloadedRemoteAsset)
        assertFalse(
            remoteBook("remote-source-opds-book-7-bbbbbbbb")
                .copy(downloadStatus = DownloadStatus.NOT_DOWNLOADED)
                .hasDownloadedRemoteAsset,
        )
        assertFalse(remoteBook("remote-source-opds-book-7-bbbbbbbb").copy(localUri = null).hasDownloadedRemoteAsset)
    }

    @Test
    fun `remote offline readability requires completed download while local imports only require uri`() {
        assertTrue(remoteBook("remote-source-html-book-9-cccccccc").isOfflineReadable)
        assertFalse(
            remoteBook("remote-source-html-book-9-cccccccc")
                .copy(downloadStatus = DownloadStatus.NOT_DOWNLOADED)
                .isOfflineReadable,
        )
        assertTrue(
            remoteBook("local-import")
                .copy(downloadStatus = DownloadStatus.NOT_DOWNLOADED)
                .isOfflineReadable,
        )
    }

    private fun remoteBook(id: String) = BookMeta(
        id = id,
        title = "Book",
        author = "Author",
        format = BookFormat.EPUB,
        downloadStatus = DownloadStatus.DOWNLOADED,
        localUri = "file:///books/$id.epub",
    )
}
