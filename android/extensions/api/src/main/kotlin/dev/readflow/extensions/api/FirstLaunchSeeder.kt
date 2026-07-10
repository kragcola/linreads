package dev.readflow.extensions.api

import android.content.Context
import android.net.Uri
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.UncoordinatedBookAssetOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds sample books from assets/sample_books/ into Room.
 * Tracks already-seeded filenames in SharedPrefs so new seed books added in
 * subsequent APK versions are imported on the next launch (even after a cover install).
 */
class FirstLaunchSeeder(
    private val context: Context,
    private val localSource: LocalFileBookSource,
    private val repository: LibraryRepository,
    private val assetOperations: BookAssetOperationCoordinator = UncoordinatedBookAssetOperations,
) {
    suspend fun seedIfEmpty() = assetOperations.produce(bookId = null) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            val done = prefs.getStringSet("seeded_files", emptySet())!!.toMutableSet()

            val assets = context.assets.list("sample_books") ?: return@withContext
            val pending = assets.filter { it !in done }
            if (pending.isEmpty()) return@withContext

            val newBooks = pending.mapNotNull { fileName ->
                try {
                    val cached = copyAssetToCache(fileName)
                    when (val result = localSource.import(cached)) {
                        is ReadflowResult.Success -> { done += fileName; result.value.first }
                        is ReadflowResult.Failure -> {
                            android.util.Log.w("FirstLaunchSeeder", "导入 $fileName 失败: ${result.error.message}")
                            null
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (e: Exception) {
                    android.util.Log.w("FirstLaunchSeeder", "播种 $fileName 异常", e)
                    null
                }
            }

            if (newBooks.isNotEmpty()) {
                repository.upsertAll(newBooks)
                prefs.edit().putStringSet("seeded_files", done).apply()
                android.util.Log.i("FirstLaunchSeeder", "播种了 ${newBooks.size} 本新示例书")
            }
        }
    }

    private fun copyAssetToCache(fileName: String): Uri {
        val cacheFile = java.io.File(context.cacheDir, "seed_$fileName")
        context.assets.open("sample_books/$fileName").use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(cacheFile)
    }
}
