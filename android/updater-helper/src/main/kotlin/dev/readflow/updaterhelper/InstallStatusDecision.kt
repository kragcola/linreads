package dev.readflow.updaterhelper

import android.content.pm.PackageInstaller

internal fun targetVersionInstalled(installedVersion: Long?, expectedVersion: Long): Boolean =
    installedVersion != null && expectedVersion > 0L && installedVersion >= expectedVersion

@Suppress("UNUSED_PARAMETER")
internal fun shouldConsumePendingTerminalAction(activityResumed: Boolean): Boolean = false

internal const val RETURN_LAUNCH_MAX_ATTEMPTS = 4

internal fun shouldScheduleReturnLaunch(
    terminalPending: Boolean,
    returnScheduled: Boolean,
    activityResumed: Boolean,
    hasWindowFocus: Boolean,
    launchAttempts: Int,
): Boolean = terminalPending &&
    !returnScheduled &&
    activityResumed &&
    hasWindowFocus &&
    launchAttempts < RETURN_LAUNCH_MAX_ATTEMPTS

internal fun returnLaunchDelayMs(launchAttempt: Int): Long =
    RETURN_LAUNCH_SETTLE_DELAY_MS * (launchAttempt + 1).coerceIn(1, 3)

internal fun nextReturnLaunchAttempt(
    terminalPending: Boolean,
    pendingAction: String?,
    activityResumed: Boolean,
    hasWindowFocus: Boolean,
    persistedAttempts: Int,
): Int? = if (
    terminalPending &&
    pendingAction == HelperContract.ACTION_RETURN_TO_APP &&
    activityResumed &&
    hasWindowFocus &&
    persistedAttempts in 0 until RETURN_LAUNCH_MAX_ATTEMPTS
) {
    persistedAttempts + 1
} else {
    null
}

internal fun shouldConsumeReturnAck(
    terminalPending: Boolean,
    pendingAction: String?,
    armedSessionId: Int,
    armedExpectedVersion: Long,
    armedNonce: String?,
    ackSessionId: Int,
    ackExpectedVersion: Long,
    ackNonce: String?,
    targetSignerMatches: Boolean,
    installedVersion: Long?,
): Boolean = terminalPending &&
    pendingAction == HelperContract.ACTION_RETURN_TO_APP &&
    armedSessionId >= 0 &&
    armedSessionId == ackSessionId &&
    armedExpectedVersion > 0L &&
    armedExpectedVersion == ackExpectedVersion &&
    !armedNonce.isNullOrBlank() &&
    armedNonce == ackNonce &&
    targetSignerMatches &&
    targetVersionInstalled(installedVersion, armedExpectedVersion)

internal fun shouldPostReturnNotification(
    terminalPending: Boolean,
    pendingAction: String?,
    hasRecoverableHelperTask: Boolean,
): Boolean = terminalPending &&
    pendingAction == HelperContract.ACTION_RETURN_TO_APP &&
    !hasRecoverableHelperTask

private const val RETURN_LAUNCH_SETTLE_DELAY_MS = 300L

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
        (armedExpiresAtEpochMs < nowEpochMs && !confirmationDispatched) ||
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
