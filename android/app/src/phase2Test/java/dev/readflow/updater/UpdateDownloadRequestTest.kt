package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateDownloadRequestTest {

    @Test
    fun `automatic download request preserves the configured OTA token`() {
        val info = UpdateInfo(
            tagName = "dev-latest",
            buildTag = "dev-285-example",
            apkUrl = "https://example.test/linreads.apk",
            notes = "",
            versionCode = 285L,
        )

        val request = automaticUpdateDownloadRequest(info, authToken = "token-for-private-release")

        assertEquals(info.apkUrl, request.apkUrl)
        assertEquals(info.buildTag, request.buildTag)
        assertEquals(info.versionCode, request.versionCode)
        assertEquals("token-for-private-release", request.authToken)
        assertTrue(request.automatic)
    }
}
