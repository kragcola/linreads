package dev.readflow

import dev.readflow.core.model.FontChoice
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadflowApplicationRecoveryTest {
    @Test
    fun `startup contains recovery enumeration failure`() = runTest {
        val failure = IllegalStateException("cannot list staging directory")
        val reported = mutableListOf<Pair<String?, Throwable>>()

        recoverBookDeletionsAtStartup(
            recover = { throw failure },
            onFailure = { bookId, error -> reported += bookId to error },
        )

        assertEquals(listOf(null to failure), reported)
    }

    @Test
    fun `startup completes every durable font deletion before recovering orphan tombstones`() = runTest {
        val first = FontChoice.Custom("Novel.ttf")
        val second = FontChoice.Custom("Code.otf")
        val events = mutableListOf<String>()

        recoverImportedFontDeletionsAtStartup(
            pendingDeletions = { setOf(first, second) },
            finalizeFile = { choice ->
                events += "file:${choice.serialize()}"
                Result.success(Unit)
            },
            completeDeletion = { choice -> events += "ledger:${choice.serialize()}" },
            recoverOrphans = {
                events += "orphans"
                Result.success(Unit)
            },
            onFailure = { choice, error ->
                events += "failure:${choice?.serialize()}:${error.message}"
            },
        )

        assertEquals(
            listOf(
                "file:custom:Novel.ttf",
                "ledger:custom:Novel.ttf",
                "file:custom:Code.otf",
                "ledger:custom:Code.otf",
                "orphans",
            ),
            events,
        )
    }

    @Test
    fun `startup retains ledger when font file finalization fails`() = runTest {
        val choice = FontChoice.Custom("Novel.ttf")
        val failure = IllegalStateException("disk busy")
        val completed = mutableListOf<FontChoice.Custom>()
        val reported = mutableListOf<Pair<FontChoice.Custom?, Throwable>>()
        var orphanRecoveryCalls = 0

        recoverImportedFontDeletionsAtStartup(
            pendingDeletions = { setOf(choice) },
            finalizeFile = { Result.failure(failure) },
            completeDeletion = { completed += it },
            recoverOrphans = {
                orphanRecoveryCalls += 1
                Result.success(Unit)
            },
            onFailure = { failedChoice, error -> reported += failedChoice to error },
        )

        assertEquals(emptyList<FontChoice.Custom>(), completed)
        assertEquals(listOf(choice to failure), reported)
        assertEquals(1, orphanRecoveryCalls)
    }

    @Test
    fun `startup does not restore tombstones when deletion ledger cannot be read`() = runTest {
        val failure = IllegalStateException("datastore unavailable")
        val reported = mutableListOf<Pair<FontChoice.Custom?, Throwable>>()
        var orphanRecoveryCalls = 0

        recoverImportedFontDeletionsAtStartup(
            pendingDeletions = { throw failure },
            finalizeFile = { Result.success(Unit) },
            completeDeletion = {},
            recoverOrphans = {
                orphanRecoveryCalls += 1
                Result.success(Unit)
            },
            onFailure = { choice, error -> reported += choice to error },
        )

        assertEquals(listOf(null to failure), reported)
        assertEquals(0, orphanRecoveryCalls)
    }
}
