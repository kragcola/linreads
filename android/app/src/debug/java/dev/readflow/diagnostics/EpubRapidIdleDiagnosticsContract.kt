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
    @Suppress("UNUSED_PARAMETER")
    fun parse(method: String?, windowMsRaw: String?): EpubRapidIdleDiagnosticCommand =
        EpubRapidIdleDiagnosticCommand.Invalid(EpubRapidIdleDiagnosticError.MISSING_METHOD)
}
