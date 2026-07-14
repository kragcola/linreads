package dev.readflow.core.database

import androidx.room.Room
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookDaoCreateGroupTest {

    private lateinit var database: ReadflowDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            ReadflowDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.bookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create group updates both single books together`() = runBlocking {
        dao.upsert(book("source"))
        dao.upsert(book("target"))

        dao.createGroup("source", "target", "group-reading", "Reading List")

        assertEquals("group-reading", dao.getById("source")?.collectionId)
        assertEquals("group-reading", dao.getById("target")?.collectionId)
        assertEquals("Reading List", dao.getById("source")?.collectionName)
        assertEquals("Reading List", dao.getById("target")?.collectionName)
    }

    @Test
    fun `create group leaves source untouched when target disappeared`() = runBlocking {
        dao.upsert(book("source"))

        dao.createGroup("source", "missing-target", "group-reading", "Reading List")

        assertEquals(null, dao.getById("source")?.collectionId)
        assertEquals(null, dao.getById("source")?.collectionName)
    }

    @Test
    fun `create group leaves both books untouched when target joined another group`() = runBlocking {
        dao.upsert(book("source"))
        dao.upsert(book("target", collectionId = "group-existing", collectionName = "Existing Group"))

        dao.createGroup("source", "target", "group-reading", "Reading List")

        assertEquals(null, dao.getById("source")?.collectionId)
        assertEquals(null, dao.getById("source")?.collectionName)
        assertEquals("group-existing", dao.getById("target")?.collectionId)
        assertEquals("Existing Group", dao.getById("target")?.collectionName)
    }

    @Test
    fun `collection update reports whether one row was changed`() = runBlocking {
        assertEquals(0, dao.updateCollection("missing", "group-reading", "Reading List"))
        dao.upsert(book("source"))

        assertEquals(1, dao.updateCollection("source", "group-reading", "Reading List"))
        assertEquals("group-reading", dao.getById("source")?.collectionId)
        assertEquals("Reading List", dao.getById("source")?.collectionName)
    }

    @Test
    fun `move to group updates a single source only when target group exists`() = runBlocking {
        dao.upsert(book("source"))
        dao.upsert(book("target", collectionId = "group-reading", collectionName = "Reading List"))

        assertEquals(1, dao.moveToGroup("source", "group-reading"))
        assertEquals("group-reading", dao.getById("source")?.collectionId)
        assertEquals("Reading List", dao.getById("source")?.collectionName)
    }

    @Test
    fun `move to group leaves source untouched when target group disappeared`() = runBlocking {
        dao.upsert(book("source"))

        assertEquals(0, dao.moveToGroup("source", "missing-group"))
        assertEquals(null, dao.getById("source")?.collectionId)
        assertEquals(null, dao.getById("source")?.collectionName)
    }

    @Test
    fun `move to group does not overwrite a concurrent group assignment`() = runBlocking {
        dao.upsert(book("source", collectionId = "group-other", collectionName = "Other Group"))
        dao.upsert(book("target", collectionId = "group-reading", collectionName = "Reading List"))

        assertEquals(0, dao.moveToGroup("source", "group-reading"))
        assertEquals("group-other", dao.getById("source")?.collectionId)
        assertEquals("Other Group", dao.getById("source")?.collectionName)
    }

    @Test
    fun `rename emits the new group name to an existing shelf subscriber`() = runBlocking {
        dao.upsert(book("first", collectionId = "group-reading", collectionName = "Reading List", sortOrder = 0))
        dao.upsert(book("second", collectionId = "group-reading", collectionName = "Reading List", sortOrder = 1))
        val repository = LibraryRepository(dao, NoOpDownloadedBookCache)
        val initialEmissionSeen = CompletableDeferred<Unit>()
        val emissions = async {
            withTimeout(2_000) {
                repository.observeShelf()
                    .onEach {
                        if (!initialEmissionSeen.isCompleted) initialEmissionSeen.complete(Unit)
                    }
                    .take(2)
                    .toList()
            }
        }
        withTimeout(2_000) { initialEmissionSeen.await() }

        repository.renameBundle("group-reading", "Renamed List")

        assertEquals(
            "Renamed List",
            (emissions.await().last().single() as LibraryItem.Bundle).bundle.name,
        )
    }

    @Test
    fun `ungroup emits cleared group names to an existing shelf subscriber`() = runBlocking {
        dao.upsert(book("first", collectionId = "group-reading", collectionName = "Reading List", sortOrder = 0))
        dao.upsert(book("second", collectionId = "group-reading", collectionName = "Reading List", sortOrder = 1))
        val repository = LibraryRepository(dao, NoOpDownloadedBookCache)
        val initialEmissionSeen = CompletableDeferred<Unit>()
        val emissions = async {
            withTimeout(2_000) {
                repository.observeShelf()
                    .onEach {
                        if (!initialEmissionSeen.isCompleted) initialEmissionSeen.complete(Unit)
                    }
                    .take(2)
                    .toList()
            }
        }
        withTimeout(2_000) { initialEmissionSeen.await() }

        repository.ungroupBundle("group-reading")

        assertEquals(
            listOf(null, null),
            emissions.await().last()
                .filterIsInstance<LibraryItem.Single>()
                .map { it.book.collectionName },
        )
    }

    private object NoOpDownloadedBookCache : DownloadedBookCacheStore {
        override suspend fun trim(protectedBookId: String?): List<DownloadedCacheEviction> = emptyList()
        override suspend fun removeDownloadedAsset(bookId: String): DownloadedCacheEviction? = null
    }

    private fun book(
        id: String,
        collectionId: String? = null,
        collectionName: String? = null,
        sortOrder: Int = 0,
    ) = BookEntity(
        id = id,
        title = id,
        author = "",
        format = "EPUB",
        downloadStatus = "DOWNLOADED",
        collectionId = collectionId,
        collectionName = collectionName,
        sortOrder = sortOrder,
    )
}
