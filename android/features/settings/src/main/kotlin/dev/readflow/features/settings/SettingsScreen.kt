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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.readerThemeLabel
import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.core.ui.AccessibleSlider
import dev.readflow.core.ui.FontProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCheckForUpdate: suspend (Context) -> UpdatePackageInfo? = { null },
    cachedNotes: String = "",
    authToken: String = "",
    buildTag: String = "",
) {
    val vm = koinViewModel<SettingsViewModel>()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val lineSpacing by vm.lineSpacing.collectAsStateWithLifecycle()
    val readingMode by vm.readingMode.collectAsStateWithLifecycle()
    val txtEncoding by vm.txtEncoding.collectAsStateWithLifecycle()
    val fontChoice by vm.fontChoice.collectAsStateWithLifecycle()
    val epubFontReplacements by vm.epubFontReplacements.collectAsStateWithLifecycle()
    val readerMenuConfig by vm.readerMenuConfig.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val backupExportState by vm.backupExportState.collectAsStateWithLifecycle()
    val backupRestoreState by vm.backupRestoreState.collectAsStateWithLifecycle()
    val notesExportState by vm.notesExportState.collectAsStateWithLifecycle()
    val themeExportState by vm.themeExportState.collectAsStateWithLifecycle()
    val themeImportState by vm.themeImportState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.READING) }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var pendingUpdateDownload by remember { mutableStateOf<UpdateState.Available?>(null) }
    fun startUpdateDownload(update: UpdateState.Available) {
        val dlId = context.startFreshDownload(update.packageInfo, authToken)
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
    var fontImportError by remember { mutableStateOf<String?>(null) }
    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                FontProvider.importFont(context.applicationContext, uri)
            }
            result.onSuccess { choice ->
                fontImportError = null
                vm.setFontChoice(choice)
            }.onFailure { error ->
                fontImportError = when (error) {
                    is IllegalArgumentException ->
                        error.message ?: "请选择可用的 TTF 或 OTF 字体文件"
                    else -> "字体导入失败，请重新选择字体文件"
                }
            }
        }
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
                        if (uri != null && context.launchUpdateInstaller(uri)) {
                            updateState = UpdateState.ReadyToInstall(uri)
                        }
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
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it },
            sourceContent = {
            SettingsSection(
                title = "在线书源",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.Link,
                title = "统一书源管理",
                currentValue = "在书架的在线书库中管理",
            )
            Text(
                text = "支持网页小说站、OPDS / Atom、JSON 目录与 Calibre；每个来源都可独立添加、切换和删除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            },
            readingContent = {
            SettingsSection(
                title = "阅读体验",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.TextFields,
                title = "正文字号",
                currentValue = "${fontSize}sp",
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
            val previewFontFamily by produceState<FontFamily>(
                initialValue = FontFamily.Serif,
                key1 = fontChoice,
                key2 = context.applicationContext,
            ) {
                value = withContext(Dispatchers.IO) {
                    FontProvider.fontFamilyFor(
                        context.applicationContext,
                        fontChoice.serialize(),
                    )
                }
            }
            Text(
                text = "这是 ${fontSize}sp 的正文效果。The quick brown fox.",
                fontSize = fontSize.sp,
                lineHeight = (fontSize * ReaderTypography.clampLineSpacing(lineSpacing)).sp,
                fontFamily = previewFontFamily,
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

            var showReaderMenuEditor by rememberSaveable { mutableStateOf(false) }
            val resolvedMenu = remember(readerMenuConfig) {
                ReaderMenuConfig.resolve(readerMenuConfig)
            }
            val visibleMenuEntries = remember(resolvedMenu) {
                resolvedMenu.entries.filter { it.visible }
            }
            val menuVisibleSummary = remember(visibleMenuEntries) {
                if (visibleMenuEntries.isEmpty()) {
                    "0 项显示"
                } else {
                    val orderSummary = visibleMenuEntries
                        .joinToString(" · ") { readerMenuCommandLabel(it.id) }
                    "${visibleMenuEntries.size} 项 · $orderSummary"
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = { showReaderMenuEditor = true },
                    )
                    .semantics(mergeDescendants = true) {
                        stateDescription = menuVisibleSummary
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(width = 28.dp, height = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ViewCarousel,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "阅读菜单命令",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = menuVisibleSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showReaderMenuEditor) {
                ReaderMenuConfigEditorDialog(
                    config = resolvedMenu,
                    onDismiss = { showReaderMenuEditor = false },
                    onToggleVisible = { id, visible ->
                        vm.setReaderMenuCommandVisible(id, visible)
                    },
                    onMoveUp = { id -> vm.moveReaderMenuCommandUp(id) },
                    onMoveDown = { id -> vm.moveReaderMenuCommandDown(id) },
                    onReset = { vm.resetReaderMenuConfig() },
                )
            }

            var customFonts by remember { mutableStateOf(emptyList<String>()) }
            // key 于 fontChoice：导入成功会更新 fontChoice，从而刷新列表显示新字体
            LaunchedEffect(fontChoice) {
                customFonts = withContext(Dispatchers.IO) {
                    FontProvider.listCustomFonts(context.applicationContext)
                }
            }
            SettingItemHeader(
                icon = Icons.Outlined.FontDownload,
                title = "正文字体",
                currentValue = FontProvider.displayName(fontChoice),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FontProvider.availableChoices(customFonts).forEach { choice ->
                        FilterChip(
                            selected = fontChoice == choice,
                            onClick = { vm.setFontChoice(choice) },
                            label = {
                                Text(
                                    text = FontProvider.displayName(choice),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .heightIn(min = 48.dp),
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        fontImportError = null
                        fontImportLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream"))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入字体")
                }
                fontImportError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SettingItemHeader(
                icon = Icons.Outlined.FontDownload,
                title = "书籍字体",
                currentValue = "在阅读菜单中设置",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "打开书籍后，在底部菜单的“字体与排版”中选择和导入字体。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (epubFontReplacements.isNotEmpty()) {
                    Text(
                        text = "检测到 ${epubFontReplacements.size} 条旧版字体规则，它们仍会生效。清除后可按每本书重新选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = vm::clearEpubFontReplacements,
                    enabled = epubFontReplacements.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清除旧版字体规则")
                }
            }

            SettingItemHeader(
                icon = Icons.Outlined.Code,
                title = "TXT 编码",
                currentValue = txtEncoding.label(),
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
            dataContent = {

            SettingsSection(
                title = "同步与备份",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.Sync,
                title = "阅读数据同步",
                currentValue = if (syncStatus.isRemoteSyncEnabled) "已启用" else "仅本机",
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
            ) {
            SettingItemHeader(
                icon = Icons.AutoMirrored.Outlined.Notes,
                title = "阅读笔记",
                currentValue = "Markdown",
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
            },
            aboutContent = {
            SettingsSection(
                title = "关于与更新",
            ) {
            SettingItemHeader(
                icon = Icons.Outlined.Info,
                title = "LinReads Android",
                currentValue = buildTag.displayBuildLabel().ifBlank { "开发版本" },
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
            )

            when (val s = updateState) {
                UpdateState.Idle -> Button(
                    onClick = {
                        scope.launch {
                            updateState = UpdateState.Checking
                            runCatching { onCheckForUpdate(context) }
                                .onSuccess { result ->
                                    updateState = if (result != null) UpdateState.Available(result)
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
                            context.applyUpdateArtifactEvent(UpdateArtifactEvent.DownloadCancelled)
                            updateState = UpdateState.Idle
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("取消下载") }
                }

                is UpdateState.ReadyToInstall -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("下载完成", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        if (!context.launchUpdateInstaller(s.uri)) {
                            updateState = UpdateState.Error("无法打开系统安装器，请重新下载")
                        }
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
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageLayout(
    modifier: Modifier = Modifier,
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    readingContent: @Composable ColumnScope.() -> Unit,
    sourceContent: @Composable ColumnScope.() -> Unit,
    dataContent: @Composable ColumnScope.() -> Unit,
    aboutContent: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val layoutMode = settingsLayoutMode(maxWidth.value)
        val content: @Composable ColumnScope.() -> Unit = when (selectedCategory) {
            SettingsCategory.READING -> readingContent
            SettingsCategory.SOURCE -> sourceContent
            SettingsCategory.DATA -> dataContent
            SettingsCategory.ABOUT -> aboutContent
        }
        if (layoutMode == SettingsLayoutMode.TWO_COLUMNS) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 960.dp)
                    .fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .width(196.dp)
                        .fillMaxHeight()
                        .padding(12.dp)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    settingsCategoryOrder().forEach { category ->
                        SettingsCategoryItem(
                            category = category,
                            selected = selectedCategory == category,
                            onClick = { onCategorySelected(category) },
                        )
                    }
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                SettingsDetailPane(
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PrimaryTabRow(
                    selectedTabIndex = settingsCategoryOrder().indexOf(selectedCategory),
                ) {
                    settingsCategoryOrder().forEach { category ->
                        Tab(
                            selected = selectedCategory == category,
                            onClick = { onCategorySelected(category) },
                            text = { Text(category.label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                SettingsDetailPane(
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SettingsDetailPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SettingsCategoryItem(
    category: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.READING -> Icons.Outlined.TextFields
    SettingsCategory.SOURCE -> Icons.Outlined.Link
    SettingsCategory.DATA -> Icons.Outlined.ImportExport
    SettingsCategory.ABOUT -> Icons.Outlined.Info
}

/** Chinese labels for reader bottom-bar command toggles (must match ReaderCommandCatalog). */
internal fun readerMenuCommandLabel(id: ReaderCommandId): String = when (id) {
    ReaderCommandId.TOC -> "目录"
    ReaderCommandId.SEARCH -> "搜索"
    ReaderCommandId.BOOKMARKS -> "书签"
    ReaderCommandId.ANNOTATIONS -> "标注"
    ReaderCommandId.FONT -> "排版"
    ReaderCommandId.THEME -> "主题"
}

@Composable
private fun ReaderMenuConfigEditorDialog(
    config: ReaderMenuConfig,
    onDismiss: () -> Unit,
    onToggleVisible: (ReaderCommandId, Boolean) -> Unit,
    onMoveUp: (ReaderCommandId) -> Unit,
    onMoveDown: (ReaderCommandId) -> Unit,
    onReset: () -> Unit,
) {
    val entries = config.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读菜单命令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                entries.forEachIndexed { index, entry ->
                    val label = readerMenuCommandLabel(entry.id)
                    val canMoveUp = index > 0
                    val canMoveDown = index < entries.lastIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = entry.visible,
                                    role = Role.Switch,
                                    onValueChange = { visible ->
                                        onToggleVisible(entry.id, visible)
                                    },
                                )
                                .semantics {
                                    stateDescription = if (entry.visible) "已显示" else "已隐藏"
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = entry.visible,
                                onCheckedChange = null,
                            )
                        }
                        IconButton(
                            onClick = { onMoveUp(entry.id) },
                            enabled = canMoveUp,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "上移$label",
                            )
                        }
                        IconButton(
                            onClick = { onMoveDown(entry.id) },
                            enabled = canMoveDown,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "下移$label",
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onReset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("恢复默认")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("完成")
            }
        },
    )
}

@Composable
private fun SettingItemHeader(
    icon: ImageVector,
    title: String,
    currentValue: String? = null,
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
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        currentValue?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 132.dp),
                maxLines = 1,
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
 * Clears the previous download identity before starting a new one.
 * The completed APK is retained after launching the installer so a cancelled or misrouted install
 * can be retried. Replacement downloads use a separate file so they cannot invalidate an installer
 * that is still reading the previous DownloadManager URI.
 */
private fun Context.startFreshDownload(update: UpdatePackageInfo, authToken: String): Long {
    applyUpdateArtifactEvent(UpdateArtifactEvent.ReplacedByNewDownload)
    val metadata = updateDownloadMetadata(update)
    val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
    val dm = getSystemService(DownloadManager::class.java)

    val dlId = dm.enqueue(
        DownloadManager.Request(Uri.parse(metadata.apkUrl)).apply {
            if (authToken.isNotEmpty()) addRequestHeader("Authorization", "Bearer $authToken")
            setTitle("LinReads 更新下载中")
            setDescription("正在下载新版本…")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@startFreshDownload,
                null,
                createUpdateDownloadFileName(),
            )
        }
    )
    prefs.edit()
        .putLong("dl_id", dlId)
        .putString("dl_url", metadata.apkUrl)
        .putString("dl_tag", metadata.buildTag)
        .apply()
    return dlId
}

private fun Context.applyUpdateArtifactEvent(event: UpdateArtifactEvent) {
    val action = updateArtifactAction(event)
    if (!action.removeDownload && !action.clearMetadata) return
    val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
    val oldId = prefs.getLong("dl_id", -1L)
    if (action.removeDownload && oldId != -1L) {
        getSystemService(DownloadManager::class.java).remove(oldId)
    }
    if (action.clearMetadata) {
        prefs.edit()
            .remove("dl_id")
            .remove("dl_url")
            .remove("dl_tag")
            .apply()
    }
}

private fun Context.launchUpdateInstaller(uri: Uri): Boolean =
    runCatching {
        startActivity(createUpdateInstallIntent(uri, launchInNewTask = false))
        applyUpdateArtifactEvent(UpdateArtifactEvent.InstallerLaunched)
    }.isSuccess

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
    data class Available(val packageInfo: UpdatePackageInfo) : UpdateState {
        val notes: String get() = packageInfo.notes
    }
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
