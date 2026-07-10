package dev.readflow.core.database

import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.model.UncoordinatedBookAssetOperations
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface LibraryDeletionTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

interface StagedBookAssetDeletion {
    fun commit()
    fun rollback()
}

data class InterruptedBookAssetDeletion(
    val bookId: String,
    val stagedAsset: StagedBookAssetDeletion,
)

data class BookDeletionRecoveryFailure(
    val bookId: String,
    val error: Throwable,
)

interface ManagedBookAssetDeletionStore {
    fun stage(bookId: String, localUri: String?): StagedBookAssetDeletion?
    fun interruptedDeletions(): List<InterruptedBookAssetDeletion>
}

class CompleteBookDeletionStore(
    private val deletionDao: BookDeletionDao,
    private val assetStore: ManagedBookAssetDeletionStore,
    private val transactionRunner: LibraryDeletionTransactionRunner,
    private val assetOperations: BookAssetOperationCoordinator = UncoordinatedBookAssetOperations,
) {
    private val deletionMutex = Mutex()

    suspend fun recoverInterruptedDeletions(): List<BookDeletionRecoveryFailure> = deletionMutex.withLock {
        val failures = mutableListOf<BookDeletionRecoveryFailure>()
        assetStore.interruptedDeletions()
            .groupBy(InterruptedBookAssetDeletion::bookId)
            .forEach { (bookId, interrupted) ->
                try {
                    val bookStillExists = deletionDao.getBook(bookId) != null
                    interrupted.forEach { deletion ->
                        if (bookStillExists) {
                            deletion.stagedAsset.rollback()
                        } else {
                            deletion.stagedAsset.commit()
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failures += BookDeletionRecoveryFailure(bookId, error)
                }
            }
        failures
    }

    suspend fun delete(bookId: String) = assetOperations.delete(bookId) {
        deletionMutex.withLock { deleteLocked(bookId) }
    }

    private suspend fun deleteLocked(bookId: String) {
        val stagedAsset = assetStore.stage(bookId, deletionDao.getBook(bookId)?.localUri)
        try {
            transactionRunner.run {
                deletionDao.deleteProgress(bookId)
                deletionDao.deleteAnnotations(bookId)
                deletionDao.deleteInkStrokes(bookId)
                deletionDao.deleteBookmarks(bookId)
                deletionDao.deleteSessions(bookId)
                deletionDao.deleteBook(bookId)
            }
        } catch (error: Throwable) {
            val bookStillExists = withContext(NonCancellable) {
                try {
                    deletionDao.getBook(bookId) != null
                } catch (resolutionError: Throwable) {
                    if (resolutionError !== error) error.addSuppressed(resolutionError)
                    null
                }
            }
            withContext(NonCancellable) {
                try {
                    when (bookStillExists) {
                        true -> stagedAsset?.rollback()
                        false -> stagedAsset?.commit()
                        null -> Unit
                    }
                } catch (finalizationError: Throwable) {
                    if (finalizationError !== error) error.addSuppressed(finalizationError)
                }
            }
            throw error
        }
        stagedAsset?.commit()
    }
}

class FileManagedBookAssetDeletionStore(
    private val managedBooksDir: File,
    private val managedCoversDir: File = File(managedBooksDir.parentFile, "covers"),
) : ManagedBookAssetDeletionStore {
    override fun stage(bookId: String, localUri: String?): StagedBookAssetDeletion? {
        val assets = buildList {
            addAll(managedBookAssets(bookId, localUri))
            managedCoverAsset(bookId)?.let(::add)
        }.distinctBy { it.canonicalPath }
        if (assets.isEmpty()) return null

        val stagedAssets = mutableListOf<FileStagedBookAssetDeletion>()
        try {
            assets.forEach { asset ->
                val staged = File(asset.parentFile, "${asset.name}.deleting")
                check(!staged.exists()) { "已有未完成的文件删除：${asset.name}" }
                move(asset, staged)
                stagedAssets += FileStagedBookAssetDeletion(asset, staged)
            }
        } catch (error: Throwable) {
            stagedAssets.asReversed().forEach { it.rollback() }
            throw error
        }
        return CompositeStagedBookAssetDeletion(stagedAssets)
    }

    override fun interruptedDeletions(): List<InterruptedBookAssetDeletion> =
        listOf(managedBooksDir, managedCoversDir).flatMap { directory ->
            directory.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.name.endsWith(DELETING_SUFFIX) }
                .mapNotNull { staged -> interruptedDeletion(staged) }
                .toList()
        }

    private fun managedBookAssets(bookId: String, localUri: String?): List<File> {
        val root = managedBooksDir.canonicalFile
        val explicitAsset = localUri
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?.takeIf { it.scheme == "file" }
            ?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            ?.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
        val matchingAssets = root.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    !file.name.endsWith(DELETING_SUFFIX) &&
                    file.name.substringBeforeLast('.', missingDelimiterValue = "") == bookId
            }
        return listOfNotNull(explicitAsset) + matchingAssets
    }

    private fun managedCoverAsset(bookId: String): File? {
        val root = managedCoversDir.canonicalFile
        val asset = runCatching { File(root, "$bookId.jpg").canonicalFile }.getOrNull() ?: return null
        if (!asset.toPath().startsWith(root.toPath()) || !asset.isFile) return null
        return asset
    }

    private fun interruptedDeletion(staged: File): InterruptedBookAssetDeletion? {
        val originalName = staged.name.removeSuffix(DELETING_SUFFIX)
        val extensionStart = originalName.lastIndexOf('.')
        if (extensionStart <= 0) return null
        val bookId = originalName.substring(0, extensionStart)
        if (bookId.isBlank()) return null
        val original = File(staged.parentFile, originalName)
        return InterruptedBookAssetDeletion(
            bookId = bookId,
            stagedAsset = FileStagedBookAssetDeletion(original, staged),
        )
    }

    private class CompositeStagedBookAssetDeletion(
        private val assets: List<FileStagedBookAssetDeletion>,
    ) : StagedBookAssetDeletion {
        override fun commit() {
            assets.forEach { it.commit() }
        }

        override fun rollback() {
            assets.asReversed().forEach { it.rollback() }
        }
    }

    private class FileStagedBookAssetDeletion(
        private val original: File,
        private val staged: File,
    ) : StagedBookAssetDeletion {
        override fun commit() {
            Files.deleteIfExists(staged.toPath())
        }

        override fun rollback() {
            if (!staged.exists()) return
            if (original.exists()) {
                Files.deleteIfExists(staged.toPath())
            } else {
                move(staged, original)
            }
        }
    }

    companion object {
        private const val DELETING_SUFFIX = ".deleting"

        private fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
        }
    }
}
