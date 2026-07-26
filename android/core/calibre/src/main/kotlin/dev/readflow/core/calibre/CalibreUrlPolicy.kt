package dev.readflow.core.calibre

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

const val CALIBRE_COVER_SOURCE_QUERY_PARAMETER = "__readflow_calibre_source"

data class CalibreUrlValidation(
    val normalizedUrl: String,
    val errorMessage: String?,
) {
    val isValid: Boolean get() = errorMessage == null
}

fun validateCalibreBaseUrl(rawUrl: String): CalibreUrlValidation {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return CalibreUrlValidation(normalizedUrl = "", errorMessage = null)
    if (!trimmed.contains("://")) {
        return invalid("地址缺少协议，请以 http:// 或 https:// 开头")
    }

    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: return invalid("地址格式不正确，请输入 http://私网IP:端口 或 https://地址")
    val scheme = uri.scheme?.lowercase()
        ?: return invalid("地址缺少协议，请以 http:// 或 https:// 开头")
    if (scheme != "http" && scheme != "https") {
        return invalid("Calibre 地址只支持 http:// 或 https://")
    }
    val host = uri.host?.withoutIpv6Brackets()
        ?: return invalid("地址缺少主机名或 IP")
    if (uri.userInfo != null) {
        return invalid("请不要把用户名密码写在地址里")
    }
    if (uri.rawQuery != null || uri.rawFragment != null) {
        return invalid("Calibre 服务器地址不应包含查询参数或片段")
    }
    if (scheme == "http" && !host.isAllowedCalibreHttpHost()) {
        return invalid(CALIBRE_HTTP_HOST_ERROR)
    }

    val normalized = normalizeBaseUrl(trimmed)
    return CalibreUrlValidation(
        normalizedUrl = normalized,
        errorMessage = null,
    )
}

fun requireValidCalibreBaseUrl(rawUrl: String): String {
    val validation = validateCalibreBaseUrl(rawUrl)
    require(validation.isValid) { validation.errorMessage.orEmpty() }
    return validation.normalizedUrl
}

/** Kept for callers migrating from older releases; validation no longer changes transport. */
internal fun canonicalizeTailscaleServeCalibreUrl(rawUrl: String): String {
    return requireValidCalibreBaseUrl(rawUrl)
}

/**
 * Returns the direct Content Server candidate for a bare HTTPS MagicDNS endpoint.
 *
 * The configured URL remains authoritative. Callers must try it first and may only persist this
 * candidate after a successful Calibre probe.
 */
internal fun directTailscaleContentServerFallback(normalized: String): String? {
    val uri = URI(normalized)
    val host = uri.host?.withoutIpv6Brackets() ?: return null
    val isDefaultHttpsPort = uri.port == -1 || uri.port == 443
    val isDirectCalibrePath = uri.rawPath.orEmpty().trimEnd('/').let {
        it.isEmpty() || it.equals(CALIBRE_OPDS_TERMINAL_PATH, ignoreCase = true)
    }
    if (
        uri.scheme.equals("https", ignoreCase = true) &&
        isDefaultHttpsPort &&
        isDirectCalibrePath &&
        host.isTailscaleMagicDnsHostname()
    ) {
        val path = uri.rawPath.orEmpty()
        return "http://${host.lowercase()}:8080$path".trimEnd('/')
    }
    return null
}

/**
 * A generated direct HTTP endpoint is safe to probe with stored credentials only when it is the
 * exact fallback for the configured HTTPS MagicDNS address and Android has positively established
 * that this app is routed through a VPN.  An unknown network state is deliberately not enough:
 * otherwise a hostile DNS or LAN responder could solicit Basic credentials over cleartext HTTP.
 */
internal fun isVpnProtectedDirectTailscaleFallback(
    configuredUrl: String,
    candidateUrl: String,
    network: CalibreNetworkSnapshot,
): Boolean {
    if (!network.hasActiveVpnForCalibre()) return false
    val configured = runCatching { requireValidCalibreBaseUrl(configuredUrl) }.getOrNull()
        ?: return false
    val candidate = runCatching { requireValidCalibreBaseUrl(candidateUrl) }.getOrNull()
        ?: return false
    return directTailscaleContentServerFallback(configured) == candidate
}

/**
 * A persisted HTTP tailnet endpoint must not be opened outside the VPN either.  Otherwise a
 * previously verified fallback would become a credential leak on a later hostile network after
 * the VPN disconnects.
 */
fun requiresActiveVpnForCalibreHttp(baseUrl: String): Boolean {
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val host = uri.host?.withoutIpv6Brackets().orEmpty()
    return host.isTailscaleMagicDnsHostname() || host.isTailscaleIpv4() || host.isTailscaleIpv6()
}

/**
 * Stored credentials may be used normally over HTTPS and private LAN HTTP. Tailnet HTTP is the
 * exception: an unknown network state is deliberately treated as unsafe so a stale route or
 * hostile DNS responder cannot solicit credentials after Tailscale disconnects.
 */
fun canUseStoredCalibreCredentials(
    requestUrl: String,
    network: CalibreNetworkSnapshot,
): Boolean {
    val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (uri.host.isNullOrBlank() || uri.userInfo != null || (scheme != "http" && scheme != "https")) {
        return false
    }
    return !requiresActiveVpnForCalibreHttp(requestUrl) || network.hasActiveVpnForCalibre()
}

internal fun CalibreNetworkSnapshot.hasActiveVpnForCalibre(): Boolean =
    this is CalibreNetworkSnapshot.Active && vpnAppliesToApp

/**
 * A credential may follow an endpoint change only for the tightly constrained, VPN-protected
 * MagicDNS HTTPS -> direct HTTP:8080 transition.  Equal scopes include harmless spelling changes
 * such as equivalent IPv6 literals and need no credential write at all.
 */
internal fun calibreCredentialTransition(
    currentUrl: String,
    verifiedUrl: String,
    network: CalibreNetworkSnapshot,
): CalibreCredentialTransition {
    val currentScope = calibreCredentialScopeForRequestUrl(currentUrl)
    val verifiedScope = calibreCredentialScopeForRequestUrl(verifiedUrl)
    if (currentScope == verifiedScope) return CalibreCredentialTransition.UNCHANGED
    return if (isVpnProtectedDirectTailscaleFallback(currentUrl, verifiedUrl, network)) {
        CalibreCredentialTransition.MIGRATE_TRUSTED_FALLBACK
    } else {
        CalibreCredentialTransition.CLEAR
    }
}

internal enum class CalibreCredentialTransition {
    UNCHANGED,
    MIGRATE_TRUSTED_FALLBACK,
    CLEAR,
}

/**
 * Calibre exposes its OPDS catalog at a terminal `/opds` path while its AJAX and content
 * endpoints are siblings of that path. Moon+ Reader's Calibre setup therefore commonly stores
 * a URL ending in `/opds`; retain any reverse-proxy prefix but use its parent for this adapter.
 */
internal fun requireCalibreAjaxBaseUrl(rawUrl: String): String {
    val normalized = requireValidCalibreBaseUrl(rawUrl)
    return if (normalized.endsWith(CALIBRE_OPDS_TERMINAL_PATH, ignoreCase = true)) {
        normalized.dropLast(CALIBRE_OPDS_TERMINAL_PATH.length)
    } else {
        normalized
    }
}

fun requireAllowedCalibreRequestUrl(url: String) {
    val uri = runCatching { URI(url) }.getOrNull()
    requireNotNull(uri) { "Calibre 请求地址格式不正确" }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Calibre 请求只支持 HTTP 或 HTTPS" }
    val host = uri.host?.withoutIpv6Brackets()
    require(!host.isNullOrBlank()) { "Calibre 请求地址缺少主机" }
    require(uri.userInfo == null) { "Calibre 请求地址不得包含凭据" }
    require(scheme != "http" || host.isAllowedCalibreHttpHost()) {
        CALIBRE_HTTP_HOST_ERROR
    }
}

fun requireSameCalibreOrigin(url: String, baseUrl: String) {
    val request = URI(url)
    val base = URI(requireValidCalibreBaseUrl(baseUrl))
    require(
        request.scheme.equals(base.scheme, ignoreCase = true) &&
            request.host?.canonicalCalibreHost() == base.host?.canonicalCalibreHost() &&
            request.effectivePort() == base.effectivePort()
    ) { "Calibre 重定向不得离开已配置的服务器" }
}

fun authenticatedCalibreCoverUrl(coverUrl: String, sourceId: String): String {
    require(sourceId.isNotBlank() && sourceId.all { it.isLetterOrDigit() || it in "-_." }) {
        "Invalid Calibre source id"
    }
    val uri = URI(coverUrl)
    require(uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) {
        "Calibre cover URL must not contain query, fragment, or credentials"
    }
    requireAllowedCalibreRequestUrl(coverUrl)
    return "$coverUrl?$CALIBRE_COVER_SOURCE_QUERY_PARAMETER=$sourceId"
}

fun calibreCredentialScopeForRequestUrl(requestUrl: String): String {
    val canonicalUrl = requireValidCalibreBaseUrl(requestUrl)
    return credentialScopeForUri(URI(canonicalUrl))
}

/**
 * Returns the origin key used by releases before bare HTTPS MagicDNS URLs were normalized.
 *
 * This exists only to recover a credential already stored under that legacy origin. Do not use
 * it for new requests: new Calibre sources must use [calibreCredentialScopeForRequestUrl] so
 * their credential follows the normalized direct endpoint.
 */
internal fun legacyCalibreCredentialScopeForStoredBaseUrl(storedBaseUrl: String): String {
    val trimmed = storedBaseUrl.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: error("Invalid legacy Calibre URL")
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.canonicalCalibreHost().orEmpty()
    require(scheme == "http" || scheme == "https") { "Invalid legacy Calibre URL scheme" }
    require(host.isNotBlank()) { "Invalid legacy Calibre URL host" }
    require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Invalid legacy Calibre URL"
    }
    require(scheme != "http" || host.isAllowedCalibreHttpHost()) {
        CALIBRE_HTTP_HOST_ERROR
    }
    return credentialScopeForUri(uri)
}

private fun credentialScopeForUri(uri: URI): String {
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.canonicalCalibreHost().orEmpty()
    require(scheme.isNotBlank() && host.isNotBlank()) { "Invalid Calibre request URL" }
    val canonicalHost = if (':' in host) "[$host]" else host
    val port = when {
        uri.port < 0 -> ""
        scheme == "http" && uri.port == 80 -> ""
        scheme == "https" && uri.port == 443 -> ""
        else -> ":${uri.port}"
    }
    return "$scheme://$canonicalHost$port"
}

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("http", ignoreCase = true) -> 80
    scheme.equals("https", ignoreCase = true) -> 443
    else -> -1
}

private fun invalid(message: String): CalibreUrlValidation =
    CalibreUrlValidation(normalizedUrl = "", errorMessage = message)

private fun normalizeBaseUrl(url: String): String =
    url.trim().trimEnd('/')

private const val CALIBRE_OPDS_TERMINAL_PATH = "/opds"

private const val CALIBRE_HTTP_HOST_ERROR =
    "HTTP 仅允许本机、局域网或 Tailscale 地址；其他地址请使用 HTTPS"

private fun String.isAllowedCalibreHttpHost(): Boolean =
    isLocalhost() ||
        isRfc1918Ipv4() ||
        isTailscaleIpv4() ||
        isTailscaleIpv6() ||
        isTailscaleMagicDnsHostname()

private fun String.isLocalhost(): Boolean =
    equals("localhost", ignoreCase = true) ||
        equals("ip6-localhost", ignoreCase = true) ||
        this == "127.0.0.1" ||
        this == "::1"

internal fun String.withoutIpv6Brackets(): String = removePrefix("[").removeSuffix("]")

private fun String.canonicalCalibreHost(): String {
    val host = withoutIpv6Brackets().lowercase()
    if (':' !in host) return host
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address
        ?: return host
    val words = IntArray(8) { index ->
        val offset = index * 2
        ((address.address[offset].toInt() and 0xff) shl 8) or
            (address.address[offset + 1].toInt() and 0xff)
    }
    var bestStart = -1
    var bestLength = 0
    var index = 0
    while (index < words.size) {
        if (words[index] != 0) {
            index += 1
            continue
        }
        val start = index
        while (index < words.size && words[index] == 0) index += 1
        val length = index - start
        if (length > bestLength && length >= 2) {
            bestStart = start
            bestLength = length
        }
    }
    return buildString {
        index = 0
        while (index < words.size) {
            if (index == bestStart) {
                append("::")
                index += bestLength
            } else {
                if (isNotEmpty() && last() != ':') append(':')
                append(words[index].toString(16))
                index += 1
            }
        }
    }
}

private fun String.isRfc1918Ipv4(): Boolean {
    val values = ipv4Octets() ?: return false
    val first = values[0]
    val second = values[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168)
}

internal fun String.isTailscaleIpv4(): Boolean {
    val values = ipv4Octets() ?: return false
    return values[0] == 100 && values[1] in 64..127
}

/** MagicDNS FQDNs use the tailnet-scoped `<machine>.<tailnet>.ts.net` form. */
internal fun String.isTailscaleMagicDnsHostname(): Boolean {
    val hostname = lowercase().removeSuffix(".")
    if (!hostname.endsWith(TAILSCALE_MAGIC_DNS_SUFFIX)) return false
    val labels = hostname.removeSuffix(TAILSCALE_MAGIC_DNS_SUFFIX).split('.')
    return labels.size == 2 && labels.all(String::isNotBlank)
}

private fun String.ipv4Octets(): List<Int>? {
    val octets = split('.')
    if (octets.size != 4) return null
    return octets.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
}

internal fun String.isTailscaleIpv6(): Boolean {
    if (':' !in this) return false
    val address = runCatching { InetAddress.getByName(this) }.getOrNull() as? Inet6Address
        ?: return false
    val bytes = address.address
    val prefix = intArrayOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)
    return prefix.indices.all { index -> bytes[index].toInt() and 0xff == prefix[index] }
}

private const val TAILSCALE_MAGIC_DNS_SUFFIX = ".ts.net"
