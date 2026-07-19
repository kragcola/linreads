package dev.readflow.features.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral source contracts for AnnotationPanel: 48dp delete target, stable keys,
 * count header, and Chinese TalkBack labels without a Compose instrumentation harness.
 */
class ReaderAnnotationPanelContractTest {

    @Test
    fun `annotation panel keeps navigation row plus independent 48dp delete control`() {
        val source = readerScreenSource()
        val annotationPanel = source
            .substringAfter("private fun AnnotationPanel(", missingDelimiterValue = "")
            .substringBefore("private fun SearchPanel(")
        val annotationDelete = annotationPanel
            .substringAfter("onClick = { onAnnotationRemove(annotation) }", missingDelimiterValue = "")
            .substringBefore("}")

        assertTrue(
            "host must wire RemoveAnnotation intent separately from GoToAnnotation",
            source.contains("onAnnotationRemove = { viewModel.onIntent(ReaderIntent.RemoveAnnotation(it)) }") &&
                source.contains("onAnnotationClick = { viewModel.onIntent(ReaderIntent.GoToAnnotation(it)) }"),
        )
        assertTrue(
            "AnnotationPanel must accept a distinct remove callback",
            annotationPanel.contains("onAnnotationRemove: (ReaderAnnotationItem) -> Unit") ||
                source.contains("onAnnotationRemove: (ReaderAnnotationItem) -> Unit"),
        )
        assertTrue(
            "AnnotationPanel header exposes total count",
            (
                annotationPanel.contains("annotationState.items.size") ||
                    annotationPanel.contains("items.size")
                ) &&
                (
                    annotationPanel.contains("\"标注（") ||
                        annotationPanel.contains("标注（") ||
                        annotationPanel.contains("个标注")
                    ),
        )
        assertTrue(
            "AnnotationPanel rows stay whole-row navigable with min 48dp height and jump a11y",
            annotationPanel.contains("heightIn(min = 48.dp)") &&
                annotationPanel.contains("contentDescription = annotation.accessibilityLabel(") &&
                annotationPanel.contains(".clickable { onAnnotationClick(annotation) }"),
        )
        assertTrue(
            "LazyColumn uses stable annotation id keys",
            annotationPanel.contains("key = { it.id }") ||
                annotationPanel.contains("key = { annotation -> annotation.id }") ||
                annotationPanel.contains("key = { annotation.id }"),
        )
        assertTrue(
            "delete control is independent >=48dp IconButton with Chinese description from item",
            annotationPanel.contains("onClick = { onAnnotationRemove(annotation) }") &&
                annotationDelete.contains("sizeIn(minWidth = 48.dp, minHeight = 48.dp)") &&
                (
                    annotationPanel.contains("contentDescription = annotation.deleteAccessibilityLabel(") ||
                        annotationPanel.contains("deleteAccessibilityLabel()")
                    ),
        )
        assertTrue(
            "annotation text uses ellipsis overflow as appropriate",
            annotationPanel.contains("TextOverflow.Ellipsis") ||
                annotationPanel.contains("overflow = TextOverflow.Ellipsis"),
        )
        assertTrue(
            "empty annotation state stays compact Chinese copy",
            annotationPanel.contains("\"暂无标注\"") &&
                annotationPanel.contains("padding(vertical = 12.dp)"),
        )
        assertTrue(
            "annotation panel must not introduce schema, Room migration, or sync contracts",
            !source.contains("Migration(") &&
                !source.contains("@Database") &&
                !source.contains("autoMigrations") &&
                !annotationPanel.contains("Room") &&
                !annotationPanel.contains("syncAnnotation") &&
                !annotationPanel.contains("TextAnnotationEntity("),
        )
    }

    private fun readerScreenSource(): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
            File(workingDir, "features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
            File(workingDir, "android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("ReaderScreen.kt not found from ${workingDir.absolutePath}")
    }
}
