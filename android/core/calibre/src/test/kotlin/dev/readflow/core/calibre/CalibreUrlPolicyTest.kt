package dev.readflow.core.calibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreUrlPolicyTest {

    @Test
    fun acceptsRfc1918HttpHostsAcrossAllPrivateRanges() {
        listOf(
            "http://10.0.2.2:8080",
            "http://172.16.0.10:8080",
            "http://172.31.255.254:8080",
            "http://192.168.1.50:8080",
        ).forEach { url ->
            assertValid(url)
        }
    }

    @Test
    fun acceptsLocalhostHttpAndPublicHttps() {
        assertValid("http://localhost:8080")
        assertValid("http://127.0.0.1:8080")
        assertValid("https://example.com/calibre")
    }

    @Test
    fun acceptsTailscaleIpv4HttpAcrossConfigurationRequestRedirectAndCoverPolicies() {
        listOf(
            "http://100.64.0.0:8080",
            "http://100.67.26.101:8080",
            "http://100.127.255.255:8080",
        ).forEach { url ->
            assertValid(url)
            requireAllowedCalibreRequestUrl("$url/ajax/search")
        }

        requireSameCalibreOrigin(
            "http://100.67.26.101:8080/get/EPUB/1/library",
            "http://100.67.26.101:8080",
        )
        assertEquals(
            "http://100.67.26.101:8080/get/cover/1/library?$CALIBRE_COVER_SOURCE_QUERY_PARAMETER=source-1",
            authenticatedCalibreCoverUrl(
                "http://100.67.26.101:8080/get/cover/1/library",
                "source-1",
            ),
        )
    }

    @Test
    fun acceptsTailscaleIpv6HttpAcrossConfigurationRequestRedirectAndCoverPolicies() {
        val baseUrl = "http://[fd7a:115c:a1e0::1234]:8080"
        assertValid(baseUrl)
        requireAllowedCalibreRequestUrl("$baseUrl/ajax/search")
        requireSameCalibreOrigin("$baseUrl/get/EPUB/1/library", baseUrl)
        assertEquals(
            "$baseUrl/get/cover/1/library?$CALIBRE_COVER_SOURCE_QUERY_PARAMETER=source-1",
            authenticatedCalibreCoverUrl("$baseUrl/get/cover/1/library", "source-1"),
        )
    }

    @Test
    fun equivalentTailscaleIpv6SpellingsShareOriginAndCredentialScope() {
        val expandedBaseUrl = "http://[fd7a:115c:a1e0:0:0:0:0:1234]:8080"
        val compressedRequestUrl = "http://[fd7a:115c:a1e0::1234]:8080/get/EPUB/1/library"

        requireSameCalibreOrigin(compressedRequestUrl, expandedBaseUrl)
        assertEquals(
            calibreCredentialScopeForRequestUrl(expandedBaseUrl),
            calibreCredentialScopeForRequestUrl(compressedRequestUrl),
        )
    }

    @Test
    fun rejectsPublicOrNonPrivateHttpHosts() {
        listOf(
            "http://8.8.8.8:8080",
            "http://172.15.0.1:8080",
            "http://172.32.0.1:8080",
            "http://100.63.255.255:8080",
            "http://100.128.0.0:8080",
            "http://[fd7a:115c:a1df:ffff::1]:8080",
            "http://[fd7a:115c:a1e1::1]:8080",
            "http://example.com:8080",
        ).forEach { url ->
            val validation = validateCalibreBaseUrl(url)
            assertEquals(false, validation.isValid)
            assertEquals(
                "HTTP 仅允许本机、局域网或 Tailscale 地址；其他地址请使用 HTTPS",
                validation.errorMessage,
            )
        }
    }

    @Test
    fun requestPolicyRejectsPublicHttpRedirectTargets() {
        val error = runCatching {
            requireAllowedCalibreRequestUrl("http://example.com/download.epub")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun redirectPolicyRequiresSameSchemeHostAndPort() {
        requireSameCalibreOrigin(
            "http://192.168.1.5:8080/get/EPUB/1/library",
            "http://192.168.1.5:8080",
        )
        listOf(
            "https://192.168.1.5:8080/get/EPUB/1/library",
            "http://192.168.1.6:8080/get/EPUB/1/library",
            "http://192.168.1.5:8081/get/EPUB/1/library",
        ).forEach { target ->
            assertTrue(runCatching {
                requireSameCalibreOrigin(target, "http://192.168.1.5:8080")
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun rejectsMalformedUnsupportedOrCredentialUrls() {
        assertEquals("地址缺少协议，请以 http:// 或 https:// 开头", validateCalibreBaseUrl("192.168.1.5:8080").errorMessage)
        assertEquals("Calibre 地址只支持 http:// 或 https://", validateCalibreBaseUrl("ftp://192.168.1.5").errorMessage)
        assertEquals("请不要把用户名密码写在地址里", validateCalibreBaseUrl("http://user:pass@192.168.1.5").errorMessage)
        assertEquals("Calibre 服务器地址不应包含查询参数或片段", validateCalibreBaseUrl("http://192.168.1.5?x=1").errorMessage)
    }

    @Test
    fun normalizesTrailingSlashAndAllowsBlankAsClearedSetting() {
        assertEquals("http://192.168.1.5:8080", validateCalibreBaseUrl(" http://192.168.1.5:8080/ ").normalizedUrl)
        assertEquals("", validateCalibreBaseUrl(" ").normalizedUrl)
        assertTrue(validateCalibreBaseUrl(" ").isValid)
    }

    private fun assertValid(url: String) {
        val validation = validateCalibreBaseUrl(url)
        assertTrue("Expected valid URL: $url, got ${validation.errorMessage}", validation.isValid)
        assertEquals(url, validation.normalizedUrl)
    }
}
