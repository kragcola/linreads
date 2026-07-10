package dev.readflow.core.model

/** Coordinates managed book-file producers with destructive operations for the same book. */
interface BookAssetOperationCoordinator {
    suspend fun <T> produce(bookId: String?, operation: suspend () -> T): T
    suspend fun <T> delete(bookId: String, operation: suspend () -> T): T
}

object UncoordinatedBookAssetOperations : BookAssetOperationCoordinator {
    override suspend fun <T> produce(bookId: String?, operation: suspend () -> T): T = operation()
    override suspend fun <T> delete(bookId: String, operation: suspend () -> T): T = operation()
}
