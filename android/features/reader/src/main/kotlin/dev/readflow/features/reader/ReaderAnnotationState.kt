package dev.readflow.features.reader

import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.Locator
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val TEXT_ANNOTATION_ANCHOR_TYPE = "text-selection-range"

@Serializable
data class ReaderTextAnnotationAnchor(
    val start: Locator,
    val end: Locator,
)

data class ReaderAnnotationState(
    val items: List<ReaderAnnotationItem> = emptyList(),
    val renderAnnotations: List<ReaderTextAnnotation> = emptyList(),
)

data class ReaderAnnotationItem(
    val id: String,
    val start: Locator,
    val end: Locator,
    val selectedText: String,
    val note: String?,
    val color: Int,
    val totalProgression: Float,
)

fun readerTextAnnotationEntityFor(
    bookId: String,
    selection: ReaderTextSelection,
    note: String?,
    color: Int,
    deviceId: String,
    now: Long,
    id: String,
): TextAnnotationEntity =
    TextAnnotationEntity(
        id = id,
        bookId = bookId,
        totalProgression = selection.start.totalProgression
            ?: selection.end.totalProgression
            ?: 0f,
        anchorType = TEXT_ANNOTATION_ANCHOR_TYPE,
        anchorJson = Json.encodeToString(ReaderTextAnnotationAnchor(selection.start, selection.end)),
        selectedText = selection.selectedText,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
        color = color,
        createdAt = now,
        updatedAt = now,
        deviceId = deviceId,
    )

fun readerAnnotationStateFor(entities: List<TextAnnotationEntity>): ReaderAnnotationState {
    val items = entities
        .asSequence()
        .filter { !it.isDeleted && it.anchorType == TEXT_ANNOTATION_ANCHOR_TYPE }
        .mapNotNull { entity ->
            val anchor = runCatching {
                Json.decodeFromString<ReaderTextAnnotationAnchor>(entity.anchorJson)
            }.getOrNull() ?: return@mapNotNull null
            ReaderAnnotationItem(
                id = entity.id,
                start = anchor.start,
                end = anchor.end,
                selectedText = entity.selectedText,
                note = entity.note,
                color = entity.color,
                totalProgression = entity.totalProgression,
            )
        }
        .sortedBy { it.totalProgression }
        .toList()
    return ReaderAnnotationState(
        items = items,
        renderAnnotations = items.map {
            ReaderTextAnnotation(
                id = it.id,
                start = it.start,
                end = it.end,
                selectedText = it.selectedText,
                note = it.note,
                color = it.color,
            )
        },
    )
}
