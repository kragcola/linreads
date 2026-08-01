package dev.readflow.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallPolicyTest {
    @Test
    fun cancellationRemovesTheActiveDownload() {
        val action = updateArtifactAction(UpdateArtifactEvent.DownloadCancelled)

        assertTrue(action.removeDownload)
        assertTrue(action.clearMetadata)
    }

    @Test
    fun replacementDownloadsUseASeparateDestinationFile() {
        assertEquals("update-release-210.apk", createUpdateDownloadFileName("release-210"))
    }

}
