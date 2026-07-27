package dev.readflow.core.calibre

import io.ktor.client.plugins.ResponseException
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException

internal enum class CalibreRequestPhase(val wireName: String) {
    OPDS_ROOT("opds_root"),
    OPDS_NAVIGATION("opds_navigation"),
    OPDS_SEARCH("opds_search"),
    OPDS_PAGE("opds_page"),
    OPDS_DOWNLOAD("opds_download"),
    AJAX_SEARCH("ajax_search"),
    AJAX_METADATA("ajax_metadata"),
    AJAX_BOOK("ajax_book"),
    AJAX_DOWNLOAD("ajax_download"),
}

internal class CalibreRequestContextException(
    val phase: CalibreRequestPhase,
    val origin: String?,
    cause: Throwable,
) : IOException("Calibre request failed", cause)

internal suspend fun <T> withCalibreRequestContext(
    phase: CalibreRequestPhase,
    requestUrl: String,
    request: suspend () -> T,
): T = try {
    request()
} catch (error: CancellationException) {
    throw error
} catch (error: CalibreRequestContextException) {
    throw error
} catch (error: Throwable) {
    throw CalibreRequestContextException(
        phase = phase,
        origin = calibreDiagnosticOrigin(requestUrl),
        cause = error,
    )
}

internal data class CalibreRequestDiagnostic(
    val phase: CalibreRequestPhase?,
    val origin: String?,
    val status: Int?,
) {
    fun suffix(): String {
        val fields = listOfNotNull(
            phase?.wireName?.let { "phase=$it" },
            origin?.let { "origin=$it" },
            status?.let { "status=$it" },
        )
        return fields.takeIf(List<String>::isNotEmpty)
            ?.joinToString(separator = " ", prefix = " [", postfix = "]")
            .orEmpty()
    }
}

internal fun Throwable.calibreRequestDiagnostic(fallbackUrl: String? = null): CalibreRequestDiagnostic {
    val context = findCalibreCause<CalibreRequestContextException>()
    val response = findCalibreCause<ResponseException>()
    return CalibreRequestDiagnostic(
        phase = context?.phase,
        origin = context?.origin ?: fallbackUrl?.let(::calibreDiagnosticOrigin),
        status = response?.response?.status?.value,
    )
}

private fun calibreDiagnosticOrigin(url: String): String? = runCatching {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.withoutIpv6Brackets().orEmpty().lowercase()
    if (scheme !in setOf("http", "https") || host.isBlank() || uri.userInfo != null) return@runCatching null
    val renderedHost = if (':' in host) "[$host]" else host
    val port = when {
        uri.port < 0 -> ""
        scheme == "http" && uri.port == 80 -> ""
        scheme == "https" && uri.port == 443 -> ""
        else -> ":${uri.port}"
    }
    "$scheme://$renderedHost$port"
}.getOrNull()

internal inline fun <reified T : Throwable> Throwable.findCalibreCause(): T? {
    var current: Throwable? = this
    repeat(12) {
        if (current is T) return current
        val next = current?.cause
        if (next == null || next === current) return null
        current = next
    }
    return null
}
