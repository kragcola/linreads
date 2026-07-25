package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdatePackageInstallerTest {

    @Test
    fun duplicateDownloadCompletionKeepsTheActiveStagingWorker() {
        assertEquals(
            InstallEnqueueAction.KEEP_EXISTING,
            installEnqueueAction(
                currentDownloadId = 42,
                currentStage = InstallStage.STAGING,
                requestedDownloadId = 42,
            ),
        )
    }

    @Test
    fun duplicateDownloadCompletionNeverCommitsASecondSession() {
        listOf(InstallStage.COMMITTED, InstallStage.AWAITING_USER).forEach { stage ->
            assertEquals(
                InstallEnqueueAction.KEEP_EXISTING,
                installEnqueueAction(
                    currentDownloadId = 42,
                    currentStage = stage,
                    requestedDownloadId = 42,
                ),
            )
        }
    }

    @Test
    fun persistedStagingKeepsTheRecoverableWorkAndFailureCanRestart() {
        assertEquals(
            InstallEnqueueAction.KEEP_EXISTING,
            installEnqueueAction(42, InstallStage.STAGING, 42),
        )
        assertEquals(
            InstallEnqueueAction.START,
            installEnqueueAction(42, InstallStage.FAILED, 42),
        )
    }

    @Test
    fun explicitRetryRebuildsCommittedAndAwaitingUserSessions() {
        listOf(InstallStage.COMMITTED, InstallStage.AWAITING_USER).forEach { stage ->
            assertEquals(
                InstallEnqueueAction.START,
                installEnqueueAction(
                    currentDownloadId = 42,
                    currentStage = stage,
                    requestedDownloadId = 42,
                    retryRequested = true,
                ),
            )
        }
    }
}
