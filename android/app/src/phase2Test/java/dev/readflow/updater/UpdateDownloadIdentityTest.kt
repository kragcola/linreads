package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateDownloadIdentityTest {
    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }

    @Test
    fun `reuses download only for the same url and tag`() {
        assertTrue(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app-b.apk",
                savedTag = "build-b",
                savedVersionCode = 286L,
                requestedUrl = "https://example.test/app-b.apk",
                requestedTag = "build-b",
                requestedVersionCode = 286L,
            ),
        )
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app-a.apk",
                savedTag = "build-a",
                savedVersionCode = 285L,
                requestedUrl = "https://example.test/app-b.apk",
                requestedTag = "build-b",
                requestedVersionCode = 286L,
            ),
        )
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app.apk",
                savedTag = "build-a",
                savedVersionCode = 285L,
                requestedUrl = "https://example.test/app.apk",
                requestedTag = "build-b",
                requestedVersionCode = 286L,
            ),
        )
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app.apk",
                savedTag = "build-a",
                savedVersionCode = 285L,
                requestedUrl = "https://example.test/app.apk",
                requestedTag = "build-a",
                requestedVersionCode = 286L,
            ),
        )
    }

    @Test
    fun `missing build tag never reuses a mutable release url`() {
        assertFalse(
            canReuseUpdateDownload(
                savedUrl = "https://example.test/app.apk",
                savedTag = null,
                savedVersionCode = null,
                requestedUrl = "https://example.test/app.apk",
                requestedTag = null,
                requestedVersionCode = null,
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

    @Test
    fun `installed build tag clears the previous update session before foreground resume`() {
        assertTrue(isInstalledUpdateBuild("dev-285-example", "dev-285-example"))
        assertFalse(isInstalledUpdateBuild("dev-284-example", "dev-285-example"))
        assertFalse(isInstalledUpdateBuild(null, "dev-285-example"))
    }

    @Test
    fun `only a tagged strictly newer version can start zero touch update`() {
        assertTrue(isAutomaticUpdateEligible("dev-286-$SHA", 286L, currentVersionCode = 285L))
        assertFalse(isAutomaticUpdateEligible("dev-285-$SHA", 285L, currentVersionCode = 285L))
        assertFalse(isAutomaticUpdateEligible("dev-284-$SHA", 284L, currentVersionCode = 285L))
        assertFalse(isAutomaticUpdateEligible(null, 286L, currentVersionCode = 285L))
        assertFalse(isAutomaticUpdateEligible("dev-286-$SHA", null, currentVersionCode = 285L))
        assertFalse(isAutomaticUpdateEligible("dev-285-$SHA", 286L, currentVersionCode = 285L))
        assertFalse(isAutomaticUpdateEligible("arbitrary-build", 286L, currentVersionCode = 285L))
    }

    @Test
    fun `release availability requires a verified CI identity when version metadata exists`() {
        assertTrue(
            shouldOfferRelease(
                releaseBuildTag = "dev-286-$SHA",
                releaseVersionCode = 286L,
                currentTag = "dev-285-$SHA",
                currentVersionCode = 285L,
            ),
        )
        assertFalse(
            shouldOfferRelease(
                releaseBuildTag = "dev-285-$SHA",
                releaseVersionCode = 286L,
                currentTag = "dev-285-$SHA",
                currentVersionCode = 285L,
            ),
        )
        assertFalse(
            shouldOfferRelease(
                releaseBuildTag = "untrusted-build",
                releaseVersionCode = 286L,
                currentTag = "dev-285-$SHA",
                currentVersionCode = 285L,
            ),
        )
    }

    @Test
    fun `known stale downloads are never staged after the app advances`() {
        assertTrue(isPendingUpdateInstallable(versionCode = null, currentVersionCode = 285L))
        assertTrue(isPendingUpdateInstallable(versionCode = 286L, currentVersionCode = 285L))
        assertFalse(isPendingUpdateInstallable(versionCode = 285L, currentVersionCode = 285L))
        assertFalse(isPendingUpdateInstallable(versionCode = 284L, currentVersionCode = 285L))
    }

    @Test
    fun `release version code comes only from release metadata`() {
        val body = """
            Subject VERSION_CODE: 1

            ---
            VERSION_CODE: 286
            BUILD_TAG: dev-286-$SHA
        """.trimIndent()

        assertEquals(286L, releaseVersionCodeFromBody(body))
        assertNull(releaseVersionCodeFromBody("---\nVERSION_CODE: invalid"))
        assertNull(releaseVersionCodeFromBody("---\nVERSION_CODE: 0"))
    }

    @Test
    fun `verified releases use immutable versioned apk assets`() {
        assertEquals("app-ota-100302.apk", otaApkAssetName(100_302L))
        assertEquals("app-ota.apk", otaApkAssetName(null))
    }
}
