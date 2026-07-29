package dev.readflow.updaterhelper

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallStatusDecisionTest {
    @Test
    fun `terminal action remains pending until helper activity resumes`() {
        assertEquals(false, shouldConsumePendingTerminalAction(activityResumed = false))
        assertEquals(true, shouldConsumePendingTerminalAction(activityResumed = true))
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
}
