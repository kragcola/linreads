package dev.readflow.extensions.api

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScannedBook(val uri: Uri, val name: String, val format: BookFormat)

object FolderScanner {
    suspend fun scan(context: Context, treeUri: Uri): List<ScannedBook> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            collect(root)
        }

    private fun collect(dir: DocumentFile): List<ScannedBook> = buildList {
        for (f in dir.listFiles()) {
            if (f.isDirectory) addAll(collect(f))
            else {
                val name = f.name ?: continue
                val fmt = BookFormat.fromExtension(name.substringAfterLast('.', ""))
                if (fmt != BookFormat.UNKNOWN) add(ScannedBook(f.uri, name, fmt))
            }
        }
    }
}
