package dev.readflow.features.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readflow.core.model.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCheckForUpdate: suspend () -> Pair<String, String>? = { null },
    authToken: String = "",
    buildTag: String = "",
) {
    val vm = koinViewModel<SettingsViewModel>()
    val url by vm.calibreBaseUrl.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var urlDraft by remember(url) { mutableStateOf(url ?: "") }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // Poll DownloadManager progress while downloading
    if (updateState is UpdateState.Downloading) {
        val dlId = (updateState as UpdateState.Downloading).dlId
        LaunchedEffect(dlId) {
            val dm = context.getSystemService(DownloadManager::class.java)
            while (true) {
                delay(600)
                val q = dm.query(DownloadManager.Query().setFilterById(dlId))
                if (!q.moveToFirst()) { q.close(); updateState = UpdateState.Error("下载失败"); break }
                val status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val soFar = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                q.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getUriForDownloadedFile(dlId)
                        if (uri != null) { launchInstaller(context, uri); updateState = UpdateState.ReadyToInstall(uri) }
                        else updateState = UpdateState.Error("安装包丢失")
                        break
                    }
                    DownloadManager.STATUS_FAILED -> { updateState = UpdateState.Error("下载失败"); break }
                    else -> updateState = UpdateState.Downloading(
                        progress = if (total > 0) soFar.toFloat() / total else -1f, dlId = dlId,
                    )
                }
            }
        }
    }

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
                value = urlDraft, onValueChange = { urlDraft = it },
                label = { Text("Calibre 服务器地址") },
                placeholder = { Text("http://192.168.1.x:8080") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                trailingIcon = {
                    if (urlDraft != (url ?: ""))
                        TextButton(onClick = { vm.setCalibreUrl(urlDraft) }) { Text("保存") }
                },
            )

            Text("字号：${fontSize}sp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = fontSize.toFloat(), onValueChange = { vm.setFontSize(it.toInt()) },
                valueRange = 12f..28f, steps = 7, modifier = Modifier.fillMaxWidth())
            Text("这是 ${fontSize}sp 的正文效果。The quick brown fox.",
                fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onBackground)

            Text("主题", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = theme == mode, onClick = { vm.setTheme(mode) },
                        label = { Text(mode.label()) })
                }
            }

            HorizontalDivider()

            // 诊断：显示当前构建标识
            val buildNum = buildTag.removePrefix("dev-").substringBefore("-").takeIf { it.all { c -> c.isDigit() } }
            val tagDisplay = if (buildNum != null) "构建 #$buildNum" else buildTag
            Text(
                text = tagDisplay,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )

            when (val s = updateState) {
                UpdateState.Idle -> Button(onClick = {
                    scope.launch {
                        updateState = UpdateState.Checking
                        runCatching { onCheckForUpdate() }
                            .onSuccess { result ->
                                updateState = if (result != null) UpdateState.Available(result.first, result.second)
                                else UpdateState.UpToDate
                            }
                            .onFailure { updateState = UpdateState.Error(it.message ?: "检查失败") }
                    }
                }) { Text("检查更新") }

                UpdateState.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("检查中…", style = MaterialTheme.typography.bodyMedium)
                }

                UpdateState.UpToDate -> Text("✓ 已是最新版本", style = MaterialTheme.typography.bodyMedium)

                is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("发现新版本，点击下载安装", style = MaterialTheme.typography.bodyMedium)
                    if (s.notes.isNotBlank()) {
                        Text(
                            text = s.notes,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                        )
                    }
                    Button(
                        onClick = {
                            val dlId = context.startFreshDownload(s.apkUrl, authToken)
                            updateState = UpdateState.Downloading(progress = -1f, dlId = dlId)
                        },
                        enabled = true
                    ) { Text("下载并安装") }
                }

                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.progress < 0f) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("下载中…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("下载中 ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(
                        onClick = {
                            context.clearDownloadState()
                            updateState = UpdateState.Idle
                        }
                    ) { Text("取消下载") }
                }

                is UpdateState.ReadyToInstall -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("下载完成", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        launchInstaller(context, s.uri)
                        context.clearDownloadState()
                    }) { Text("安装") }
                }

                is UpdateState.Error -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✗ ${s.msg}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { updateState = UpdateState.Idle }) { Text("重试") }
                }
            }
        }
    }
}

/**
 * Always start a fresh download — GitHub release URL never changes between builds
 * so we cannot rely on URL comparison for version detection.
 * Cleans up any previous download before starting a new one.
 * After installation, caller should call clearDownloadState() to wipe the dl_id.
 */
private fun Context.startFreshDownload(apkUrl: String, authToken: String): Long {
    val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
    val dm = getSystemService(DownloadManager::class.java)

    // 清理所有旧下载记录和文件
    val oldId = prefs.getLong("dl_id", -1L)
    if (oldId != -1L) {
        dm.remove(oldId)
    }

    val dlId = dm.enqueue(
        DownloadManager.Request(Uri.parse(apkUrl)).apply {
            if (authToken.isNotEmpty()) addRequestHeader("Authorization", "Bearer $authToken")
            setTitle("LinReads 更新下载中")
            setDescription("正在下载新版本…")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@startFreshDownload, null, "update.apk")
        }
    )
    prefs.edit().putLong("dl_id", dlId).apply()
    return dlId
}

/** 安装完成后调用，清除下载状态防止下次误用旧包 */
fun Context.clearDownloadState() {
    val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
    val oldId = prefs.getLong("dl_id", -1L)
    if (oldId != -1L) {
        getSystemService(DownloadManager::class.java).remove(oldId)
    }
    prefs.edit().remove("dl_id").apply()
}

private fun launchInstaller(context: Context, uri: Uri) {
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val apkUrl: String, val notes: String = "") : UpdateState
    data class Downloading(val progress: Float, val dlId: Long) : UpdateState
    data class ReadyToInstall(val uri: Uri) : UpdateState
    data class Error(val msg: String) : UpdateState
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT  -> "日间"
    ThemeMode.DARK   -> "夜间"
    ThemeMode.SEPIA  -> "护眼"
}
