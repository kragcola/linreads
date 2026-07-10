package dev.readflow.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.model.BookAssetOperationCoordinator
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
import dev.readflow.updater.ForegroundUpdateCheckGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext

@Composable
fun ReadflowApp(
    incomingBookUri: Uri? = null,
    incomingBookMimeType: String? = null,
    onIncomingBookConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    ForegroundUpdateCheckEffect(AppUpdateManager::checkOnForeground)

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
        val assetOperations = remember { GlobalContext.get().get<BookAssetOperationCoordinator>() }
        var incomingImportError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(incomingBookUri) {
            val uri = incomingBookUri ?: return@LaunchedEffect
            try {
                assetOperations.produce(bookId = null) {
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
                }
            } finally {
                onIncomingBookConsumed()
            }
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
                val scope = rememberCoroutineScope()
                var isClosingReader by remember(bookId) { mutableStateOf(false) }
                fun closeReader() {
                    if (isClosingReader) return
                    isClosingReader = true
                    scope.launch {
                        try {
                            vm.closeBook()
                        } finally {
                            navController.popBackStack()
                        }
                    }
                }
                LaunchedEffect(bookId) { vm.onIntent(ReaderIntent.OpenById(bookId)) }
                BackHandler(onBack = ::closeReader)
                ReaderScreen(
                    viewModel = vm,
                    onBack = ::closeReader,
                )
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

@Composable
private fun ForegroundUpdateCheckEffect(
    onCheck: (Context, CoroutineScope) -> Job,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentOnCheck by rememberUpdatedState(onCheck)

    DisposableEffect(context, lifecycleOwner) {
        val gate = ForegroundUpdateCheckGate {
            currentOnCheck(context, scope)
        }
        val observer = LifecycleEventObserver { _, event -> gate.onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gate.dispose()
        }
    }
}
