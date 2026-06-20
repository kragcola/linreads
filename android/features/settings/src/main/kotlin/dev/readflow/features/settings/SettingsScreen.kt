package dev.readflow.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readflow.core.model.ThemeMode
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** Returns APK download URL if update available, null if already latest. */
    onCheckForUpdate: suspend () -> String? = { null },
    /** Called with the APK URL when the user confirms download. */
    onStartDownload: (String) -> Unit = {},
) {
    val vm = koinViewModel<SettingsViewModel>()
    val url by vm.calibreBaseUrl.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()

    var urlDraft by remember(url) { mutableStateOf(url ?: "") }

    // Update check state
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Calibre 服务器地址") },
                placeholder = { Text("http://192.168.1.x:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (urlDraft != (url ?: "")) {
                        TextButton(onClick = { vm.setCalibreUrl(urlDraft) }) { Text("保存") }
                    }
                },
            )

            Text("字号：${fontSize}sp", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { vm.setFontSize(it.toInt()) },
                valueRange = 12f..28f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "这是 ${fontSize}sp 的正文效果。The quick brown fox.",
                fontSize = fontSize.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text("主题", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }

            HorizontalDivider()

            // Update check
            when (val s = updateState) {
                UpdateState.Idle -> Button(onClick = {
                    scope.launch {
                        updateState = UpdateState.Checking
                        runCatching { onCheckForUpdate() }
                            .onSuccess { apkUrl -> updateState = if (apkUrl != null) UpdateState.Available(apkUrl) else UpdateState.UpToDate }
                            .onFailure { updateState = UpdateState.Error(it.message ?: "检查失败") }
                    }
                }) { Text("检查更新") }

                UpdateState.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("检查中…", style = MaterialTheme.typography.bodyMedium)
                }

                UpdateState.UpToDate -> Text("✓ 已是最新版本", style = MaterialTheme.typography.bodyMedium)

                is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("发现新版本，点击下载安装", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onStartDownload(s.apkUrl) }) { Text("下载并安装") }
                }

                is UpdateState.Error -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✗ ${s.msg}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { updateState = UpdateState.Idle }) { Text("重试") }
                }
            }
        }
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val apkUrl: String) : UpdateState
    data class Error(val msg: String) : UpdateState
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT  -> "日间"
    ThemeMode.DARK   -> "夜间"
    ThemeMode.SEPIA  -> "护眼"
}
