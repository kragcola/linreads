package dev.readflow.a03

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.readflow.MainActivity
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class A03AccessibilityRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        resetTargetAppState()
        settings.setReaderGuideShown(false)
        settings.setFontSize(16)
        settings.setLineSpacing(1.75f)
        settings.setThemeMode(ThemeMode.LIGHT)
        evidenceDir().deleteRecursively()
        evidenceDir().mkdirs()
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun libraryAndReaderExposeTalkBackReadyLabelsAndActionsRuntime() {
        val title = "a03-accessibility-${UUID.randomUUID().toString().take(8)}"
        val readerUri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            val guide = checkNotNull(waitForFreshObject(By.desc(READER_GUIDE_DESC))) {
                "expected first-reader guide"
            }
            assertTrue("reader guide must expose a clickable dismiss action", guide.isClickable)
            assertTrue(
                "reader guide must expose ACTION_CLICK",
                accessibilityNodeHasAction(
                    READER_GUIDE_DESC,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                ),
            )
            assertTrue(
                "reader guide ACTION_CLICK must dismiss the overlay",
                performAccessibilityNodeAction(
                    READER_GUIDE_DESC,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                ),
            )
            waitForObject(By.desc(EPUB_READER_DESC))
            waitForObject(By.textContains(OPENING_PARAGRAPH))
            val importedBook = waitForBookByTitle(title)
            takeScreenshot("reader-after-import.png")
            dumpHierarchy("reader-after-import.xml")

            ensureChromeVisible()
            clickObject(By.desc("返回"))
            waitForObject(By.desc("打开 $title，未知作者"))
            val libraryCardDescription =
                waitForObject(By.desc("打开 $title，未知作者")).contentDescription.orEmpty()
            val libraryMenuDescription = waitForObject(By.desc("$title 的菜单")).contentDescription.orEmpty()
            takeScreenshot("library-book-card.png")
            dumpHierarchy("library-book-card.xml")

            clickObject(By.desc("打开 $title，未知作者"))
            waitForObject(By.desc(EPUB_READER_DESC))
            waitForObject(By.textContains(OPENING_PARAGRAPH))
            ensureChromeVisible()

            val readerSurfaceSummary = scenario.withActivity { activity ->
                val readerSurface = activity.findReaderSurface()
                val node = checkNotNull(readerSurface.createAccessibilityNodeInfo()) {
                    "Unable to create accessibility node info for reader surface"
                }
                try {
                    ReaderSurfaceSummary(
                        contentDescription = readerSurface.contentDescription?.toString().orEmpty(),
                        actions = node.actionList.mapNotNull { it.label?.toString() },
                    )
                } finally {
                    node.recycle()
                }
            }

            waitForObject(By.desc("返回"))
            waitForObject(By.desc("添加书签"))
            waitForObject(By.desc("上一章"))
            waitForObject(By.desc("下一章"))
            val overallProgress = waitForObject(By.desc("全书进度，拖动跳转"))
            assertAdjustableTouchTarget("全书进度", overallProgress)
            waitForObject(By.descContains("第 1 / 2 章"))
            listOf("目录", "搜索", "书签", "标注", "排版", "主题").forEach { label ->
                waitForObject(By.desc(label))
            }
            takeScreenshot("reader-chrome.png")
            dumpHierarchy("reader-chrome.xml")

            openBottomPanel("目录", "A03 Start")
            val tocClose = waitForObject(By.desc("关闭目录"))
            assertTrue("expected TOC scrim to expose a close action", tocClose.isClickable)
            val tocCloseDescription = tocClose.contentDescription.orEmpty()
            val tocDescription = waitForObject(By.desc("1 级目录，A03 Start")).contentDescription.orEmpty()
            takeScreenshot("panel-toc.png")
            dumpHierarchy("panel-toc.xml")
            clickObject(By.desc("关闭目录"))

            openBottomPanel("搜索", "关键词")
            waitForObject(By.desc("执行搜索"))
            waitForObject(By.desc("清空搜索"))
            takeScreenshot("panel-search.png")
            dumpHierarchy("panel-search.xml")

            openBottomPanel("书签", "暂无书签")
            takeScreenshot("panel-bookmarks.png")
            dumpHierarchy("panel-bookmarks.xml")

            openBottomPanel("标注", "暂无标注")
            takeScreenshot("panel-annotations.png")
            dumpHierarchy("panel-annotations.xml")

            openBottomPanel("排版", "正文预览")
            val fontSlider = waitForObject(By.desc("字号"))
            val lineSpacingSlider = waitForObject(By.desc("行距"))
            assertAdjustableTouchTarget("字号", fontSlider)
            assertAdjustableTouchTarget("行距", lineSpacingSlider)
            val fontSliderDescription = fontSlider.contentDescription.orEmpty()
            val lineSpacingSliderDescription = lineSpacingSlider.contentDescription.orEmpty()
            val fontSizeLabel = waitForObject(By.text("16sp")).text.orEmpty()
            val lineSpacingLabel = waitForObject(By.text("1.75x")).text.orEmpty()
            waitForObject(By.text("滚动"))
            waitForObject(By.text("分页"))
            takeScreenshot("panel-font.png")
            dumpHierarchy("panel-font.xml")

            openBottomPanel("主题", "跟随系统")
            waitForObject(By.text("日间"))
            waitForObject(By.text("夜间"))
            waitForObject(By.text("护眼黄"))
            waitForObject(By.text("护眼绿"))
            takeScreenshot("panel-theme.png")
            dumpHierarchy("panel-theme.xml")

            copyDatabaseSnapshot("a03-accessibility")
            writeTextEvidence(
                "a03-accessibility-summary.txt",
                buildString {
                    appendLine("book_id=${importedBook.id}")
                    appendLine("title=$title")
                    appendLine("library_card_description=$libraryCardDescription")
                    appendLine("library_menu_description=$libraryMenuDescription")
                    appendLine("reader_surface_description=${readerSurfaceSummary.contentDescription}")
                    appendLine("reader_surface_actions=${readerSurfaceSummary.actions.joinToString("|")}")
                    appendLine("reader_chrome_labels=返回|添加书签|上一章|下一章|全书进度，拖动跳转|目录|搜索|书签|标注|排版|主题")
                    appendLine("toc_close_description=$tocCloseDescription")
                    appendLine("toc_item_description=$tocDescription")
                    appendLine("search_actions=执行搜索|清空搜索")
                    appendLine("empty_panels=暂无书签|暂无标注")
                    appendLine("font_slider_desc=$fontSliderDescription visible_label=$fontSizeLabel")
                    appendLine("line_spacing_slider_desc=$lineSpacingSliderDescription visible_label=$lineSpacingLabel")
                    appendLine("theme_options=跟随系统|日间|夜间|护眼黄|护眼绿")
                    appendLine("touch_target_min_dp=48")
                    appendLine("evidence_boundary=AVD instrumentation + UIAutomator/XML/screenshots; no physical device, human TalkBack speech, or full focus-order traversal")
                },
            )

            assertEquals("打开 $title，未知作者", libraryCardDescription)
            assertEquals("$title 的菜单", libraryMenuDescription)
            assertEquals(EPUB_READER_DESC, readerSurfaceSummary.contentDescription)
            assertTrue(
                "expected reader surface to expose toolbar toggle accessibility action",
                readerSurfaceSummary.actions.contains("显示或隐藏阅读工具栏"),
            )
            assertEquals("1 级目录，A03 Start", tocDescription)
        }
    }

    private fun readerIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "application/epub+zip")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("a03-accessibility-smoke", uri)
        }

    private fun createEpubUri(fileName: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeEpub(file)
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun writeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun addText(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            addText(
                "META-INF/container.xml",
                """
                    <container>
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent(),
            )
            addText(
                "OEBPS/content.opf",
                """
                    <package version="3.0">
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                      </spine>
                    </package>
                """.trimIndent(),
            )
            addText(
                "OEBPS/nav.xhtml",
                """
                    <html xmlns:epub="http://www.idpf.org/2007/ops">
                      <body>
                        <nav epub:type="toc">
                          <ol>
                            <li><a href="ch1.xhtml#start">A03 Start</a></li>
                            <li><a href="ch2.xhtml#deep-dive">A03 Deep Dive</a></li>
                          </ol>
                        </nav>
                      </body>
                    </html>
                """.trimIndent(),
            )
            addText(
                "OEBPS/ch1.xhtml",
                """
                    <html><body>
                      <h1 id="start">A03 Start</h1>
                      <p>$OPENING_PARAGRAPH</p>
                      <p>A03 second paragraph keeps reader chrome visible for accessibility smoke.</p>
                    </body></html>
                """.trimIndent(),
            )
            addText(
                "OEBPS/ch2.xhtml",
                """
                    <html><body>
                      <h1 id="deep-dive">A03 Deep Dive</h1>
                      <p>A03 second chapter gives the progress semantics a real 1 of 2 chapter context.</p>
                    </body></html>
                """.trimIndent(),
            )
        }
    }

    private fun openBottomPanel(buttonText: String, expectedText: String) {
        ensureChromeVisible()
        val clickedBounds = clickBottomControl(buttonText)
        if (waitForFreshObject(By.text(expectedText)) == null) {
            takeScreenshot("panel-$buttonText-click-failure.png")
            dumpHierarchy("panel-$buttonText-click-failure.xml")
            error(
                "Timed out waiting for panel text '$expectedText' after clicking " +
                    "'$buttonText' at $clickedBounds",
            )
        }
    }

    private fun waitForFreshObject(
        selector: BySelector,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            clearAccessibilityCache()
            device.findObject(selector)?.let { return it }
            Thread.sleep(100)
        }
        clearAccessibilityCache()
        return device.findObject(selector)
    }

    private fun ensureChromeVisible() {
        if (device.wait(Until.findObject(By.desc("目录")), 750) == null) {
            val reader = waitForObject(By.desc(EPUB_READER_DESC))
            reader.click()
            device.waitForIdle()
        }
        waitForObject(By.desc("目录"))
    }

    private fun clickBottomControl(label: String): android.graphics.Rect {
        val objectToClick = device.findObjects(By.desc(label))
            .maxByOrNull { it.visibleBounds.centerY() }
            ?: device.findObjects(By.text(label)).maxByOrNull { it.visibleBounds.centerY() }
            ?: error("Unable to find bottom control: $label")
        val clickTarget = generateSequence(objectToClick) { it.parent }
            .firstOrNull { it.isClickable }
            ?: error("Unable to find clickable ancestor for bottom control: $label")
        val bounds = clickTarget.visibleBounds
        clickTarget.click()
        device.waitForIdle()
        return bounds
    }

    private fun clickObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) {
        waitForObject(selector, timeoutMs).click()
        device.waitForIdle()
    }

    private fun resetTargetAppState() {
        appContext.deleteDatabase(DB_NAME)
        deleteIfExists(appContext.getDatabasePath(DB_NAME))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-wal"))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-shm"))
        deleteRecursively(File(appContext.filesDir, "books"))
        deleteRecursively(File(appContext.filesDir, "covers"))
        deleteIfExists(File(appContext.filesDir, "datastore/readflow_settings.preferences_pb"))
        markSeedBooksAsAlreadyImported()
    }

    private fun markSeedBooksAsAlreadyImported() {
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun waitForBookByTitle(title: String): BookEntity =
        waitForConditionResult("expected imported book row for title $title", DB_TIMEOUT_MS) {
            latestBookByTitle(title)
        }

    private fun latestBookByTitle(title: String): BookEntity? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking {
                db.bookDao().observeAll().first().lastOrNull { it.title == title }
            }
        } finally {
            db.close()
        }
    }

    private fun copyDatabaseSnapshot(label: String) {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        copyIfExists(dbFile, File(evidenceDir(), "$label-readflow.db"))
        copyIfExists(File(dbFile.path + "-wal"), File(evidenceDir(), "$label-readflow.db-wal"))
        copyIfExists(File(dbFile.path + "-shm"), File(evidenceDir(), "$label-readflow.db-shm"))
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun dumpHierarchy(name: String) {
        device.dumpWindowHierarchy(File(evidenceDir(), name))
    }

    private fun takeScreenshot(name: String) {
        device.takeScreenshot(File(evidenceDir(), name))
    }

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
    }

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("a03-accessibility-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun waitForObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
            "Timed out waiting for selector: $selector"
        }

    private fun assertAdjustableTouchTarget(label: String, target: UiObject2) {
        val minHeightPx = kotlin.math.ceil(
            48f * appContext.resources.displayMetrics.density,
        ).toInt()
        assertTrue(
            "$label touch target must be at least 48dp: ${target.visibleBounds}",
            target.visibleBounds.height() >= minHeightPx,
        )
        assertEquals("$label must expose one adjustable node", "android.widget.SeekBar", target.className)
        assertTrue(
            "$label must expose ACTION_SET_PROGRESS",
            accessibilityNodeHasAction(
                target.contentDescription.orEmpty(),
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            ),
        )
    }

    private fun accessibilityNodeHasAction(description: String, actionId: Int): Boolean {
        return withAccessibilityNode(description) { node ->
            node.actionList.any { it.id == actionId }
        }
    }

    private fun performAccessibilityNodeAction(description: String, actionId: Int): Boolean {
        return withAccessibilityNode(description) { node ->
            node.performAction(actionId)
        }
    }

    private fun withAccessibilityNode(
        description: String,
        block: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        clearAccessibilityCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.contentDescription?.toString() == description) {
                val result = block(node)
                node.recycle()
                pending.forEach(AccessibilityNodeInfo::recycle)
                return result
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(pending::addLast)
            }
            node.recycle()
        }
        return false
    }

    private fun clearAccessibilityCache() {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            automation.clearCache()
        } else {
            automation.serviceInfo = checkNotNull(automation.serviceInfo)
        }
    }

    private fun waitForConditionResult(
        message: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        producer: () -> BookEntity?,
    ): BookEntity {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            producer()?.let { return it }
            Thread.sleep(150)
        }
        return checkNotNull(producer()) { message }
    }

    private fun MainActivity.findReaderSurface(): View =
        checkNotNull(window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }) {
            "Unable to find reader surface"
        }

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun <T> ActivityScenario<MainActivity>.withActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { activity ->
            result = runCatching { block(activity) }
        }
        return checkNotNull(result) { "activity callback did not return a result" }.getOrThrow()
    }

    private fun copyIfExists(source: File, destination: File) {
        if (source.exists()) {
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    private data class ReaderSurfaceSummary(
        val contentDescription: String,
        val actions: List<String>,
    )

    private companion object {
        private const val DB_NAME = "readflow.db"
        private const val EPUB_READER_DESC = "阅读内容，捏合调整字号"
        private const val READER_GUIDE_DESC = "阅读手势引导，点击开始阅读"
        private const val OPENING_PARAGRAPH = "A03 opening paragraph proves TalkBack-ready reader text."
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
