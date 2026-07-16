package dev.readflow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    private fun updateIncomingBook(intent: Intent) {
        incomingBookUri = intent.extractIncomingBookUri()
        incomingBookMimeType = if (incomingBookUri != null) intent.type else null
    }
}
