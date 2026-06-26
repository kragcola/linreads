package dev.readflow.render.api

/**
 * Stores engine-private acceleration data outside SavedStateHandle.
 *
 * The bytes are optional cache data: losing them may make reopen slower, but must not
 * change the semantic reading position, which lives in ReaderState/Locator.
 */
interface EngineStateStore {
    suspend fun load(bookId: String): ByteArray?
    suspend fun save(bookId: String, state: ByteArray)
    suspend fun evict(bookId: String)
}
