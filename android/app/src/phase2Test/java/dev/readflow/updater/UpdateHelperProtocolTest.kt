package dev.readflow.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateHelperProtocolTest {
    @Test
    fun `helper handshake falls back only after failure or timeout`() {
        assertEquals(
            HelperHandshakeAction.WAIT,
            helperHandshakeAction(
                callbackFinished = false,
                callbackCommitted = false,
                timeoutReached = false,
                sessionCurrent = true,
            ),
        )
        assertEquals(
            HelperHandshakeAction.COMPLETE,
            helperHandshakeAction(true, true, false, true),
        )
        assertEquals(
            HelperHandshakeAction.COMMIT_IN_APP_FALLBACK,
            helperHandshakeAction(true, false, false, true),
        )
        assertEquals(
            HelperHandshakeAction.COMMIT_IN_APP_FALLBACK,
            helperHandshakeAction(false, false, true, true),
        )
        assertEquals(
            HelperHandshakeAction.COMPLETE,
            helperHandshakeAction(false, false, true, false),
        )
    }

    @Test
    fun `helper route requires an enabled compatible same-signer package`() {
        assertEquals(
            HelperCommitRoute.HELPER,
            helperCommitRoute(
                helperInstalled = true,
                helperEnabled = true,
                protocolVersion = UPDATE_HELPER_PROTOCOL_VERSION,
                signerMatches = true,
            ),
        )
        assertEquals(
            HelperCommitRoute.IN_APP_FALLBACK,
            helperCommitRoute(true, false, UPDATE_HELPER_PROTOCOL_VERSION, true),
        )
        assertEquals(
            HelperCommitRoute.IN_APP_FALLBACK,
            helperCommitRoute(true, true, UPDATE_HELPER_PROTOCOL_VERSION + 1, true),
        )
        assertEquals(
            HelperCommitRoute.IN_APP_FALLBACK,
            helperCommitRoute(true, true, UPDATE_HELPER_PROTOCOL_VERSION, false),
        )
        assertEquals(
            HelperCommitRoute.IN_APP_FALLBACK,
            helperCommitRoute(false, false, null, false),
        )
    }

    @Test
    fun `callback accepts only the current unexpired helper sender`() {
        val envelope = validEnvelope()

        assertTrue(
            isTrustedHelperCallback(
                envelope = envelope,
                currentSessionId = 42,
                currentExpectedVersion = 100_302L,
                helperUid = 10_287,
                signerMatches = true,
                nowEpochMs = 1_500L,
            ),
        )
    }

    @Test
    fun `callback rejects stale forged or mismatched state`() {
        val valid = validEnvelope()
        val baseline = {
            envelope: HelperCallbackEnvelope,
            helperUid: Int?,
            signerMatches: Boolean,
            now: Long,
            ->
            isTrustedHelperCallback(
                envelope = envelope,
                currentSessionId = 42,
                currentExpectedVersion = 100_302L,
                helperUid = helperUid,
                signerMatches = signerMatches,
                nowEpochMs = now,
            )
        }

        assertFalse(baseline(valid.copy(protocolVersion = 2), 10_287, true, 1_500L))
        assertFalse(baseline(valid.copy(sessionId = 43), 10_287, true, 1_500L))
        assertFalse(baseline(valid.copy(expectedVersion = 100_303L), 10_287, true, 1_500L))
        assertFalse(baseline(valid.copy(nonce = ""), 10_287, true, 1_500L))
        assertFalse(baseline(valid.copy(senderCreatorPackage = "example.invalid"), 10_287, true, 1_500L))
        assertFalse(baseline(valid.copy(senderCreatorUid = 10_288), 10_287, true, 1_500L))
        assertFalse(baseline(valid, null, true, 1_500L))
        assertFalse(baseline(valid, 10_287, false, 1_500L))
        assertFalse(baseline(valid, 10_287, true, 2_001L))
    }

    private fun validEnvelope() = HelperCallbackEnvelope(
        protocolVersion = UPDATE_HELPER_PROTOCOL_VERSION,
        sessionId = 42,
        expectedVersion = 100_302L,
        nonce = "nonce-1",
        expiresAtEpochMs = 2_000L,
        senderCreatorPackage = UPDATE_HELPER_PACKAGE,
        senderCreatorUid = 10_287,
    )
}
