package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowError
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface CalibreConnectionCheckResult {
    data class Success(val bookCount: Int?) : CalibreConnectionCheckResult
    data class Failure(
        val message: String,
        val nextStep: String,
        val kind: Kind = Kind.OTHER,
    ) : CalibreConnectionCheckResult {
        enum class Kind {
            AUTHENTICATION_REQUIRED,
            DNS_FAILURE,
            TAILNET_UNREACHABLE,
            CONNECT_TIMEOUT,
            RESPONSE_TIMEOUT,
            TLS_FAILURE,
            CONNECTION_REFUSED,
            SERVER_RESPONSE,
            OTHER,
        }
    }
}

fun interface CalibreConnectionTester {
    suspend fun check(baseUrl: String): CalibreConnectionCheckResult

    suspend fun check(
        baseUrl: String,
        credentials: SourceCredentials?,
    ): CalibreConnectionCheckResult = check(baseUrl)
}

fun createCalibreConnectionTester(
    networkSnapshotProvider: CalibreNetworkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
): CalibreConnectionTester = KtorCalibreConnectionTester(networkSnapshotProvider)

internal class KtorCalibreConnectionTester internal constructor(
    private val httpClientFactory: (String, String, String) -> HttpClient,
    private val networkSnapshotProvider: CalibreNetworkSnapshotProvider,
) : CalibreConnectionTester {

    constructor() : this(UnknownCalibreNetworkSnapshotProvider)

    constructor(networkSnapshotProvider: CalibreNetworkSnapshotProvider) : this(
        httpClientFactory = { baseUrl, username, password ->
            defaultCalibreHttpClient(
                allowedBaseUrl = baseUrl,
                username = username,
                password = password,
                networkSnapshotProvider = networkSnapshotProvider,
            )
        },
        networkSnapshotProvider = networkSnapshotProvider,
    )

    constructor(httpClientFactory: (String) -> HttpClient) : this(
        httpClientFactory = { baseUrl, _, _ -> httpClientFactory(baseUrl) },
        networkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
    )

    override suspend fun check(baseUrl: String): CalibreConnectionCheckResult = check(baseUrl, null)

    override suspend fun check(
        baseUrl: String,
        credentials: SourceCredentials?,
    ): CalibreConnectionCheckResult {
        val validation = validateCalibreBaseUrl(baseUrl)
        if (!validation.isValid || validation.normalizedUrl.isBlank()) {
            return CalibreConnectionCheckResult.Failure(
                message = validation.errorMessage ?: "请先填写 Calibre 服务器地址",
                nextStep = "同一 Wi-Fi 可填电脑局域网地址；远程连接可填 Tailscale 100.x 地址",
            )
        }
        val effectiveBaseUrl = validation.normalizedUrl
        val opdsUrl = requireCalibreOpdsUrl(effectiveBaseUrl)

        return runCatching {
            httpClientFactory(
                effectiveBaseUrl,
                credentials?.username.orEmpty(),
                credentials?.password.orEmpty(),
            ).use { http ->
                val body = withCalibreRequestContext(
                    phase = CalibreRequestPhase.OPDS_ROOT,
                    requestUrl = opdsUrl,
                ) {
                    http.get(opdsUrl).body<String>()
                }
                parseCalibreOpdsFeed(
                    body = body,
                    descriptor = SourceDescriptor(
                        id = "calibre-connection-probe",
                        adapterId = SourceAdapterIds.CALIBRE,
                        name = "Calibre",
                        configVersion = 1,
                        configJson = calibreSourceConfigJson(effectiveBaseUrl),
                        baseUrl = effectiveBaseUrl,
                    ),
                    feedUrl = opdsUrl,
                )
                CalibreConnectionCheckResult.Success(bookCount = null)
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            error.toConnectionFailure(
                endpointKind = calibreEndpointKind(effectiveBaseUrl),
                network = networkSnapshotProvider.snapshot(),
            )
        }
    }
}

internal fun defaultCalibreHttpClient(
    engine: HttpClientEngine? = null,
    allowedBaseUrl: String? = null,
    username: String = "",
    password: String = "",
    networkSnapshotProvider: CalibreNetworkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
): HttpClient {
    val config: HttpClientConfigBlock = {
        expectSuccess = true
        // ignoreUnknownKeys: Calibre returns 10+ extra fields (sort_order, offset, num,
        // base_url, cover, last_modified, …) beyond what our data classes declare.
        // Default Json rejects any unknown key with SerializationException.
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        if (username.isNotBlank()) {
            install(Auth) {
                digest {
                    credentials { DigestAuthCredentials(username, password) }
                }
                basic {
                    credentials { BasicAuthCredentials(username, password) }
                    sendWithoutRequest { false }
                }
            }
        }
    }
    val client = if (engine == null) HttpClient(OkHttp, config) else HttpClient(engine, config)
    client.plugin(HttpSend).intercept { request ->
        requireAllowedCalibreRequestUrl(request.url.buildString())
        if (allowedBaseUrl != null) {
            requireSameCalibreOrigin(request.url.buildString(), allowedBaseUrl)
        }
        if (
            username.isNotBlank() &&
            requiresActiveVpnForCalibreHttp(request.url.buildString()) &&
            !canUseStoredCalibreCredentials(
                requestUrl = request.url.buildString(),
                network = networkSnapshotProvider.snapshot(),
            )
        ) {
            throw CalibreVpnRequiredException()
        }
        execute(request)
    }
    return client
}

private typealias HttpClientConfigBlock = io.ktor.client.HttpClientConfig<*>.() -> Unit

internal fun Throwable.toConnectionFailure(
    endpointKind: CalibreEndpointKind = CalibreEndpointKind.OTHER,
    network: CalibreNetworkSnapshot = CalibreNetworkSnapshot.Unknown,
): CalibreConnectionCheckResult.Failure {
    val diagnostic = calibreRequestDiagnostic()
    val clientFailure = findCalibreCause<ClientRequestException>()
    val serverFailure = findCalibreCause<ServerResponseException>()
    val responseFailure = findCalibreCause<ResponseException>()
    return when {
    clientFailure != null -> when (clientFailure.response.status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> CalibreConnectionCheckResult.Failure(
            message = "Calibre 服务器需要认证${diagnostic.suffix()}",
            nextStep = "请在书源设置中填写 Calibre 用户名和密码",
            kind = CalibreConnectionCheckResult.Failure.Kind.AUTHENTICATION_REQUIRED,
        )
        HttpStatusCode.NotFound -> CalibreConnectionCheckResult.Failure(
            message = "没有找到 Calibre OPDS 目录${diagnostic.suffix()}",
            nextStep = "确认地址指向 Calibre Content Server 根地址或其 /opds 地址",
        )
        else -> CalibreConnectionCheckResult.Failure(
            message = "服务器拒绝了连接测试（HTTP ${clientFailure.response.status.value}）${diagnostic.suffix()}",
            nextStep = "检查 Calibre Content Server 是否允许当前设备访问",
        )
    }
    serverFailure != null -> {
        CalibreConnectionCheckResult.Failure(
            message = "Calibre 请求收到 HTTP ${serverFailure.response.status.value}${diagnostic.suffix()}",
            nextStep = "该响应可能来自当前端点或中间代理；按 phase 检查 OPDS、Content Server 和网络路径",
            kind = CalibreConnectionCheckResult.Failure.Kind.SERVER_RESPONSE,
        )
    }
    findCalibreCause<JsonConvertException>() != null ||
        findCalibreCause<SerializationException>() != null ||
        findCalibreCause<IllegalStateException>() != null ||
        findCalibreCause<IllegalArgumentException>() != null -> CalibreConnectionCheckResult.Failure(
        message = "服务器响应不是可识别的 Calibre OPDS 目录${diagnostic.suffix()}",
        nextStep = "确认地址直接指向 Calibre Content Server，例如 http://192.168.1.5:8080",
    )
    responseFailure != null -> CalibreConnectionCheckResult.Failure(
        message = "Calibre 连接测试失败（HTTP ${responseFailure.response.status.value}）${diagnostic.suffix()}",
        nextStep = "检查服务器状态后再重试",
    )
    else -> classifyCalibreConnectionFailure(this, endpointKind, network).let { failure ->
        failure.copy(message = failure.message + diagnostic.suffix())
    }
    }
}

internal fun Throwable.toCalibreReadflowError(
    baseUrl: String? = null,
    network: CalibreNetworkSnapshot = CalibreNetworkSnapshot.Unknown,
): ReadflowError {
    val diagnostic = calibreRequestDiagnostic(baseUrl)
    val clientFailure = findCalibreCause<ClientRequestException>()
    val serverFailure = findCalibreCause<ServerResponseException>()
    val responseFailure = findCalibreCause<ResponseException>()
    return when {
    clientFailure != null -> when (clientFailure.response.status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> ReadflowError(
            kind = ReadflowError.Kind.AUTH,
            message = "Calibre 认证失败，请在当前书源设置中填写或检查用户名和密码${diagnostic.suffix()}",
            code = clientFailure.response.status.value,
        )
        HttpStatusCode.NotFound -> ReadflowError.network(
            clientFailure.response.status.value,
            "没有找到 Calibre OPDS 目录${diagnostic.suffix()}",
        )
        else -> ReadflowError.network(
            clientFailure.response.status.value,
            "Calibre 服务器拒绝了请求（HTTP ${clientFailure.response.status.value}）${diagnostic.suffix()}",
        )
    }
    serverFailure != null -> {
        ReadflowError.network(
            serverFailure.response.status.value,
            "Calibre 请求收到 HTTP ${serverFailure.response.status.value}${diagnostic.suffix()}。" +
                "该响应可能来自当前端点或中间代理",
        )
    }
    findCalibreCause<JsonConvertException>() != null ||
        findCalibreCause<SerializationException>() != null ||
        findCalibreCause<IllegalStateException>() != null ||
        findCalibreCause<IllegalArgumentException>() != null ->
        ReadflowError.parse("Calibre 返回了无法识别的数据${diagnostic.suffix()}")
    responseFailure != null -> ReadflowError.network(
        responseFailure.response.status.value,
        "Calibre 请求失败（HTTP ${responseFailure.response.status.value}）${diagnostic.suffix()}",
    )
    else -> classifyCalibreConnectionFailure(
        error = this,
        endpointKind = baseUrl?.let(::calibreEndpointKind) ?: CalibreEndpointKind.OTHER,
        network = network,
    ).let { failure ->
        ReadflowError.network(null, "${failure.message}${diagnostic.suffix()}。${failure.nextStep}")
    }
    }
}
