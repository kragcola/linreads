package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowResult

/** Persists an endpoint only after a real Calibre API probe has verified it. */
fun interface VerifiedCalibreEndpointSink {
    suspend fun persistVerifiedCalibreEndpoint(baseUrl: String): ReadflowResult<Unit>
}
