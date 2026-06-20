package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** Generic content loading state (§7.4). Sealed for pure-data polymorphism. */
@Serializable
sealed interface LoadingState {
    @Serializable data object Idle : LoadingState
    @Serializable data object Loading : LoadingState
    @Serializable data object Loaded : LoadingState
    @Serializable data class Error(val error: ReadflowError) : LoadingState
}

/** Page-transition animation kind (§7.4). */
enum class TransitionType { SLIDE, CURL, FADE, NONE }

/** Platform-neutral downloaded book asset (R1-2, replaces java.io.File in model). */
@Serializable
data class DownloadedAsset(
    val bookId: String,
    val format: String,
    val localUri: String,     // platform-neutral; data layer maps to Uri / File / SAF doc
    val sizeBytes: Long,
    val checksum: String? = null,
)
