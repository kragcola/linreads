package dev.readflow.ui

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.ui.ReadflowTheme
import dev.readflow.extensions.api.LocalFileBookSource
import dev.readflow.features.library.LibraryScreen
import dev.readflow.features.reader.ReaderIntent
import dev.readflow.features.reader.ReaderScreen
import dev.readflow.features.reader.ReaderViewModel
import dev.readflow.features.settings.SettingsScreen
import dev.readflow.features.settings.SettingsViewModel
import dev.readflow.updater.AppUpdateManager
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext

@Composable
fun ReadflowApp(
    incomingBookUri: Uri? = null,
    incomingBookMimeType: String? = null,
    onIncomingBookConsumed: () -> Unit = {},
) {
    val context = LocalContext.current

    // Observe theme from settings so changes take effect immediately.
    val themeVm = koinViewModel<SettingsViewModel>()
    val themeMode by themeVm.themeMode.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    // The reading presets (see ReaderPalette) decide the app chrome too: any night preset → dark
    // chrome, SEPIA keeps its dedicated parchment chrome, the rest → light chrome.
    val sepiaTheme = themeMode == ThemeMode.SEPIA
    val darkTheme = !sepiaTheme && readerPaletteFor(themeMode, systemDark).isNight

    ReadflowTheme(darkTheme = darkTheme, sepiaTheme = sepiaTheme) {
        val navController = rememberNavController()
        val localSource = remember { GlobalContext.get().get<LocalFileBookSource>() }
        val libraryRepository = remember { GlobalContext.get().get<LibraryRepository>() }
        var incomingImportError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(incomingBookUri) {
            val uri = incomingBookUri ?: return@LaunchedEffect
            when (val result = localSource.import(uri, incomingBookMimeType)) {
                is ReadflowResult.Success -> {
                    val book = result.value.first
                    libraryRepository.upsertBook(book)
                    navController.navigate("reader/${book.id}") {
                        launchSingleTop = true
                    }
                }
                is ReadflowResult.Failure -> {
                    incomingImportError = result.error.message
                }
            }
            onIncomingBookConsumed()
        }

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
                LaunchedEffect(bookId) { vm.onIntent(ReaderIntent.OpenById(bookId)) }
                ReaderScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onCheckForUpdate = { AppUpdateManager.checkForUpdate(context) },
                    cachedNotes = AppUpdateManager.getCachedNotes(context),
                    authToken = dev.readflow.BuildConfig.GITHUB_OTA_TOKEN,
                    buildTag = dev.readflow.BuildConfig.BUILD_TAG,
                )
            }
        }

        incomingImportError?.let { message ->
            AlertDialog(
                onDismissRequest = { incomingImportError = null },
                title = { Text("无法打开文件") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { incomingImportError = null }) {
                        Text("知道了")
                    }
                },
            )
        }

    }
}
