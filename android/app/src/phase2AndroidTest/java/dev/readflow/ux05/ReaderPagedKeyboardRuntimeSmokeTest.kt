package dev.readflow.ux05

import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TransitionType
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.render.animate.ViewPagerTransitionHost
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderPagedKeyboardRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        settings.setFontSize(18)
        settings.setLineSpacing(1.75f)
        settings.setThemeMode(ThemeMode.LIGHT)
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
    fun pdfReaderContainerExposesPagedAccessibilityActionsAndKeyboardNavigation() {
        val totalPages = 4
        val readerUri = createPdfUri(totalPages = totalPages)

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "application/pdf")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(PDF_READER_DESC))
            waitForCondition("expected first PDF page to render") {
                scenario.withActivity { activity ->
                    activity.currentPdfPageLabelOrNull() == pageLabel(pageIndex = 0, totalPages = totalPages)
                }
            }

            val baseline = scenario.withActivity { activity ->
                val readerSurface = activity.findReaderSurface()
                val pager = activity.findPdfPagerHost()
                val node = checkNotNull(readerSurface.createAccessibilityNodeInfo()) {
                    "Unable to create accessibility node info for the reader surface"
                }
                try {
                    KeyboardRuntimeSummary(
                        baselineLabel = activity.currentPdfPageLabel(),
                        currentItem = pager.pagerCurrentItem(),
                        itemCount = pager.pagerAdapterItemCount(),
                        transformerClass = pager.pageTransformerClassName().orEmpty(),
                        accessibilityActions = node.actionList.mapNotNull { it.label?.toString() },
                    )
                } finally {
                    node.recycle()
                }
            }
            takeScreenshot("keyboard-baseline.png")
            dumpHierarchy("keyboard-baseline.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 1,
                totalPages = totalPages,
                message = "expected accessibility scroll forward to advance to page 2",
            )

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 0,
                totalPages = totalPages,
                message = "expected accessibility scroll backward to return to page 1",
            )

            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 1,
                totalPages = totalPages,
                message = "expected DPAD_RIGHT to advance to page 2",
            )
            val afterRight = scenario.withActivity { activity ->
                PageSnapshot(
                    label = activity.currentPdfPageLabel(),
                    currentItem = activity.findPdfPagerHost().pagerCurrentItem(),
                )
            }

            sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 0,
                totalPages = totalPages,
                message = "expected DPAD_LEFT to return to page 1",
            )

            sendKey(KeyEvent.KEYCODE_PAGE_DOWN)
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 1,
                totalPages = totalPages,
                message = "expected PAGE_DOWN to advance to page 2",
            )

            sendKey(KeyEvent.KEYCODE_PAGE_UP)
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 0,
                totalPages = totalPages,
                message = "expected PAGE_UP to return to page 1",
            )

            sendKey(KeyEvent.KEYCODE_SPACE)
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 1,
                totalPages = totalPages,
                message = "expected SPACE to advance to page 2",
            )

            sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
            waitForObject(By.text("排版"))
            waitForObject(By.text("主题"))
            takeScreenshot("keyboard-after-center.png")
            dumpHierarchy("keyboard-after-center.xml")

            writeTextEvidence(
                "keyboard-runtime-summary.txt",
                buildString {
                    appendLine("baseline_label=${baseline.baselineLabel}")
                    appendLine("baseline_current_item=${baseline.currentItem}")
                    appendLine("baseline_item_count=${baseline.itemCount}")
                    appendLine("baseline_transformer=${baseline.transformerClass}")
                    appendLine("baseline_actions=${baseline.accessibilityActions.joinToString("|")}")
                    appendLine("after_dpad_right_label=${afterRight.label}")
                    appendLine("after_dpad_right_item=${afterRight.currentItem}")
                    appendLine("after_center_chrome=true")
                },
            )

            assertEquals(pageLabel(pageIndex = 0, totalPages = totalPages), baseline.baselineLabel)
            assertEquals(0, baseline.currentItem)
            assertEquals(totalPages, baseline.itemCount)
            assertTrue(
                "expected reader surface accessibility actions to expose previous page",
                baseline.accessibilityActions.contains("上一页"),
            )
            assertTrue(
                "expected reader surface accessibility actions to expose next page",
                baseline.accessibilityActions.contains("下一页"),
            )
            assertTrue(
                "expected reader surface accessibility actions to expose toolbar toggle",
                baseline.accessibilityActions.contains("显示或隐藏阅读工具栏"),
            )
            assertTrue(
                "expected default paged host to use the curl transformer",
                baseline.transformerClass.contains("CurlPageTransformer"),
            )
            assertEquals(pageLabel(pageIndex = 1, totalPages = totalPages), afterRight.label)
            assertEquals(1, afterRight.currentItem)
        }
    }

    @Test
    fun pdfPagedHostResetsZoomWhenPageChanges() {
        val totalPages = 4
        val readerUri = createPdfUri(totalPages = totalPages)

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "application/pdf")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(PDF_READER_DESC))
            waitForCondition("expected first PDF page drawable to be ready before zoom check") {
                scenario.withActivity { activity ->
                    activity.currentPdfPageViewOrNull()?.drawable != null
                }
            }

            val baselineScaleType = scenario.withActivity { activity ->
                activity.currentPdfPageView().scaleType
            }
            takeScreenshot("zoom-reset-before-pinch.png")

            dispatchPdfZoomPreview(
                target = scenario.withActivity { activity -> activity.findReaderSurface() },
                scale = 1.5f,
            )

            waitForCondition("expected PDF zoom preview callback to switch current page into matrix zoom") {
                scenario.withActivity { activity ->
                    activity.findReaderSurface().readerSurfaceCurrentZoomScale() ?: 1f > 1f &&
                        activity.currentPdfPageView().scaleType == ImageView.ScaleType.MATRIX
                }
            }
            val afterZoomPreview = scenario.withActivity { activity ->
                ZoomPreviewSnapshot(
                    scaleType = activity.currentPdfPageView().scaleType,
                    zoomScale = activity.findReaderSurface().readerSurfaceCurrentZoomScale() ?: 1f,
                )
            }
            takeScreenshot("zoom-reset-after-pinch.png")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCurrentPage(
                scenario = scenario,
                pageIndex = 1,
                totalPages = totalPages,
                message = "expected accessibility page change after pinch to advance to page 2",
            )
            waitForCondition("expected current PDF page zoom to reset after page change") {
                scenario.withActivity { activity ->
                    activity.findReaderSurface().readerSurfaceCurrentZoomScale() == 1f &&
                        activity.currentPdfPageView().scaleType == ImageView.ScaleType.FIT_CENTER
                }
            }
            val afterPageChange = scenario.withActivity { activity ->
                ZoomResetSnapshot(
                    scaleType = activity.currentPdfPageView().scaleType,
                    label = activity.currentPdfPageLabel(),
                    zoomScale = activity.findReaderSurface().readerSurfaceCurrentZoomScale() ?: Float.NaN,
                )
            }
            takeScreenshot("zoom-reset-after-page-change.png")

            writeTextEvidence(
                "zoom-reset-summary.txt",
                buildString {
                    appendLine("baseline_scale_type=$baselineScaleType")
                    appendLine("after_zoom_preview_scale=${afterZoomPreview.zoomScale}")
                    appendLine("after_zoom_preview_scale_type=${afterZoomPreview.scaleType}")
                    appendLine("after_page_change_scale_type=${afterPageChange.scaleType}")
                    appendLine("after_page_change_zoom_scale=${afterPageChange.zoomScale}")
                    appendLine("after_page_change_label=${afterPageChange.label}")
                },
            )

            assertEquals(ImageView.ScaleType.FIT_CENTER, baselineScaleType)
            assertTrue(afterZoomPreview.zoomScale > 1f)
            assertEquals(ImageView.ScaleType.MATRIX, afterZoomPreview.scaleType)
            assertEquals(ImageView.ScaleType.FIT_CENTER, afterPageChange.scaleType)
            assertEquals(1f, afterPageChange.zoomScale)
            assertEquals(pageLabel(pageIndex = 1, totalPages = totalPages), afterPageChange.label)
        }
    }

    @Test
    fun viewPagerHostSupportsFadeAndSlideTransformersAtRuntime() {
        val result = instrumentation.runOnMainSyncWithResult {
            val host = ViewPagerTransitionHost(appContext, TransitionType.FADE)
            val pager = host.hostView()
            val engine = FakePagedEngine(appContext, initialPageCount = 3)
            host.bind(engine)

            val fadeTransformer = pager.pageTransformerClassName().orEmpty()
            val itemCount = pager.pagerAdapterItemCount()
            host.setTransition(TransitionType.NONE)
            runBlocking { host.next() }
            val afterNext = pager.pagerCurrentItem()

            host.setTransition(TransitionType.SLIDE)
            val slideTransformer = pager.pageTransformerClassName().orEmpty()
            host.setTransition(TransitionType.NONE)
            runBlocking { host.previous() }
            val afterPrevious = pager.pagerCurrentItem()
            host.unbind()

            HostTransformerSummary(
                fadeTransformer = fadeTransformer,
                slideTransformer = slideTransformer,
                itemCount = itemCount,
                afterNext = afterNext,
                afterPrevious = afterPrevious,
            )
        }

        writeTextEvidence(
            "host-transformer-summary.txt",
            buildString {
                appendLine("fade_transformer=${result.fadeTransformer}")
                appendLine("slide_transformer=${result.slideTransformer}")
                appendLine("item_count=${result.itemCount}")
                appendLine("after_next=${result.afterNext}")
                appendLine("after_previous=${result.afterPrevious}")
            },
        )

        assertTrue(
            "expected fade host to install the fade transformer",
            result.fadeTransformer.contains("FadePageTransformer"),
        )
        assertTrue(
            "expected slide host to map back to the reset/native transformer path",
            result.slideTransformer.contains("ResetPageTransformer"),
        )
        assertEquals(3, result.itemCount)
        assertEquals(1, result.afterNext)
        assertEquals(0, result.afterPrevious)
    }

    private fun readerIntent(uri: Uri, mimeType: String) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("ux05-reader-smoke", uri)
        }

    private fun createPdfUri(totalPages: Int): Uri {
        val file = File(appContext.cacheDir, "ux05-${UUID.randomUUID()}.pdf")
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
            page.canvas.drawText(
                "Keyboard/runtime smoke page ${(pageIndex + 1).toString().padStart(2, '0')}",
                72f,
                120f,
                paint,
            )
            repeat(18) { lineIndex ->
                page.canvas.drawText(
                    "Page ${pageIndex + 1} line ${(lineIndex + 1).toString().padStart(2, '0')}",
                    72f,
                    240f + lineIndex * 72f,
                    paint,
                )
            }
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun performReaderAccessibilityAction(
        scenario: ActivityScenario<MainActivity>,
        action: Int,
    ) {
        val handled = scenario.withActivity { activity ->
            activity.findReaderSurface().performAccessibilityAction(action, null)
        }
        check(handled) { "Accessibility action $action was not handled by the reader surface" }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun sendKey(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun waitForCurrentPage(
        scenario: ActivityScenario<MainActivity>,
        pageIndex: Int,
        totalPages: Int,
        message: String,
    ) {
        waitForCondition(message) {
            scenario.withActivity { activity ->
                activity.findPdfPagerHost().pagerCurrentItem() == pageIndex &&
                    activity.currentPdfPageLabelOrNull() == pageLabel(pageIndex, totalPages)
            }
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
        dispatchPinchGesture(
            target = target,
            pointerOneStart = Point(centerX - startHalfSpan, centerY),
            pointerTwoStart = Point(centerX + startHalfSpan, centerY),
            pointerOneEnd = Point(centerX - endHalfSpan, centerY),
            pointerTwoEnd = Point(centerX + endHalfSpan, centerY),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatchPdfZoomPreview(target: View, scale: Float) {
        instrumentation.runOnMainSync {
            val callbackField = target.javaClass.getDeclaredField("onZoomPreview").apply {
                isAccessible = true
            }
            val callback = callbackField.get(target) as (Float) -> Unit
            callback(scale)
        }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
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
        checkNotNull(appContext.getExternalFilesDir("ux05-page-runtime-smoke")) {
            "external files dir unavailable"
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

    private fun pageLabel(pageIndex: Int, totalPages: Int): String =
        "第 ${pageIndex + 1} 页，共 $totalPages 页"

    private fun MainActivity.findReaderSurface(): View =
        checkNotNull(window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }) {
            "Unable to find reader surface"
        }

    private fun MainActivity.findPdfPagerHost(): View =
        checkNotNull(findReaderSurface().findDescendant { it.javaClass.name == VIEW_PAGER_CLASS_NAME }) {
            "Unable to find ViewPager2 paged host"
        }

    private fun MainActivity.currentPdfPageView(): ImageView {
        return checkNotNull(currentPdfPageViewOrNull()) {
            "Unable to find current PDF page view for item ${findPdfPagerHost().pagerCurrentItem()}"
        }
    }

    private fun MainActivity.currentPdfPageViewOrNull(): ImageView? {
        val pager = findPdfPagerHost()
        val currentItem = pager.pagerCurrentItem()
        return findReaderSurface().findDescendant { view ->
            view is ImageView &&
                view.isShown &&
                view.tag == currentItem &&
                view.contentDescription?.toString()?.startsWith("第 ") == true
        } as? ImageView
    }

    private fun MainActivity.currentPdfPageLabel(): String =
        currentPdfPageView().contentDescription?.toString().orEmpty()

    private fun MainActivity.currentPdfPageLabelOrNull(): String? =
        currentPdfPageViewOrNull()?.contentDescription?.toString()

    private fun View.pageTransformerClassName(): String? =
        runCatching {
            val adapterField = javaClass.getDeclaredField("mPageTransformerAdapter").apply {
                isAccessible = true
            }
            val adapter = adapterField.get(this)
            val getPageTransformer = adapter.javaClass.getDeclaredMethod("getPageTransformer").apply {
                isAccessible = true
            }
            getPageTransformer.invoke(adapter)?.javaClass?.name
        }.getOrNull()

    private fun View.pagerCurrentItem(): Int =
        runCatching {
            javaClass.getMethod("getCurrentItem").invoke(this) as Int
        }.getOrDefault(0)

    private fun View.pagerAdapterItemCount(): Int =
        runCatching {
            val adapter = javaClass.getMethod("getAdapter").invoke(this) ?: return@runCatching -1
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        }.getOrDefault(-1)

    private fun View.readerSurfaceCurrentZoomScale(): Float? =
        runCatching {
            val field = javaClass.getDeclaredField("currentZoomScale").apply {
                isAccessible = true
            }
            field.getFloat(this)
        }.getOrNull()

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

    private fun <T> Instrumentation.runOnMainSyncWithResult(
        block: () -> T,
    ): T {
        var result: Result<T>? = null
        runOnMainSync {
            result = runCatching(block)
        }
        return checkNotNull(result) { "main sync callback did not return a result" }.getOrThrow()
    }

    private data class Point(val x: Float, val y: Float) {
        fun interpolateTo(other: Point, progress: Float): Point =
            Point(
                x = x + (other.x - x) * progress,
                y = y + (other.y - y) * progress,
            )
    }

    private data class KeyboardRuntimeSummary(
        val baselineLabel: String,
        val currentItem: Int,
        val itemCount: Int,
        val transformerClass: String,
        val accessibilityActions: List<String>,
    )

    private data class PageSnapshot(
        val label: String,
        val currentItem: Int,
    )

    private data class ZoomResetSnapshot(
        val scaleType: ImageView.ScaleType,
        val label: String,
        val zoomScale: Float,
    )

    private data class ZoomPreviewSnapshot(
        val scaleType: ImageView.ScaleType,
        val zoomScale: Float,
    )

    private data class HostTransformerSummary(
        val fadeTransformer: String,
        val slideTransformer: String,
        val itemCount: Int,
        val afterNext: Int,
        val afterPrevious: Int,
    )

    private class FakePagedEngine(
        private val context: Context,
        initialPageCount: Int,
    ) : PagedReaderEngine {
        private val locatorState = MutableStateFlow(
            Locator(LocatorStrategy.Page(index = 0, total = initialPageCount)),
        )
        private val pageCountState = MutableStateFlow(initialPageCount)
        private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null

        override val id: String = "android-test-fake-paged"
        override val format: BookFormat = BookFormat.PDF
        override val priority: Int = 0
        override val pagingKind: StateFlow<PagingKind> = MutableStateFlow(PagingKind.PAGED)
        override val supportsSearch: Boolean = false
        override val currentLocator: StateFlow<Locator> = locatorState
        override val pageCount: StateFlow<Int> = pageCountState

        override suspend fun supports(uri: Uri): Boolean = true

        override suspend fun openBook(uri: Uri): Locator = locatorState.value

        override fun createView(): View = View(context)

        override fun createPageView(pageIndex: Int): View = View(context)

        override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
            pageRequestCallback = callback
        }

        override suspend fun close() = Unit

        override suspend fun goTo(locator: Locator) {
            locatorState.value = locator
            val pageIndex = (locator.strategy as? LocatorStrategy.Page)?.index ?: 0
            pageRequestCallback?.invoke(pageIndex)
        }

        override suspend fun setFontSize(sp: Float) = Unit

        override suspend fun setMode(mode: ReadingMode) = Unit
    }

    private companion object {
        private const val PDF_READER_DESC = "阅读内容，捏合缩放页面"
        private const val VIEW_PAGER_CLASS_NAME = "androidx.viewpager2.widget.ViewPager2"
        private const val EVENT_STEP_MS = 16L
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
