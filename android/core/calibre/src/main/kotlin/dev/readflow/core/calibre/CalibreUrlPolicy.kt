package dev.readflow.core.calibre

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
    if (scheme == "http" && !host.isLocalhost() && !host.isRfc1918Ipv4()) {
        return invalid("HTTP 只允许局域网私有地址：10.x、172.16-31.x、192.168.x，公网地址请使用 HTTPS")
    }

    return CalibreUrlValidation(normalizeBaseUrl(trimmed), errorMessage = null)
}

fun requireValidCalibreBaseUrl(rawUrl: String): String {
    val validation = validateCalibreBaseUrl(rawUrl)
    require(validation.isValid) { validation.errorMessage.orEmpty() }
    return validation.normalizedUrl
}

fun requireAllowedCalibreRequestUrl(url: String) {
    val uri = runCatching { URI(url) }.getOrNull()
    requireNotNull(uri) { "Calibre 请求地址格式不正确" }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Calibre 请求只支持 HTTP 或 HTTPS" }
    val host = uri.host?.withoutIpv6Brackets()
    require(!host.isNullOrBlank()) { "Calibre 请求地址缺少主机" }
    require(uri.userInfo == null) { "Calibre 请求地址不得包含凭据" }
    require(scheme != "http" || host.isLocalhost() || host.isRfc1918Ipv4()) {
        "HTTP 只允许局域网私有地址：10.x、172.16-31.x、192.168.x，公网地址请使用 HTTPS"
    }
}

fun requireSameCalibreOrigin(url: String, baseUrl: String) {
    val request = URI(url)
    val base = URI(requireValidCalibreBaseUrl(baseUrl))
    require(
        request.scheme.equals(base.scheme, ignoreCase = true) &&
            request.host.equals(base.host, ignoreCase = true) &&
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
    val uri = URI(requestUrl)
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.withoutIpv6Brackets()?.lowercase().orEmpty()
    require(scheme.isNotBlank() && host.isNotBlank()) { "Invalid Calibre request URL" }
    val canonicalHost = if (':' in host) "[$host]" else host
    val port = when {
        uri.port < 0 -> ""
        scheme == "http" && uri.port == 80 -> ""
        scheme == "https" && uri.port == 443 -> ""
        else -> ":${uri.port}"
    }
    return requireValidCalibreBaseUrl("$scheme://$canonicalHost$port")
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

private fun String.isLocalhost(): Boolean =
    equals("localhost", ignoreCase = true) ||
        equals("ip6-localhost", ignoreCase = true) ||
        this == "127.0.0.1" ||
    this == "::1"

private fun String.withoutIpv6Brackets(): String = removePrefix("[").removeSuffix("]")

private fun String.isRfc1918Ipv4(): Boolean {
    val octets = split('.')
    if (octets.size != 4) return false
    val values = octets.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    }
    val first = values[0]
    val second = values[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168)
}
