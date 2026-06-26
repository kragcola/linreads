package dev.readflow.ux02

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.render.api.SelectionAwareTextView
import java.io.File
import java.util.UUID
import kotlin.math.max
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
class ReaderPinchRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        settings.setFontSize(18)
        settings.setLineSpacing(1.75f)
        settings.setThemeMode(ThemeMode.SYSTEM)
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
    fun txtPinchUpdatesVisibleFontPreviewAndPersistsFontSize() {
        val readerUri = createTxtUri(
            listOf(
                "Pinch smoke TXT paragraph 000 keeps the reader stable for font scaling validation.",
                "Pinch smoke TXT paragraph 001 gives the runtime enough content to keep a visible text row.",
                "Pinch smoke TXT paragraph 002 makes sure a second row stays on screen after preview updates.",
                "Pinch smoke TXT paragraph 003 keeps the scroll list alive during gesture replay.",
            ).joinToString("\n\n"),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "text/plain")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(TXT_READER_DESC))
            waitForObject(By.textContains("Pinch smoke TXT paragraph 000"))
            openFontPanel(TXT_READER_DESC)
            val baselineFontLabel = currentFontSizeLabel()
            takeScreenshot("txt-font-before.png")

            val baselineTextSizePx = scenario.withActivity { activity ->
                activity.findVisibleTxtTextView().textSize
            }

            dispatchPinchOut(scenario.withActivity { activity -> activity.findReaderSurface() })

            waitForCondition("expected TXT visible text size to increase after pinch preview") {
                scenario.withActivity { activity ->
                    activity.findVisibleTxtTextView().textSize > baselineTextSizePx + 1f
                }
            }
            waitForCondition("expected persisted TXT font size to move beyond baseline") {
                runBlocking { settings.fontSize.first() > 18 }
            }

            val afterTextSizePx = scenario.withActivity { activity ->
                activity.findVisibleTxtTextView().textSize
            }
            val persistedFontSize = runBlocking { settings.fontSize.first() }
            val visibleSpLabel = currentFontSizeLabel()

            takeScreenshot("txt-font-after-pinch.png")
            writeTextEvidence(
                "txt-summary.txt",
                buildString {
                    appendLine("baseline_font_label=$baselineFontLabel")
                    appendLine("after_font_label=$visibleSpLabel")
                    appendLine("baseline_text_size_px=$baselineTextSizePx")
                    appendLine("after_text_size_px=$afterTextSizePx")
                    appendLine("persisted_font_size_sp=$persistedFontSize")
                },
            )

            assertTrue(
                "expected TXT text size to increase after pinch preview",
                afterTextSizePx > baselineTextSizePx,
            )
            assertTrue(
                "expected persisted TXT font size to move beyond 18sp baseline",
                persistedFontSize > 18,
            )
        }
    }

    @Test
    fun pdfPinchAppliesMatrixZoomToVisiblePage() {
        val readerUri = createPdfUri()

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "application/pdf")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(PDF_READER_DESC))
            waitForObject(By.desc(PDF_PAGE_DESC))
            waitForCondition("expected PDF page drawable to be ready before zoom check") {
                scenario.withActivity { activity ->
                    activity.findVisiblePdfPageView().drawable != null
                }
            }

            val baselineScaleType = scenario.withActivity { activity ->
                activity.findVisiblePdfPageView().scaleType
            }
            takeScreenshot("pdf-before-pinch.png")

            dispatchPinchOut(scenario.withActivity { activity -> activity.findReaderSurface() })

            waitForCondition("expected PDF pinch to switch the visible page into matrix zoom") {
                scenario.withActivity { activity ->
                    activity.findVisiblePdfPageView().scaleType == ImageView.ScaleType.MATRIX
                }
            }

            val afterScaleType = scenario.withActivity { activity ->
                activity.findVisiblePdfPageView().scaleType
            }
            val matrixValues = scenario.withActivity { activity ->
                FloatArray(9).also(activity.findVisiblePdfPageView().imageMatrix::getValues)
            }

            takeScreenshot("pdf-after-pinch.png")
            writeTextEvidence(
                "pdf-summary.txt",
                buildString {
                    appendLine("baseline_scale_type=$baselineScaleType")
                    appendLine("after_scale_type=$afterScaleType")
                    appendLine("matrix_scale_x=${matrixValues[0]}")
                    appendLine("matrix_scale_y=${matrixValues[4]}")
                    appendLine("matrix_translate_x=${matrixValues[2]}")
                    appendLine("matrix_translate_y=${matrixValues[5]}")
                },
            )

            assertEquals(ImageView.ScaleType.FIT_CENTER, baselineScaleType)
            assertEquals(ImageView.ScaleType.MATRIX, afterScaleType)
        }
    }

    private fun readerIntent(uri: Uri, mimeType: String) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("ux02-reader-smoke", uri)
        }

    private fun createTxtUri(content: String): Uri {
        val file = File(appContext.cacheDir, "ux02-${UUID.randomUUID()}.txt")
        file.writeText(content)
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun createPdfUri(): Uri {
        val file = File(appContext.cacheDir, "ux02-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(1200, 1800, 1).create()
        val page = document.startPage(pageInfo)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 42f
            isAntiAlias = true
        }
        page.canvas.drawColor(Color.WHITE)
        repeat(20) { index ->
            page.canvas.drawText(
                "Pinch smoke PDF line ${index.toString().padStart(2, '0')}",
                72f,
                120f + index * 72f,
                paint,
            )
        }
        document.finishPage(page)
        file.outputStream().use(document::writeTo)
        document.close()
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun openFontPanel(readerDescription: String) {
        waitForObject(By.desc(readerDescription)).click()
        waitForObject(By.text("排版")).click()
        waitForObject(By.text("阅读正文预览"))
    }

    private fun currentFontSizeLabel(): String =
        waitForObject(By.textContains("sp")).text

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun dispatchPinchOut(target: View) {
        val width = target.width.toFloat().coerceAtLeast(1f)
        val height = target.height.toFloat().coerceAtLeast(1f)
        val centerX = width / 2f
        val centerY = max(height * 0.35f, 120f)
        val maxHalfSpan = (width / 2f) - 32f
        val startHalfSpan = max(width * 0.04f, 32f).coerceAtMost(maxHalfSpan)
        val endHalfSpan = max(width * 0.32f, 180f).coerceAtMost(maxHalfSpan)
        val pointerOneStart = Point(centerX - startHalfSpan, centerY)
        val pointerTwoStart = Point(centerX + startHalfSpan, centerY)
        val pointerOneEnd = Point(centerX - endHalfSpan, centerY)
        val pointerTwoEnd = Point(centerX + endHalfSpan, centerY)

        dispatchPinchGesture(
            target = target,
            pointerOneStart = pointerOneStart,
            pointerTwoStart = pointerTwoStart,
            pointerOneEnd = pointerOneEnd,
            pointerTwoEnd = pointerTwoEnd,
        )
    }

    private fun dispatchPinchGesture(
        target: View,
        pointerOneStart: Point,
        pointerTwoStart: Point,
        pointerOneEnd: Point,
        pointerTwoEnd: Point,
        steps: Int = 6,
    ) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        dispatchTouchEvent(
            target,
            motionEvent(
                downTime = downTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_DOWN,
                coordinates = listOf(pointerOneStart),
            ),
        )

        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(
            target,
            motionEvent(
                downTime = downTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                coordinates = listOf(pointerOneStart, pointerTwoStart),
            ),
        )

        repeat(steps) { step ->
            val progress = (step + 1) / steps.toFloat()
            eventTime += EVENT_STEP_MS
            dispatchTouchEvent(
                target,
                motionEvent(
                    downTime = downTime,
                    eventTime = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    coordinates = listOf(
                        pointerOneStart.interpolateTo(pointerOneEnd, progress),
                        pointerTwoStart.interpolateTo(pointerTwoEnd, progress),
                    ),
                ),
            )
        }

        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(
            target,
            motionEvent(
                downTime = downTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                coordinates = listOf(pointerOneEnd, pointerTwoEnd),
            ),
        )

        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(
            target,
            motionEvent(
                downTime = downTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_UP,
                coordinates = listOf(pointerOneEnd),
            ),
        )
    }

    private fun dispatchTouchEvent(target: View, event: MotionEvent) {
        try {
            instrumentation.runOnMainSync {
                target.dispatchTouchEvent(event)
            }
        } finally {
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        coordinates: List<Point>,
    ): MotionEvent {
        val properties = Array(coordinates.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val pointerCoords = Array(coordinates.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = coordinates[index].x
                y = coordinates[index].y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            coordinates.size,
            properties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun MainActivity.findReaderSurface(): View =
        checkNotNull(window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }) {
            "Unable to find reader surface"
        }

    private fun MainActivity.findVisibleTxtTextView(): SelectionAwareTextView =
        checkNotNull(findReaderSurface().findDescendant { view ->
            view is SelectionAwareTextView && view.isShown && view.text?.isNotBlank() == true
        } as? SelectionAwareTextView) {
            "Unable to find visible TXT text view"
        }

    private fun MainActivity.findVisiblePdfPageView(): ImageView =
        checkNotNull(findReaderSurface().findDescendant { view ->
            view is ImageView &&
                view.isShown &&
                view.contentDescription?.toString()?.startsWith("第 ") == true
        } as? ImageView) {
            "Unable to find visible PDF page view"
        }

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun waitForObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
            "Timed out waiting for selector: $selector"
        }

    private fun waitForCondition(
        message: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        check(condition()) { message }
    }

    private fun takeScreenshot(name: String) {
        device.takeScreenshot(File(evidenceDir(), name))
    }

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
    }

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("ux02-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun <T> ActivityScenario<MainActivity>.withActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { activity ->
            result = runCatching { block(activity) }
        }
        return checkNotNull(result) { "activity callback did not return a result" }.getOrThrow()
    }

    private data class Point(val x: Float, val y: Float) {
        fun interpolateTo(other: Point, progress: Float): Point =
            Point(
                x = x + (other.x - x) * progress,
                y = y + (other.y - y) * progress,
            )
    }

    private companion object {
        private const val TXT_READER_DESC = "阅读内容，捏合调整字号"
        private const val PDF_READER_DESC = "阅读内容，捏合缩放页面"
        private const val PDF_PAGE_DESC = "第 1 页，共 1 页"
        private const val EVENT_STEP_MS = 16L
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
