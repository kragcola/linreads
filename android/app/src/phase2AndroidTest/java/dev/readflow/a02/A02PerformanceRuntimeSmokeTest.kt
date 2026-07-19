package dev.readflow.a02

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
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
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.render.pdf.PdfRendererEngine
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class A02PerformanceRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        resetTargetAppState()
        // resetTargetAppState deletes DataStore; keep the first-run gesture guide from covering
        // the reader surface that this runtime performance smoke measures.
        settings.setReaderGuideShown(true)
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
    fun recordsAvdPerformanceProxyForCoreReadingFormats() {
        writeTextEvidence(
            "environment.txt",
            buildString {
                appendLine("device=${shell("getprop ro.product.model").trim()}")
                appendLine("sdk=${shell("getprop ro.build.version.sdk").trim()}")
                appendLine("package=${appContext.packageName}")
                appendLine("installed_source_apk_bytes=${File(appContext.applicationInfo.sourceDir).length()}")
                appendLine("native_lib_dir=${appContext.applicationInfo.nativeLibraryDir}")
                appendLine("native_libs=${nativeLibraryNames().joinToString("|")}")
                appendLine("contains_mupdf_native=${nativeLibraryNames().any { it.contains("mupdf", ignoreCase = true) }}")
                appendLine("boundary=AVD instrumentation proxy: includes test runner/process noise; not real phone/tablet benchmark")
            },
        )

        val results = listOf(
            measureOpenAndGesture(
                caseName = "txt-1mb",
                uri = createTxtUri(),
                mimeType = "text/plain",
                firstPaintSelector = By.textContains("A02 TXT line 000000"),
                gesture = Gesture.Scroll,
            ),
            measureOpenAndGesture(
                caseName = "epub-1mb",
                uri = createEpubUri(),
                mimeType = "application/epub+zip",
                firstPaintSelector = By.textContains("A02 EPUB paragraph 0000"),
                gesture = Gesture.Scroll,
            ),
            measureOpenAndGesture(
                caseName = "pdf-20p",
                uri = createPdfUri(totalPages = PDF_PAGE_COUNT),
                mimeType = "application/pdf",
                firstPaintSelector = By.desc("第 1 页，共 $PDF_PAGE_COUNT 页"),
                gesture = Gesture.PageForward,
            ),
        )

        writeTextEvidence(
            "a02-performance-summary.txt",
            buildString {
                appendLine("case_count=${results.size}")
                results.forEach { result ->
                    appendLine(
                        listOf(
                            "case=${result.caseName}",
                            "first_paint_ms=${result.firstPaintMs}",
                            "total_pss_kb=${result.totalPssKb}",
                            "java_heap_pss_kb=${result.javaHeapPssKb}",
                            "native_heap_pss_kb=${result.nativeHeapPssKb}",
                            "gfx_total_frames=${result.gfxTotalFrames ?: "unknown"}",
                            "gfx_janky_frames=${result.gfxJankyFrames ?: "unknown"}",
                            "gfx_p90_ms=${result.gfxP90Ms ?: "unknown"}",
                            "gfx_p95_ms=${result.gfxP95Ms ?: "unknown"}",
                            "reader_probe=${result.readerProbe}",
                        ).joinToString(" | "),
                    )
                }
                appendLine("remaining_boundary=not a real device/tablet run; not human-perceived frame pacing; keep A-02 PARTIAL")
            },
        )

        assertTrue(results.all { it.firstPaintMs > 0 })
        assertTrue(results.all { it.totalPssKb > 0 })
    }

    @Test
    fun pdfEngineOpensGeneratedDocumentOnApi36Runtime() = runBlocking {
        val engine = PdfRendererEngine(appContext)
        try {
            engine.openBook(createPdfUri(totalPages = 4))
            assertEquals(4, engine.pageCount.value)
        } finally {
            engine.close()
        }
    }

    private fun measureOpenAndGesture(
        caseName: String,
        uri: Uri,
        mimeType: String,
        firstPaintSelector: BySelector,
        gesture: Gesture,
    ): PerfCaseResult {
        val startedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch<MainActivity>(readerIntent(uri, mimeType)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val firstPaint = waitForObject(firstPaintSelector)
            val readerProbe = firstPaint.textOrDescription()
            val firstPaintMs = SystemClock.elapsedRealtime() - startedAt
            val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)

            takeScreenshot("$caseName-first-paint.png")
            dumpHierarchy("$caseName-first-paint.xml")
            writeTextEvidence("$caseName-meminfo.txt", shell("dumpsys meminfo ${appContext.packageName}"))

            shell("dumpsys gfxinfo ${appContext.packageName} reset")
            when (gesture) {
                Gesture.Scroll -> {
                    val width = device.displayWidth
                    val height = device.displayHeight
                    device.swipe(width / 2, (height * 0.78f).toInt(), width / 2, (height * 0.28f).toInt(), 24)
                    device.waitForIdle()
                }
                Gesture.PageForward -> {
                    val handled = scenario.withActivity { activity ->
                        checkNotNull(activity.window.decorView.findDescendant { view ->
                            view.contentDescription?.toString()?.startsWith("阅读内容") == true
                        }) {
                            "Unable to find reader surface"
                        }.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    }
                    check(handled) { "PDF page-forward accessibility action was not handled" }
                    instrumentation.waitForIdleSync()
                    device.waitForIdle()
                    waitForObject(By.desc("第 2 页，共 $PDF_PAGE_COUNT 页"))
                }
            }
            val gfxInfo = shell("dumpsys gfxinfo ${appContext.packageName}")
            writeTextEvidence("$caseName-gfxinfo.txt", gfxInfo)
            takeScreenshot("$caseName-after-gesture.png")

            val gfxSummary = parseGfxInfo(gfxInfo)
            val result = PerfCaseResult(
                caseName = caseName,
                firstPaintMs = firstPaintMs,
                totalPssKb = memoryInfo.totalPss,
                javaHeapPssKb = memoryInfo.dalvikPss,
                nativeHeapPssKb = memoryInfo.nativePss,
                gfxTotalFrames = gfxSummary.totalFrames,
                gfxJankyFrames = gfxSummary.jankyFrames,
                gfxP90Ms = gfxSummary.p90Ms,
                gfxP95Ms = gfxSummary.p95Ms,
                readerProbe = readerProbe,
            )
            writeTextEvidence(
                "$caseName-summary.txt",
                buildString {
                    appendLine("first_paint_ms=${result.firstPaintMs}")
                    appendLine("total_pss_kb=${result.totalPssKb}")
                    appendLine("java_heap_pss_kb=${result.javaHeapPssKb}")
                    appendLine("native_heap_pss_kb=${result.nativeHeapPssKb}")
                    appendLine("gfx_total_frames=${result.gfxTotalFrames ?: "unknown"}")
                    appendLine("gfx_janky_frames=${result.gfxJankyFrames ?: "unknown"}")
                    appendLine("gfx_p90_ms=${result.gfxP90Ms ?: "unknown"}")
                    appendLine("gfx_p95_ms=${result.gfxP95Ms ?: "unknown"}")
                    appendLine("reader_probe=${result.readerProbe}")
                },
            )
            return result
        }
    }

    private fun readerIntent(uri: Uri, mimeType: String) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("a02-performance-runtime-smoke", uri)
        }

    private fun createTxtUri(): Uri {
        val file = File(appContext.cacheDir, "a02-${UUID.randomUUID()}.txt")
        val line = "A02 TXT line %06d: performance proxy corpus keeps hard wrapped local reading text stable.\n"
        file.bufferedWriter().use { writer ->
            var index = 0
            while (file.length() < ONE_MIB_BYTES) {
                writer.write(line.format(index))
                index += 1
                if (index % 256 == 0) writer.flush()
            }
        }
        return cacheFileUri(file)
    }

    private fun createEpubUri(): Uri {
        val file = File(appContext.cacheDir, "a02-${UUID.randomUUID()}.epub")
        val paragraphs = buildString {
            var index = 0
            while (length < ONE_MIB_BYTES) {
                append("<p>A02 EPUB paragraph ${index.toString().padStart(4, '0')} keeps generated runtime performance text stable across reader import, parse, and scroll.</p>\n")
                index += 1
            }
        }
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
                        <item id="c0" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="c0"/>
                      </spine>
                    </package>
                """.trimIndent(),
            )
            addText(
                "OEBPS/chapter.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        $paragraphs
                      </body>
                    </html>
                """.trimIndent(),
            )
        }
        return cacheFileUri(file)
    }

    private fun createPdfUri(totalPages: Int): Uri {
        val file = File(appContext.cacheDir, "a02-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        repeat(totalPages) { pageIndex ->
            val pageInfo = PdfDocument.PageInfo.Builder(1200, 1800, pageIndex + 1).create()
            val page = document.startPage(pageInfo)
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 42f
                isAntiAlias = true
            }
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("A02 PDF page ${(pageIndex + 1).toString().padStart(2, '0')}", 72f, 120f, paint)
            repeat(22) { lineIndex ->
                page.canvas.drawText(
                    "Performance proxy line ${(lineIndex + 1).toString().padStart(2, '0')}",
                    72f,
                    240f + lineIndex * 64f,
                    paint,
                )
            }
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
        return cacheFileUri(file)
    }

    private fun cacheFileUri(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    private fun parseGfxInfo(output: String): GfxSummary =
        GfxSummary(
            totalFrames = output.lineValueAfter("Total frames rendered:")?.toIntOrNull(),
            jankyFrames = output.lineValueAfter("Janky frames:")?.substringBefore(" ")?.toIntOrNull(),
            p90Ms = output.lineValueAfter("90th percentile:")?.removeSuffix("ms")?.trim()?.toIntOrNull(),
            p95Ms = output.lineValueAfter("95th percentile:")?.removeSuffix("ms")?.trim()?.toIntOrNull(),
        )

    private fun String.lineValueAfter(prefix: String): String? =
        lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()

    private fun resetTargetAppState() {
        appContext.deleteDatabase(DB_NAME)
        deleteIfExists(appContext.getDatabasePath(DB_NAME))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-wal"))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-shm"))
        deleteRecursively(File(appContext.filesDir, "books"))
        deleteRecursively(File(appContext.filesDir, "covers"))
        deleteChildrenRecursively(appContext.cacheDir)
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

    private fun nativeLibraryNames(): List<String> =
        File(appContext.applicationInfo.nativeLibraryDir)
            .listFiles()
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
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
        checkNotNull(appContext.getExternalFilesDir("a02-performance-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun waitForObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
            "Timed out waiting for selector: $selector"
        }

    private fun UiObject2.textOrDescription(): String =
        text ?: contentDescription.orEmpty()

    private fun <T> ActivityScenario<MainActivity>.withActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { activity ->
            result = runCatching { block(activity) }
        }
        return checkNotNull(result) { "activity callback did not return a result" }.getOrThrow()
    }

    private fun android.view.View.findDescendant(predicate: (android.view.View) -> Boolean): android.view.View? {
        if (predicate(this)) return this
        val group = this as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) file.delete()
    }

    private fun deleteRecursively(file: File) {
        if (file.exists()) file.deleteRecursively()
    }

    private fun deleteChildrenRecursively(directory: File) {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    private enum class Gesture { Scroll, PageForward }

    private data class PerfCaseResult(
        val caseName: String,
        val firstPaintMs: Long,
        val totalPssKb: Int,
        val javaHeapPssKb: Int,
        val nativeHeapPssKb: Int,
        val gfxTotalFrames: Int?,
        val gfxJankyFrames: Int?,
        val gfxP90Ms: Int?,
        val gfxP95Ms: Int?,
        val readerProbe: String,
    )

    private data class GfxSummary(
        val totalFrames: Int?,
        val jankyFrames: Int?,
        val p90Ms: Int?,
        val p95Ms: Int?,
    )

    private companion object {
        private const val DB_NAME = "readflow.db"
        private const val ONE_MIB_BYTES = 1_048_576L
        private const val PDF_PAGE_COUNT = 20
        private val UI_TIMEOUT_MS = 30.seconds.inWholeMilliseconds
    }
}
