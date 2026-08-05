package dev.readflow.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubRapidIdleDiagnosticsContractTest {

    @Test
    fun `parse accepts only the documented rapid idle probe commands`() {
        assertEquals(
            EpubRapidIdleDiagnosticCommand.StartRapidIdleProbe(windowMs = 1_000L),
            EpubRapidIdleDiagnosticsContract.parse(
                method = "startRapidIdleProbe",
                windowMsRaw = "1000",
            ),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.StartRapidIdleProbe(windowMs = 30_000L),
            EpubRapidIdleDiagnosticsContract.parse(
                method = "startRapidIdleProbe",
                windowMsRaw = "30000",
            ),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.SnapshotRapidIdleProbe,
            EpubRapidIdleDiagnosticsContract.parse(
                method = "snapshotRapidIdleProbe",
                windowMsRaw = null,
            ),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.StopRapidIdleProbe,
            EpubRapidIdleDiagnosticsContract.parse(
                method = "stopRapidIdleProbe",
                windowMsRaw = null,
            ),
        )

        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.MISSING_METHOD),
            EpubRapidIdleDiagnosticsContract.parse(method = null, windowMsRaw = null),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.MISSING_METHOD),
            EpubRapidIdleDiagnosticsContract.parse(method = "", windowMsRaw = null),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.UNKNOWN_METHOD),
            EpubRapidIdleDiagnosticsContract.parse(method = "restartRapidIdleProbe", windowMsRaw = null),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.MISSING_WINDOW_MS),
            EpubRapidIdleDiagnosticsContract.parse(method = "startRapidIdleProbe", windowMsRaw = null),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.INVALID_WINDOW_MS),
            EpubRapidIdleDiagnosticsContract.parse(method = "startRapidIdleProbe", windowMsRaw = "not-a-number"),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.INVALID_WINDOW_MS),
            EpubRapidIdleDiagnosticsContract.parse(method = "startRapidIdleProbe", windowMsRaw = "999"),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.INVALID_WINDOW_MS),
            EpubRapidIdleDiagnosticsContract.parse(method = "startRapidIdleProbe", windowMsRaw = "30001"),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.UNEXPECTED_WINDOW_MS),
            EpubRapidIdleDiagnosticsContract.parse(method = "snapshotRapidIdleProbe", windowMsRaw = "1000"),
        )
        assertEquals(
            EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.UNEXPECTED_WINDOW_MS),
            EpubRapidIdleDiagnosticsContract.parse(method = "stopRapidIdleProbe", windowMsRaw = "1000"),
        )
    }
}
