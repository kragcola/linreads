package dev.readflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.readflow.core.ui.ReadflowTheme
import dev.readflow.features.library.LibraryScreen
import dev.readflow.features.reader.ReaderIntent
import dev.readflow.features.reader.ReaderScreen
import dev.readflow.features.reader.ReaderViewModel
import dev.readflow.features.settings.SettingsScreen
import dev.readflow.updater.AppUpdateManager
import org.koin.androidx.compose.koinViewModel

@Composable
fun ReadflowApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Check for updates every time the app comes to foreground.
    LifecycleResumeEffect(Unit) {
        AppUpdateManager.checkOnForeground(context, scope)
        onPauseOrDispose {}
    }

    ReadflowTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                    onSettings = { navController.navigate("settings") },
                )
            }
            composable("reader/{bookId}") { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                val vm = koinViewModel<ReaderViewModel>()
                androidx.compose.runtime.LaunchedEffect(bookId) {
                    vm.onIntent(ReaderIntent.OpenById(bookId))
                }
                ReaderScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
