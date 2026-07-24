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
    val uri = URI(requestUrl)
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

private const val CALIBRE_HTTP_HOST_ERROR =
    "HTTP 仅允许本机、局域网或 Tailscale 地址；其他地址请使用 HTTPS"

private fun String.isAllowedCalibreHttpHost(): Boolean =
    isLocalhost() || isRfc1918Ipv4() || isTailscaleIpv4() || isTailscaleIpv6()

private fun String.isLocalhost(): Boolean =
    equals("localhost", ignoreCase = true) ||
        equals("ip6-localhost", ignoreCase = true) ||
        this == "127.0.0.1" ||
        this == "::1"

private fun String.withoutIpv6Brackets(): String = removePrefix("[").removeSuffix("]")

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

private fun String.isTailscaleIpv4(): Boolean {
    val values = ipv4Octets() ?: return false
    return values[0] == 100 && values[1] in 64..127
}

private fun String.ipv4Octets(): List<Int>? {
    val octets = split('.')
    if (octets.size != 4) return null
    return octets.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
}

private fun String.isTailscaleIpv6(): Boolean {
    if (':' !in this) return false
    val address = runCatching { InetAddress.getByName(this) }.getOrNull() as? Inet6Address
        ?: return false
    val bytes = address.address
    val prefix = intArrayOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)
    return prefix.indices.all { index -> bytes[index].toInt() and 0xff == prefix[index] }
}
