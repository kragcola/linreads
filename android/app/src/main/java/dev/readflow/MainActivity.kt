package dev.readflow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.readflow.ui.ReadflowApp

class MainActivity : ComponentActivity() {
    private var incomingBookUri: Uri? by mutableStateOf(null)
    private var incomingBookMimeType: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge before super so decor fits system windows is cleared before content attaches.
        // Default styles: transparent status bar (API 29+); navigation keeps platform contrast scrim
        // on 3-button devices. ReaderScreen further scopes paper continuity + icon appearance.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        updateIncomingBook(intent)
        setContent {
            ReadflowApp(
                incomingBookUri = incomingBookUri,
                incomingBookMimeType = incomingBookMimeType,
                onIncomingBookConsumed = {
                    incomingBookUri = null
                    incomingBookMimeType = null
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateIncomingBook(intent)
    }

    override fun onResume() {
        super.onResume()
        acknowledgeHelperReturn(intent)
    }

    private fun acknowledgeHelperReturn(intent: Intent) {
        val request = helperReturnRequest(
            action = intent.action,
            sessionId = intent.getIntExtra(EXTRA_HELPER_RETURN_SESSION_ID, -1),
            expectedVersion = intent.getLongExtra(EXTRA_HELPER_RETURN_EXPECTED_VERSION, -1L),
            nonce = intent.getStringExtra(EXTRA_HELPER_RETURN_NONCE),
        ) ?: return
        sendBroadcast(
            Intent().setClassName(
                UPDATE_HELPER_PACKAGE_NAME,
                UPDATE_HELPER_ACK_RECEIVER,
            ).setAction(ACTION_HELPER_RETURN_ACK)
                .putExtra(EXTRA_HELPER_RETURN_SESSION_ID, request.sessionId)
                .putExtra(EXTRA_HELPER_RETURN_EXPECTED_VERSION, request.expectedVersion)
                .putExtra(EXTRA_HELPER_RETURN_NONCE, request.nonce),
        )
    }

    private fun updateIncomingBook(intent: Intent) {
        incomingBookUri = intent.extractIncomingBookUri()
        incomingBookMimeType = if (incomingBookUri != null) intent.type else null
        Log.i(
            IMPORT_TRACE_TAG,
            "incoming action=${intent.action} type=${intent.type} " +
                "hasData=${intent.data != null} resolved=${incomingBookUri != null} " +
                "scheme=${incomingBookUri?.scheme}",
        )
    }

    private companion object {
        const val IMPORT_TRACE_TAG = "ReadflowImportTrace"
    }
}
