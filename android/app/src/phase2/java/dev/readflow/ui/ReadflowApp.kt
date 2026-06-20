package dev.readflow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.ui.ReadflowTheme
import dev.readflow.features.library.LibraryScreen
import dev.readflow.features.reader.ReaderIntent
import dev.readflow.features.reader.ReaderScreen
import dev.readflow.features.reader.ReaderViewModel
import dev.readflow.features.settings.SettingsScreen
import dev.readflow.features.settings.SettingsViewModel
import dev.readflow.updater.AppUpdateManager
import dev.readflow.updater.UpdateInstallReceiver
import org.koin.androidx.compose.koinViewModel

@Composable
fun ReadflowApp() {
    val context = LocalContext.current

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showInstallPermDialog by remember {
        mutableStateOf(!context.packageManager.canRequestPackageInstalls())
    }
    LifecycleResumeEffect(Unit) {
        showInstallPermDialog = !context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose {}
    }

    // Observe theme from settings so changes take effect immediately.
    val themeVm = koinViewModel<SettingsViewModel>()
    val themeMode by themeVm.themeMode.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode == ThemeMode.DARK || (themeMode == ThemeMode.SYSTEM && systemDark)
    val sepiaTheme = themeMode == ThemeMode.SEPIA

    ReadflowTheme(darkTheme = darkTheme, sepiaTheme = sepiaTheme) {
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
                LaunchedEffect(bookId) { vm.onIntent(ReaderIntent.OpenById(bookId)) }
                ReaderScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onCheckForUpdate = { AppUpdateManager.checkForUpdate() },
                    onStartDownload = { apkUrl ->
                        context.sendBroadcast(
                            Intent(context, UpdateInstallReceiver::class.java)
                                .putExtra("apk_url", apkUrl)
                                .putExtra("tag_name", "dev-latest")
                        )
                    },
                )
            }
        }

        if (showInstallPermDialog) {
            AlertDialog(
                onDismissRequest = { showInstallPermDialog = false },
                title = { Text("需要安装权限") },
                text = { Text("OTA 自动更新需要「安装未知来源应用」权限，请在设置中允许 LinReads 安装应用。") },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        showInstallPermDialog = false
                    }) { Text("前往设置") }
                },
                dismissButton = {
                    TextButton(onClick = { showInstallPermDialog = false }) { Text("暂不") }
                },
            )
        }
    }
}
