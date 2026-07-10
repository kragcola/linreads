package dev.readflow.core.database

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompleteBookDeletionStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun completeDeleteRemovesManagedFileAndAllBookRowsInOneTransaction() = runTest {
        val booksDir = temp.newFolder("books")
        val coversDir = temp.newFolder("covers")
        val bookFile = File(booksDir, "book.epub").apply { writeText("book") }
        val coverFile = File(coversDir, "book-1.jpg").apply { writeText("cover") }
        val dao = RecordingDeletionDao(localUri = bookFile.toURI().toString())
        var transactionCount = 0
        val store = CompleteBookDeletionStore(
            deletionDao = dao,
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { block ->
                transactionCount++
                block()
            },
        )

        store.delete("book-1")

        assertFalse(bookFile.exists())
        assertFalse("managed cover must be deleted with the book", coverFile.exists())
        assertEquals(1, transactionCount)
        assertEquals(
            listOf("progress", "annotations", "ink", "bookmarks", "sessions", "book"),
            dao.deleted,
        )
    }

    @Test
    fun databaseFailureRestoresStagedManagedFile() = runTest {
        val booksDir = temp.newFolder("books")
        val coversDir = temp.newFolder("covers")
        val bookFile = File(booksDir, "book.epub").apply { writeText("book") }
        val coverFile = File(coversDir, "book-1.jpg").apply { writeText("cover") }
        val dao = RecordingDeletionDao(
            localUri = bookFile.toURI().toString(),
            failAt = "bookmarks",
        )
        var bookWasStaged = false
        var coverWasStaged = false
        val store = CompleteBookDeletionStore(
            deletionDao = dao,
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { transaction ->
                bookWasStaged = !bookFile.exists()
                coverWasStaged = !coverFile.exists()
                transaction()
            },
        )

        runCatching { store.delete("book-1") }

        assertTrue("managed book must be staged before the database transaction", bookWasStaged)
        assertTrue("managed cover must be staged before the database transaction", coverWasStaged)
        assertTrue(bookFile.exists())
        assertEquals("book", bookFile.readText())
        assertTrue(coverFile.exists())
        assertEquals("cover", coverFile.readText())
    }

    @Test
    fun unknownDatabaseStateLeavesStagedAssetsForStartupRecovery() = runTest {
        val booksDir = temp.newFolder("unknown-state-books")
        val coversDir = temp.newFolder("covers")
        val bookFile = File(booksDir, "book-1.epub").apply { writeText("book") }
        val coverFile = File(coversDir, "book-1.jpg").apply { writeText("cover") }
        val dao = RecordingDeletionDao(
            localUri = bookFile.toURI().toString(),
            failAt = "bookmarks",
            failGetAfterFirst = true,
        )
        val store = CompleteBookDeletionStore(
            deletionDao = dao,
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        runCatching { store.delete("book-1") }

        assertFalse(bookFile.exists())
        assertFalse(coverFile.exists())
        assertTrue(File(booksDir, "book-1.epub.deleting").exists())
        assertTrue(File(coversDir, "book-1.jpg.deleting").exists())
    }

    @Test
    fun cancellationReportedAfterCommittedTransactionDeletesStagedAssets() = runTest {
        val booksDir = temp.newFolder("post-commit-books")
        val coversDir = temp.newFolder("covers")
        val bookFile = File(booksDir, "book-1.epub").apply { writeText("book") }
        val coverFile = File(coversDir, "book-1.jpg").apply { writeText("cover") }
        val dao = RecordingDeletionDao(localUri = bookFile.toURI().toString())
        val store = CompleteBookDeletionStore(
            deletionDao = dao,
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { transaction ->
                transaction()
                throw CancellationException("cancelled after commit")
            },
        )

        val failure = runCatching { store.delete("book-1") }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse("committed deletion must not restore the managed book", bookFile.exists())
        assertFalse("committed deletion must not restore the managed cover", coverFile.exists())
        assertFalse(File(booksDir, "book-1.epub.deleting").exists())
        assertFalse(File(coversDir, "book-1.jpg.deleting").exists())
    }

    @Test
    fun interruptedDeletionRestoresStagedFileWhenBookRowStillExists() = runTest {
        val booksDir = temp.newFolder("recovery-books")
        val coversDir = temp.newFolder("covers")
        val originalFile = File(booksDir, "book-1.epub")
        val stagedFile = File(booksDir, "book-1.epub.deleting").apply { writeText("book") }
        val originalCover = File(coversDir, "book-1.jpg")
        val stagedCover = File(coversDir, "book-1.jpg.deleting").apply { writeText("cover") }
        val store = CompleteBookDeletionStore(
            deletionDao = RecordingDeletionDao(originalFile.toURI().toString()),
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        store.recoverInterruptedDeletions()

        assertTrue(
            "staged file must be restored when its database row still exists",
            originalFile.exists(),
        )
        assertEquals("book", originalFile.readText())
        assertFalse(stagedFile.exists())
        assertTrue(originalCover.exists())
        assertEquals("cover", originalCover.readText())
        assertFalse(stagedCover.exists())
    }

    @Test
    fun interruptedDeletionCleansStagedFileWhenBookRowIsGone() = runTest {
        val booksDir = temp.newFolder("stale-recovery-books")
        val coversDir = temp.newFolder("covers")
        val originalFile = File(booksDir, "book-1.epub")
        val stagedFile = File(booksDir, "book-1.epub.deleting").apply { writeText("book") }
        val stagedCover = File(coversDir, "book-1.jpg.deleting").apply { writeText("cover") }
        val store = CompleteBookDeletionStore(
            deletionDao = RecordingDeletionDao(
                localUri = originalFile.toURI().toString(),
                bookExists = false,
            ),
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        store.recoverInterruptedDeletions()

        assertFalse(
            "stale staged file must be removed when its database row is gone",
            stagedFile.exists(),
        )
        assertFalse(originalFile.exists())
        assertFalse(stagedCover.exists())
    }

    @Test
    fun recoveryNeverOverwritesAnExistingOriginalFile() = runTest {
        val booksDir = temp.newFolder("existing-original-books")
        val originalFile = File(booksDir, "book-1.epub").apply { writeText("new") }
        val stagedFile = File(booksDir, "book-1.epub.deleting").apply { writeText("old") }
        val store = CompleteBookDeletionStore(
            deletionDao = RecordingDeletionDao(originalFile.toURI().toString()),
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        store.recoverInterruptedDeletions()

        assertEquals("new", originalFile.readText())
        assertFalse(stagedFile.exists())
    }

    @Test
    fun recoveryFailureForOneBookDoesNotBlockOtherBooks() = runTest {
        var secondBookCommitted = false
        val assetStore = object : ManagedBookAssetDeletionStore {
            override fun stage(bookId: String, localUri: String?) = null

            override fun interruptedDeletions(): List<InterruptedBookAssetDeletion> = listOf(
                InterruptedBookAssetDeletion(
                    bookId = "book-1",
                    stagedAsset = object : StagedBookAssetDeletion {
                        override fun commit() = error("damaged staging file")
                        override fun rollback() = error("unexpected rollback")
                    },
                ),
                InterruptedBookAssetDeletion(
                    bookId = "book-2",
                    stagedAsset = object : StagedBookAssetDeletion {
                        override fun commit() {
                            secondBookCommitted = true
                        }

                        override fun rollback() = error("unexpected rollback")
                    },
                ),
            )
        }
        val store = CompleteBookDeletionStore(
            deletionDao = RecordingDeletionDao(localUri = null, bookExists = false),
            assetStore = assetStore,
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        val failures = store.recoverInterruptedDeletions()

        assertTrue("a damaged book group must not block recovery of later groups", secondBookCommitted)
        assertEquals(listOf("book-1"), failures.map(BookDeletionRecoveryFailure::bookId))
        assertEquals("damaged staging file", failures.single().error.message)
    }

    @Test
    fun completeDeleteFindsManagedBookByIdWhenDatabaseUriIsStale() = runTest {
        val booksDir = temp.newFolder("stale-uri-books")
        val managedFile = File(booksDir, "calibre-42.epub").apply { writeText("download") }
        val store = CompleteBookDeletionStore(
            deletionDao = RecordingDeletionDao(localUri = null),
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        store.delete("calibre-42")

        assertFalse("a completed download not yet reflected in Room must still be removed", managedFile.exists())
    }

    @Test
    fun externalFileUriIsNeverDeleted() = runTest {
        val booksDir = temp.newFolder("books")
        val externalFile = temp.newFile("external.epub").apply { writeText("external") }
        val dao = RecordingDeletionDao(externalFile.toURI().toString())
        val store = CompleteBookDeletionStore(
            deletionDao = dao,
            assetStore = FileManagedBookAssetDeletionStore(booksDir),
            transactionRunner = LibraryDeletionTransactionRunner { it() },
        )

        store.delete("book-1")

        assertTrue(externalFile.exists())
        assertEquals("external", externalFile.readText())
    }
}

private class RecordingDeletionDao(
    private val localUri: String?,
    private val failAt: String? = null,
    private val bookExists: Boolean = true,
    private val expectedBookId: String = "book-1",
    private val failGetAfterFirst: Boolean = false,
) : BookDeletionDao {
    val deleted = mutableListOf<String>()
    private var exists = bookExists
    private var getCalls = 0

    override suspend fun getBook(bookId: String): BookEntity? {
        getCalls++
        if (failGetAfterFirst && getCalls > 1) error("database state unavailable")
        return if (exists && bookId == expectedBookId) {
            BookEntity(
                id = bookId,
                title = "Book",
                author = "Author",
                format = "EPUB",
                downloadStatus = "DOWNLOADED",
                localUri = localUri,
            )
        } else {
            null
        }
    }

    override suspend fun deleteProgress(bookId: String) = record("progress")
    override suspend fun deleteAnnotations(bookId: String) = record("annotations")
    override suspend fun deleteInkStrokes(bookId: String) = record("ink")
    override suspend fun deleteBookmarks(bookId: String) = record("bookmarks")
    override suspend fun deleteSessions(bookId: String) = record("sessions")
    override suspend fun deleteBook(bookId: String) {
        record("book")
        exists = false
    }

    private fun record(name: String) {
        if (name == failAt) error("failed at $name")
        deleted += name
    }
}
