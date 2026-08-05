package dev.readflow.diagnostics

internal enum class EpubRapidIdleDiagnosticError {
    MISSING_METHOD,
    UNKNOWN_METHOD,
    MISSING_WINDOW_MS,
    INVALID_WINDOW_MS,
    UNEXPECTED_WINDOW_MS,
}

internal sealed interface EpubRapidIdleDiagnosticCommand {
    data class StartRapidIdleProbe(val windowMs: Long) : EpubRapidIdleDiagnosticCommand

    data object SnapshotRapidIdleProbe : EpubRapidIdleDiagnosticCommand

    data object StopRapidIdleProbe : EpubRapidIdleDiagnosticCommand

    data class Invalid(val error: EpubRapidIdleDiagnosticError) : EpubRapidIdleDiagnosticCommand
}

internal object EpubRapidIdleDiagnosticsContract {
    const val EXTRA_WINDOW_MS = "windowMs"

    fun parse(method: String?, windowMsRaw: String?): EpubRapidIdleDiagnosticCommand = when (method) {
        null, "" -> EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.MISSING_METHOD)
        START_METHOD -> parseStart(windowMsRaw)
        SNAPSHOT_METHOD -> parseWindowless(windowMsRaw, EpubRapidIdleDiagnosticCommand.SnapshotRapidIdleProbe)
        STOP_METHOD -> parseWindowless(windowMsRaw, EpubRapidIdleDiagnosticCommand.StopRapidIdleProbe)
        else -> EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.UNKNOWN_METHOD)
    }

    private fun parseStart(windowMsRaw: String?): EpubRapidIdleDiagnosticCommand {
        if (windowMsRaw == null) {
            return EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.MISSING_WINDOW_MS)
        }
        val windowMs = windowMsRaw.toLongOrNull()
            ?.takeIf { it in MIN_WINDOW_MS..MAX_WINDOW_MS }
            ?: return EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.INVALID_WINDOW_MS)
        return EpubRapidIdleDiagnosticCommand.StartRapidIdleProbe(windowMs)
    }

    private fun parseWindowless(
        windowMsRaw: String?,
        command: EpubRapidIdleDiagnosticCommand,
    ): EpubRapidIdleDiagnosticCommand = if (windowMsRaw == null) {
        command
    } else {
        EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.UNEXPECTED_WINDOW_MS)
    }

    private const val START_METHOD = "startRapidIdleProbe"
    private const val SNAPSHOT_METHOD = "snapshotRapidIdleProbe"
    private const val STOP_METHOD = "stopRapidIdleProbe"
    private const val MIN_WINDOW_MS = 1_000L
    private const val MAX_WINDOW_MS = 30_000L
}
