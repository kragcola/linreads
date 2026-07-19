package dev.readflow.features.reader

import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextSelection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderAnnotationStateTest {

    @Test
    fun `builds text annotation entity from current selection and note`() {
        val selection = ReaderTextSelection(
            start = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 3, charOffset = 12), totalProgression = 0.24f),
            end = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 3, charOffset = 18), totalProgression = 0.26f),
            selectedText = "重点内容",
        )

        val entity = readerTextAnnotationEntityFor(
            bookId = "book-1",
            selection = selection,
            note = "这里要回看",
            color = 0x66FFE082,
            deviceId = "device-uuid",
            now = 1234L,
            id = "annotation-id",
        )

        assertEquals("annotation-id", entity.id)
        assertEquals("book-1", entity.bookId)
        assertEquals(0.24f, entity.totalProgression)
        assertEquals(TEXT_ANNOTATION_ANCHOR_TYPE, entity.anchorType)
        assertEquals("重点内容", entity.selectedText)
        assertEquals("这里要回看", entity.note)
        assertEquals(0x66FFE082, entity.color)
        assertEquals("device-uuid", entity.deviceId)
        assertEquals(1234L, entity.createdAt)
        assertEquals(1234L, entity.updatedAt)

        val anchor = Json.decodeFromString<ReaderTextAnnotationAnchor>(entity.anchorJson)
        assertEquals(selection.start, anchor.start)
        assertEquals(selection.end, anchor.end)
    }

    @Test
    fun `PageText start and end annotation anchors round trip through JSON`() {
        val start = Locator(
            strategy = LocatorStrategy.PageText(index = 2, total = 40, charOffset = 15),
            totalProgression = 0.05f,
        )
        val end = Locator(
            strategy = LocatorStrategy.PageText(index = 2, total = 40, charOffset = 48),
            totalProgression = 0.05f,
        )
        val selection = ReaderTextSelection(
            start = start,
            end = end,
            selectedText = "PDF 高亮片段",
        )
        val entity = readerTextAnnotationEntityFor(
            bookId = "pdf-book",
            selection = selection,
            note = "页内字符点",
            color = 0x66FFE082,
            deviceId = "device-uuid",
            now = 999L,
            id = "page-text-ann",
        )
        val anchor = Json.decodeFromString<ReaderTextAnnotationAnchor>(entity.anchorJson)
        assertEquals(start, anchor.start)
        assertEquals(end, anchor.end)
        assertEquals(
            LocatorStrategy.PageText(2, 40, 15),
            (anchor.start.strategy as LocatorStrategy.PageText),
        )
        assertEquals(
            LocatorStrategy.PageText(2, 40, 48),
            (anchor.end.strategy as LocatorStrategy.PageText),
        )
        // Opaque Room JSON path: encode→decode of entity anchor is stable.
        val reencoded = Json.encodeToString(anchor)
        val again = Json.decodeFromString<ReaderTextAnnotationAnchor>(reencoded)
        assertEquals(anchor, again)
    }

    @Test
    fun `maps active annotation entities to render annotations in progression order`() {
        val earlyStart = Locator(LocatorStrategy.ByteOffset(offset = 10L, length = 4), totalProgression = 0.2f)
        val earlyEnd = Locator(LocatorStrategy.ByteOffset(offset = 14L, length = 0), totalProgression = 0.21f)
        val early = annotationEntity(
            id = "early",
            totalProgression = 0.2f,
            start = earlyStart,
            end = earlyEnd,
            selectedText = "早",
            note = null,
        )
        val late = annotationEntity(
            id = "late",
            totalProgression = 0.8f,
            start = Locator(LocatorStrategy.ByteOffset(offset = 80L, length = 6), totalProgression = 0.8f),
            end = Locator(LocatorStrategy.ByteOffset(offset = 86L, length = 0), totalProgression = 0.81f),
            selectedText = "晚",
            note = "memo",
        )
        val deleted = annotationEntity(
            id = "deleted",
            totalProgression = 0.1f,
            start = earlyStart,
            end = earlyEnd,
            selectedText = "删",
            note = null,
            isDeleted = true,
        )

        val state = readerAnnotationStateFor(listOf(late, deleted, early))

        assertEquals(listOf("early", "late"), state.items.map { it.id })
        assertEquals(listOf("早", "晚"), state.items.map { it.selectedText })
        assertEquals(listOf("early", "late"), state.renderAnnotations.map { it.id })
        assertEquals("memo", state.renderAnnotations.last().note)
    }

    private fun annotationEntity(
        id: String,
        totalProgression: Float,
        start: Locator,
        end: Locator,
        selectedText: String,
        note: String?,
        isDeleted: Boolean = false,
    ): TextAnnotationEntity =
        TextAnnotationEntity(
            id = id,
            bookId = "book-1",
            totalProgression = totalProgression,
            anchorType = TEXT_ANNOTATION_ANCHOR_TYPE,
            anchorJson = Json.encodeToString(ReaderTextAnnotationAnchor(start, end)),
            selectedText = selectedText,
            note = note,
            color = 0x66FFE082,
            createdAt = 100L,
            updatedAt = 100L,
            deviceId = "device",
            isDeleted = isDeleted,
        )
}
