package dev.readflow

internal data class HelperReturnRequest(
    val sessionId: Int,
    val expectedVersion: Long,
    val nonce: String,
)

internal fun helperReturnRequest(
    action: String?,
    sessionId: Int,
    expectedVersion: Long,
    nonce: String?,
): HelperReturnRequest? {
    if (
        action != ACTION_HELPER_RETURN ||
        sessionId < 0 ||
        expectedVersion <= 0L ||
        nonce.isNullOrBlank()
    ) {
        return null
    }
    return HelperReturnRequest(
        sessionId = sessionId,
        expectedVersion = expectedVersion,
        nonce = nonce,
    )
}

internal const val ACTION_HELPER_RETURN = "dev.readflow.updater.RETURN_FROM_HELPER_V1"
internal const val ACTION_HELPER_RETURN_ACK = "dev.readflow.updaterhelper.RETURN_ACK_V1"
internal const val EXTRA_HELPER_RETURN_SESSION_ID = "session_id"
internal const val EXTRA_HELPER_RETURN_EXPECTED_VERSION = "expected_version"
internal const val EXTRA_HELPER_RETURN_NONCE = "nonce"
internal const val UPDATE_HELPER_PACKAGE_NAME = "dev.readflow.updaterhelper"
internal const val UPDATE_HELPER_ACK_RECEIVER =
    "dev.readflow.updaterhelper.ReturnAckReceiver"
