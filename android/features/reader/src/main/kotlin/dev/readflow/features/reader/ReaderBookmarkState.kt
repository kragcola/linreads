package dev.readflow.features.reader

import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.model.Bookmark
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

data class ReaderBookmarkItem(
    val id: String,
    val locator: Locator,
    val label: String,
    val totalProgression: Float,
    val createdAt: Long,
)

data class ReaderBookmarkState(
    val items: List<ReaderBookmarkItem> = emptyList(),
    val currentBookmarkId: String? = null,
) {
    val isCurrentBookmarked: Boolean
        get() = currentBookmarkId != null
}

internal fun readerBookmarkStateFor(
    entities: List<BookmarkEntity>,
    currentLocator: Locator?,
): ReaderBookmarkState {
    val items = entities
        .asSequence()
        .filterNot { it.isDeleted }
        .sortedWith(compareBy<BookmarkEntity> { it.totalProgression }.thenBy { it.createdAt })
        .mapNotNull { entity ->
            val locator = runCatching { Json.decodeFromString<Locator>(entity.locatorJson) }.getOrNull()
                ?: return@mapNotNull null
            ReaderBookmarkItem(
                id = entity.id,
                locator = locator,
                label = readerBookmarkLabel(entity.totalProgression),
                totalProgression = entity.totalProgression,
                createdAt = entity.createdAt,
            )
        }
        .toList()
    return ReaderBookmarkState(
        items = items,
        currentBookmarkId = currentLocator?.let { locator ->
            items.firstOrNull { it.locator.matchesBookmarkPosition(locator) }?.id
        },
    )
}

internal fun readerBookmarkEntityFor(
    bookId: String,
    locator: Locator,
    deviceId: String,
    now: Long,
    id: String,
): BookmarkEntity =
    bookmarkProgression(locator).let { totalProgression ->
    BookmarkEntity(
        id = id,
        bookId = bookId,
        totalProgression = totalProgression,
        locatorJson = Json.encodeToString(locator),
        text = readerBookmarkLabel(totalProgression),
        createdAt = now,
        updatedAt = now,
        deviceId = deviceId,
    )
    }

private fun Locator.matchesBookmarkPosition(other: Locator): Boolean =
    if (strategy == other.strategy) {
        true
    } else {
        val left = totalProgression
        val right = other.totalProgression
        left != null && right != null && kotlin.math.abs(left - right) <= BOOKMARK_PROGRESS_EPSILON
    }

private fun bookmarkProgression(locator: Locator): Float =
    (locator.totalProgression ?: locator.progression ?: 0f).coerceIn(0f, 1f)

private fun readerBookmarkLabel(totalProgression: Float): String =
    "书签 ${(totalProgression.coerceIn(0f, 1f) * 100f).roundToInt()}%"

/**
 * Optional secondary line for BookmarkPanel: user-readable strategy detail only.
 * Never returns raw byte offsets as the sole position label.
 */
internal fun readerBookmarkDetailLabel(item: ReaderBookmarkItem): String? =
    when (val strategy = item.locator.strategy) {
        is LocatorStrategy.Page -> pageDetailLabel(strategy.index, strategy.total)
        // Imported/annotation PageText may show page number; creation stays bare Page.
        is LocatorStrategy.PageText -> pageDetailLabel(strategy.index, strategy.total)
        is LocatorStrategy.Section -> {
            val section = (strategy.spineIndex + 1).coerceAtLeast(1)
            "第 $section 节"
        }
        is LocatorStrategy.ByteOffset,
        LocatorStrategy.Unknown,
        -> null
    }

private fun pageDetailLabel(index: Int, totalRaw: Int): String {
    val total = totalRaw.coerceAtLeast(0)
    val page = (index + 1).let { n ->
        if (total > 0) n.coerceIn(1, total) else n.coerceAtLeast(1)
    }
    return if (total > 0) "第 $page / $total 页" else "第 $page 页"
}

internal fun BookmarkEntity.toBookmarkModel(): Bookmark? {
    val locator = runCatching { Json.decodeFromString<Locator>(locatorJson) }.getOrNull() ?: return null
    return Bookmark(
        id = id,
        bookId = bookId,
        locator = locator,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        isDeleted = isDeleted,
    )
}

internal fun ReaderBookmarkItem.toDeletedBookmarkModel(
    bookId: String,
    deviceId: String,
    updatedAt: Long,
): Bookmark =
    Bookmark(
        id = id,
        bookId = bookId,
        locator = locator,
        text = label,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        isDeleted = true,
    )

private const val BOOKMARK_PROGRESS_EPSILON = 0.0005f
