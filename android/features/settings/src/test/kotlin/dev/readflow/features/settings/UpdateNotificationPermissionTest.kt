package dev.readflow.features.settings

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotificationPermissionTest {
    @Test
    fun requestsPermissionOnlyOnAndroid13PlusWhenMissing() {
        assertFalse(shouldRequestUpdateNotificationPermission(Build.VERSION_CODES.S_V2, false))
        assertFalse(shouldRequestUpdateNotificationPermission(Build.VERSION_CODES.TIRAMISU, true))
        assertTrue(shouldRequestUpdateNotificationPermission(Build.VERSION_CODES.TIRAMISU, false))
    }
}
