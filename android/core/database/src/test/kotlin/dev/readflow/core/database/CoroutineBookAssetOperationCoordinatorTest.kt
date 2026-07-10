package dev.readflow.core.database

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineBookAssetOperationCoordinatorTest {
    @Test
    fun matchingProducerWaitsUntilDeletionFinishes() = runTest {
        val coordinator = CoroutineBookAssetOperationCoordinator()
        val deletionStarted = CompletableDeferred<Unit>()
        val finishDeletion = CompletableDeferred<Unit>()
        var producerRan = false
        val deletion = launch {
            coordinator.delete("book-1") {
                deletionStarted.complete(Unit)
                finishDeletion.await()
            }
        }
        deletionStarted.await()

        val producer = launch {
            coordinator.produce("book-1") { producerRan = true }
        }
        runCurrent()

        assertFalse("a new matching producer must wait behind the active deletion", producerRan)
        finishDeletion.complete(Unit)
        deletion.join()
        producer.join()
        assertTrue(producerRan)
    }

    @Test
    fun nestedDeletionBySameCoroutineDoesNotDeadlock() = runTest {
        val coordinator = CoroutineBookAssetOperationCoordinator()
        var nestedDeletionRan = false

        coordinator.delete("book-1") {
            coordinator.delete("book-1") { nestedDeletionRan = true }
        }

        assertTrue(nestedDeletionRan)
    }

    @Test
    fun deletionWaitsForUnknownLocalImportInsteadOfCancellingIt() = runTest {
        val coordinator = CoroutineBookAssetOperationCoordinator()
        val importStarted = CompletableDeferred<Unit>()
        val finishImport = CompletableDeferred<Unit>()
        var importFinished = false
        var deletionRan = false
        val producer = launch {
            coordinator.produce(bookId = null) {
                importStarted.complete(Unit)
                finishImport.await()
                importFinished = true
            }
        }
        importStarted.await()

        val deletion = launch {
            coordinator.delete("book-1") {
                assertTrue("the unknown import must finish before deletion runs", importFinished)
                deletionRan = true
            }
        }
        runCurrent()

        assertFalse("an unrelated or not-yet-identified import must not be cancelled", producer.isCancelled)
        assertFalse(deletionRan)
        finishImport.complete(Unit)
        producer.join()
        deletion.join()
        assertTrue(deletionRan)
    }
}
