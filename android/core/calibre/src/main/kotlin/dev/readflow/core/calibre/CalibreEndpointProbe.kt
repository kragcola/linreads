package dev.readflow.core.calibre

import dev.readflow.extensions.api.SourceCredentials
import java.net.Inet6Address
import java.net.InetAddress

data class CalibreProbeAttempt(
    val baseUrl: String,
    val message: String,
)

sealed interface CalibreProbeResult {
    data class Success(
        val baseUrl: String,
        val bookCount: Int?,
    ) : CalibreProbeResult

    data class Failure(
        val message: String,
        val nextStep: String,
        val attempts: List<CalibreProbeAttempt>,
    ) : CalibreProbeResult

    /** The endpoint answered, but the caller must provide credentials before it can be verified. */
    data class AuthenticationRequired(
        val baseUrl: String,
        val attempts: List<CalibreProbeAttempt>,
    ) : CalibreProbeResult
}

fun interface CalibreEndpointProbe {
    suspend fun probe(hint: String): CalibreProbeResult

    suspend fun probe(hint: String, credentials: SourceCredentials?): CalibreProbeResult = probe(hint)
}

class GuidedCalibreEndpointProbe(
    private val connectionTester: CalibreConnectionTester,
    @Suppress("unused")
    private val networkSnapshotProvider: CalibreNetworkSnapshotProvider =
        UnknownCalibreNetworkSnapshotProvider,
) : CalibreEndpointProbe {
    override suspend fun probe(hint: String): CalibreProbeResult = probe(hint, null)

    override suspend fun probe(hint: String, credentials: SourceCredentials?): CalibreProbeResult {
        val candidates = calibreEndpointCandidates(hint)
        if (candidates.isEmpty()) {
            return CalibreProbeResult.Failure(
                message = "请先填写 Calibre 服务器 IP 或地址",
                nextStep = "同一 Wi-Fi 可填 192.168.x.x；Tailscale 可填 100.x.x.x",
                attempts = emptyList(),
            )
        }

        val normalizedCandidates = mutableListOf<String>()
        for (candidate in candidates) {
            val validation = validateCalibreBaseUrl(candidate)
            if (!validation.isValid) {
                return CalibreProbeResult.Failure(
                    message = validation.errorMessage.orEmpty(),
                    nextStep = "请填写 Calibre 所在电脑的局域网或 Tailscale 地址",
                    attempts = emptyList(),
                )
            }
            normalizedCandidates += validation.normalizedUrl
        }

        val attempts = mutableListOf<CalibreProbeAttempt>()
        for (baseUrl in normalizedCandidates.distinct()) {
            val candidateCredentials = credentials.takeIf { normalizedCandidates.size == 1 }
            when (val result = connectionTester.check(baseUrl, candidateCredentials)) {
                is CalibreConnectionCheckResult.Success -> {
                    return CalibreProbeResult.Success(
                        baseUrl = baseUrl,
                        bookCount = result.bookCount,
                    )
                }
                is CalibreConnectionCheckResult.Failure -> {
                    attempts += CalibreProbeAttempt(baseUrl, result.message)
                    if (result.kind == CalibreConnectionCheckResult.Failure.Kind.AUTHENTICATION_REQUIRED) {
                        return CalibreProbeResult.AuthenticationRequired(baseUrl, attempts)
                    }
                    if (normalizedCandidates.size == 1) {
                        return CalibreProbeResult.Failure(
                            message = result.message,
                            nextStep = result.nextStep,
                            attempts = attempts,
                        )
                    }
                }
            }
        }

        return CalibreProbeResult.Failure(
            message = "没有在常用 Calibre 地址发现服务",
            nextStep = "确认 Calibre Content Server 已启动，并检查 Wi-Fi 或 Tailscale 地址；如果改过端口，请填写完整地址",
            attempts = attempts,
        )
    }

}

internal fun calibreEndpointCandidates(hint: String): List<String> {
    val trimmed = hint.trim().trimEnd('/')
    if (trimmed.isBlank()) return emptyList()
    if (trimmed.contains("://")) {
        val validation = validateCalibreBaseUrl(trimmed)
        if (!validation.isValid || validation.normalizedUrl.isBlank()) return listOf(trimmed)
        return listOf(validation.normalizedUrl)
    }
    if (trimmed.startsWith('[')) {
        val closingBracket = trimmed.indexOf(']')
        if (closingBracket > 1) {
            val host = trimmed.substring(1, closingBracket)
            if (host.isIpv6Literal()) {
                val suffix = trimmed.substring(closingBracket + 1)
                if (suffix.isBlank()) {
                    return COMMON_PORTS.map { port -> "http://[$host]:$port" }
                }
                if (suffix.startsWith(':') && suffix.drop(1).toIntOrNull() in 1..65535) {
                    return listOf("http://$trimmed")
                }
            }
        }
    }
    if (trimmed.isIpv6Literal()) {
        return COMMON_PORTS.map { port -> "http://[$trimmed]:$port" }
    }
    if (trimmed.hasPort()) return listOf("http://$trimmed")
    return COMMON_PORTS.map { port -> "http://$trimmed:$port" }
}

private fun String.isIpv6Literal(): Boolean =
    ':' in this && runCatching { InetAddress.getByName(this) }.getOrNull() is Inet6Address

private fun String.hasPort(): Boolean {
    val lastColon = lastIndexOf(':')
    return lastColon > 0 &&
        substring(lastColon + 1).toIntOrNull() in 1..65535 &&
        count { it == ':' } == 1
}

private val COMMON_PORTS = listOf(8080, 8081)
