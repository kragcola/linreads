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
        super.onCreate(savedInstanceState)
        updateIncomingBook(intent)
        enableEdgeToEdge()
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
