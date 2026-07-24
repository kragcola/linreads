package dev.readflow.core.calibre

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

sealed interface CalibreDiscoveryResult {
    data class Found(
        val baseUrl: String,
        val serviceName: String,
    ) : CalibreDiscoveryResult

    data object NotFound : CalibreDiscoveryResult

    data class Unavailable(val message: String) : CalibreDiscoveryResult
}

fun interface CalibreServiceDiscovery {
    suspend fun discover(): CalibreDiscoveryResult
}

class AndroidCalibreServiceDiscovery(
    context: Context,
    private val connectionTester: CalibreConnectionTester,
    private val timeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MS,
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java),
) : CalibreServiceDiscovery {

    override suspend fun discover(): CalibreDiscoveryResult =
        withTimeoutOrNull(timeoutMillis) {
            discoverReachableCalibre(
                candidates = discoverCandidates(),
                connectionTester = connectionTester,
            )
        } ?: CalibreDiscoveryResult.NotFound

    @Suppress("DEPRECATION")
    private fun discoverCandidates(): Flow<CalibreDiscoveryCandidate> = callbackFlow {
        val active = AtomicBoolean(true)
        val queueLock = Any()
        val pending = ArrayDeque<NsdServiceInfo>()
        val seenServices = mutableSetOf<String>()
        var resolving = false
        lateinit var discoveryListener: NsdManager.DiscoveryListener
        lateinit var resolveNext: () -> Unit

        fun stopDiscovery() {
            if (active.compareAndSet(true, false)) {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
        }

        fun resolutionFinished() {
            synchronized(queueLock) { resolving = false }
            resolveNext()
        }

        resolveNext = next@{
            val service = synchronized(queueLock) {
                if (!active.get() || resolving || pending.isEmpty()) return@next
                resolving = true
                pending.removeFirst()
            }
            runCatching {
                nsdManager.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolutionFinished()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val addresses = serviceInfo.resolvedAddresses()
                            if (addresses.isEmpty()) {
                                trySend(
                                    CalibreDiscoveryCandidate(
                                        serviceName = serviceInfo.serviceName,
                                        address = null,
                                        port = serviceInfo.port,
                                    ),
                                )
                            } else {
                                addresses.forEach { address ->
                                    trySend(
                                        CalibreDiscoveryCandidate(
                                            serviceName = serviceInfo.serviceName,
                                            address = address,
                                            port = serviceInfo.port,
                                        ),
                                    )
                                }
                            }
                            resolutionFinished()
                        }
                    },
                )
            }.onFailure { resolutionFinished() }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.equals(SERVICE_TYPE, ignoreCase = true)) return
                val queued = synchronized(queueLock) {
                    val key = serviceInfo.serviceName + "|" + serviceInfo.serviceType
                    if (!seenServices.add(key)) false else pending.add(serviceInfo)
                }
                if (queued) resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                active.set(false)
                close()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                active.set(false)
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                active.set(false)
                close()
            }
        }

        runCatching {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure { close(it) }
        awaitClose { stopDiscovery() }
    }.buffer(Channel.UNLIMITED)

    private companion object {
        const val SERVICE_TYPE = "_calibre._tcp."
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 12_000L
    }
}

internal data class CalibreDiscoveryCandidate(
    val serviceName: String,
    val address: InetAddress?,
    val port: Int,
)

internal suspend fun discoverReachableCalibre(
    candidates: Flow<CalibreDiscoveryCandidate>,
    connectionTester: CalibreConnectionTester,
    probeTimeoutMillis: Long = DEFAULT_CANDIDATE_PROBE_TIMEOUT_MS,
): CalibreDiscoveryResult {
    var lastFailure: CalibreDiscoveryResult.Unavailable? = null
    val found = candidates.mapNotNull { candidate ->
        val address = candidate.address
        if (address == null) {
            lastFailure = CalibreDiscoveryResult.Unavailable("Calibre 广播未提供可用地址")
            return@mapNotNull null
        }
        val baseUrl = discoveredCalibreBaseUrl(address, candidate.port)
        if (baseUrl == null) {
            lastFailure = CalibreDiscoveryResult.Unavailable("Calibre 广播地址不在受信任的局域网范围")
            return@mapNotNull null
        }
        val result = withTimeoutOrNull(probeTimeoutMillis) {
            connectionTester.check(baseUrl)
        }
        when (result) {
            is CalibreConnectionCheckResult.Success -> CalibreDiscoveryResult.Found(
                baseUrl = baseUrl,
                serviceName = candidate.serviceName,
            )
            is CalibreConnectionCheckResult.Failure -> {
                lastFailure = CalibreDiscoveryResult.Unavailable(result.message)
                null
            }
            null -> {
                lastFailure = CalibreDiscoveryResult.Unavailable("Calibre 连接探测超时")
                null
            }
        }
    }.firstOrNull()
    return found ?: lastFailure ?: CalibreDiscoveryResult.NotFound
}

private const val DEFAULT_CANDIDATE_PROBE_TIMEOUT_MS = 3_000L

@Suppress("DEPRECATION")
private fun NsdServiceInfo.resolvedAddresses(): List<InetAddress> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        hostAddresses
    } else {
        listOfNotNull(host)
    }

internal fun discoveredCalibreBaseUrl(address: InetAddress, port: Int): String? {
    if (address !is Inet4Address || address.isLoopbackAddress || port !in 1..65535) return null
    val validation = validateCalibreBaseUrl("http://${address.hostAddress}:$port")
    return validation.normalizedUrl.takeIf { validation.isValid }
}
