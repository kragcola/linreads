package dev.readflow.extensions.api

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class ScannedBook(val uri: Uri, val name: String, val format: BookFormat)

object FolderScanner {
    /** 流式扫描：每找到一本书立即回调 [onFound]，支持协程取消。 */
    suspend fun scan(context: Context, treeUri: Uri, onFound: suspend (ScannedBook) -> Unit) =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
            collect(root, onFound)
        }

    private suspend fun collect(dir: DocumentFile, onFound: suspend (ScannedBook) -> Unit) {
        for (f in dir.listFiles()) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
            if (f.isDirectory) collect(f, onFound)
            else {
                val name = f.name ?: continue
                val fmt = BookFormat.fromExtension(name.substringAfterLast('.', ""))
                if (fmt != BookFormat.UNKNOWN) onFound(ScannedBook(f.uri, name, fmt))
            }
        }
    }
}
