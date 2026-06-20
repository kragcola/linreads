package dev.readflow.render.api

import android.net.Uri
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.flow.StateFlow

/** Lightweight, eagerly-collectible engine metadata. No engine instance created here (§5.2). */
data class EngineDescriptor(
    val id: String,
    val format: BookFormat,
    val priority: Int,
    val quickSupports: (Uri) -> Boolean,
    val provider: () -> ReaderEngine,
)

class NoEngineException(uri: Uri) : IllegalStateException("No ReaderEngine supports: $uri")

/**
 * Resolves the best engine for a uri. Holds only immutable descriptors + a read-only
 * override snapshot → thread-safe (§5.2).
 */
class ReaderEngineRegistry(
    private val descriptors: Set<EngineDescriptor>,
    private val userOverrides: StateFlow<Map<BookFormat, String>>,
) {
    suspend fun resolve(uri: Uri): ReaderEngine {
        val path = uri.lastPathSegment ?: uri.path ?: ""
        val format = BookFormat.fromExtension(path.substringAfterLast('.', ""))

        userOverrides.value[format]?.let { id ->
            descriptors.find { it.id == id }?.let { return it.provider() }
        }

        val winner = descriptors
            .filter { it.format == format && it.quickSupports(uri) }
            .minByOrNull { it.priority }
            ?: throw NoEngineException(uri)

        return winner.provider().also {
            if (!it.supports(uri)) throw NoEngineException(uri)
        }
    }

    fun candidatesFor(format: BookFormat): List<EngineDescriptor> =
        descriptors.filter { it.format == format }.sortedBy { it.priority }
}
