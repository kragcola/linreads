package dev.readflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HelperReturnAckTest {
    @Test
    fun `valid helper return request is accepted for resume acknowledgement`() {
        assertEquals(
            HelperReturnRequest(
                sessionId = 42,
                expectedVersion = 100_302L,
                nonce = "nonce-1",
            ),
            request(),
        )
    }

    @Test
    fun `invalid helper return request is ignored`() {
        assertNull(request(action = "other"))
        assertNull(request(sessionId = -1))
        assertNull(request(expectedVersion = 0L))
        assertNull(request(nonce = ""))
        assertNull(request(nonce = null))
    }

    private fun request(
        action: String? = ACTION_HELPER_RETURN,
        sessionId: Int = 42,
        expectedVersion: Long = 100_302L,
        nonce: String? = "nonce-1",
    ): HelperReturnRequest? = helperReturnRequest(
        action = action,
        sessionId = sessionId,
        expectedVersion = expectedVersion,
        nonce = nonce,
    )
}
