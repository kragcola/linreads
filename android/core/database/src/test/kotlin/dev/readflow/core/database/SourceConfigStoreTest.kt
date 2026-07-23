package dev.readflow.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceConfigStoreTest {

    @Test
    fun explicitEmptyObjectConfigIsPreservedForConfiguredAdapter() = runTest {
        val entity = BookSourceEntity(
            id = "source-plugin",
            kind = "third-party",
            name = "Plugin",
            baseUrl = "https://example.com",
            adapterId = "plugin-v1",
            configJson = "{}",
            configVersion = 3,
        )
        val store = RoomSourceConfigStore(FakeBookSourceDao(entity))

        val source = store.observeUserSources().first().single()

        assertEquals("plugin-v1", source.adapterId)
        assertEquals("{}", source.configJson)
        assertEquals(3, source.configVersion)
    }

    private class FakeBookSourceDao(
        private val entity: BookSourceEntity,
    ) : BookSourceDao {
        override fun observeEnabled(): Flow<List<BookSourceEntity>> = flowOf(listOf(entity))
        override fun observeAll(): Flow<List<BookSourceEntity>> = flowOf(listOf(entity))
        override suspend fun getById(id: String): BookSourceEntity? = entity.takeIf { it.id == id }
        override suspend fun upsert(entity: BookSourceEntity) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun maxSortOrder(): Int = entity.sortOrder
    }
}
