package dev.readflow.core.calibre

data class CalibreProbeAttempt(
    val baseUrl: String,
    val message: String,
)

sealed interface CalibreProbeResult {
    data class Success(
        val baseUrl: String,
        val bookCount: Int,
    ) : CalibreProbeResult

    data class Failure(
        val message: String,
        val nextStep: String,
        val attempts: List<CalibreProbeAttempt>,
    ) : CalibreProbeResult
}

fun interface CalibreEndpointProbe {
    suspend fun probe(hint: String): CalibreProbeResult
}

class GuidedCalibreEndpointProbe(
    private val connectionTester: CalibreConnectionTester,
) : CalibreEndpointProbe {
    override suspend fun probe(hint: String): CalibreProbeResult {
        val candidates = buildCandidates(hint)
        if (candidates.isEmpty()) {
            return CalibreProbeResult.Failure(
                message = "请先填写 Calibre 服务器 IP 或地址",
                nextStep = "示例：192.168.1.5 或 http://192.168.1.5:8080",
                attempts = emptyList(),
            )
        }

        val normalizedCandidates = mutableListOf<String>()
        for (candidate in candidates) {
            val validation = validateCalibreBaseUrl(candidate)
            if (!validation.isValid) {
                return CalibreProbeResult.Failure(
                    message = validation.errorMessage.orEmpty(),
                    nextStep = "请填写 Calibre 所在电脑的局域网 IP，例如 192.168.1.5",
                    attempts = emptyList(),
                )
            }
            normalizedCandidates += validation.normalizedUrl
        }

        val attempts = mutableListOf<CalibreProbeAttempt>()
        for (baseUrl in normalizedCandidates.distinct()) {
            when (val result = connectionTester.check(baseUrl)) {
                is CalibreConnectionCheckResult.Success -> {
                    return CalibreProbeResult.Success(
                        baseUrl = baseUrl,
                        bookCount = result.bookCount,
                    )
                }
                is CalibreConnectionCheckResult.Failure -> {
                    attempts += CalibreProbeAttempt(baseUrl, result.message)
                }
            }
        }

        return CalibreProbeResult.Failure(
            message = "没有在常用 Calibre 地址发现服务",
            nextStep = "确认电脑端 Calibre Content Server 已启动，并检查 IP 是否正确；如果改过端口，请直接填写完整地址",
            attempts = attempts,
        )
    }

    private fun buildCandidates(hint: String): List<String> {
        val trimmed = hint.trim().trimEnd('/')
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.contains("://")) return listOf(trimmed)
        if (trimmed.hasPort()) return listOf("http://$trimmed")
        return COMMON_PORTS.map { port -> "http://$trimmed:$port" }
    }

    private fun String.hasPort(): Boolean {
        val lastColon = lastIndexOf(':')
        return lastColon > 0 &&
            substring(lastColon + 1).toIntOrNull() in 1..65535 &&
            count { it == ':' } == 1
    }

    private companion object {
        val COMMON_PORTS = listOf(8080, 8081)
    }
}
