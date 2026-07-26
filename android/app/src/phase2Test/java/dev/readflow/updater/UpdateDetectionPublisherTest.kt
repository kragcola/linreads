package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateDetectionPublisherTest {
    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }

    private val update = UpdateInfo(
        tagName = "dev-latest",
        buildTag = "dev-285-$SHA",
        apkUrl = "https://example.test/linreads.apk",
        notes = "Update notes",
        versionCode = 285L,
    )

    @Test
    fun `detected update starts its download without notification permission`() {
        val downloads = mutableListOf<UpdateInfo>()
        val notifications = mutableListOf<UpdateInfo>()

        publishDetectedUpdate(
            info = update,
            currentVersionCode = 284L,
            notificationsAllowed = false,
            startDownload = { downloads += it },
            postNotification = { notifications += it },
        )

        assertEquals(listOf(update), downloads)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `detected update also reports its automatic download when notifications are allowed`() {
        val downloads = mutableListOf<UpdateInfo>()
        val notifications = mutableListOf<UpdateInfo>()

        publishDetectedUpdate(
            info = update,
            currentVersionCode = 284L,
            notificationsAllowed = true,
            startDownload = { downloads += it },
            postNotification = { notifications += it },
        )

        assertEquals(listOf(update), downloads)
        assertEquals(listOf(update), notifications)
    }

    @Test
    fun `release without a monotonic version remains manual`() {
        val legacyUpdate = update.copy(versionCode = null)
        val downloads = mutableListOf<UpdateInfo>()
        val notifications = mutableListOf<UpdateInfo>()

        publishDetectedUpdate(
            info = legacyUpdate,
            currentVersionCode = 284L,
            notificationsAllowed = true,
            startDownload = { downloads += it },
            postNotification = { notifications += it },
        )

        assertTrue(downloads.isEmpty())
        assertEquals(listOf(legacyUpdate), notifications)
    }

    @Test
    fun `release without a monotonic version does nothing when notifications are unavailable`() {
        val legacyUpdate = update.copy(versionCode = null)
        val downloads = mutableListOf<UpdateInfo>()
        val notifications = mutableListOf<UpdateInfo>()

        publishDetectedUpdate(
            info = legacyUpdate,
            currentVersionCode = 284L,
            notificationsAllowed = false,
            startDownload = { downloads += it },
            postNotification = { notifications += it },
        )

        assertTrue(downloads.isEmpty())
        assertTrue(notifications.isEmpty())
    }
}
