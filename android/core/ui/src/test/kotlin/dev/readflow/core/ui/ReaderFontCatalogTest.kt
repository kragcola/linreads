package dev.readflow.core.ui

import dev.readflow.core.model.FontChoice
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReaderFontCatalogTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `catalog exposes three real system families before custom fonts`() {
        assertEquals(
            listOf(
                FontChoice.System,
                FontChoice.SystemSans,
                FontChoice.SystemMonospace,
                FontChoice.Custom("Novel.otf"),
            ),
            FontProvider.availableChoices(listOf("Novel.otf")),
        )
    }

    @Test
    fun `catalog labels describe actual system fonts and preserve custom names`() {
        assertEquals("系统衬线", FontProvider.label(FontChoice.System))
        assertEquals("系统无衬线", FontProvider.label(FontChoice.SystemSans))
        assertEquals("系统等宽", FontProvider.label(FontChoice.SystemMonospace))
        assertEquals("Novel.otf", FontProvider.label(FontChoice.Custom("Novel.otf")))
    }

    @Test
    fun `reader display names hide storage extensions and separators`() {
        assertEquals("系统衬线", FontProvider.displayName(FontChoice.System))
        assertEquals("Novel Serif", FontProvider.displayName(FontChoice.Custom("Novel_Serif.otf")))
        assertEquals("方正 楷体", FontProvider.displayName(FontChoice.Custom("方正-楷体.ttf")))
    }

    @Test
    fun `font import copy rejects content beyond its byte budget`() {
        val input = ByteArrayInputStream(ByteArray(5) { it.toByte() })
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            FontProvider.copyFontWithLimit(input, output, maxBytes = 4L)
        }
    }

    @Test
    fun `font content identity detects renamed duplicates and ignores different bytes`() {
        val dir = tempDir.toFile()
        val existing = dir.resolve("Novel Serif.ttf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val renamedDuplicate = dir.resolve("renamed.otf.part").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val different = dir.resolve("different.ttf.part").apply { writeBytes(byteArrayOf(1, 2, 3, 5)) }

        assertEquals(existing, FontProvider.findDuplicateFontFile(dir, renamedDuplicate))
        assertNull(FontProvider.findDuplicateFontFile(dir, different))
    }

    @Test
    fun `staged font deletion can roll back or commit without exposing a half deleted file`() {
        val dir = tempDir.toFile()
        val choice = FontChoice.Custom("Novel.ttf")
        val original = dir.resolve(choice.fileName).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val rolledBack = FontProvider.stageImportedFontDeletion(dir, choice).getOrThrow()
        assertFalse(original.exists())
        FontProvider.rollbackImportedFontDeletion(rolledBack).getOrThrow()
        assertTrue(original.exists())

        val committed = FontProvider.stageImportedFontDeletion(dir, choice).getOrThrow()
        FontProvider.commitImportedFontDeletion(committed).getOrThrow()
        assertFalse(original.exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".readflow-delete") })
    }

    @Test
    fun `a second staged deletion cannot claim the active tombstone`() {
        val dir = tempDir.toFile()
        val choice = FontChoice.Custom("Novel.ttf")
        dir.resolve(choice.fileName).writeBytes(byteArrayOf(1, 2, 3, 4))

        val owner = FontProvider.stageImportedFontDeletion(dir, choice).getOrThrow()
        val duplicate = FontProvider.stageImportedFontDeletion(dir, choice)

        assertTrue(duplicate.isFailure)
        FontProvider.rollbackImportedFontDeletion(owner).getOrThrow()
        assertTrue(dir.resolve(choice.fileName).exists())
    }

    @Test
    fun `startup finalization deletes pending originals and tombstones without restoring them`() {
        val dir = tempDir.toFile()
        val originalChoice = FontChoice.Custom("Original.ttf")
        val tombstoneChoice = FontChoice.Custom("Staged.otf")
        dir.resolve(originalChoice.fileName).writeBytes(byteArrayOf(1, 2, 3, 4))
        dir.resolve(".${tombstoneChoice.fileName}.readflow-delete")
            .writeBytes(byteArrayOf(5, 6, 7, 8))

        FontProvider.finalizePendingImportedFontDeletion(dir, originalChoice).getOrThrow()
        FontProvider.finalizePendingImportedFontDeletion(dir, tombstoneChoice).getOrThrow()

        assertFalse(dir.resolve(originalChoice.fileName).exists())
        assertFalse(dir.resolve(tombstoneChoice.fileName).exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".readflow-delete") })
    }

    @Test
    fun `startup recovery restores tombstones that have no durable deletion ledger`() {
        val dir = tempDir.toFile()
        val tombstone = dir.resolve(".Interrupted.ttf.readflow-delete")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        FontProvider.recoverInterruptedFontDeletions(dir)

        assertFalse(tombstone.exists())
        assertTrue(dir.resolve("Interrupted.ttf").exists())
    }
}
