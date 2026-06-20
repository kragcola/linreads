package dev.readflow.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies the bundled sample.txt to cacheDir and returns a file:// Uri the engine's
 * ContentResolver can open (android_asset is not a ContentResolver scheme). Minimal
 * slice only — real books come from BookSource/SAF later.
 */
suspend fun copySampleToCache(context: Context): Uri = withContext(Dispatchers.IO) {
    val out = File(context.cacheDir, "sample.txt")
    if (!out.exists()) {
        context.assets.open("sample.txt").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
    }
    Uri.fromFile(out)
}
