package dev.readflow.core.database

import dev.readflow.core.model.BookAssetOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CoroutineBookAssetOperationCoordinator : BookAssetOperationCoordinator {
    private data class ActiveDeletion(
        val owner: Job,
        val completed: CompletableDeferred<Unit>,
    )

    private val stateMutex = Mutex()
    private val producers = mutableMapOf<Job, String?>()
    private val deletions = mutableMapOf<String, ActiveDeletion>()

    override suspend fun <T> produce(bookId: String?, operation: suspend () -> T): T {
        val producer = checkNotNull(currentCoroutineContext()[Job]) {
            "Managed book asset production requires a coroutine Job"
        }
        while (true) {
            val blockers = stateMutex.withLock {
                val activeBlockers = if (bookId == null) {
                    deletions.values.map(ActiveDeletion::completed)
                } else {
                    listOfNotNull(deletions[bookId]?.completed)
                }
                if (activeBlockers.isEmpty()) {
                    producers[producer] = bookId
                    null
                } else {
                    activeBlockers
                }
            }
            if (blockers == null) break
            blockers.forEach { it.await() }
        }

        return try {
            operation()
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock { producers.remove(producer) }
            }
        }
    }

    override suspend fun <T> delete(bookId: String, operation: suspend () -> T): T {
        val owner = checkNotNull(currentCoroutineContext()[Job]) {
            "Managed book asset deletion requires a coroutine Job"
        }
        var ownedDeletion: ActiveDeletion? = null
        var producersToJoin: List<Job> = emptyList()
        var producersToCancel: List<Job> = emptyList()

        while (ownedDeletion == null) {
            var existingDeletion: ActiveDeletion? = null
            var reentrant = false
            stateMutex.withLock {
                val existing = deletions[bookId]
                when {
                    existing == null -> {
                        ownedDeletion = ActiveDeletion(owner, CompletableDeferred())
                        deletions[bookId] = checkNotNull(ownedDeletion)
                        producersToJoin = producers
                            .filterValues { producerBookId ->
                                producerBookId == null || producerBookId == bookId
                            }
                            .keys
                            .filterNot { it === owner }
                        producersToCancel = producers
                            .filterValues { producerBookId -> producerBookId == bookId }
                            .keys
                            .filterNot { it === owner }
                    }
                    existing.owner === owner -> reentrant = true
                    else -> existingDeletion = existing
                }
            }
            if (reentrant) return operation()
            existingDeletion?.completed?.await()
        }

        val deletion = checkNotNull(ownedDeletion)
        return try {
            producersToCancel.forEach { producer ->
                producer.cancel(CancellationException("Book $bookId was deleted"))
            }
            withContext(NonCancellable) { producersToJoin.joinAll() }
            currentCoroutineContext().ensureActive()
            operation()
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (deletions[bookId] === deletion) {
                        deletions.remove(bookId)
                        deletion.completed.complete(Unit)
                    }
                }
            }
        }
    }
}
