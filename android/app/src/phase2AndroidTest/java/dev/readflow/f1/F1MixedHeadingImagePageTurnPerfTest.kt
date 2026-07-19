package dev.readflow.f1

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.os.Debug
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.readflow.MainActivity
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device frame-time gate for F1 mixed heading→image page-turn.
 *
 * Static/compile verification only in CI agent path. Hard gfx gate and evidence require a real
 * tablet run under [evidenceDir] (`externalFilesDir/f1`).
 *
 * No main-source probes or debug hooks: all observation is reflection + dumpsys + screenshots.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class F1MixedHeadingImagePageTurnPerfTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)
    private val fixture = F1MixedHeadingImageFixture.build()
    private val minCropBandHeightPx: Int =
        (MIN_CROP_BAND_DP * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(MIN_CROP_BAND_DP)

    @Before
    fun setUp() = runBlocking {
        resetTargetAppState()
        val dir = evidenceDir()
        dir.deleteRecursively()
        check(dir.mkdirs() || dir.isDirectory) { "failed to recreate evidence dir: $dir" }
        settings.setFontSize(18)
        settings.setLineSpacing(1.6f)
        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setReadingMode(ReaderReadingMode.PAGED)
        // Pref true = guide already dismissed → overlay absent on launch.
        settings.setReaderGuideShown(true)
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun mixedHeadingImagePageTurnFrameGate_slideAndSimulation_warmAndCold() {
        writeEnvironmentEvidence()
        val thresholds = resolveThresholds()
        writeTextEvidence(
            "thresholds.txt",
            buildString {
                appendLine("max_p95_ms=${thresholds.maxP95Ms}")
                appendLine("max_janky_ratio=${thresholds.maxJankyRatio}")
                appendLine("min_total_frames=${thresholds.minTotalFrames}")
                appendLine("override_p95=${arguments.getString(ARG_MAX_P95_MS) ?: "none"}")
                appendLine("override_janky_ratio=${arguments.getString(ARG_MAX_JANKY_RATIO) ?: "none"}")
                appendLine("override_min_frames=${arguments.getString(ARG_MIN_TOTAL_FRAMES) ?: "none"}")
                appendLine("default_p95=${F1FrameThresholds.DEFAULT_MAX_P95_MS}")
                appendLine("default_janky_ratio=${F1FrameThresholds.DEFAULT_MAX_JANKY_RATIO}")
                appendLine("default_min_frames=${F1FrameThresholds.DEFAULT_MIN_TOTAL_FRAMES}")
            },
        )

        val uri = createFixtureEpubUri()
        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            val flowView = waitForConditionResult("EpubFlowView ready without guide overlay") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    if (view.width <= 0 || view.height <= 0) return@withActivity null
                    val pageCount = view.reflectIntMethod("pageCount")
                    if (pageCount < 2) return@withActivity null
                    view
                }
            }
            val guideAbsent = device.findObject(By.descStartsWith("阅读手势引导")) == null
            writeTextEvidence(
                "guide-overlay.txt",
                buildString {
                    appendLine("reader_guide_shown_pref=true")
                    appendLine("guide_overlay_absent=$guideAbsent")
                },
            )
            assertTrue("reader guide overlay must be absent after setReaderGuideShown(true)", guideAbsent)

            waitForImagesDecoded(scenario, flowView)
            val selection = waitForConditionResult("viewport-adaptive heading→image boundary") {
                scenario.withActivity {
                    extractAndSelectBoundary(flowView)
                }
            }
            writeTextEvidence(
                "boundary-selection.txt",
                buildString {
                    appendLine("candidate_index=${selection.candidateIndex}")
                    appendLine("heading_page=${selection.headingPage}")
                    appendLine("image_page=${selection.imagePage}")
                    appendLine("crop_band_top_px=${selection.cropBandTopPx}")
                    appendLine("crop_band_bottom_px=${selection.cropBandBottomPx}")
                    appendLine("band_height_px=${selection.bandHeightPx}")
                    appendLine("min_crop_band_height_px=$minCropBandHeightPx")
                    appendLine("min_crop_band_dp=$MIN_CROP_BAND_DP")
                    appendLine("synthetic_image_rgb=0x${fixture.syntheticImageColorRgb.toString(16)}")
                    appendLine("hardcoded_page=false")
                },
            )

            scenario.withActivity {
                flowView.reflectVoidMethod("goToPage", selection.headingPage)
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            waitForCondition("parked on selected heading page") {
                scenario.withActivity {
                    flowView.reflectIntMethod("currentPageIndex") == selection.headingPage
                }
            }

            waitForImagesDecoded(scenario, flowView)
            proveCropBandHasSyntheticColor(scenario, flowView, selection)
            takeScreenshot("heading-crop-band.png")

            val cases = listOf(
                Case("slide-warm", PageFlipStyle.SLIDE, cold = false),
                Case("slide-cold", PageFlipStyle.SLIDE, cold = true),
                Case("simulation-warm", PageFlipStyle.SIMULATION, cold = false),
                Case("simulation-cold", PageFlipStyle.SIMULATION, cold = true),
            )
            val caseResults = mutableListOf<CaseGateOutcome>()
            for (case in cases) {
                caseResults += runTurnCase(scenario, flowView, selection, thresholds, case)
            }
            writeTextEvidence(
                "f1-frame-summary.txt",
                buildString {
                    appendLine("case_count=${caseResults.size}")
                    caseResults.forEach { appendLine(it.summaryLine) }
                    val allPass = caseResults.all { it.pass }
                    appendLine("all_cases_pass=$allPass")
                    appendLine("failed_cases=${caseResults.filterNot { it.pass }.joinToString(",") { it.name }}")
                    appendLine("physical_tablet_gate=required")
                    appendLine(
                        "boundary=runtime path exercised on device/AVD; hard thresholds are physical-tablet " +
                            "acceptance and must not be diluted for emulator GPU (SwiftShader/ANGLE)",
                    )
                },
            )
            val failures = caseResults.filterNot { it.pass }
            assertTrue(
                "F1 hard gate failed for ${failures.joinToString("; ") { "${it.name}: ${it.reasons}" }}",
                failures.isEmpty(),
            )
        }
    }

    private fun runTurnCase(
        scenario: ActivityScenario<MainActivity>,
        flowView: View,
        selection: F1BoundarySelection,
        thresholds: F1FrameThresholds,
        case: Case,
    ): CaseGateOutcome {
        // Persist preference for environment parity; live engine must be updated via setter.
        runBlocking { settings.setPageFlipStyle(case.style) }
        scenario.withActivity {
            flowView.invokeFlipStyleSetter(case.style)
        }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        waitForCondition("flip style ${case.style} on live engine") {
            scenario.withActivity {
                flowView.reflectPrivateAny("flipStyle")?.toString() == case.style.name
            }
        }

        scenario.withActivity {
            flowView.reflectVoidMethod("goToPage", selection.headingPage)
        }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        waitForTurnSettled(scenario, flowView, selection.headingPage)

        var previousPrecache: Any? = null
        var coldSwapped = false
        try {
            if (case.cold) {
                scenario.withActivity {
                    previousPrecache = flowView.swapPrivateField("pageTexturePrecacheEnabled", false)
                    flowView.invokeNoArgCompat("recycleCachedTextures")
                }
                coldSwapped = true
            } else {
                waitForWarmCaches(scenario, flowView, selection)
            }

            takeScreenshot("${case.name}-before.png")
            // Sample memory before gfx reset so the measured frame window starts at DOWN.
            val memoryBefore = Debug.MemoryInfo().also(Debug::getMemoryInfo)

            val slidePoints = scenario.withActivity {
                screenSlidePoints(flowView)
            }
            // Reset immediately before DOWN; inject off the main / withActivity thread.
            shell("dumpsys gfxinfo ${appContext.packageName} reset")
            dispatchForwardSlide(slidePoints.start, slidePoints.end)

            // Poll settle without device/instrumentation idle after the gesture.
            waitForTurnSettled(scenario, flowView, selection.imagePage)

            val gfxInfo = shell("dumpsys gfxinfo ${appContext.packageName}")
            writeTextEvidence("${case.name}-gfxinfo.txt", gfxInfo)
            writeTextEvidence(
                "${case.name}-meminfo.txt",
                shell("dumpsys meminfo ${appContext.packageName}"),
            )
            val memoryAfter = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            writeTextEvidence(
                "${case.name}-pss.txt",
                buildString {
                    appendLine("before_total_pss_kb=${memoryBefore.totalPss}")
                    appendLine("after_total_pss_kb=${memoryAfter.totalPss}")
                    appendLine("before_java_heap_pss_kb=${memoryBefore.dalvikPss}")
                    appendLine("after_java_heap_pss_kb=${memoryAfter.dalvikPss}")
                    appendLine("before_native_heap_pss_kb=${memoryBefore.nativePss}")
                    appendLine("after_native_heap_pss_kb=${memoryAfter.nativePss}")
                },
            )
            takeScreenshot("${case.name}-after.png")

            val metrics = GfxInfoParser.parse(gfxInfo)
            val gate = F1FrameGate.evaluate(metrics, thresholds)
            writeTextEvidence(
                "${case.name}-gate.txt",
                buildString {
                    appendLine("pass=${gate.pass}")
                    appendLine("reasons=${gate.reasons.joinToString(";")}")
                    appendLine("total_frames=${gate.totalFrames}")
                    appendLine("janky_frames=${gate.jankyFrames}")
                    appendLine("janky_ratio=${gate.jankyRatio}")
                    appendLine("p90_ms=${gate.p90Ms}")
                    appendLine("p95_ms=${gate.p95Ms}")
                    appendLine("max_p95_ms=${thresholds.maxP95Ms}")
                    appendLine("max_janky_ratio=${thresholds.maxJankyRatio}")
                    appendLine("min_total_frames=${thresholds.minTotalFrames}")
                },
            )
            // Defer hard assert until all four cases are measured so emulator evidence is complete
            // while production thresholds stay undiluted.
            return CaseGateOutcome(
                name = case.name,
                pass = gate.pass,
                reasons = gate.reasons,
                summaryLine = listOf(
                    "case=${case.name}",
                    "pass=${gate.pass}",
                    "total_frames=${gate.totalFrames}",
                    "janky_frames=${gate.jankyFrames}",
                    "janky_ratio=${gate.jankyRatio}",
                    "p90_ms=${gate.p90Ms}",
                    "p95_ms=${gate.p95Ms}",
                ).joinToString(" | "),
            )
        } finally {
            if (coldSwapped) {
                scenario.withActivity {
                    flowView.swapPrivateField("pageTexturePrecacheEnabled", previousPrecache ?: true)
                }
            }
        }
    }

    private fun proveCropBandHasSyntheticColor(
        scenario: ActivityScenario<MainActivity>,
        flowView: View,
        selection: F1BoundarySelection,
    ) {
        val sample = scenario.withActivity {
            val bitmap = Bitmap.createBitmap(flowView.width, flowView.height, Bitmap.Config.ARGB_8888)
            flowView.draw(Canvas(bitmap))
            val top = (selection.cropBandTopPx + 2).coerceIn(0, flowView.height - 1)
            val bottom = (selection.cropBandBottomPx - 2).coerceAtLeast(top + 1).coerceAtMost(flowView.height)
            val left = flowView.width / 4
            val right = (flowView.width * 3) / 4
            val hits = countColorHits(
                bitmap,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                targetRgb = fixture.syntheticImageColorRgb,
            )
            bitmap.recycle()
            hits
        }
        writeTextEvidence("crop-band-color-hits.txt", "hits=$sample rgb=0x${fixture.syntheticImageColorRgb.toString(16)}")
        assertTrue(
            "selected heading-page crop band must paint synthetic image color; hits=$sample",
            sample > 0,
        )
    }

    private fun extractAndSelectBoundary(flowView: View): F1BoundarySelection? {
        val metadata = flowView.reflectPrivateAny("pageLayoutMetadata") ?: return null
        @Suppress("UNCHECKED_CAST")
        val previews = metadata.javaClass.getDeclaredField("pageBoundaryImagePreviews")
            .apply { isAccessible = true }
            .get(metadata) as? List<Any> ?: return null
        @Suppress("UNCHECKED_CAST")
        val pages = flowView.reflectPrivateAny("paged") as? List<Any> ?: return null
        val textView = flowView.reflectPrivateAny("textView") as? TextView ?: return null
        val layout = textView.layout ?: return null
        val padTop = textView.paddingTop
        val padBottom = textView.paddingBottom
        val viewportH = flowView.height
        if (viewportH <= 0 || pages.isEmpty()) return null

        val candidates = mutableListOf<F1BoundaryCandidate>()
        previews.forEachIndexed { index, preview ->
            val precedingIsHeading = preview.fieldBool("precedingIsHeading")
            if (!precedingIsHeading) return@forEachIndexed
            val precedingEndLineExclusive = preview.fieldInt("precedingEndLineExclusive")
            val imageLine = preview.fieldInt("imageLine")
            val imageLayoutStart = preview.fieldInt("imageLayoutStart")

            val headingPage = pages.indexOfFirst { page ->
                val startLine = page.fieldInt("startLine")
                val endLineExclusive = page.fieldInt("endLineExclusive")
                precedingEndLineExclusive > startLine &&
                    precedingEndLineExclusive <= endLineExclusive &&
                    imageLine >= endLineExclusive
            }
            if (headingPage < 0) return@forEachIndexed

            val imagePage = pages.indexOfFirst { page ->
                val startOffset = page.fieldInt("startOffset")
                val endOffset = page.fieldInt("endOffset")
                imageLayoutStart >= startOffset && imageLayoutStart < endOffset
            }.takeIf { it >= 0 } ?: pages.indexOfFirst { page ->
                val startLine = page.fieldInt("startLine")
                val endLineExclusive = page.fieldInt("endLineExclusive")
                imageLine in startLine until endLineExclusive
            }
            if (imagePage < 0) return@forEachIndexed

            val headingWindow = pages[headingPage]
            val lastWindowLine =
                (headingWindow.fieldInt("endLineExclusive") - 1)
                    .coerceAtLeast(headingWindow.fieldInt("startLine"))
            val pageTopPx = headingWindow.fieldInt("topPx")
            val precedingBottom =
                layout.getLineBottom(lastWindowLine) + padTop - pageTopPx
            val previewTopInViewport = precedingBottom.coerceIn(0, viewportH)
            val previewBottomInViewport =
                (viewportH - padBottom).coerceAtLeast(previewTopInViewport)
            val imageLineHeight =
                layout.getLineBottom(imageLine) - layout.getLineTop(imageLine)

            candidates += F1BoundaryCandidate(
                candidateIndex = index,
                headingPage = headingPage,
                imagePage = imagePage,
                cropBandTopPx = previewTopInViewport,
                cropBandBottomPx = previewBottomInViewport,
                viewportHeightPx = viewportH,
                imageLineHeightPx = imageLineHeight,
            )
        }
        return F1BoundarySelector.select(candidates, minBandHeightPx = minCropBandHeightPx)
    }

    private fun waitForWarmCaches(
        scenario: ActivityScenario<MainActivity>,
        flowView: View,
        selection: F1BoundarySelection,
    ) {
        waitForCondition("front/revealed caches match selection pages and tops") {
            scenario.withActivity {
                warmCachesMatchSelection(flowView, selection)
            }
        }
    }

    private fun warmCachesMatchSelection(flowView: View, selection: F1BoundarySelection): Boolean {
        val front = flowView.reflectPrivateAny("cachedFrontBitmap") as? Bitmap
        val revealed = flowView.reflectPrivateAny("cachedRevealedBitmap") as? Bitmap
        if (front == null || front.isRecycled || revealed == null || revealed.isRecycled) {
            return false
        }
        val fromPage = flowView.reflectPrivateAny("cachedFromPage") as? Int ?: return false
        val targetPage = flowView.reflectPrivateAny("cachedTargetPage") as? Int ?: return false
        if (fromPage != selection.headingPage || targetPage != selection.imagePage) {
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val pages = flowView.reflectPrivateAny("paged") as? List<Any> ?: return false
        if (selection.headingPage !in pages.indices || selection.imagePage !in pages.indices) {
            return false
        }
        val headingTop = pages[selection.headingPage].fieldInt("topPx")
        val imageTop = pages[selection.imagePage].fieldInt("topPx")
        val fromTop = flowView.reflectPrivateAny("cachedFromTopPx") as? Int ?: return false
        val targetTop = flowView.reflectPrivateAny("cachedTargetTopPx") as? Int ?: return false
        if (fromTop != headingTop || targetTop != imageTop) {
            return false
        }
        val pending = flowView.reflectPrivateAny("pageTexturePrecachePending") as? Boolean ?: return false
        return !pending
    }

    private fun waitForImagesDecoded(
        scenario: ActivityScenario<MainActivity>,
        flowView: View,
    ) {
        waitForCondition("async image decode settled") {
            scenario.withActivity {
                val pending = flowView.reflectPrivateAny("pendingDecodesProvider") as? (() -> Boolean)
                pending?.invoke() != true
            }
        }
        // Allow layout/metadata rebuild after decode.
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(250)
    }

    private fun waitForTurnSettled(
        scenario: ActivityScenario<MainActivity>,
        flowView: View,
        pageIndex: Int,
    ) {
        waitForCondition("turn settled on page $pageIndex") {
            scenario.withActivity {
                val animator = flowView.reflectPrivateAny("flipAnimator")
                val animatorRunning = try {
                    animator?.javaClass?.getMethod("isRunning")?.invoke(animator) as? Boolean == true
                } catch (_: Throwable) {
                    false
                }
                flowView.reflectIntMethod("currentPageIndex") == pageIndex &&
                    flowView.reflectPrivateAny("curlDrawable") == null &&
                    flowView.reflectPrivateAny("slideDrawable") == null &&
                    !animatorRunning
            }
        }
    }

    /**
     * Wall-clock spaced screen-coordinate pointer injection via UiAutomation.
     * Must not run on the main thread or inside [ActivityScenario.withActivity].
     */
    private fun dispatchForwardSlide(start: PointF, end: PointF) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "dispatchForwardSlide must not run on the main thread"
        }
        val downTime = SystemClock.uptimeMillis()
        injectPointer(downTime, downTime, MotionEvent.ACTION_DOWN, start.x, start.y)
        val steps = 8
        repeat(steps) { step ->
            SystemClock.sleep(FRAME_STEP_MS)
            val progress = (step + 1) / steps.toFloat()
            val x = start.x + (end.x - start.x) * progress
            val y = start.y + (end.y - start.y) * progress
            injectPointer(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
        }
        SystemClock.sleep(FRAME_STEP_MS)
        injectPointer(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, end.x, end.y)
    }

    private fun injectPointer(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) {
                "injectInputEvent failed action=$action x=$x y=$y"
            }
        } finally {
            event.recycle()
        }
    }

    private fun screenSlidePoints(target: View): SlidePoints {
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        return SlidePoints(
            start = PointF(left + width * 0.85f, top + height * 0.50f),
            end = PointF(left + width * 0.15f, top + height * 0.50f),
        )
    }

    private fun resolveThresholds(): F1FrameThresholds =
        F1FrameThresholds.fromInstrumentationArgs(
            maxP95Raw = arguments.getString(ARG_MAX_P95_MS),
            maxJankyRaw = arguments.getString(ARG_MAX_JANKY_RATIO),
            minFramesRaw = arguments.getString(ARG_MIN_TOTAL_FRAMES),
        )

    private fun createFixtureEpubUri(): android.net.Uri {
        val file = File(appContext.cacheDir, "f1-mixed-${UUID.randomUUID().toString().take(8)}.epub")
        val color = Color.rgb(
            (fixture.syntheticImageColorRgb shr 16) and 0xFF,
            (fixture.syntheticImageColorRgb shr 8) and 0xFF,
            fixture.syntheticImageColorRgb and 0xFF,
        )
        val platePng = oversizedPngBytes(color)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun addStored(path: String, bytes: ByteArray) {
                val entry = ZipEntry(path).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            fun addText(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fun addBinary(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            // EPUB requires uncompressed mimetype as first entry.
            addStored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            // container rootfile full-path is fixture.packagePath (default OEBPS/content.opf).
            // chapterHref / imageHref are relative to the OPF directory (OEBPS/).
            val opfPath = fixture.packagePath
            val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            fun underOpf(relative: String): String =
                if (opfDir.isEmpty()) relative else "$opfDir/$relative"
            addText("META-INF/container.xml", fixture.containerXml)
            addText(opfPath, fixture.contentOpf)
            addText(underOpf(fixture.chapterHref), fixture.chapterXhtml)
            fixture.candidates.forEach { candidate ->
                addBinary(underOpf(candidate.imageHref), platePng)
            }
        }
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private fun oversizedPngBytes(color: Int): ByteArray {
        // Large enough that after CSS/content width scaling the image line still exceeds leftover band.
        val bitmap = Bitmap.createBitmap(1200, 2400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG compress failed" }
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun countColorHits(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        targetRgb: Int,
        tolerance: Int = 28,
    ): Int {
        val tr = (targetRgb shr 16) and 0xFF
        val tg = (targetRgb shr 8) and 0xFF
        val tb = targetRgb and 0xFF
        var hits = 0
        val l = left.coerceIn(0, bitmap.width - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val t = top.coerceIn(0, bitmap.height - 1)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = bitmap.getPixel(x, y)
                val pr = Color.red(c)
                val pg = Color.green(c)
                val pb = Color.blue(c)
                if (abs(pr - tr) <= tolerance && abs(pg - tg) <= tolerance && abs(pb - tb) <= tolerance) {
                    hits += 1
                }
                x += 2
            }
            y += 2
        }
        return hits
    }

    private fun writeEnvironmentEvidence() {
        writeTextEvidence(
            "environment.txt",
            buildString {
                appendLine("device=${shell("getprop ro.product.model").trim()}")
                appendLine("sdk=${shell("getprop ro.build.version.sdk").trim()}")
                appendLine("fingerprint=${shell("getprop ro.build.fingerprint").trim()}")
                appendLine("package=${appContext.packageName}")
                appendLine("font_size_sp=18")
                appendLine("line_spacing=1.6")
                appendLine("reading_mode=PAGED")
                appendLine("reader_guide_shown_pref=true")
                appendLine("guide_overlay_absent=expected_true")
                appendLine("min_crop_band_dp=$MIN_CROP_BAND_DP")
                appendLine("min_crop_band_height_px=$minCropBandHeightPx")
                appendLine("fixture_candidates=${fixture.candidates.size}")
                appendLine("boundary=physical tablet frame gate; AVD may fail hard thresholds")
            },
        )
    }

    private fun readerIntent(uri: android.net.Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "application/epub+zip")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("f1-mixed-heading-image", uri)
        }

    private fun resetTargetAppState() {
        appContext.deleteDatabase(DB_NAME)
        deleteIfExists(appContext.getDatabasePath(DB_NAME))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-wal"))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-shm"))
        deleteRecursively(File(appContext.filesDir, "books"))
        deleteRecursively(File(appContext.filesDir, "covers"))
        deleteChildrenRecursively(appContext.cacheDir)
        deleteIfExists(File(appContext.filesDir, "datastore/readflow_settings.preferences_pb"))
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun MainActivity.findEpubFlowView(): View? =
        window.decorView.findDescendant { it.javaClass.name == EPUB_FLOW_VIEW_CLASS_NAME }

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun View.reflectIntMethod(name: String): Int =
        javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this) as Int

    private fun View.reflectVoidMethod(name: String, arg: Int) {
        javaClass.getDeclaredMethod(name, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(this, arg)
    }

    /** Invokes the live [EpubFlowView.flipStyle] property setter (generated as setFlipStyle). */
    private fun View.invokeFlipStyleSetter(style: PageFlipStyle) {
        val method = javaClass.methods.firstOrNull { candidate ->
            candidate.name == "setFlipStyle" && candidate.parameterCount == 1
        } ?: javaClass.declaredMethods.first { candidate ->
            candidate.name == "setFlipStyle" && candidate.parameterCount == 1
        }
        method.isAccessible = true
        method.invoke(this, style)
        val applied = reflectPrivateAny("flipStyle")
        check(applied?.toString() == style.name) {
            "setFlipStyle did not update flipStyle field: expected=${style.name} actual=$applied"
        }
    }

    private fun View.reflectPrivateAny(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun View.swapPrivateField(name: String, value: Any?): Any? {
        val field = javaClass.getDeclaredField(name).apply { isAccessible = true }
        val previous = field.get(this)
        field.set(this, value)
        return previous
    }

    private fun Any.invokeNoArgCompat(name: String) {
        val exact = javaClass.declaredMethods.firstOrNull { method ->
            method.name == name && method.parameterCount == 0
        }
        val method = exact ?: javaClass.declaredMethods.single { candidate ->
            candidate.parameterCount == 0 && candidate.name.startsWith("$name\$")
        }
        method.isAccessible = true
        method.invoke(this)
    }

    private fun Any.fieldInt(name: String): Int =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(this)

    private fun Any.fieldBool(name: String): Boolean =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(this)

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun dismissBlockingDialogs() {
        listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow").forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun takeScreenshot(name: String) {
        device.takeScreenshot(File(evidenceDir(), name))
    }

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
    }

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("f1")) {
            "external files dir unavailable for f1 evidence"
        }

    private fun waitForCondition(message: String, timeoutMs: Long = UI_TIMEOUT_MS, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return
            SystemClock.sleep(50)
        }
        error("Timed out: $message")
    }

    private fun <T> waitForConditionResult(
        message: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        block: () -> T?,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: T? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runCatching(block).getOrNull()
            if (last != null) return last
            SystemClock.sleep(50)
        }
        error("Timed out: $message last=$last")
    }

    private fun <T> ActivityScenario<MainActivity>.withActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { activity ->
            result = runCatching { block(activity) }
        }
        return checkNotNull(result) { "activity callback did not return" }.getOrThrow()
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

    private data class Case(
        val name: String,
        val style: PageFlipStyle,
        val cold: Boolean,
    )

    private data class CaseGateOutcome(
        val name: String,
        val pass: Boolean,
        val reasons: List<String>,
        val summaryLine: String,
    )

    private data class SlidePoints(
        val start: PointF,
        val end: PointF,
    )

    private companion object {
        private const val DB_NAME = "readflow.db"
        private const val EPUB_FLOW_VIEW_CLASS_NAME = "dev.readflow.render.epub.EpubFlowView"
        private const val ARG_MAX_P95_MS = "f1_max_p95_ms"
        private const val ARG_MAX_JANKY_RATIO = "f1_max_janky_ratio"
        private const val ARG_MIN_TOTAL_FRAMES = "f1_min_total_frames"
        private const val MIN_CROP_BAND_DP = 24
        private const val FRAME_STEP_MS = 16L
        private val UI_TIMEOUT_MS = 45.seconds.inWholeMilliseconds
    }
}
