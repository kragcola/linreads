package dev.readflow.core.calibre

import java.net.URI

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
    val host = uri.host
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

private fun invalid(message: String): CalibreUrlValidation =
    CalibreUrlValidation(normalizedUrl = "", errorMessage = message)

private fun normalizeBaseUrl(url: String): String =
    url.trim().trimEnd('/')

private fun String.isLocalhost(): Boolean =
    equals("localhost", ignoreCase = true) ||
        equals("ip6-localhost", ignoreCase = true) ||
        this == "127.0.0.1" ||
        this == "::1"

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
