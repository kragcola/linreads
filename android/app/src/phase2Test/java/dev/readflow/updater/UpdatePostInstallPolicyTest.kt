package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdatePostInstallPolicyTest {

    @Test
    fun `task role is derived from the LinReads root component`() {
        assertEquals(
            PostInstallTaskRole.MAIN,
            postInstallTaskRole(
                packageName = "dev.readflow",
                rootPackageName = "dev.readflow",
                rootClassName = "dev.readflow.MainActivity",
            ),
        )
        assertEquals(
            PostInstallTaskRole.INSTALLER,
            postInstallTaskRole(
                packageName = "dev.readflow",
                rootPackageName = "dev.readflow",
                rootClassName = "dev.readflow.updater.UpdateApkInstallActivity",
            ),
        )
        assertEquals(
            PostInstallTaskRole.OTHER,
            postInstallTaskRole(
                packageName = "dev.readflow",
                rootPackageName = "com.huawei.appmarket",
                rootClassName = "com.huawei.appgallery.search.ui.BaseSearchActivity",
            ),
        )
    }

    @Test
    fun `non OTA package replacement never takes over the foreground`() {
        assertEquals(
            emptyList<PostInstallTakeoverAction>(),
            postInstallTakeoverActions(
                savedBuildTag = "dev-100298-old",
                armedBuildTag = "dev-100298-old",
                currentBuildTag = "dev-100299-new",
                handledBuildTag = null,
                hasMainTask = true,
                installerTaskCount = 1,
            ),
        )
    }

    @Test
    fun `current OTA restores the main task before removing installer tasks`() {
        assertEquals(
            listOf(
                PostInstallTakeoverAction.POST_COMPLETION_NOTIFICATION,
                PostInstallTakeoverAction.MOVE_MAIN_TASK_TO_FRONT,
                PostInstallTakeoverAction.REMOVE_INSTALLER_TASK,
                PostInstallTakeoverAction.REMOVE_INSTALLER_TASK,
            ),
            postInstallTakeoverActions(
                savedBuildTag = "dev-100299-new",
                armedBuildTag = "dev-100299-new",
                currentBuildTag = "dev-100299-new",
                handledBuildTag = null,
                hasMainTask = true,
                installerTaskCount = 2,
            ),
        )
    }

    @Test
    fun `current OTA launches MainActivity when no main task survived`() {
        assertEquals(
            listOf(
                PostInstallTakeoverAction.POST_COMPLETION_NOTIFICATION,
                PostInstallTakeoverAction.LAUNCH_MAIN_ACTIVITY,
                PostInstallTakeoverAction.REMOVE_INSTALLER_TASK,
            ),
            postInstallTakeoverActions(
                savedBuildTag = "dev-100299-new",
                armedBuildTag = "dev-100299-new",
                currentBuildTag = "dev-100299-new",
                handledBuildTag = null,
                hasMainTask = false,
                installerTaskCount = 1,
            ),
        )
    }

    @Test
    fun `repeated replacement signal is idempotent`() {
        assertEquals(
            emptyList<PostInstallTakeoverAction>(),
            postInstallTakeoverActions(
                savedBuildTag = "dev-100299-new",
                armedBuildTag = "dev-100299-new",
                currentBuildTag = "dev-100299-new",
                handledBuildTag = "dev-100299-new",
                hasMainTask = true,
                installerTaskCount = 1,
            ),
        )
    }

    @Test
    fun `downloaded build without an armed install never takes over the foreground`() {
        assertEquals(
            emptyList<PostInstallTakeoverAction>(),
            postInstallTakeoverActions(
                savedBuildTag = "dev-100299-new",
                armedBuildTag = null,
                currentBuildTag = "dev-100299-new",
                handledBuildTag = null,
                hasMainTask = true,
                installerTaskCount = 1,
            ),
        )
    }

    @Test
    fun `installer task remains when no visible recovery route exists`() {
        assertEquals(
            true,
            shouldRemovePostInstallInstallerTask(
                hadMainTask = true,
                completionNotificationPosted = false,
            ),
        )
        assertEquals(
            true,
            shouldRemovePostInstallInstallerTask(
                hadMainTask = false,
                completionNotificationPosted = true,
            ),
        )
        assertEquals(
            false,
            shouldRemovePostInstallInstallerTask(
                hadMainTask = false,
                completionNotificationPosted = false,
            ),
        )
    }
}
