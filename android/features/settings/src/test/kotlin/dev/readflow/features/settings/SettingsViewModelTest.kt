package dev.readflow.features.settings

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.calibre.CalibreConnectionCheckResult
import dev.readflow.core.calibre.CalibreConnectionTester
import dev.readflow.core.calibre.CalibreEndpointProbe
import dev.readflow.core.calibre.CalibreProbeAttempt
import dev.readflow.core.calibre.CalibreProbeResult
import dev.readflow.core.database.LinReadsBackupExportResult
import dev.readflow.core.database.LinReadsBackupExportStore
import dev.readflow.core.database.LinReadsBackupRestoreResult
import dev.readflow.core.database.LinReadsBackupRestoreStore
import dev.readflow.core.database.NotesMarkdownExportStore
import dev.readflow.core.model.Bookmark
import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderMenuEntry
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReadingProgress
import dev.readflow.core.sync.SyncBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rejectsPublicHttpCalibreUrlWithoutSaving() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        viewModel.setCalibreUrl("http://8.8.8.8:8080")
        advanceUntilIdle()

        assertEquals(
            "HTTP 只允许局域网私有地址：10.x、172.16-31.x、192.168.x，公网地址请使用 HTTPS",
            viewModel.calibreUrlError.value,
        )
        assertNull(settings.savedCalibreUrl)
    }

    @Test
    fun savesNormalizedPrivateHttpCalibreUrl() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        viewModel.setCalibreUrl(" http://172.31.0.7:8080/ ")
        advanceUntilIdle()

        assertNull(viewModel.calibreUrlError.value)
        assertEquals("http://172.31.0.7:8080", settings.savedCalibreUrl)
    }

    @Test
    fun testConnectionSavesNormalizedUrlAndShowsSuccessGuidance() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val tester = FakeCalibreConnectionTester(
            result = CalibreConnectionCheckResult.Success(bookCount = 12),
        )
        val viewModel = createViewModel(settings, connectionTester = tester)

        viewModel.testCalibreConnection(" http://192.168.1.5:8080/ ")
        advanceUntilIdle()

        assertEquals("http://192.168.1.5:8080", settings.savedCalibreUrl)
        assertEquals("http://192.168.1.5:8080", tester.checkedUrl)
        assertNull(viewModel.calibreUrlError.value)
        assertEquals(
            CalibreConnectionUiState.Success(
                message = "已连接到 Calibre，发现 12 本书",
                nextStep = "返回书架后可以搜索并下载书籍",
            ),
            viewModel.calibreConnectionState.value,
        )
    }

    @Test
    fun testConnectionFailureShowsActionableGuidanceWithoutSaving() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val tester = FakeCalibreConnectionTester(
            result = CalibreConnectionCheckResult.Failure(
                message = "无法连接到服务器",
                nextStep = "确认手机和 Calibre 在同一局域网，并检查端口是否为 8080",
            ),
        )
        val viewModel = createViewModel(settings, connectionTester = tester)

        viewModel.testCalibreConnection("http://192.168.1.5:8080")
        advanceUntilIdle()

        assertNull(settings.savedCalibreUrl)
        assertEquals(
            CalibreConnectionUiState.Failure(
                message = "无法连接到服务器",
                nextStep = "确认手机和 Calibre 在同一局域网，并检查端口是否为 8080",
            ),
            viewModel.calibreConnectionState.value,
        )
    }

    @Test
    fun clearsConnectionStateWhenDraftChanges() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(
            settings,
            connectionTester = FakeCalibreConnectionTester(CalibreConnectionCheckResult.Success(bookCount = 1)),
        )

        viewModel.testCalibreConnection("http://192.168.1.5:8080")
        advanceUntilIdle()
        viewModel.clearCalibreConnectionState()

        assertEquals(CalibreConnectionUiState.Idle, viewModel.calibreConnectionState.value)
    }

    @Test
    fun probeConnectionSavesDiscoveredUrlAndShowsSuccessGuidance() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val probe = FakeCalibreEndpointProbe(
            result = CalibreProbeResult.Success(
                baseUrl = "http://192.168.1.5:8081",
                bookCount = 4,
            ),
        )
        val viewModel = createViewModel(settings, endpointProbe = probe)

        viewModel.probeCalibreConnection("192.168.1.5")
        advanceUntilIdle()

        assertEquals("192.168.1.5", probe.probedHint)
        assertEquals("http://192.168.1.5:8081", settings.savedCalibreUrl)
        assertEquals(
            CalibreConnectionUiState.Success(
                message = "已发现 Calibre：192.168.1.5:8081，发现 4 本书",
                nextStep = "已保存该地址，返回书架后可以搜索并下载书籍",
            ),
            viewModel.calibreConnectionState.value,
        )
    }

    @Test
    fun probeConnectionFailureShowsAttemptedUrlsWithoutSaving() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val probe = FakeCalibreEndpointProbe(
            result = CalibreProbeResult.Failure(
                message = "没有在常用 Calibre 地址发现服务",
                nextStep = "确认电脑端 Calibre Content Server 已启动",
                attempts = listOf(
                    CalibreProbeAttempt("http://192.168.1.5:8080", "连接 Calibre 超时"),
                    CalibreProbeAttempt("http://192.168.1.5:8081", "无法连接到服务器"),
                ),
            ),
        )
        val viewModel = createViewModel(settings, endpointProbe = probe)

        viewModel.probeCalibreConnection("192.168.1.5")
        advanceUntilIdle()

        assertNull(settings.savedCalibreUrl)
        assertEquals(
            CalibreConnectionUiState.Failure(
                message = "没有在常用 Calibre 地址发现服务",
                nextStep = "确认电脑端 Calibre Content Server 已启动\n已尝试：http://192.168.1.5:8080（连接 Calibre 超时）、http://192.168.1.5:8081（无法连接到服务器）",
            ),
            viewModel.calibreConnectionState.value,
        )
    }

    @Test
    fun noOpSyncBackendIsShownAsLocalOnlyInsteadOfSynced() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            syncBackend = FakeSyncBackend(
                backendId = "noop",
                isAvailable = false,
            ),
        )

        val status = viewModel.syncStatus.value

        assertFalse(status.isRemoteSyncEnabled)
        assertEquals("远程同步未启用", status.title)
        assertEquals("当前只在本机保存进度、书签和标注。真实同步后端已延期。", status.detail)
    }

    @Test
    fun exportBackupWritesZipAndShowsCounts() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val backup = FakeBackupExporter(
            result = LinReadsBackupExportResult(
                progressCount = 2,
                bookmarkCount = 3,
                textAnnotationCount = 4,
            ),
        )
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            backupExporter = backup,
        )

        viewModel.exportBackup(FakeOutputStream())
        advanceUntilIdle()

        assertEquals(1, backup.exportCalls)
        assertEquals(
            BackupExportUiState.Success("已导出：进度 2 条，书签 3 条，标注 4 条"),
            viewModel.backupExportState.value,
        )
    }

    @Test
    fun exportBackupFailureShowsReadableError() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            backupExporter = FakeBackupExporter(error = IllegalStateException("磁盘已满")),
        )

        viewModel.exportBackup(FakeOutputStream())
        advanceUntilIdle()

        assertEquals(
            BackupExportUiState.Failure("导出失败：磁盘已满"),
            viewModel.backupExportState.value,
        )
    }

    @Test
    fun restoreBackupReadsZipAndShowsCounts() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val backup = FakeBackupRestorer(
            result = LinReadsBackupRestoreResult(
                progressCount = 1,
                bookmarkCount = 2,
                textAnnotationCount = 3,
            ),
        )
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            backupRestorer = backup,
        )

        viewModel.restoreBackup(FakeInputStream())
        advanceUntilIdle()

        assertEquals(1, backup.restoreCalls)
        assertEquals(
            BackupRestoreUiState.Success("已恢复：进度 1 条，书签 2 条，标注 3 条"),
            viewModel.backupRestoreState.value,
        )
    }

    @Test
    fun restoreBackupFailureShowsReadableError() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            backupRestorer = FakeBackupRestorer(error = IllegalArgumentException("备份文件缺少 manifest.json")),
        )

        viewModel.restoreBackup(FakeInputStream())
        advanceUntilIdle()

        assertEquals(
            BackupRestoreUiState.Failure("恢复失败：备份文件缺少 manifest.json"),
            viewModel.backupRestoreState.value,
        )
    }

    @Test
    fun exportNotesSuccessReportsBookCount() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            notesExporter = FakeNotesExporter(count = 3),
        )

        viewModel.exportNotes(FakeOutputStream())
        advanceUntilIdle()

        assertEquals(
            BackupExportUiState.Success("已导出 3 本书的阅读笔记"),
            viewModel.notesExportState.value,
        )
    }

    @Test
    fun exportNotesFailureShowsReadableError() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = createViewModel(
            settings = FakeSettingsRepository(),
            notesExporter = FakeNotesExporter(error = IllegalStateException("磁盘已满")),
        )

        viewModel.exportNotes(FakeOutputStream())
        advanceUntilIdle()

        assertEquals(
            BackupExportUiState.Failure("笔记导出失败：磁盘已满"),
            viewModel.notesExportState.value,
        )
    }

    @Test
    fun exportThemeWritesCurrentSettingsAsJson() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            fontSize.value = 22
            themeMode.value = ThemeMode.DARK
        }
        val viewModel = createViewModel(settings = settings)
        val output = java.io.ByteArrayOutputStream()

        viewModel.exportTheme(output)
        advanceUntilIdle()

        assertTrue(viewModel.themeExportState.value is BackupExportUiState.Success)
        val json = output.toString("UTF-8")
        assertTrue(json.contains("\"fontSize\":22"))
        assertTrue(json.contains("\"themeMode\":\"DARK\""))
    }

    @Test
    fun importThemeAppliesValidatedSettings() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings = settings)
        // fontSize 999 is out of range → validated to 32; DARK applies as-is.
        val json = """{"fontSize":999,"lineSpacing":1.8,"themeMode":"DARK","fontChoice":"system","txtEncoding":"AUTO","readingMode":"PAGED"}"""

        viewModel.importTheme(java.io.ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        advanceUntilIdle()

        assertEquals(BackupRestoreUiState.Success("主题已导入"), viewModel.themeImportState.value)
        assertEquals(32, settings.fontSize.value)
        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals(ReaderReadingMode.PAGED, settings.readingMode.value)
        assertEquals(FontChoice.System, settings.fontChoice.value)
    }

    @Test
    fun importThemeRejectsDirtyJson() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = createViewModel(settings = FakeSettingsRepository())

        viewModel.importTheme(java.io.ByteArrayInputStream("not json".toByteArray(Charsets.UTF_8)))
        advanceUntilIdle()

        assertTrue(viewModel.themeImportState.value is BackupRestoreUiState.Failure)
    }

    @Test
    fun setEpubFontReplacementNormalizesFamilyAndPersistsMapping() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        viewModel.setEpubFontReplacement("  Book   Serif  ", FontChoice.SystemSans)
        advanceUntilIdle()

        assertEquals(
            mapOf("book serif" to FontChoice.SystemSans.serialize()),
            settings.epubFontReplacements.value,
        )
    }

    @Test
    fun rapidEpubFontReplacementAddsDoNotLosePriorMappings() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        // Fire several read-modify-write adds without intermediate idle drains.
        // Without a shared mutex these would race and keep only the last write.
        viewModel.setEpubFontReplacement("serif", FontChoice.System)
        viewModel.setEpubFontReplacement("sans", FontChoice.SystemSans)
        viewModel.setEpubFontReplacement("mono", FontChoice.SystemMonospace)
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "serif" to FontChoice.System.serialize(),
                "sans" to FontChoice.SystemSans.serialize(),
                "mono" to FontChoice.SystemMonospace.serialize(),
            ),
            settings.epubFontReplacements.value,
        )
        assertEquals(3, settings.setEpubFontReplacementsCalls)
    }

    @Test
    fun rapidEpubFontReplacementRemoveIsSerializedAndKeepsSiblings() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            epubFontReplacements.value = mapOf(
                "serif" to FontChoice.System.serialize(),
                "sans" to FontChoice.SystemSans.serialize(),
                "mono" to FontChoice.SystemMonospace.serialize(),
            )
        }
        val viewModel = createViewModel(settings)

        viewModel.removeEpubFontReplacement("serif")
        viewModel.removeEpubFontReplacement("MONO") // normalization + concurrent remove
        advanceUntilIdle()

        assertEquals(
            mapOf("sans" to FontChoice.SystemSans.serialize()),
            settings.epubFontReplacements.value,
        )
        assertEquals(2, settings.setEpubFontReplacementsCalls)
    }

    @Test
    fun interleavedAddAndRemoveEpubFontReplacementsStayConsistent() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            epubFontReplacements.value = mapOf(
                "keep" to FontChoice.System.serialize(),
            )
        }
        val viewModel = createViewModel(settings)

        viewModel.setEpubFontReplacement("new-a", FontChoice.SystemSans)
        viewModel.removeEpubFontReplacement("keep")
        viewModel.setEpubFontReplacement("new-b", FontChoice.SystemMonospace)
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "new-a" to FontChoice.SystemSans.serialize(),
                "new-b" to FontChoice.SystemMonospace.serialize(),
            ),
            settings.epubFontReplacements.value,
        )
    }

    @Test
    fun blankEpubFontFamilyIsRejectedWithoutPersisting() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        viewModel.setEpubFontReplacement("   ", FontChoice.SystemSans)
        viewModel.removeEpubFontReplacement("")
        advanceUntilIdle()

        assertTrue(settings.epubFontReplacements.value.isEmpty())
        assertEquals(0, settings.setEpubFontReplacementsCalls)
    }

    @Test
    fun clearLegacyEpubFontReplacementsRemovesAllRules() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            epubFontReplacements.value = mapOf(
                "serif" to FontChoice.System.serialize(),
                "sans" to FontChoice.SystemSans.serialize(),
            )
        }
        val viewModel = createViewModel(settings)

        viewModel.clearEpubFontReplacements()
        advanceUntilIdle()

        assertTrue(settings.epubFontReplacements.value.isEmpty())
        assertEquals(1, settings.setEpubFontReplacementsCalls)
    }

    @Test
    fun moveReaderMenuCommandUpPersistsOrderWhileKeepingVisibility() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            readerMenuConfig.value = ReaderMenuConfig.resolve(
                ReaderMenuConfig(
                    entries = listOf(
                        ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                        ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                        ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                    ),
                ),
            )
        }
        val viewModel = createViewModel(settings)

        viewModel.moveReaderMenuCommandUp(ReaderCommandId.SEARCH)
        advanceUntilIdle()

        val loaded = settings.readerMenuConfig.value
        assertEquals(ReaderCommandId.SEARCH, loaded.entries[0].id)
        assertFalse(loaded.entries[0].visible)
        assertEquals(ReaderCommandId.TOC, loaded.entries[1].id)
        assertEquals(6, loaded.entries.size)
        assertEquals(1, settings.setReaderMenuConfigCalls)
    }

    @Test
    fun moveReaderMenuCommandDownAtLastEntryIsNoOp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        viewModel.moveReaderMenuCommandDown(ReaderCommandId.THEME)
        advanceUntilIdle()

        assertEquals(ReaderMenuConfig.v1Defaults(), settings.readerMenuConfig.value)
        assertEquals(1, settings.setReaderMenuConfigCalls)
    }

    @Test
    fun resetReaderMenuConfigRestoresDefaults() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            readerMenuConfig.value = ReaderMenuConfig(
                version = ReaderMenuConfig.VERSION_V1,
                entries = listOf(
                    ReaderMenuEntry(ReaderCommandId.THEME, visible = false),
                    ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
                    ReaderMenuEntry(ReaderCommandId.TOC, visible = false),
                    ReaderMenuEntry(ReaderCommandId.SEARCH, visible = true),
                    ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = false),
                    ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ),
            )
        }
        val viewModel = createViewModel(settings)

        viewModel.resetReaderMenuConfig()
        advanceUntilIdle()

        assertEquals(ReaderMenuConfig.v1Defaults(), settings.readerMenuConfig.value)
    }

    @Test
    fun rapidReaderMenuMoveAndVisibilityDoNotLoseUpdates() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val viewModel = createViewModel(settings)

        // Fire without intermediate idle drains; mutex must serialize read-modify-write.
        viewModel.setReaderMenuCommandVisible(ReaderCommandId.ANNOTATIONS, false)
        viewModel.moveReaderMenuCommandUp(ReaderCommandId.THEME)
        viewModel.moveReaderMenuCommandUp(ReaderCommandId.THEME)
        viewModel.setReaderMenuCommandVisible(ReaderCommandId.SEARCH, false)
        viewModel.moveReaderMenuCommandDown(ReaderCommandId.TOC)
        viewModel.setReaderMenuCommandVisible(ReaderCommandId.ANNOTATIONS, true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ReaderMenuEntry(ReaderCommandId.BOOKMARKS, visible = true),
                ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
                ReaderMenuEntry(ReaderCommandId.ANNOTATIONS, visible = true),
                ReaderMenuEntry(ReaderCommandId.FONT, visible = true),
            ),
            settings.readerMenuConfig.value.entries,
        )
        assertEquals(6, settings.setReaderMenuConfigCalls)
    }

    private fun createViewModel(
        settings: SettingsRepository,
        connectionTester: CalibreConnectionTester = FakeCalibreConnectionTester(),
        endpointProbe: CalibreEndpointProbe = FakeCalibreEndpointProbe(),
        syncBackend: SyncBackend = FakeSyncBackend(),
        backupExporter: LinReadsBackupExportStore = FakeBackupExporter(),
        backupRestorer: LinReadsBackupRestoreStore = FakeBackupRestorer(),
        notesExporter: NotesMarkdownExportStore = FakeNotesExporter(),
    ): SettingsViewModel =
        SettingsViewModel(
            settings,
            connectionTester,
            endpointProbe,
            syncBackend,
            backupExporter,
            backupRestorer,
            notesExporter,
            dispatcher,
        )

    private class FakeSettingsRepository : SettingsRepository {
        override val calibreBaseUrl = MutableStateFlow<String?>(null)
        override val fontSize = MutableStateFlow(18)
        override val lineSpacing = MutableStateFlow(1.75f)
        override val readingMode = MutableStateFlow(ReaderReadingMode.SCROLL)
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override val deviceId = MutableStateFlow("device")
        override val engineOverrides = MutableStateFlow(emptyMap<BookFormat, String>())
        override val useSourceHanFont = MutableStateFlow(true)
        override val txtEncoding = MutableStateFlow(TxtEncoding.AUTO)
        override val fontChoice = MutableStateFlow<FontChoice>(FontChoice.System)
        override val epubFontReplacements = MutableStateFlow<Map<String, String>>(emptyMap())
        override val readerGuideShown = MutableStateFlow(false)
        override val pageFlipStyle = MutableStateFlow(dev.readflow.core.model.PageFlipStyle.SLIDE)
        override val readerMenuConfig = MutableStateFlow(ReaderMenuConfig.v1Defaults())
        var savedCalibreUrl: String? = null
        var setEpubFontReplacementsCalls = 0
        var setReaderMenuConfigCalls = 0

        override suspend fun setCalibreBaseUrl(url: String) {
            savedCalibreUrl = url
            calibreBaseUrl.value = url
        }

        override suspend fun setFontSize(size: Int) {
            fontSize.value = size
        }

        override suspend fun setLineSpacing(multiplier: Float) {
            lineSpacing.value = multiplier
        }

        override suspend fun setReadingMode(mode: ReaderReadingMode) {
            readingMode.value = mode
        }

        override suspend fun setThemeMode(mode: ThemeMode) {
            themeMode.value = mode
        }

        override suspend fun setEngineOverride(format: BookFormat, engineId: String?) {
            engineOverrides.value = if (engineId == null) {
                engineOverrides.value - format
            } else {
                engineOverrides.value + (format to engineId)
            }
        }

        override suspend fun setUseSourceHanFont(enabled: Boolean) {
            useSourceHanFont.value = enabled
        }

        override suspend fun setTxtEncoding(encoding: TxtEncoding) {
            txtEncoding.value = encoding
        }

        override suspend fun setFontChoice(choice: FontChoice) {
            fontChoice.value = choice
        }

        override suspend fun setEpubFontReplacements(replacements: Map<String, String>) {
            setEpubFontReplacementsCalls += 1
            epubFontReplacements.value = replacements
        }

        override suspend fun setReaderGuideShown(shown: Boolean) {
            readerGuideShown.value = shown
        }

        override suspend fun setPageFlipStyle(style: dev.readflow.core.model.PageFlipStyle) {
            pageFlipStyle.value = style
        }

        override suspend fun setReaderMenuConfig(config: ReaderMenuConfig) {
            setReaderMenuConfigCalls += 1
            readerMenuConfig.value = ReaderMenuConfig.resolve(config)
        }
    }

    private class FakeCalibreConnectionTester(
        private val result: CalibreConnectionCheckResult = CalibreConnectionCheckResult.Success(bookCount = 0),
    ) : CalibreConnectionTester {
        var checkedUrl: String? = null

        override suspend fun check(baseUrl: String): CalibreConnectionCheckResult {
            checkedUrl = baseUrl
            return result
        }
    }

    private class FakeCalibreEndpointProbe(
        private val result: CalibreProbeResult = CalibreProbeResult.Failure(
            message = "没有在常用 Calibre 地址发现服务",
            nextStep = "确认电脑端 Calibre Content Server 已启动",
            attempts = emptyList(),
        ),
    ) : CalibreEndpointProbe {
        var probedHint: String? = null

        override suspend fun probe(hint: String): CalibreProbeResult {
            probedHint = hint
            return result
        }
    }

    private class FakeSyncBackend(
        override val backendId: String = "noop",
        override val isAvailable: Boolean = false,
    ) : SyncBackend {
        override suspend fun pushProgress(bookId: String, progress: ReadingProgress): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit)

        override suspend fun pullProgress(bookId: String): ReadflowResult<ReadingProgress?> =
            ReadflowResult.Success(null)

        override suspend fun pushBookmark(bookmark: Bookmark): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit)

        override suspend fun pullBookmarks(bookId: String): ReadflowResult<List<Bookmark>> =
            ReadflowResult.Success(emptyList())
    }

    private class FakeBackupExporter(
        private val result: LinReadsBackupExportResult = LinReadsBackupExportResult(
            progressCount = 0,
            bookmarkCount = 0,
            textAnnotationCount = 0,
        ),
        private val error: Throwable? = null,
    ) : LinReadsBackupExportStore {
        var exportCalls = 0

        override suspend fun export(output: OutputStream): LinReadsBackupExportResult {
            exportCalls += 1
            error?.let { throw it }
            output.write(1)
            return result
        }
    }

    private class FakeOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
    }

    private class FakeBackupRestorer(
        private val result: LinReadsBackupRestoreResult = LinReadsBackupRestoreResult(
            progressCount = 0,
            bookmarkCount = 0,
            textAnnotationCount = 0,
        ),
        private val error: Throwable? = null,
    ) : LinReadsBackupRestoreStore {
        var restoreCalls = 0

        override suspend fun restore(input: InputStream): LinReadsBackupRestoreResult {
            restoreCalls += 1
            error?.let { throw it }
            input.read()
            return result
        }
    }

    private class FakeInputStream : InputStream() {
        override fun read(): Int = -1
    }

    private class FakeNotesExporter(
        private val count: Int = 0,
        private val error: Throwable? = null,
    ) : NotesMarkdownExportStore {
        var exportCalls = 0

        override suspend fun export(output: OutputStream): Int {
            exportCalls += 1
            error?.let { throw it }
            return count
        }
    }
}
