package dev.readflow.ui

import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector

private enum class Tab(val label: String, val icon: ImageVector) {
    Library("书库", Icons.Default.LibraryBooks),
    Reading("阅读", Icons.Default.MenuBook),
    Settings("设置", Icons.Default.Settings),
}

@Composable
fun ReadflowApp() {
    var selected by remember { mutableStateOf(Tab.Library) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { _ ->
        when (selected) {
            Tab.Library -> LibraryScreen()
            Tab.Reading -> Text("阅读中")
            Tab.Settings -> SettingsScreen()
        }
    }
}

@Composable fun LibraryScreen() = Text("书库 — 连接 Calibre")
@Composable fun SettingsScreen() = Text("设置")
