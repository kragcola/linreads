package dev.readflow.updaterhelper

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallStatusDecisionTest {
    @Test
    fun `helper lifecycle never consumes pending terminal success`() {
        assertEquals(false, shouldConsumePendingTerminalAction(activityResumed = true))
        assertEquals(false, shouldConsumePendingTerminalAction(activityResumed = false))
    }

    @Test
    fun `return launch uses exact delays and preserves budget while focus is lost`() {
        assertEquals(300L, returnLaunchDelayMs(0).toLong())
        assertEquals(600L, returnLaunchDelayMs(1).toLong())
        assertEquals(900L, returnLaunchDelayMs(2).toLong())
        assertEquals(900L, returnLaunchDelayMs(3).toLong())
        assertEquals(
            false,
            shouldScheduleReturnLaunch(
                terminalPending = true,
                returnScheduled = false,
                activityResumed = true,
                hasWindowFocus = false,
                launchAttempts = 2,
            ),
        )
        assertEquals(
            true,
            shouldScheduleReturnLaunch(
                terminalPending = true,
                returnScheduled = false,
                activityResumed = true,
                hasWindowFocus = true,
                launchAttempts = 2,
            ),
        )
        val persistedLaunchAttemptsAfterRecreation = 4
        assertEquals(
            false,
            shouldScheduleReturnLaunch(
                terminalPending = true,
                returnScheduled = false,
                activityResumed = true,
                hasWindowFocus = true,
                launchAttempts = persistedLaunchAttemptsAfterRecreation,
            ),
        )
    }

    @Test
    fun `return launch attempt increments only when a focused launch executes`() {
        assertEquals(
            null,
            nextReturnLaunchAttempt(
                terminalPending = true,
                pendingAction = HelperContract.ACTION_RETURN_TO_APP,
                activityResumed = true,
                hasWindowFocus = false,
                persistedAttempts = 2,
            ),
        )
        assertEquals(
            3,
            nextReturnLaunchAttempt(
                terminalPending = true,
                pendingAction = HelperContract.ACTION_RETURN_TO_APP,
                activityResumed = true,
                hasWindowFocus = true,
                persistedAttempts = 2,
            ),
        )
        assertEquals(
            null,
            nextReturnLaunchAttempt(
                terminalPending = true,
                pendingAction = HelperContract.ACTION_RETURN_TO_APP,
                activityResumed = true,
                hasWindowFocus = true,
                persistedAttempts = RETURN_LAUNCH_MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `only a matching resumed app acknowledgement consumes terminal success`() {
        assertEquals(true, consumeReturnAck())
        assertEquals(false, consumeReturnAck(ackSessionId = 43))
        assertEquals(false, consumeReturnAck(ackExpectedVersion = 100_303L))
        assertEquals(false, consumeReturnAck(ackNonce = "nonce-2"))
        assertEquals(false, consumeReturnAck(targetSignerMatches = false))
        assertEquals(false, consumeReturnAck(installedVersion = 100_301L))
        assertEquals(false, consumeReturnAck(terminalPending = false))
        assertEquals(false, consumeReturnAck(pendingAction = HelperContract.ACTION_INSTALL_FAILED))
    }

    @Test
    fun `cold helper task requires a user recoverable return notification`() {
        assertEquals(
            true,
            shouldPostReturnNotification(
                terminalPending = true,
                pendingAction = HelperContract.ACTION_RETURN_TO_APP,
                hasRecoverableHelperTask = false,
            ),
        )
        assertEquals(
            false,
            shouldPostReturnNotification(
                terminalPending = true,
                pendingAction = HelperContract.ACTION_RETURN_TO_APP,
                hasRecoverableHelperTask = true,
            ),
        )
        assertEquals(
            false,
            shouldPostReturnNotification(
                terminalPending = false,
                pendingAction = HelperContract.ACTION_RETURN_TO_APP,
                hasRecoverableHelperTask = false,
            ),
        )
    }

    @Test
    fun `pending callback confirms only the armed unexpired session`() {
        assertEquals(
            CallbackAction.CONFIRM,
            action(status = PackageInstaller.STATUS_PENDING_USER_ACTION),
        )
        assertEquals(
            CallbackAction.IGNORE,
            action(
                status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                confirmationDispatched = true,
            ),
        )
        assertEquals(
            CallbackAction.IGNORE,
            action(
                status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                targetSignerMatches = false,
            ),
        )
    }

    @Test
    fun `successful callback opens LinReads only after version and signer verification`() {
        assertEquals(
            CallbackAction.OPEN_LINREADS,
            action(status = PackageInstaller.STATUS_SUCCESS),
        )
        assertEquals(
            CallbackAction.FAIL,
            action(
                status = PackageInstaller.STATUS_SUCCESS,
                installedVersion = 100_301L,
            ),
        )
        assertEquals(
            CallbackAction.FAIL,
            action(
                status = PackageInstaller.STATUS_SUCCESS,
                targetSignerMatches = false,
            ),
        )
    }

    @Test
    fun `callback rejects mismatched expired and replayed state`() {
        assertEquals(
            CallbackAction.IGNORE,
            action(status = PackageInstaller.STATUS_SUCCESS, callbackSessionId = 43),
        )
        assertEquals(
            CallbackAction.IGNORE,
            action(status = PackageInstaller.STATUS_SUCCESS, callbackNonce = "nonce-2"),
        )
        assertEquals(
            CallbackAction.IGNORE,
            action(status = PackageInstaller.STATUS_SUCCESS, terminalConsumed = true),
        )
        assertEquals(
            CallbackAction.IGNORE,
            action(status = PackageInstaller.STATUS_SUCCESS, terminalPending = true),
        )
        assertEquals(
            CallbackAction.IGNORE,
            action(status = PackageInstaller.STATUS_SUCCESS, nowEpochMs = 2_001L),
        )
    }

    @Test
    fun `terminal callback remains valid after confirmation even when handshake ttl expired`() {
        assertEquals(
            CallbackAction.OPEN_LINREADS,
            action(
                status = PackageInstaller.STATUS_SUCCESS,
                confirmationDispatched = true,
                nowEpochMs = 2_001L,
            ),
        )
        assertEquals(
            CallbackAction.FAIL,
            action(
                status = PackageInstaller.STATUS_FAILURE_INVALID,
                confirmationDispatched = true,
                nowEpochMs = 2_001L,
            ),
        )
    }

    @Test
    fun `terminal installer failure is reported for a valid callback`() {
        assertEquals(
            CallbackAction.FAIL,
            action(status = PackageInstaller.STATUS_FAILURE_INVALID),
        )
    }

    private fun action(
        status: Int,
        callbackSessionId: Int = 42,
        callbackNonce: String = "nonce-1",
        terminalConsumed: Boolean = false,
        terminalPending: Boolean = false,
        confirmationDispatched: Boolean = false,
        nowEpochMs: Long = 1_500L,
        targetSignerMatches: Boolean = true,
        installedVersion: Long? = 100_302L,
    ): CallbackAction = callbackAction(
        armedSessionId = 42,
        armedNonce = "nonce-1",
        armedExpectedVersion = 100_302L,
        armedExpiresAtEpochMs = 2_000L,
        callbackSessionId = callbackSessionId,
        callbackNonce = callbackNonce,
        status = status,
        terminalConsumed = terminalConsumed,
        terminalPending = terminalPending,
        confirmationDispatched = confirmationDispatched,
        nowEpochMs = nowEpochMs,
        targetSignerMatches = targetSignerMatches,
        installedVersion = installedVersion,
    )

    private fun consumeReturnAck(
        terminalPending: Boolean = true,
        pendingAction: String = HelperContract.ACTION_RETURN_TO_APP,
        ackSessionId: Int = 42,
        ackExpectedVersion: Long = 100_302L,
        ackNonce: String = "nonce-1",
        targetSignerMatches: Boolean = true,
        installedVersion: Long? = 100_302L,
    ): Boolean = shouldConsumeReturnAck(
        terminalPending = terminalPending,
        pendingAction = pendingAction,
        armedSessionId = 42,
        armedExpectedVersion = 100_302L,
        armedNonce = "nonce-1",
        ackSessionId = ackSessionId,
        ackExpectedVersion = ackExpectedVersion,
        ackNonce = ackNonce,
        targetSignerMatches = targetSignerMatches,
        installedVersion = installedVersion,
    )
}
