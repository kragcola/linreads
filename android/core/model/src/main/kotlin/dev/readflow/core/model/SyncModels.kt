package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** Per-book reading position with sync metadata (§7.7). `updatedAt`+`deviceId` = LWW key. */
@Serializable
data class ReadingProgress(
    val bookId: String,
    val locator: Locator,
    val progressPercent: Float,
    val updatedAt: Long,        // LWW key
    val deviceId: String,       // origin replica
)

/** A bookmark with Union-merge metadata (§7.7). `id` stable across devices, `isDeleted` tombstone. */
@Serializable
data class Bookmark(
    val id: String,             // UUID, stable across devices
    val bookId: String,
    val locator: Locator,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,        // LWW
    val deviceId: String,
    val isDeleted: Boolean = false,  // tombstone for Union merge
)
