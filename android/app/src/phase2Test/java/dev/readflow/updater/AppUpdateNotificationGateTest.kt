package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateNotificationGateTest {
    @Test
    fun `notification requires runtime permission on Android 13 and later`() {
        assertFalse(shouldPostUpdateNotification(sdkInt = 33, permissionGranted = false, notificationsEnabled = true))
        assertTrue(shouldPostUpdateNotification(sdkInt = 33, permissionGranted = true, notificationsEnabled = true))
    }

    @Test
    fun `disabled notification channel skips foreground notification on every sdk`() {
        assertFalse(shouldPostUpdateNotification(sdkInt = 32, permissionGranted = true, notificationsEnabled = false))
        assertFalse(shouldPostUpdateNotification(sdkInt = 35, permissionGranted = true, notificationsEnabled = false))
    }
}
