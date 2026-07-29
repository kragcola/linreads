package dev.readflow.updaterhelper

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.TextView
import java.security.MessageDigest
import java.util.UUID

class UpdaterActivity : Activity() {
    private lateinit var statusView: TextView
    private var returnWhenFocused = false
    private var returnScheduled = false
    private var returnLaunchRequested = false
    private var activityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(48, 72, 48, 48)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        setContentView(statusView)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        resumePendingTerminalAction()
        scheduleReturnWhenFocused()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleReturnWhenFocused()
    }

    override fun onStop() {
        super.onStop()
        if (returnLaunchRequested) finishAndRemoveTask()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            HelperContract.ACTION_PREPARE_CALLBACK -> prepareCallback(intent)
            HelperContract.ACTION_CONFIRM_INSTALL -> confirmInstall(intent)
            HelperContract.ACTION_RETURN_TO_APP,
            HelperContract.ACTION_INSTALL_FAILED,
            -> {
                if (shouldConsumePendingTerminalAction(activityResumed)) {
                    resumePendingTerminalAction()
                }
            }
            else -> {
                updateStatus("无法准备更新")
                Log.e(HelperContract.TAG, "invalid action=${intent.action}")
            }
        }
    }

    private fun prepareCallback(intent: Intent) {
        val protocolVersion = intent.getIntExtra(HelperContract.EXTRA_PROTOCOL_VERSION, -1)
        val sessionId = intent.getIntExtra(HelperContract.EXTRA_SESSION_ID, -1)
        val expectedVersion = intent.getLongExtra(HelperContract.EXTRA_EXPECTED_VERSION, -1L)
        if (
            protocolVersion != HelperContract.PROTOCOL_VERSION ||
            sessionId < 0 ||
            expectedVersion <= 0L
        ) {
            updateStatus("更新协议不兼容")
            return
        }

        val nonce = UUID.randomUUID().toString()
        val expiresAtEpochMs = System.currentTimeMillis() + HelperContract.CALLBACK_TTL_MS
        helperPreferences().edit()
            .putInt(HelperContract.KEY_PROTOCOL_VERSION, protocolVersion)
            .putInt(HelperContract.KEY_SESSION_ID, sessionId)
            .putLong(HelperContract.KEY_EXPECTED_VERSION, expectedVersion)
            .putString(HelperContract.KEY_NONCE, nonce)
            .putLong(HelperContract.KEY_EXPIRES_AT_EPOCH_MS, expiresAtEpochMs)
            .putInt(HelperContract.KEY_TASK_ID, taskId)
            .putBoolean(HelperContract.KEY_TERMINAL_CONSUMED, false)
            .putBoolean(HelperContract.KEY_TERMINAL_PENDING, false)
            .putBoolean(HelperContract.KEY_CONFIRMATION_DISPATCHED, false)
            .remove(HelperContract.KEY_PENDING_TERMINAL_ACTION)
            .remove(HelperContract.KEY_PENDING_ERROR_MESSAGE)
            .commit()

        val callbackIntent = Intent(this, InstallStatusReceiver::class.java)
            .setAction(HelperContract.ACTION_INSTALL_STATUS)
            .setData(Uri.parse("linreads-updater://install-status/$nonce"))
            .putExtra(HelperContract.EXTRA_NONCE, nonce)
        val callback = PendingIntent.getBroadcast(
            this,
            sessionId,
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        Log.i(
            HelperContract.TAG,
            "prepared sessionId=$sessionId helperUid=${Process.myUid()} creatorUid=${callback.creatorUid}",
        )
        updateStatus("正在准备系统安装")
        val delivered = runCatching {
            startActivity(
                Intent().setClassName(
                    HelperContract.TARGET_PACKAGE,
                    HelperContract.TARGET_CALLBACK_ACTIVITY,
                ).setAction(HelperContract.ACTION_CALLBACK_READY)
                    .putExtra(HelperContract.EXTRA_PROTOCOL_VERSION, protocolVersion)
                    .putExtra(HelperContract.EXTRA_SESSION_ID, sessionId)
                    .putExtra(HelperContract.EXTRA_EXPECTED_VERSION, expectedVersion)
                    .putExtra(HelperContract.EXTRA_NONCE, nonce)
                    .putExtra(HelperContract.EXTRA_EXPIRES_AT_EPOCH_MS, expiresAtEpochMs)
                    .putExtra(HelperContract.EXTRA_STATUS_SENDER, callback.intentSender)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }.isSuccess
        if (!delivered) updateStatus("无法连接 LinReads，将使用兼容安装方式")
    }

    private fun confirmInstall(intent: Intent) {
        val confirmation = intent.parcelableExtra<Intent>(HelperContract.EXTRA_CONFIRM_INTENT)
        if (confirmation == null) {
            updateStatus("系统确认页面不可用")
            return
        }
        updateStatus("请在系统页面确认安装")
        runCatching { startActivity(confirmation) }
            .onFailure { updateStatus("无法打开系统确认页面") }
    }

    private fun openLinReads() {
        returnLaunchRequested = true
        runCatching {
            startActivity(
                Intent().setClassName(
                    HelperContract.TARGET_PACKAGE,
                    HelperContract.TARGET_ACTIVITY,
                ).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }.onFailure {
            returnLaunchRequested = false
            updateStatus("LinReads 已更新，请返回应用")
        }
    }

    private fun scheduleReturnWhenFocused() {
        if (!returnWhenFocused || returnScheduled || !hasWindowFocus()) return
        returnScheduled = true
        statusView.post {
            returnScheduled = false
            if (!returnWhenFocused || !hasWindowFocus() || isFinishing) return@post
            returnWhenFocused = false
            openLinReads()
        }
    }

    private fun resumePendingTerminalAction() {
        val preferences = helperPreferences()
        if (!preferences.getBoolean(HelperContract.KEY_TERMINAL_PENDING, false)) return
        consumePendingTerminalAction(
            action = preferences.getString(HelperContract.KEY_PENDING_TERMINAL_ACTION, null),
            errorMessage = preferences.getString(HelperContract.KEY_PENDING_ERROR_MESSAGE, null),
        )
    }

    private fun consumePendingTerminalAction(action: String?, errorMessage: String?) {
        if (action !in setOf(HelperContract.ACTION_RETURN_TO_APP, HelperContract.ACTION_INSTALL_FAILED)) return
        helperPreferences().edit()
            .putBoolean(HelperContract.KEY_TERMINAL_CONSUMED, true)
            .putBoolean(HelperContract.KEY_TERMINAL_PENDING, false)
            .remove(HelperContract.KEY_PENDING_TERMINAL_ACTION)
            .remove(HelperContract.KEY_PENDING_ERROR_MESSAGE)
            .commit()
        if (action == HelperContract.ACTION_RETURN_TO_APP) {
            returnWhenFocused = true
            updateStatus("安装完成，正在返回 LinReads")
            scheduleReturnWhenFocused()
        } else {
            updateStatus(errorMessage ?: "更新安装失败")
        }
    }

    private fun updateStatus(message: String) {
        if (!::statusView.isInitialized) return
        runOnUiThread {
            statusView.text = message
        }
    }

    private fun helperPreferences() = getSharedPreferences(HelperContract.PREFS_NAME, MODE_PRIVATE)
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HelperContract.ACTION_INSTALL_STATUS) return
        val preferences = context.getSharedPreferences(HelperContract.PREFS_NAME, Context.MODE_PRIVATE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val nonce = intent.getStringExtra(HelperContract.EXTRA_NONCE)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val installedVersion = installedVersion(context, HelperContract.TARGET_PACKAGE)
        val signerMatches = packageSignerDigests(context, context.packageName) != null &&
            packageSignerDigests(context, context.packageName) ==
            packageSignerDigests(context, HelperContract.TARGET_PACKAGE)
        val action = callbackAction(
            armedSessionId = preferences.getInt(HelperContract.KEY_SESSION_ID, -1),
            armedNonce = preferences.getString(HelperContract.KEY_NONCE, null),
            armedExpectedVersion = preferences.getLong(HelperContract.KEY_EXPECTED_VERSION, -1L),
            armedExpiresAtEpochMs = preferences.getLong(HelperContract.KEY_EXPIRES_AT_EPOCH_MS, -1L),
            callbackSessionId = sessionId,
            callbackNonce = nonce,
            status = status,
            terminalConsumed = preferences.getBoolean(HelperContract.KEY_TERMINAL_CONSUMED, false),
            terminalPending = preferences.getBoolean(HelperContract.KEY_TERMINAL_PENDING, false),
            confirmationDispatched = preferences.getBoolean(
                HelperContract.KEY_CONFIRMATION_DISPATCHED,
                false,
            ),
            nowEpochMs = System.currentTimeMillis(),
            targetSignerMatches = signerMatches,
            installedVersion = installedVersion,
        )
        Log.i(HelperContract.TAG, "callback action=$action status=$status sessionId=$sessionId")
        when (action) {
            CallbackAction.IGNORE -> Unit
            CallbackAction.CONFIRM -> {
                preferences.edit().putBoolean(HelperContract.KEY_CONFIRMATION_DISPATCHED, true).commit()
                showConfirmation(context, intent, preferences)
            }
            CallbackAction.OPEN_LINREADS -> {
                persistPendingTerminalAction(
                    preferences,
                    HelperContract.ACTION_RETURN_TO_APP,
                    errorMessage = null,
                )
                bringHelperTaskForward(context, preferences.getInt(HelperContract.KEY_TASK_ID, -1))
                startHelperActivity(context, HelperContract.ACTION_RETURN_TO_APP)
            }
            CallbackAction.FAIL -> {
                val errorMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "更新安装失败（状态 $status）"
                persistPendingTerminalAction(
                    preferences,
                    HelperContract.ACTION_INSTALL_FAILED,
                    errorMessage,
                )
                bringHelperTaskForward(context, preferences.getInt(HelperContract.KEY_TASK_ID, -1))
                startHelperActivity(
                    context,
                    HelperContract.ACTION_INSTALL_FAILED,
                    errorMessage,
                )
            }
        }
    }

    private fun showConfirmation(
        context: Context,
        callbackIntent: Intent,
        preferences: android.content.SharedPreferences,
    ) {
        val confirmation = callbackIntent.parcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (confirmation == null) {
            val errorMessage = "系统未返回安装确认页面"
            persistPendingTerminalAction(
                preferences,
                HelperContract.ACTION_INSTALL_FAILED,
                errorMessage,
            )
            bringHelperTaskForward(context, preferences.getInt(HelperContract.KEY_TASK_ID, -1))
            startHelperActivity(
                context,
                HelperContract.ACTION_INSTALL_FAILED,
                errorMessage,
            )
            return
        }
        bringHelperTaskForward(context, preferences.getInt(HelperContract.KEY_TASK_ID, -1))
        context.startActivity(
            Intent(context, UpdaterActivity::class.java)
                .setAction(HelperContract.ACTION_CONFIRM_INSTALL)
                .putExtra(HelperContract.EXTRA_CONFIRM_INTENT, confirmation)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
    }
}

internal object HelperContract {
    const val TAG = "LinReadsUpdaterHelper"
    val PROTOCOL_VERSION = BuildConfig.UPDATE_HELPER_PROTOCOL_VERSION
    const val TARGET_PACKAGE = "dev.readflow"
    const val TARGET_ACTIVITY = "dev.readflow.MainActivity"
    const val TARGET_CALLBACK_ACTIVITY = "dev.readflow.updater.UpdateHelperCallbackActivity"

    const val ACTION_PREPARE_CALLBACK = "dev.readflow.updaterhelper.PREPARE_CALLBACK_V1"
    const val ACTION_CALLBACK_READY = "dev.readflow.updater.HELPER_CALLBACK_READY_V1"
    const val ACTION_INSTALL_STATUS = "dev.readflow.updaterhelper.INSTALL_STATUS_V1"
    const val ACTION_CONFIRM_INSTALL = "dev.readflow.updaterhelper.CONFIRM_INSTALL_V1"
    const val ACTION_RETURN_TO_APP = "dev.readflow.updaterhelper.RETURN_TO_APP_V1"
    const val ACTION_INSTALL_FAILED = "dev.readflow.updaterhelper.INSTALL_FAILED_V1"

    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_EXPECTED_VERSION = "expected_version"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_EXPIRES_AT_EPOCH_MS = "expires_at_epoch_ms"
    const val EXTRA_STATUS_SENDER = "status_sender"
    const val EXTRA_CONFIRM_INTENT = "confirm_intent"
    const val EXTRA_ERROR_MESSAGE = "error_message"

    const val PREFS_NAME = "update_helper"
    const val KEY_PROTOCOL_VERSION = "protocol_version"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_EXPECTED_VERSION = "expected_version"
    const val KEY_NONCE = "nonce"
    const val KEY_EXPIRES_AT_EPOCH_MS = "expires_at_epoch_ms"
    const val KEY_TASK_ID = "task_id"
    const val KEY_TERMINAL_CONSUMED = "terminal_consumed"
    const val KEY_TERMINAL_PENDING = "terminal_pending"
    const val KEY_PENDING_TERMINAL_ACTION = "pending_terminal_action"
    const val KEY_PENDING_ERROR_MESSAGE = "pending_error_message"
    const val KEY_CONFIRMATION_DISPATCHED = "confirmation_dispatched"
    const val CALLBACK_TTL_MS = 30L * 60L * 1_000L
}

private fun persistPendingTerminalAction(
    preferences: android.content.SharedPreferences,
    action: String,
    errorMessage: String?,
) {
    val editor = preferences.edit()
        .putBoolean(HelperContract.KEY_TERMINAL_PENDING, true)
        .putString(HelperContract.KEY_PENDING_TERMINAL_ACTION, action)
    if (errorMessage == null) {
        editor.remove(HelperContract.KEY_PENDING_ERROR_MESSAGE)
    } else {
        editor.putString(HelperContract.KEY_PENDING_ERROR_MESSAGE, errorMessage)
    }
    editor.commit()
}

private fun startHelperActivity(context: Context, action: String, errorMessage: String? = null) {
    runCatching {
        context.startActivity(
            Intent(context, UpdaterActivity::class.java)
                .setAction(action)
                .apply {
                    if (errorMessage != null) putExtra(HelperContract.EXTRA_ERROR_MESSAGE, errorMessage)
                }
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
    }.onFailure { error ->
        Log.e(HelperContract.TAG, "helper task launch failed action=$action", error)
    }
}

private fun bringHelperTaskForward(context: Context, taskId: Int) {
    if (taskId < 0) return
    runCatching { context.getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0) }
        .onFailure { error -> Log.e(HelperContract.TAG, "moveTaskToFront failed taskId=$taskId", error) }
}

private fun installedVersion(context: Context, packageName: String): Long? = runCatching {
    val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
    if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}.getOrNull()

private fun packageSignerDigests(context: Context, packageName: String): Set<String>? = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
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
