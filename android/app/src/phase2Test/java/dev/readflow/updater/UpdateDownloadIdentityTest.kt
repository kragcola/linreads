package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateDownloadIdentityTest {
    @Test
    fun `reuses download only for the same url and tag`() {
        assertTrue(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app-b.apk",
                savedTag = "build-b",
                requestedUrl = "https://example.test/app-b.apk",
                requestedTag = "build-b",
            ),
        )
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app-a.apk",
                savedTag = "build-a",
                requestedUrl = "https://example.test/app-b.apk",
                requestedTag = "build-b",
            ),
        )
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app.apk",
                savedTag = "build-a",
                requestedUrl = "https://example.test/app.apk",
                requestedTag = "build-b",
            ),
        )
    }

    @Test
    fun `missing build tag never reuses a mutable release url`() {
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app.apk",
                savedTag = null,
                requestedUrl = "https://example.test/app.apk",
                requestedTag = null,
            ),
        )
    }

    @Test
    fun `release build tag comes from workflow metadata rather than commit text`() {
        val body = """
            Commit subject
            BUILD_TAG: forged-commit-tag

            ---
            BUILD_TAG: dev-198-real-sha
            Commit: real-sha
        """.trimIndent()

        assertTrue(releaseBuildTagFromBody(body) == "dev-198-real-sha")
    }
}
