package dev.readflow.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallPolicyTest {
    @Test
    fun settingsDownloadKeepsTheReleaseBuildTagForNotificationReuse() {
        val metadata = updateDownloadMetadata(
            UpdatePackageInfo(
                apkUrl = "https://example.test/linreads.apk",
                notes = "Release notes",
                buildTag = "dev-210-release",
                versionCode = 210L,
            ),
        )

        assertEquals("https://example.test/linreads.apk", metadata.apkUrl)
        assertEquals("dev-210-release", metadata.buildTag)
        assertEquals(210L, metadata.versionCode)
    }

    @Test
    fun cancellationRemovesTheActiveDownload() {
        val action = updateArtifactAction(UpdateArtifactEvent.DownloadCancelled)

        assertTrue(action.removeDownload)
        assertTrue(action.clearMetadata)
    }

    @Test
    fun replacementKeepsACompletedApkThatTheInstallerMayStillBeReading() {
        val action = updateArtifactAction(UpdateArtifactEvent.ReplacedByNewDownload)

        assertFalse(action.removeDownload)
        assertTrue(action.clearMetadata)
    }

    @Test
    fun replacementDownloadsUseASeparateDestinationFile() {
        assertEquals("update-release-210.apk", createUpdateDownloadFileName("release-210"))
    }

}
