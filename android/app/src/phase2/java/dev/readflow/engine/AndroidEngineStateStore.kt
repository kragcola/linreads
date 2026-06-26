package dev.readflow.engine

import dev.readflow.render.api.EngineStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class AndroidEngineStateStore(
    cacheDir: File,
) : EngineStateStore {

    private val root = File(cacheDir, ENGINE_STATE_DIR)

    override suspend fun load(bookId: String): ByteArray? = withContext(Dispatchers.IO) {
        stateFile(bookId).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun save(bookId: String, state: ByteArray) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val target = stateFile(bookId)
        val temp = File(root, "${target.name}.tmp")
        temp.writeBytes(state)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    override suspend fun evict(bookId: String) = withContext(Dispatchers.IO) {
        stateFile(bookId).delete()
        Unit
    }

    private fun stateFile(bookId: String): File =
        File(root, "${bookId.cacheKey()}.bin")

    private companion object {
        const val ENGINE_STATE_DIR = "engine-state"

        fun String.cacheKey(): String {
            val safe = replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(64)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (safe.isNotEmpty() && safe == this) return safe
            return "${safe.ifEmpty { "book" }}-${digest.take(16)}"
        }
    }
}
