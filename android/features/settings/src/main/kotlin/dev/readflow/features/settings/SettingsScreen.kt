package dev.readflow.features.settings

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.readerThemeLabel
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.core.ui.AccessibleSlider
import dev.readflow.core.ui.FontProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCheckForUpdate: suspend (Context) -> Pair<String, String>? = { null },
    cachedNotes: String = "",
    authToken: String = "",
    buildTag: String = "",
) {
    val vm = koinViewModel<SettingsViewModel>()
    val url by vm.calibreBaseUrl.collectAsStateWithLifecycle()
    val urlError by vm.calibreUrlError.collectAsStateWithLifecycle()
    val connectionState by vm.calibreConnectionState.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val lineSpacing by vm.lineSpacing.collectAsStateWithLifecycle()
    val readingMode by vm.readingMode.collectAsStateWithLifecycle()
    val txtEncoding by vm.txtEncoding.collectAsStateWithLifecycle()
    val fontChoice by vm.fontChoice.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val backupExportState by vm.backupExportState.collectAsStateWithLifecycle()
    val backupRestoreState by vm.backupRestoreState.collectAsStateWithLifecycle()
    val notesExportState by vm.notesExportState.collectAsStateWithLifecycle()
    val themeExportState by vm.themeExportState.collectAsStateWithLifecycle()
    val themeImportState by vm.themeImportState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var urlDraft by remember(url) { mutableStateOf(url ?: "") }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var pendingUpdateDownload by remember { mutableStateOf<UpdateState.Available?>(null) }
    fun startUpdateDownload(update: UpdateState.Available) {
        val dlId = context.startFreshDownload(update.apkUrl, authToken)
        updateState = UpdateState.Downloading(progress = -1f, dlId = dlId)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingUpdateDownload?.let(::startUpdateDownload)
        pendingUpdateDownload = null
    }
    val isBackupBusy = backupExportState is BackupExportUiState.Exporting ||
        backupRestoreState is BackupRestoreUiState.Restoring
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val output = context.contentResolver.openOutputStream(uri)
        if (output == null) {
            vm.backupExportFailed("导出失败：无法写入选择的位置")
        } else {
            vm.exportBackup(output)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            vm.backupRestoreFailed("恢复失败：无法读取选择的文件")
        } else {
            vm.restoreBackup(input)
        }
    }
    val notesExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val output = context.contentResolver.openOutputStream(uri)
        if (output == null) {
            vm.backupExportFailed("笔记导出失败：无法写入选择的位置")
        } else {
            vm.exportNotes(output)
        }
    }
    val themeExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val output = context.contentResolver.openOutputStream(uri)
        if (output == null) {
            vm.backupExportFailed("主题导出失败：无法写入选择的位置")
        } else {
            vm.exportTheme(output)
        }
    }
    val themeImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            vm.backupRestoreFailed("主题导入失败：无法读取选择的文件")
        } else {
            vm.importTheme(input)
        }
    }
    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cr = context.contentResolver
        val rawName = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment
        val safeName = rawName?.let { FontProvider.sanitizeFontFileName(it) }
            ?: return@rememberLauncherForActivityResult  // 非法/非 ttf,otf 文件名，忽略
        val dest = java.io.File(FontProvider.customFontsDir(context.applicationContext), safeName)
        val input = cr.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFont(input, dest, FontChoice.Custom(safeName))
    }


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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        SettingsPageLayout(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            primaryColumn = {
            SettingsSection(
                title = "书源连接",
                description = "连接局域网中的 Calibre 内容服务器。",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.Link,
                title = "Calibre 服务器",
                currentValue = if (url.isNullOrBlank()) "未配置" else "已配置",
                description = "输入完整地址，或从主机地址探测常用端口。",
            )
            OutlinedTextField(
                value = urlDraft,
                onValueChange = {
                    urlDraft = it
                    vm.clearCalibreConnectionState()
                },
                label = { Text("Calibre 服务器地址") },
                placeholder = { Text("http://192.168.1.x:8080") },
                isError = urlError != null,
                supportingText = {
                    if (urlError != null) {
                        Text(urlError.orEmpty())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.extraSmall,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.testCalibreConnection(urlDraft) },
                    enabled = connectionState !is CalibreConnectionUiState.Checking,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("测试连接")
                }
                OutlinedButton(
                    onClick = { vm.probeCalibreConnection(urlDraft) },
                    enabled = connectionState !is CalibreConnectionUiState.Checking,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("探测常用端口")
                }
                if (urlDraft != (url ?: "")) {
                    TextButton(
                        onClick = { vm.setCalibreUrl(urlDraft) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("仅保存") }
                }
            }
            when (val state = connectionState) {
                CalibreConnectionUiState.Idle -> Unit
                CalibreConnectionUiState.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在测试 Calibre 连接", style = MaterialTheme.typography.bodyMedium)
                }
                is CalibreConnectionUiState.Success -> ConnectionResultText(
                    title = state.message,
                    detail = state.nextStep,
                    isError = false,
                )
                is CalibreConnectionUiState.Failure -> ConnectionResultText(
                    title = state.message,
                    detail = state.nextStep,
                    isError = true,
                )
            }
            }

            SettingsSection(
                title = "阅读体验",
                description = "调整排版、翻页方式、字体与文本解析。",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.TextFields,
                title = "正文字号",
                currentValue = "${fontSize}sp",
                description = "跟随系统显示缩放，并即时应用到阅读器。",
            )
            AccessibleSlider(
                value = ReaderTypography.clampFontSp(fontSize.toFloat()),
                onValueChange = { vm.setFontSize(it.toInt()) },
                valueRange = ReaderTypography.MIN_FONT_SP..ReaderTypography.MAX_FONT_SP,
                steps = ReaderTypography.FONT_SLIDER_STEPS,
                label = "字号",
                valueDescription = "${fontSize}sp",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            )
            Text(
                text = "这是 ${fontSize}sp 的正文效果。The quick brown fox.",
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.7f).sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )

            SettingItemHeader(
                icon = Icons.Outlined.FormatLineSpacing,
                title = "正文行距",
                currentValue = "${"%.2f".format(lineSpacing)}x",
                description = "增加行间留白可减轻长时间阅读疲劳。",
            )
            AccessibleSlider(
                value = ReaderTypography.clampLineSpacing(lineSpacing),
                onValueChange = { vm.setLineSpacing(it) },
                valueRange = ReaderTypography.MIN_LINE_SPACING..ReaderTypography.MAX_LINE_SPACING,
                steps = ReaderTypography.LINE_SPACING_SLIDER_STEPS,
                label = "行距",
                valueDescription = "${"%.2f".format(lineSpacing)}倍",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            )

            SettingItemHeader(
                icon = Icons.Outlined.ViewCarousel,
                title = "阅读模式",
                currentValue = readingMode.label(),
                description = "在连续滚动与分页阅读之间切换。",
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReaderReadingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = readingMode == mode,
                        onClick = { vm.setReadingMode(mode) },
                        label = { Text(mode.label()) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            var customFonts by remember { mutableStateOf(emptyList<String>()) }
            // key 于 fontChoice：导入成功会更新 fontChoice，从而刷新列表显示新字体
            LaunchedEffect(fontChoice) {
                customFonts = FontProvider.listCustomFonts(context.applicationContext)
            }
            SettingItemHeader(
                icon = Icons.Outlined.FontDownload,
                title = "正文字体",
                currentValue = fontChoice.label(),
                description = "使用内置字体、系统字体或导入本地字体文件。",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = fontChoice == FontChoice.System, onClick = { vm.setFontChoice(FontChoice.System) },
                        label = { Text("系统宋体") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = fontChoice == FontChoice.SourceHan, onClick = { vm.setFontChoice(FontChoice.SourceHan) },
                        label = { Text("思源宋体（内置）") }, modifier = Modifier.heightIn(min = 48.dp))
                    customFonts.forEach { name ->
                        val c = FontChoice.Custom(name)
                        FilterChip(selected = fontChoice == c, onClick = { vm.setFontChoice(c) },
                            label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedButton(
                    onClick = {
                        fontImportLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream"))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入字体")
                }
            }

            SettingItemHeader(
                icon = Icons.Outlined.Code,
                title = "TXT 编码",
                currentValue = txtEncoding.label(),
                description = "自动识别失败时，可手动指定文本编码。",
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TxtEncoding.entries.forEach { enc ->
                    FilterChip(selected = txtEncoding == enc, onClick = { vm.setTxtEncoding(enc) },
                        label = { Text(enc.label()) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }

            SettingItemHeader(
                icon = Icons.Outlined.Palette,
                title = "界面主题",
                currentValue = theme.readerThemeLabel(),
                description = "可跟随系统，或固定使用日间、夜间与护眼主题。",
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = theme == mode, onClick = { vm.setTheme(mode) },
                        label = { Text(mode.readerThemeLabel()) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            }
            },
            secondaryColumn = {

            SettingsSection(
                title = "同步与备份",
                description = "查看同步状态，并迁移本机阅读数据。",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.Sync,
                title = "阅读数据同步",
                currentValue = if (syncStatus.isRemoteSyncEnabled) "已启用" else "仅本机",
                description = "进度、书签与标注的当前保存范围。",
            )
            ConnectionResultText(
                title = syncStatus.title,
                detail = syncStatus.detail,
                isError = !syncStatus.isRemoteSyncEnabled,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingItemHeader(
                icon = Icons.Outlined.ImportExport,
                title = "完整备份",
                currentValue = "ZIP",
                description = "导出或合并进度、书签和标注。",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { backupLauncher.launch("LinReads Backup.zip") },
                    enabled = !isBackupBusy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导出备份")
                }
                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-zip-compressed",
                            ),
                        )
                    },
                    enabled = !isBackupBusy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("恢复备份")
                }
            }
            when (val state = backupExportState) {
                BackupExportUiState.Idle -> Unit
                BackupExportUiState.Exporting -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在导出备份", style = MaterialTheme.typography.bodyMedium)
                }
                is BackupExportUiState.Success -> ConnectionResultText(
                    title = state.message,
                    detail = "备份文件包含进度、书签、标注 manifest。",
                    isError = false,
                )
                is BackupExportUiState.Failure -> ConnectionResultText(
                    title = state.message,
                    detail = "请重新选择可写入的位置。",
                    isError = true,
                )
            }
            when (val state = backupRestoreState) {
                BackupRestoreUiState.Idle -> Unit
                BackupRestoreUiState.Restoring -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在恢复备份", style = MaterialTheme.typography.bodyMedium)
                }
                is BackupRestoreUiState.Success -> ConnectionResultText(
                    title = state.message,
                    detail = "已按更新时间合并进度、书签和标注，本地书库不会被覆盖。",
                    isError = false,
                )
                is BackupRestoreUiState.Failure -> ConnectionResultText(
                    title = state.message,
                    detail = "请选择 LinReads Backup ZIP 文件。",
                    isError = true,
                )
            }
            }

            SettingsSection(
                title = "数据与主题",
                description = "导出笔记，或在设备间复用排版方案。",
            ) {
            SettingItemHeader(
                icon = Icons.AutoMirrored.Outlined.Notes,
                title = "阅读笔记",
                currentValue = "Markdown",
                description = "按书籍导出书签与标注，便于归档和检索。",
            )
            OutlinedButton(
                onClick = { notesExportLauncher.launch("LinReads 笔记.md") },
                enabled = notesExportState !is BackupExportUiState.Exporting,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导出阅读笔记")
            }
            when (val state = notesExportState) {
                BackupExportUiState.Idle -> Unit
                BackupExportUiState.Exporting -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在导出笔记", style = MaterialTheme.typography.bodyMedium)
                }
                is BackupExportUiState.Success -> ConnectionResultText(
                    title = state.message,
                    detail = "Markdown 格式，含书签与标注。",
                    isError = false,
                )
                is BackupExportUiState.Failure -> ConnectionResultText(
                    title = state.message,
                    detail = "请重新选择可写入的位置。",
                    isError = true,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingItemHeader(
                icon = Icons.Outlined.Palette,
                title = "主题方案",
                currentValue = "JSON",
                description = "导出当前排版，或从其他设备导入。",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { themeExportLauncher.launch("LinReads 主题.json") },
                    enabled = themeExportState !is BackupExportUiState.Exporting,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导出主题")
                }
                OutlinedButton(
                    onClick = { themeImportLauncher.launch(arrayOf("application/json")) },
                    enabled = themeImportState !is BackupRestoreUiState.Restoring,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入主题")
                }
            }
            when (val state = themeExportState) {
                BackupExportUiState.Idle -> Unit
                BackupExportUiState.Exporting -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在导出主题", style = MaterialTheme.typography.bodyMedium)
                }
                is BackupExportUiState.Success -> ConnectionResultText(
                    title = state.message,
                    detail = "可在其他设备导入此 JSON 恢复排版设置。",
                    isError = false,
                )
                is BackupExportUiState.Failure -> ConnectionResultText(
                    title = state.message,
                    detail = "请重新选择可写入的位置。",
                    isError = true,
                )
            }
            when (val state = themeImportState) {
                BackupRestoreUiState.Idle -> Unit
                BackupRestoreUiState.Restoring -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在导入主题", style = MaterialTheme.typography.bodyMedium)
                }
                is BackupRestoreUiState.Success -> ConnectionResultText(
                    title = state.message,
                    detail = "排版设置已更新。",
                    isError = false,
                )
                is BackupRestoreUiState.Failure -> ConnectionResultText(
                    title = state.message,
                    detail = "请选择有效的主题 JSON 文件。",
                    isError = true,
                )
            }
            }

            SettingsSection(
                title = "关于与更新",
                description = "版本信息、更新记录与安装状态。",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.Info,
                title = "LinReads Android",
                currentValue = buildTag.displayBuildLabel().ifBlank { "开发版本" },
                description = "本机安装版本。",
            )
            // 始终显示缓存的更新日志（中文 commit message）
            if (cachedNotes.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "更新日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        text = cachedNotes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            SettingItemHeader(
                icon = Icons.Outlined.SystemUpdate,
                title = "应用更新",
                currentValue = updateState.label(),
                description = "从已配置的发布源检查并安装新版本。",
            )

            when (val s = updateState) {
                UpdateState.Idle -> Button(
                    onClick = {
                        scope.launch {
                            updateState = UpdateState.Checking
                            runCatching { onCheckForUpdate(context) }
                                .onSuccess { result ->
                                    updateState = if (result != null) UpdateState.Available(result.first, result.second)
                                    else UpdateState.UpToDate
                                }
                                .onFailure { updateState = UpdateState.Error(it.message ?: "检查失败") }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("检查更新")
                }

                UpdateState.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("检查中…", style = MaterialTheme.typography.bodyMedium)
                }

                UpdateState.UpToDate -> ConnectionResultText(
                    title = "已是最新版本",
                    detail = "当前无需更新。",
                    isError = false,
                )

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
                            if (!context.packageManager.canRequestPackageInstalls()) {
                                context.openUnknownAppSourcesSettings()
                                updateState = UpdateState.Error("请先允许 LinReads 安装未知来源应用，然后返回重试")
                            } else if (context.shouldRequestUpdateNotificationPermission()) {
                                pendingUpdateDownload = s
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startUpdateDownload(s)
                            }
                        },
                        enabled = true,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("下载并安装")
                    }
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
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("取消下载") }
                }

                is UpdateState.ReadyToInstall -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("下载完成", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        launchInstaller(context, s.uri)
                        context.clearDownloadState()
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("安装") }
                }

                is UpdateState.Error -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = s.msg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { updateState = UpdateState.Idle },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("重试") }
                }
            }
            }
            Spacer(Modifier.height(12.dp))
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SettingsPageLayout(
    modifier: Modifier = Modifier,
    primaryColumn: @Composable ColumnScope.() -> Unit,
    secondaryColumn: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val layoutMode = settingsLayoutMode(maxWidth.value)
        val horizontalPadding = if (layoutMode == SettingsLayoutMode.TWO_COLUMNS) 24.dp else 16.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 1080.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 12.dp),
        ) {
            if (layoutMode == SettingsLayoutMode.TWO_COLUMNS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        content = primaryColumn,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        content = secondaryColumn,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    primaryColumn()
                    secondaryColumn()
                }
            }
        }
    }
}

@Composable
private fun SettingItemHeader(
    icon: ImageVector,
    title: String,
    currentValue: String? = null,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                currentValue?.let { stateDescription = "当前值：$it" }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 28.dp, height = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        currentValue?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 144.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConnectionResultText(title: String, detail: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = if (isError) "需要处理" else "状态正常"
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Always start a fresh download — GitHub release URL never changes between builds
 * so we cannot rely on URL comparison for version detection.
 * Cleans up any previous download before starting a new one.
 * After installation, caller should call clearDownloadState() to wipe all download identity metadata.
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
    prefs.edit()
        .putLong("dl_id", dlId)
        .putString("dl_url", apkUrl)
        .remove("dl_tag")
        .apply()
    return dlId
}

/** 安装完成后调用，清除下载状态防止下次误用旧包 */
fun Context.clearDownloadState() {
    val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
    val oldId = prefs.getLong("dl_id", -1L)
    if (oldId != -1L) {
        getSystemService(DownloadManager::class.java).remove(oldId)
    }
    prefs.edit()
        .remove("dl_id")
        .remove("dl_url")
        .remove("dl_tag")
        .apply()
}

private fun launchInstaller(context: Context, uri: Uri) {
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

private fun Context.openUnknownAppSourcesSettings() {
    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

internal fun Context.shouldRequestUpdateNotificationPermission(): Boolean =
    shouldRequestUpdateNotificationPermission(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED,
    )

internal fun shouldRequestUpdateNotificationPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val apkUrl: String, val notes: String = "") : UpdateState
    data class Downloading(val progress: Float, val dlId: Long) : UpdateState
    data class ReadyToInstall(val uri: Uri) : UpdateState
    data class Error(val msg: String) : UpdateState
}

private fun UpdateState.label(): String = when (this) {
    UpdateState.Idle -> "未检查"
    UpdateState.Checking -> "检查中"
    UpdateState.UpToDate -> "已是最新"
    is UpdateState.Available -> "有新版本"
    is UpdateState.Downloading -> if (progress < 0f) "下载中" else "下载 ${(progress * 100).toInt()}%"
    is UpdateState.ReadyToInstall -> "待安装"
    is UpdateState.Error -> "需要处理"
}

private fun FontChoice.label(): String = when (this) {
    FontChoice.System -> "系统宋体"
    FontChoice.SourceHan -> "思源宋体"
    is FontChoice.Custom -> fileName
}

private fun ReaderReadingMode.label() = when (this) {
    ReaderReadingMode.SCROLL -> "滚动"
    ReaderReadingMode.PAGED -> "翻页"
}

private fun TxtEncoding.label() = when (this) {
    TxtEncoding.AUTO -> "自动"
    TxtEncoding.UTF_8 -> "UTF-8"
    TxtEncoding.GBK -> "GBK"
    TxtEncoding.GB18030 -> "GB18030"
    TxtEncoding.BIG5 -> "Big5"
    TxtEncoding.SHIFT_JIS -> "Shift_JIS"
}
