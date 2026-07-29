package dev.readflow.updater

internal val UPDATE_HELPER_PROTOCOL_VERSION = dev.readflow.BuildConfig.UPDATE_HELPER_PROTOCOL_VERSION
internal const val UPDATE_HELPER_PACKAGE = "dev.readflow.updaterhelper"
internal const val UPDATE_HELPER_ACTIVITY = "dev.readflow.updaterhelper.UpdaterActivity"
internal const val UPDATE_HELPER_PROTOCOL_METADATA = "dev.readflow.updaterhelper.PROTOCOL_VERSION"
internal const val UPDATE_HELPER_BRIDGE_PERMISSION = "dev.readflow.permission.UPDATE_HELPER_BRIDGE"

internal const val ACTION_PREPARE_HELPER_CALLBACK =
    "dev.readflow.updaterhelper.PREPARE_CALLBACK_V1"
internal const val ACTION_HELPER_CALLBACK_READY =
    "dev.readflow.updater.HELPER_CALLBACK_READY_V1"
internal const val EXTRA_HELPER_PROTOCOL_VERSION = "protocol_version"
internal const val EXTRA_HELPER_SESSION_ID = "session_id"
internal const val EXTRA_HELPER_EXPECTED_VERSION = "expected_version"
internal const val EXTRA_HELPER_NONCE = "nonce"
internal const val EXTRA_HELPER_EXPIRES_AT_EPOCH_MS = "expires_at_epoch_ms"
internal const val EXTRA_HELPER_STATUS_SENDER = "status_sender"

internal enum class HelperCommitRoute {
    HELPER,
    IN_APP_FALLBACK,
}

internal enum class HelperHandshakeAction {
    WAIT,
    COMMIT_IN_APP_FALLBACK,
    COMPLETE,
}

internal fun helperHandshakeAction(
    callbackFinished: Boolean,
    callbackCommitted: Boolean,
    timeoutReached: Boolean,
    sessionCurrent: Boolean,
): HelperHandshakeAction = when {
    !sessionCurrent || callbackCommitted -> HelperHandshakeAction.COMPLETE
    callbackFinished || timeoutReached -> HelperHandshakeAction.COMMIT_IN_APP_FALLBACK
    else -> HelperHandshakeAction.WAIT
}

internal fun helperCommitRoute(
    helperInstalled: Boolean,
    helperEnabled: Boolean,
    protocolVersion: Int?,
    signerMatches: Boolean,
): HelperCommitRoute = if (
    helperInstalled &&
    helperEnabled &&
    protocolVersion == UPDATE_HELPER_PROTOCOL_VERSION &&
    signerMatches
) {
    HelperCommitRoute.HELPER
} else {
    HelperCommitRoute.IN_APP_FALLBACK
}

internal data class HelperCallbackEnvelope(
    val protocolVersion: Int,
    val sessionId: Int,
    val expectedVersion: Long,
    val nonce: String,
    val expiresAtEpochMs: Long,
    val senderCreatorPackage: String?,
    val senderCreatorUid: Int,
)

internal fun isTrustedHelperCallback(
    envelope: HelperCallbackEnvelope,
    currentSessionId: Int,
    currentExpectedVersion: Long,
    helperUid: Int?,
    signerMatches: Boolean,
    nowEpochMs: Long,
): Boolean =
    envelope.protocolVersion == UPDATE_HELPER_PROTOCOL_VERSION &&
        envelope.sessionId >= 0 &&
        envelope.sessionId == currentSessionId &&
        envelope.expectedVersion > 0L &&
        envelope.expectedVersion == currentExpectedVersion &&
        envelope.nonce.isNotBlank() &&
        envelope.expiresAtEpochMs >= nowEpochMs &&
        envelope.senderCreatorPackage == UPDATE_HELPER_PACKAGE &&
        helperUid != null &&
        envelope.senderCreatorUid == helperUid &&
        signerMatches
