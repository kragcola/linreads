package dev.readflow.extensions.api

import android.content.Context
import android.net.Uri
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.model.ReadflowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First-launch seeding (Phase 1 §11 基建闭环). Checks if shelf is empty; if yes,
 * imports sample books from assets/sample_books/ into Room so the library isn't
 * empty on first open. Runs once on app cold start (called from Application.onCreate).
 */
class FirstLaunchSeeder(
    private val context: Context,
    private val localSource: LocalFileBookSource,
    private val repository: LibraryRepository,
) {
    suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        if (repository.count() > 0) return@withContext

        val assets = context.assets.list("sample_books") ?: return@withContext
        val books = assets.mapNotNull { fileName ->
            try {
                val assetUri = Uri.parse("file:///android_asset/sample_books/$fileName")
                // Copy asset to cache → let LocalFileBookSource import it.
                val cached = copyAssetToCache(fileName)
                when (val result = localSource.import(cached)) {
                    is ReadflowResult.Success -> result.value.first
                    is ReadflowResult.Failure -> {
                        android.util.Log.w("FirstLaunchSeeder", "导入 $fileName 失败: ${result.error.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("FirstLaunchSeeder", "播种 $fileName 异常", e)
                null
            }
        }

        if (books.isNotEmpty()) {
            repository.upsertAll(books)
            android.util.Log.i("FirstLaunchSeeder", "首次启动播种了 ${books.size} 本示例书")
        }
    }

    private fun copyAssetToCache(fileName: String): Uri {
        val cacheFile = java.io.File(context.cacheDir, "seed_$fileName")
        context.assets.open("sample_books/$fileName").use { input ->
            cacheFile.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(cacheFile)
    }
}
