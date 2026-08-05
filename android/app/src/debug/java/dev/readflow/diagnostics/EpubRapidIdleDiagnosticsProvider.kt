package dev.readflow.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Debug-only, shell-gated controls for collecting in-process rapid-idle timing from an open reader.
 *
 * The endpoint does not inspect or change reader state. It is deliberately absent from release and
 * OTA manifests, and is useful only while a developer has already opened the desired EPUB page.
 */
class EpubRapidIdleDiagnosticsProvider : ContentProvider() {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextSessionId = 0L
    private var activeSessionId = 0L

    override fun onCreate(): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireShellCaller()
        return when (
            val command = EpubRapidIdleDiagnosticsContract.parse(
                method = method,
                windowMsRaw = extras?.getString(EpubRapidIdleDiagnosticsContract.EXTRA_WINDOW_MS),
            )
        ) {
            is EpubRapidIdleDiagnosticCommand.StartRapidIdleProbe -> start(command.windowMs)
            EpubRapidIdleDiagnosticCommand.SnapshotRapidIdleProbe -> snapshot()
            EpubRapidIdleDiagnosticCommand.StopRapidIdleProbe -> stop()
            is EpubRapidIdleDiagnosticCommand.Invalid -> invalid(command.error)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    private fun start(windowMs: Long): Bundle {
        val sessionId: Long
        val initialSnapshot: String
        synchronized(lock) {
            sessionId = ++nextSessionId
            activeSessionId = sessionId
            initialSnapshot = RapidIdleProbeHandle.resetAndSnapshot()
            mainHandler.postDelayed({ completeWindow(sessionId, windowMs) }, windowMs)
        }
        Log.i(TAG, "started session=$sessionId windowMs=$windowMs pid=${Process.myPid()} raw=$initialSnapshot")
        return result(status = "started", sessionId = sessionId, snapshot = initialSnapshot)
    }

    private fun snapshot(): Bundle {
        val (sessionId, snapshot) = synchronized(lock) {
            activeSessionId to RapidIdleProbeHandle.snapshot()
        }
        Log.i(TAG, "snapshot session=$sessionId pid=${Process.myPid()} raw=$snapshot")
        return result(status = "snapshot", sessionId = sessionId, snapshot = snapshot)
    }

    private fun stop(): Bundle {
        val sessionId: Long
        val snapshot: String
        synchronized(lock) {
            sessionId = activeSessionId
            activeSessionId = 0L
            snapshot = RapidIdleProbeHandle.snapshotThenStop()
        }
        Log.i(TAG, "stopped session=$sessionId pid=${Process.myPid()} raw=$snapshot")
        return result(status = "stopped", sessionId = sessionId, snapshot = snapshot)
    }

    private fun completeWindow(sessionId: Long, windowMs: Long) {
        val snapshot = synchronized(lock) {
            if (activeSessionId != sessionId) return
            activeSessionId = 0L
            RapidIdleProbeHandle.snapshotThenStop()
        }
        Log.i(
            TAG,
            "completed session=$sessionId windowMs=$windowMs pid=${Process.myPid()} raw=$snapshot",
        )
    }

    private fun requireShellCaller() {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Rapid-idle diagnostics accept ADB shell only.")
        }
    }

    private fun invalid(error: EpubRapidIdleDiagnosticError): Bundle =
        Bundle().apply {
            putString("status", "invalid")
            putString("error", error.name)
        }

    private fun result(status: String, sessionId: Long, snapshot: String): Bundle =
        Bundle().apply {
            putString("status", status)
            putLong("sessionId", sessionId)
            putString("snapshot", snapshot)
        }

    private object RapidIdleProbeHandle {
        private const val PROBE_CLASS_NAME = "dev.readflow.render.epub.EpubRapidIdleWorkProbe"

        private val probeClass: Class<*> by lazy { Class.forName(PROBE_CLASS_NAME) }
        private val instance: Any by lazy {
            probeClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                ?: error("$PROBE_CLASS_NAME INSTANCE must not be null.")
        }

        fun resetAndSnapshot(): String {
            invokeNoArg("reset")
            return snapshot()
        }

        fun snapshotThenStop(): String {
            val snapshot = snapshot()
            invokeNoArg("stop")
            return snapshot
        }

        fun snapshot(): String = invokeNoArg("snapshot")?.toString() ?: "null"

        private fun invokeNoArg(methodName: String): Any? {
            val method = probeClass.declaredMethods.singleOrNull { candidate ->
                candidate.name == methodName && candidate.parameterCount == 0
            } ?: error("$PROBE_CLASS_NAME must expose $methodName().")
            method.isAccessible = true
            return try {
                method.invoke(instance)
            } catch (error: InvocationTargetException) {
                throw IllegalStateException(
                    "$PROBE_CLASS_NAME.$methodName() failed.",
                    error.targetException,
                )
            }
        }
    }

    private companion object {
        const val TAG = "EpubRapidIdleDiagnostic"
    }
}
