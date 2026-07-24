package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

sealed interface CalibreConnectionCheckResult {
    data class Success(val bookCount: Int) : CalibreConnectionCheckResult
    data class Failure(
        val message: String,
        val nextStep: String,
        val kind: Kind = Kind.OTHER,
    ) : CalibreConnectionCheckResult {
        enum class Kind { AUTHENTICATION_REQUIRED, OTHER }
    }
}

fun interface CalibreConnectionTester {
    suspend fun check(baseUrl: String): CalibreConnectionCheckResult
}

fun createCalibreConnectionTester(): CalibreConnectionTester = KtorCalibreConnectionTester()

internal class KtorCalibreConnectionTester(
    private val httpClientFactory: (String) -> HttpClient = { baseUrl ->
        defaultCalibreHttpClient(allowedBaseUrl = baseUrl)
    },
) : CalibreConnectionTester {

    override suspend fun check(baseUrl: String): CalibreConnectionCheckResult {
        val validation = validateCalibreBaseUrl(baseUrl)
        if (!validation.isValid || validation.normalizedUrl.isBlank()) {
            return CalibreConnectionCheckResult.Failure(
                message = validation.errorMessage ?: "请先填写 Calibre 服务器地址",
                nextStep = "同一 Wi-Fi 可填电脑局域网地址；远程连接可填 Tailscale 100.x 地址",
            )
        }

        return runCatching {
            httpClientFactory(validation.normalizedUrl).use { http ->
                val result = http.get("${validation.normalizedUrl}/ajax/search") {
                    parameter("query", "")
                    parameter("num", 1)
                    parameter("offset", 0)
                }.body<CalibreSearchResult>()
                CalibreConnectionCheckResult.Success(bookCount = result.total_num)
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            error.toConnectionFailure()
        }
    }
}

internal fun defaultCalibreHttpClient(
    engine: HttpClientEngine? = null,
    allowedBaseUrl: String? = null,
): HttpClient {
    val config: HttpClientConfigBlock = {
        expectSuccess = true
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 8_000
        }
    }
    val client = if (engine == null) HttpClient(config) else HttpClient(engine, config)
    client.plugin(HttpSend).intercept { request ->
        requireAllowedCalibreRequestUrl(request.url.buildString())
        if (allowedBaseUrl != null) {
            requireSameCalibreOrigin(request.url.buildString(), allowedBaseUrl)
        }
        execute(request)
    }
    return client
}

private typealias HttpClientConfigBlock = io.ktor.client.HttpClientConfig<*>.() -> Unit

private fun Throwable.toConnectionFailure(): CalibreConnectionCheckResult.Failure = when (this) {
    is ClientRequestException -> when (response.status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> CalibreConnectionCheckResult.Failure(
            message = "Calibre 服务器需要认证",
            nextStep = "请在书源设置中填写 Calibre 用户名和密码",
            kind = CalibreConnectionCheckResult.Failure.Kind.AUTHENTICATION_REQUIRED,
        )
        HttpStatusCode.NotFound -> CalibreConnectionCheckResult.Failure(
            message = "没有找到 Calibre API",
            nextStep = "确认地址不要带路径，直接填写 Content Server 根地址",
        )
        else -> CalibreConnectionCheckResult.Failure(
            message = "服务器拒绝了连接测试（HTTP ${response.status.value}）",
            nextStep = "检查 Calibre Content Server 是否允许当前设备访问",
        )
    }
    is ServerResponseException -> CalibreConnectionCheckResult.Failure(
        message = "Calibre 服务器暂时不可用（HTTP ${response.status.value}）",
        nextStep = "确认电脑端 Calibre Content Server 正在运行后再重试",
    )
    is ConnectTimeoutException, is HttpRequestTimeoutException -> CalibreConnectionCheckResult.Failure(
        message = "连接 Calibre 超时",
        nextStep = "确认本设备能通过同一 Wi-Fi 或 Tailscale 访问服务器，并检查地址与端口",
    )
    is ConnectException, is UnknownHostException -> CalibreConnectionCheckResult.Failure(
        message = "无法连接到服务器",
        nextStep = "确认本设备与服务器位于同一 Wi-Fi 或 Tailscale 网络，并检查端口",
    )
    is JsonConvertException, is SerializationException, is IllegalStateException -> CalibreConnectionCheckResult.Failure(
        message = "服务器响应不像 Calibre Content Server",
        nextStep = "确认地址直接指向 Calibre Content Server，例如 http://192.168.1.5:8080",
    )
    is ResponseException -> CalibreConnectionCheckResult.Failure(
        message = "Calibre 连接测试失败（HTTP ${response.status.value}）",
        nextStep = "检查服务器状态后再重试",
    )
    else -> CalibreConnectionCheckResult.Failure(
        message = message?.takeIf { it.isNotBlank() } ?: "无法连接到服务器",
        nextStep = "确认本设备与服务器位于同一 Wi-Fi 或 Tailscale 网络，并检查端口",
    )
}

internal fun Throwable.toCalibreReadflowError(): ReadflowError = when (this) {
    is ClientRequestException -> when (response.status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> ReadflowError(
            kind = ReadflowError.Kind.AUTH,
            message = "Calibre 认证失败，请在当前书源设置中填写或检查用户名和密码",
            code = response.status.value,
        )
        HttpStatusCode.NotFound -> ReadflowError.network(
            response.status.value,
            "没有找到 Calibre API，请确认服务器地址不带路径",
        )
        else -> ReadflowError.network(
            response.status.value,
            "Calibre 服务器拒绝了请求（HTTP ${response.status.value}）",
        )
    }
    is ServerResponseException -> ReadflowError.network(
        response.status.value,
        "Calibre 服务器暂时不可用（HTTP ${response.status.value}）",
    )
    is ConnectTimeoutException, is HttpRequestTimeoutException ->
        ReadflowError.network(null, "连接 Calibre 超时，请确认服务器在线且可通过同一 Wi-Fi 或 Tailscale 访问")
    is ConnectException, is UnknownHostException ->
        ReadflowError.network(null, "无法连接到 Calibre，请检查地址、端口、Wi-Fi 或 Tailscale 状态")
    is JsonConvertException, is SerializationException ->
        ReadflowError.parse("Calibre 返回了无法识别的数据")
    is ResponseException -> ReadflowError.network(
        response.status.value,
        "Calibre 请求失败（HTTP ${response.status.value}）",
    )
    else -> ReadflowError.network(null, "Calibre 操作失败，请检查服务器状态后重试")
}
