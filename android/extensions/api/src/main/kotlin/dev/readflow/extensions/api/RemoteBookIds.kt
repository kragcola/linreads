package dev.readflow.extensions.api

import java.security.MessageDigest

/**
 * Collision-resistant shelf book id for remote/OPDS/JSON catalog downloads.
 *
 * Format: `remote-{safeSource}-{safeRemote}-{hash8}` where [hash8] is the first
 * 8 lowercase hex chars of SHA-256 over `sourceId + '\u0000' + remoteId` (full, unsanitized).
 * Truncation of the sanitized segments cannot collide without the hash matching.
 */
fun stableRemoteBookId(sourceId: String, remoteId: String): String {
    val safeSource = sanitizeIdSegment(sourceId, maxLen = 24)
    val safeRemote = sanitizeIdSegment(remoteId, maxLen = 40)
    val hash8 = sha256Prefix8("$sourceId\u0000$remoteId")
    return "remote-$safeSource-$safeRemote-$hash8"
}

private fun sanitizeIdSegment(value: String, maxLen: Int): String =
    value.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(maxLen).ifEmpty { "x" }

private fun sha256Prefix8(payload: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { byte ->
        ((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
    }
}
