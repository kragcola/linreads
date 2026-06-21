package dev.readflow.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.readflow.core.ui.ReadflowTheme
import dev.readflow.features.library.LibraryScreen

/**
 * Phase 1 app shell: foundation only. Single placeholder route to the library screen
 * (no reader — rendering lands in Phase 2).
 */
@Composable
fun ReadflowApp(
    incomingBookUri: Uri? = null,
    onIncomingBookConsumed: () -> Unit = {},
) {
    LaunchedEffect(incomingBookUri) {
        if (incomingBookUri != null) onIncomingBookConsumed()
    }

    ReadflowTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    onOpenBook = {},
                    onSettings = {},
                )
            }
        }
    }
}
