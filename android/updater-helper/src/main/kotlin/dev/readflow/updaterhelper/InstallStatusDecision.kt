package dev.readflow.updaterhelper

import android.content.pm.PackageInstaller

internal fun targetVersionInstalled(installedVersion: Long?, expectedVersion: Long): Boolean =
    installedVersion != null && expectedVersion > 0L && installedVersion >= expectedVersion

internal fun shouldConsumePendingTerminalAction(activityResumed: Boolean): Boolean = activityResumed

internal enum class CallbackAction {
    IGNORE,
    CONFIRM,
    OPEN_LINREADS,
    FAIL,
}

internal fun callbackAction(
    armedSessionId: Int,
    armedNonce: String?,
    armedExpectedVersion: Long = -1L,
    armedExpiresAtEpochMs: Long = Long.MAX_VALUE,
    callbackSessionId: Int,
    callbackNonce: String?,
    status: Int,
    terminalConsumed: Boolean,
    terminalPending: Boolean = false,
    confirmationDispatched: Boolean = false,
    nowEpochMs: Long = 0L,
    targetSignerMatches: Boolean = true,
    installedVersion: Long? = null,
): CallbackAction {
    if (
        terminalConsumed ||
        terminalPending ||
        armedSessionId < 0 ||
        armedNonce.isNullOrBlank() ||
        armedExpectedVersion <= 0L ||
        armedExpiresAtEpochMs < nowEpochMs ||
        callbackSessionId != armedSessionId ||
        callbackNonce != armedNonce
    ) {
        return CallbackAction.IGNORE
    }
    return when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            if (targetSignerMatches && !confirmationDispatched) {
                CallbackAction.CONFIRM
            } else {
                CallbackAction.IGNORE
            }
        }
        PackageInstaller.STATUS_SUCCESS -> {
            if (
                targetSignerMatches &&
                targetVersionInstalled(installedVersion, armedExpectedVersion)
            ) {
                CallbackAction.OPEN_LINREADS
            } else {
                CallbackAction.FAIL
            }
        }
        else -> CallbackAction.FAIL
    }
}
