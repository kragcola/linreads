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
            ),
        )

        assertEquals("https://example.test/linreads.apk", metadata.apkUrl)
        assertEquals("dev-210-release", metadata.buildTag)
    }

    @Test
    fun installerLaunchKeepsTheDownloadedApkAvailableForRetry() {
        val action = updateArtifactAction(UpdateArtifactEvent.InstallerLaunched)

        assertFalse(action.removeDownload)
        assertFalse(action.clearMetadata)
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

    @Test
    fun installRequestUsesTheDedicatedPackageInstallerAction() {
        val spec = updateInstallRequestSpec(launchInNewTask = true)

        assertEquals("android.intent.action.INSTALL_PACKAGE", spec.action)
        assertEquals("application/vnd.android.package-archive", spec.mimeType)
        assertTrue(spec.grantReadPermission)
        assertTrue(spec.launchInNewTask)
    }
}
