package dev.readflow

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadflowApplicationRecoveryTest {
    @Test
    fun `startup contains recovery enumeration failure`() = runTest {
        val failure = IllegalStateException("cannot list staging directory")
        val reported = mutableListOf<Pair<String?, Throwable>>()

        recoverBookDeletionsAtStartup(
            recover = { throw failure },
            onFailure = { bookId, error -> reported += bookId to error },
        )

        assertEquals(listOf(null to failure), reported)
    }
}
