package dev.readflow.core.database

import androidx.room.Room
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryBundleIdentityTest {

    private lateinit var database: ReadflowDatabase
    private lateinit var dao: BookDao
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            ReadflowDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.bookDao()
        repository = LibraryRepository(dao, NoOpDownloadedBookCache)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `creating a same-name bundle keeps both bundle identities`() = runBlocking {
        dao.upsert(book("existing-first", sortOrder = 0))
        dao.upsert(book("existing-second", sortOrder = 1))
        dao.upsert(book("new-first", sortOrder = 2))
        dao.upsert(book("new-second", sortOrder = 3))
        repository.createGroup("existing-first", "existing-second", "Same Name")

        repository.createGroup("new-first", "new-second", "Same Name")

        val bundles = repository.observeShelf().first().bundles()
        assertEquals(2, bundles.size)
        assertEquals(setOf("Same Name"), bundles.map(BundleSnapshot::name).toSet())
        assertEquals(2, bundles.map(BundleSnapshot::id).toSet().size)
        assertEquals(
            setOf(
                setOf("existing-first", "existing-second"),
                setOf("new-first", "new-second"),
            ),
            bundles.map(BundleSnapshot::memberIds).toSet(),
        )
    }

    @Test
    fun `renaming a bundle to an existing name keeps both bundle identities`() = runBlocking {
        dao.upsert(book("first-group-first", sortOrder = 0))
        dao.upsert(book("first-group-second", sortOrder = 1))
        dao.upsert(book("renamed-group-first", sortOrder = 2))
        dao.upsert(book("renamed-group-second", sortOrder = 3))
        repository.createGroup("first-group-first", "first-group-second", "Same Name")
        repository.createGroup("renamed-group-first", "renamed-group-second", "Old Name")
        val beforeRename = repository.observeShelf().first().bundles()
        val renamedBundleId = beforeRename.single {
            it.memberIds == setOf("renamed-group-first", "renamed-group-second")
        }.id

        repository.renameBundle(renamedBundleId, "Same Name")

        val bundles = repository.observeShelf().first().bundles()
        assertEquals(2, bundles.size)
        assertEquals(setOf("Same Name"), bundles.map(BundleSnapshot::name).toSet())
        assertEquals(
            beforeRename.associate { it.id to it.memberIds },
            bundles.associate { it.id to it.memberIds },
        )
    }

    @Test
    fun `moving a book targets the selected same-name bundle id`() = runBlocking {
        dao.upsert(book("first-group-first", sortOrder = 0))
        dao.upsert(book("first-group-second", sortOrder = 1))
        dao.upsert(book("second-group-first", sortOrder = 2))
        dao.upsert(book("second-group-second", sortOrder = 3))
        dao.upsert(book("source", sortOrder = 4))
        repository.createGroup("first-group-first", "first-group-second", "Same Name")
        repository.createGroup("second-group-first", "second-group-second", "Same Name")
        val beforeMove = repository.observeShelf().first().bundles()
        val firstBundle = beforeMove.single {
            it.memberIds == setOf("first-group-first", "first-group-second")
        }
        val secondBundle = beforeMove.single {
            it.memberIds == setOf("second-group-first", "second-group-second")
        }

        repository.moveToGroup("source", secondBundle.id)

        val bundlesById = repository.observeShelf().first().bundles().associateBy(BundleSnapshot::id)
        assertEquals(2, bundlesById.size)
        assertEquals(
            setOf("first-group-first", "first-group-second"),
            bundlesById.getValue(firstBundle.id).memberIds,
        )
        assertEquals(
            setOf("second-group-first", "second-group-second", "source"),
            bundlesById.getValue(secondBundle.id).memberIds,
        )
        assertEquals(setOf("Same Name"), bundlesById.values.map(BundleSnapshot::name).toSet())
    }

    @Test
    fun `ungrouping targets the selected same-name bundle id`() = runBlocking {
        dao.upsert(book("first-group-first", sortOrder = 0))
        dao.upsert(book("first-group-second", sortOrder = 1))
        dao.upsert(book("second-group-first", sortOrder = 2))
        dao.upsert(book("second-group-second", sortOrder = 3))
        repository.createGroup("first-group-first", "first-group-second", "Same Name")
        repository.createGroup("second-group-first", "second-group-second", "Same Name")
        val beforeUngroup = repository.observeShelf().first().bundles()
        val firstBundle = beforeUngroup.single {
            it.memberIds == setOf("first-group-first", "first-group-second")
        }
        val secondBundle = beforeUngroup.single {
            it.memberIds == setOf("second-group-first", "second-group-second")
        }

        repository.ungroupBundle(firstBundle.id)

        val shelf = repository.observeShelf().first()
        assertEquals(
            listOf(
                BundleSnapshot(
                    id = secondBundle.id,
                    name = "Same Name",
                    memberIds = setOf("second-group-first", "second-group-second"),
                ),
            ),
            shelf.bundles(),
        )
        assertEquals(
            setOf(
                Triple("first-group-first", null, null),
                Triple("first-group-second", null, null),
            ),
            shelf.filterIsInstance<LibraryItem.Single>()
                .map { Triple(it.book.id, it.book.collectionId, it.book.collectionName) }
                .toSet(),
        )
    }

    private data class BundleSnapshot(
        val id: String,
        val name: String,
        val memberIds: Set<String>,
    )

    private fun List<LibraryItem>.bundles(): List<BundleSnapshot> =
        filterIsInstance<LibraryItem.Bundle>()
            .map { item ->
                BundleSnapshot(
                    id = item.bundle.id,
                    name = item.bundle.name,
                    memberIds = item.bundle.books.map { it.id }.toSet(),
                )
            }

    private object NoOpDownloadedBookCache : DownloadedBookCacheStore {
        override suspend fun trim(protectedBookId: String?): List<DownloadedCacheEviction> = emptyList()
        override suspend fun removeDownloadedAsset(bookId: String): DownloadedCacheEviction? = null
    }

    private fun book(
        id: String,
        sortOrder: Int,
    ) = BookEntity(
        id = id,
        title = id,
        author = "",
        format = "EPUB",
        downloadStatus = "DOWNLOADED",
        sortOrder = sortOrder,
    )
}
