package dev.readflow.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.calibre.CalibreConnectionCheckResult
import dev.readflow.core.calibre.CalibreConnectionTester
import dev.readflow.core.calibre.CalibreEndpointProbe
import dev.readflow.core.calibre.CalibreProbeResult
import dev.readflow.core.calibre.validateCalibreBaseUrl
import dev.readflow.core.database.LinReadsBackupExportStore
import dev.readflow.core.database.LinReadsBackupRestoreStore
import dev.readflow.core.database.NotesMarkdownExportStore
import dev.readflow.core.model.ThemeProfile
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.sync.SyncBackend
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CalibreConnectionUiState {
    data object Idle : CalibreConnectionUiState
    data object Checking : CalibreConnectionUiState
    data class Success(val message: String, val nextStep: String) : CalibreConnectionUiState
    data class Failure(val message: String, val nextStep: String) : CalibreConnectionUiState
}

data class SyncStatusUiState(
    val title: String,
    val detail: String,
    val isRemoteSyncEnabled: Boolean,
)

sealed interface BackupExportUiState {
    data object Idle : BackupExportUiState
    data object Exporting : BackupExportUiState
    data class Success(val message: String) : BackupExportUiState
    data class Failure(val message: String) : BackupExportUiState
}

sealed interface BackupRestoreUiState {
    data object Idle : BackupRestoreUiState
    data object Restoring : BackupRestoreUiState
    data class Success(val message: String) : BackupRestoreUiState
    data class Failure(val message: String) : BackupRestoreUiState
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val connectionTester: CalibreConnectionTester,
    private val endpointProbe: CalibreEndpointProbe,
    syncBackend: SyncBackend,
    private val backupExporter: LinReadsBackupExportStore,
    private val backupRestorer: LinReadsBackupRestoreStore,
    private val notesExporter: NotesMarkdownExportStore,
    private val backupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val calibreBaseUrl = settings.calibreBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val fontSize = settings.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 18)

    val themeMode = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val lineSpacing = settings.lineSpacing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.75f)

    val readingMode = settings.readingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderReadingMode.SCROLL)

    val useSourceHanFont = settings.useSourceHanFont
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val txtEncoding = settings.txtEncoding
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxtEncoding.AUTO)

    val fontChoice = settings.fontChoice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontChoice.SourceHan)

    private val _calibreUrlError = MutableStateFlow<String?>(null)
    val calibreUrlError: StateFlow<String?> = _calibreUrlError.asStateFlow()

    private val _calibreConnectionState =
        MutableStateFlow<CalibreConnectionUiState>(CalibreConnectionUiState.Idle)
    val calibreConnectionState: StateFlow<CalibreConnectionUiState> =
        _calibreConnectionState.asStateFlow()

    val syncStatus = MutableStateFlow(syncBackend.toUiState()).asStateFlow()

    private val _backupExportState = MutableStateFlow<BackupExportUiState>(BackupExportUiState.Idle)
    val backupExportState: StateFlow<BackupExportUiState> = _backupExportState.asStateFlow()

    private val _backupRestoreState = MutableStateFlow<BackupRestoreUiState>(BackupRestoreUiState.Idle)
    val backupRestoreState: StateFlow<BackupRestoreUiState> = _backupRestoreState.asStateFlow()

    private val _notesExportState = MutableStateFlow<BackupExportUiState>(BackupExportUiState.Idle)
    val notesExportState: StateFlow<BackupExportUiState> = _notesExportState.asStateFlow()

    private val _themeExportState = MutableStateFlow<BackupExportUiState>(BackupExportUiState.Idle)
    val themeExportState: StateFlow<BackupExportUiState> = _themeExportState.asStateFlow()

    private val _themeImportState = MutableStateFlow<BackupRestoreUiState>(BackupRestoreUiState.Idle)
    val themeImportState: StateFlow<BackupRestoreUiState> = _themeImportState.asStateFlow()

    fun setCalibreUrl(url: String) {
        val validation = validateCalibreBaseUrl(url)
        if (!validation.isValid) {
            _calibreUrlError.value = validation.errorMessage
            _calibreConnectionState.value = CalibreConnectionUiState.Idle
            return
        }
        _calibreUrlError.value = null
        _calibreConnectionState.value = CalibreConnectionUiState.Idle
        viewModelScope.launch { settings.setCalibreBaseUrl(validation.normalizedUrl) }
    }

    fun testCalibreConnection(url: String) {
        val validation = validateCalibreBaseUrl(url)
        if (!validation.isValid || validation.normalizedUrl.isBlank()) {
            _calibreUrlError.value = validation.errorMessage ?: "请先填写 Calibre 服务器地址"
            _calibreConnectionState.value = CalibreConnectionUiState.Idle
            return
        }

        _calibreUrlError.value = null
        _calibreConnectionState.value = CalibreConnectionUiState.Checking
        viewModelScope.launch {
            when (val result = connectionTester.check(validation.normalizedUrl)) {
                is CalibreConnectionCheckResult.Success -> {
                    settings.setCalibreBaseUrl(validation.normalizedUrl)
                    _calibreConnectionState.value = CalibreConnectionUiState.Success(
                        message = "已连接到 Calibre，发现 ${result.bookCount} 本书",
                        nextStep = "返回书架后可以搜索并下载书籍",
                    )
                }
                is CalibreConnectionCheckResult.Failure -> {
                    _calibreConnectionState.value = CalibreConnectionUiState.Failure(
                        message = result.message,
                        nextStep = result.nextStep,
                    )
                }
            }
        }
    }

    fun probeCalibreConnection(hint: String) {
        _calibreUrlError.value = null
        _calibreConnectionState.value = CalibreConnectionUiState.Checking
        viewModelScope.launch {
            when (val result = endpointProbe.probe(hint)) {
                is CalibreProbeResult.Success -> {
                    settings.setCalibreBaseUrl(result.baseUrl)
                    _calibreConnectionState.value = CalibreConnectionUiState.Success(
                        message = "已发现 Calibre：${result.baseUrl.removeProtocol()}，发现 ${result.bookCount} 本书",
                        nextStep = "已保存该地址，返回书架后可以搜索并下载书籍",
                    )
                }
                is CalibreProbeResult.Failure -> {
                    _calibreConnectionState.value = CalibreConnectionUiState.Failure(
                        message = result.message,
                        nextStep = result.nextStep.withAttempts(result),
                    )
                }
            }
        }
    }

    fun clearCalibreConnectionState() {
        _calibreConnectionState.value = CalibreConnectionUiState.Idle
    }

    fun exportBackup(output: OutputStream) {
        _backupExportState.value = BackupExportUiState.Exporting
        viewModelScope.launch {
            runCatching {
                withContext(backupDispatcher) {
                    output.use { backupExporter.export(it) }
                }
            }.onSuccess { result ->
                _backupExportState.value = BackupExportUiState.Success(
                    "已导出：进度 ${result.progressCount} 条，书签 ${result.bookmarkCount} 条，标注 ${result.textAnnotationCount} 条",
                )
            }.onFailure { error ->
                _backupExportState.value = BackupExportUiState.Failure(
                    "导出失败：${error.message ?: "请稍后重试"}",
                )
            }
        }
    }

    fun backupExportFailed(message: String) {
        _backupExportState.value = BackupExportUiState.Failure(message)
    }

    fun exportNotes(output: OutputStream) {
        _notesExportState.value = BackupExportUiState.Exporting
        viewModelScope.launch {
            runCatching {
                withContext(backupDispatcher) {
                    notesExporter.export(output)
                }
            }.onSuccess { count ->
                _notesExportState.value = BackupExportUiState.Success(
                    if (count > 0) "已导出 $count 本书的阅读笔记" else "已导出阅读笔记（暂无书签或标注）",
                )
            }.onFailure { error ->
                _notesExportState.value = BackupExportUiState.Failure(
                    "笔记导出失败：${error.message ?: "请稍后重试"}",
                )
            }
        }
    }

    fun exportTheme(output: OutputStream) {
        _themeExportState.value = BackupExportUiState.Exporting
        viewModelScope.launch {
            runCatching {
                withContext(backupDispatcher) {
                    val profile = ThemeProfile(
                        fontSize = settings.fontSize.first(),
                        lineSpacing = settings.lineSpacing.first(),
                        themeMode = settings.themeMode.first().name,
                        fontChoice = settings.fontChoice.first().serialize(),
                        txtEncoding = settings.txtEncoding.first().name,
                        readingMode = settings.readingMode.first().name,
                    )
                    output.use { it.write(profile.encode().toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                _themeExportState.value = BackupExportUiState.Success("主题已导出")
            }.onFailure { error ->
                _themeExportState.value = BackupExportUiState.Failure(
                    "主题导出失败：${error.message ?: "请稍后重试"}",
                )
            }
        }
    }

    fun importTheme(input: InputStream) {
        _themeImportState.value = BackupRestoreUiState.Restoring
        viewModelScope.launch {
            runCatching {
                withContext(backupDispatcher) {
                    val raw = input.use { it.readBytes().toString(Charsets.UTF_8) }
                    val profile = ThemeProfile.decode(raw) ?: throw IllegalStateException("无法解析主题文件")
                    ThemeProfile.validated(profile)
                }
            }.onSuccess { profile ->
                settings.setFontSize(profile.fontSize)
                settings.setLineSpacing(profile.lineSpacing)
                runCatching { ThemeMode.valueOf(profile.themeMode) }.getOrNull()?.let { settings.setThemeMode(it) }
                settings.setFontChoice(FontChoice.parse(profile.fontChoice))
                runCatching { TxtEncoding.valueOf(profile.txtEncoding) }.getOrNull()?.let { settings.setTxtEncoding(it) }
                runCatching { ReaderReadingMode.valueOf(profile.readingMode) }.getOrNull()?.let { settings.setReadingMode(it) }
                _themeImportState.value = BackupRestoreUiState.Success("主题已导入")
            }.onFailure { error ->
                _themeImportState.value = BackupRestoreUiState.Failure(
                    "主题导入失败：${error.message ?: "请稍后重试"}",
                )
            }
        }
    }

    fun restoreBackup(input: InputStream) {
        _backupRestoreState.value = BackupRestoreUiState.Restoring
        viewModelScope.launch {
            runCatching {
                withContext(backupDispatcher) {
                    input.use { backupRestorer.restore(it) }
                }
            }.onSuccess { result ->
                _backupRestoreState.value = BackupRestoreUiState.Success(
                    "已恢复：进度 ${result.progressCount} 条，书签 ${result.bookmarkCount} 条，标注 ${result.textAnnotationCount} 条",
                )
            }.onFailure { error ->
                _backupRestoreState.value = BackupRestoreUiState.Failure(
                    "恢复失败：${error.message ?: "请检查备份文件"}",
                )
            }
        }
    }

    fun backupRestoreFailed(message: String) {
        _backupRestoreState.value = BackupRestoreUiState.Failure(message)
    }

    fun setFontSize(size: Int) { viewModelScope.launch { settings.setFontSize(size) } }
    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settings.setThemeMode(mode) } }
    fun setLineSpacing(multiplier: Float) { viewModelScope.launch { settings.setLineSpacing(multiplier) } }
    fun setReadingMode(mode: ReaderReadingMode) { viewModelScope.launch { settings.setReadingMode(mode) } }
    fun setUseSourceHanFont(enabled: Boolean) { viewModelScope.launch { settings.setUseSourceHanFont(enabled) } }
    fun setTxtEncoding(encoding: TxtEncoding) { viewModelScope.launch { settings.setTxtEncoding(encoding) } }
    fun setFontChoice(choice: FontChoice) { viewModelScope.launch { settings.setFontChoice(choice) } }

    /**
     * 导入自定义字体：把 [input] 拷贝到 [dest]（已由调用方清洗为 fonts 目录内的安全文件名），
     * 成功后选用该字体。IO 在 [backupDispatcher] 上跑，避免阻塞主线程。
     */
    fun importFont(input: InputStream, dest: File, choice: FontChoice.Custom) {
        viewModelScope.launch {
            runCatching {
                withContext(backupDispatcher) {
                    input.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                }
            }.onSuccess {
                settings.setFontChoice(choice)
            }.onFailure {
                runCatching { dest.delete() }
            }
        }
    }
}

private fun String.removeProtocol(): String =
    removePrefix("http://").removePrefix("https://")

private fun String.withAttempts(result: CalibreProbeResult.Failure): String {
    if (result.attempts.isEmpty()) return this
    val attempted = result.attempts.joinToString("、") { attempt ->
        "${attempt.baseUrl}（${attempt.message}）"
    }
    return "$this\n已尝试：$attempted"
}

private fun SyncBackend.toUiState(): SyncStatusUiState =
    if (isAvailable) {
        SyncStatusUiState(
            title = "远程同步已启用",
            detail = "后端：$backendId",
            isRemoteSyncEnabled = true,
        )
    } else {
        SyncStatusUiState(
            title = "远程同步未启用",
            detail = "当前只在本机保存进度、书签和标注。真实同步后端已延期。",
            isRemoteSyncEnabled = false,
        )
    }
