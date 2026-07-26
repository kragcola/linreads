package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateInstallPermissionTest {

    @Test
    fun `completed update requests source permission only after its APK is available`() {
        assertEquals(
            CompletedUpdateAction.REQUEST_UNKNOWN_SOURCES_PERMISSION,
            completedUpdateAction(canRequestPackageInstalls = false),
        )
        assertEquals(
            CompletedUpdateAction.STAGE_INSTALL,
            completedUpdateAction(canRequestPackageInstalls = true),
        )
    }
}
