package dev.readflow.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object UpdateHelperBridge {
    private val fallbackExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "linreads-helper-fallback").apply { isDaemon = true }
    }
    private val pendingHandshakes = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    fun prepareCallback(context: Context, sessionId: Int, expectedVersion: Long): Boolean {
        val helper = helperIdentity(context)
        if (
            helperCommitRoute(
                helperInstalled = helper != null,
                helperEnabled = helper?.enabled == true,
                protocolVersion = helper?.protocolVersion,
                signerMatches = helper?.signerMatches == true,
            ) != HelperCommitRoute.HELPER
        ) {
            return false
        }
        val appContext = context.applicationContext
        val timeout = fallbackExecutor.schedule(
            { onHandshakeTimeout(appContext, sessionId) },
            HELPER_HANDSHAKE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        pendingHandshakes.put(sessionId, timeout)?.cancel(false)
        return runCatching {
            context.startActivity(
                Intent().setClassName(UPDATE_HELPER_PACKAGE, UPDATE_HELPER_ACTIVITY)
                    .setAction(ACTION_PREPARE_HELPER_CALLBACK)
                    .putExtra(EXTRA_HELPER_PROTOCOL_VERSION, UPDATE_HELPER_PROTOCOL_VERSION)
                    .putExtra(EXTRA_HELPER_SESSION_ID, sessionId)
                    .putExtra(EXTRA_HELPER_EXPECTED_VERSION, expectedVersion)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }.onFailure { error ->
            pendingHandshakes.remove(sessionId, timeout)
            timeout.cancel(false)
            Log.w(TAG, "helper preparation unavailable; using in-app callback", error)
        }.isSuccess
    }

    fun onCallbackFinished(context: Context, sessionId: Int, callbackCommitted: Boolean) {
        pendingHandshakes.remove(sessionId)?.cancel(false)
        if (
            helperHandshakeAction(
                callbackFinished = true,
                callbackCommitted = callbackCommitted,
                timeoutReached = false,
                sessionCurrent = UpdatePackageInstaller.isCurrentSession(context, sessionId),
            ) == HelperHandshakeAction.COMMIT_IN_APP_FALLBACK
        ) {
            UpdatePackageInstaller.commitSessionWithInAppCallback(context, sessionId)
        }
    }

    fun validateCallback(
        context: Context,
        envelope: HelperCallbackEnvelope,
    ): Boolean {
        val helper = helperIdentity(context)
        return isTrustedHelperCallback(
            envelope = envelope,
            currentSessionId = UpdatePackageInstaller.currentSessionId(context),
            currentExpectedVersion = UpdatePackageInstaller.expectedVersionForCurrentSession(context) ?: -1L,
            helperUid = helper?.uid,
            signerMatches = helper?.signerMatches == true,
            nowEpochMs = System.currentTimeMillis(),
        )
    }

    private fun helperIdentity(context: Context): HelperIdentity? = runCatching {
        val helperInfo = context.packageManager.applicationInfo(
            UPDATE_HELPER_PACKAGE,
            PackageManager.GET_META_DATA,
        )
        HelperIdentity(
            uid = helperInfo.uid,
            enabled = helperInfo.enabled,
            protocolVersion = helperInfo.metaData?.getInt(UPDATE_HELPER_PROTOCOL_METADATA, -1),
            signerMatches = packageSignerDigests(context, context.packageName) != null &&
                packageSignerDigests(context, context.packageName) ==
                packageSignerDigests(context, UPDATE_HELPER_PACKAGE),
        )
    }.getOrNull()

    private fun onHandshakeTimeout(context: Context, sessionId: Int) {
        if (pendingHandshakes.remove(sessionId) == null) return
        if (
            helperHandshakeAction(
                callbackFinished = false,
                callbackCommitted = false,
                timeoutReached = true,
                sessionCurrent = UpdatePackageInstaller.isCurrentSession(context, sessionId),
            ) == HelperHandshakeAction.COMMIT_IN_APP_FALLBACK
        ) {
            UpdatePackageInstaller.commitSessionWithInAppCallback(context, sessionId)
        }
    }
}

class UpdateHelperCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(callbackIntent: Intent) {
        val sessionId = callbackIntent.getIntExtra(EXTRA_HELPER_SESSION_ID, -1)
        val statusSender = callbackIntent.parcelableExtra<IntentSender>(EXTRA_HELPER_STATUS_SENDER)
        val envelope = HelperCallbackEnvelope(
            protocolVersion = callbackIntent.getIntExtra(EXTRA_HELPER_PROTOCOL_VERSION, -1),
            sessionId = sessionId,
            expectedVersion = callbackIntent.getLongExtra(EXTRA_HELPER_EXPECTED_VERSION, -1L),
            nonce = callbackIntent.getStringExtra(EXTRA_HELPER_NONCE).orEmpty(),
            expiresAtEpochMs = callbackIntent.getLongExtra(EXTRA_HELPER_EXPIRES_AT_EPOCH_MS, -1L),
            senderCreatorPackage = statusSender?.creatorPackage,
            senderCreatorUid = statusSender?.creatorUid ?: -1,
        )
        val trusted = callbackIntent.action == ACTION_HELPER_CALLBACK_READY &&
            statusSender != null &&
            UpdateHelperBridge.validateCallback(this, envelope)
        val committed = trusted && UpdatePackageInstaller.commitSession(
            context = this,
            sessionId = sessionId,
            statusSender = requireNotNull(statusSender),
        )
        UpdateHelperBridge.onCallbackFinished(this, sessionId, committed)
        finish()
    }
}

private data class HelperIdentity(
    val uid: Int,
    val enabled: Boolean,
    val protocolVersion: Int?,
    val signerMatches: Boolean,
)

private fun PackageManager.applicationInfo(packageName: String, flags: Int): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= 33) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, flags)
    }

private fun packageSignerDigests(context: Context, packageName: String): Set<String>? = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else if (Build.VERSION.SDK_INT >= 28) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.signatures.orEmpty()
    }
    signatures.mapTo(linkedSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }.takeIf { it.isNotEmpty() }
}.getOrNull()

private inline fun <reified T> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

private const val TAG = "LinReadsUpdateHelper"
private const val HELPER_HANDSHAKE_TIMEOUT_MS = 5_000L
