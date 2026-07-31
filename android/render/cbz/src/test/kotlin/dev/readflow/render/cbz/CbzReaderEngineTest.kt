package dev.readflow.render.cbz

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.util.Logger
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.PageReadingDirection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CbzReaderEngineTest {
    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `open publishes fixed page semantics and ComicInfo direction`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = CbzReaderEngine(RuntimeEnvironment.getApplication(), ioDispatcher = dispatcher)
        val archive = tempCbz(rtl = true, pages = 3)

        val initial = engine.openBook(Uri.fromFile(archive))

        assertEquals(BookFormat.CBZ, engine.format)
        assertEquals(3, engine.pageCount.value)
        assertEquals(LocatorStrategy.Page(0, 3), initial.strategy)
        assertEquals(PageReadingDirection.RIGHT_TO_LEFT, engine.pageReadingDirection.value)
        assertEquals(ChapterInfo.Kind.PAGE, engine.chapterInfo.value.kind)
        assertEquals(3, engine.chapterInfo.value.totalChapters)
        engine.close()
    }

    @Test
    fun `page navigation stays stable and prefetches bounded neighbours`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = CbzReaderEngine(RuntimeEnvironment.getApplication(), ioDispatcher = dispatcher)
        val archive = tempCbz(rtl = false, pages = 5)
        engine.openBook(Uri.fromFile(archive))
        val requests = mutableListOf<Int>()
        engine.setPageRequestCallback(requests::add)

        engine.goTo(Locator(LocatorStrategy.Page(3, 5)))
        testScheduler.advanceUntilIdle()

        assertEquals(LocatorStrategy.Page(3, 5), engine.currentLocator.value.strategy)
        assertEquals(listOf(3), requests)
        assertEquals(setOf(1, 2, 3, 4), engine.preparedPageIndexesForTest())
        engine.close()
    }

    @Test
    fun `comic pager requests two retained neighbours for rapid turns`() {
        val engine = CbzReaderEngine(RuntimeEnvironment.getApplication(), ioDispatcher = dispatcher)

        assertEquals(2, engine.preferredOffscreenPageLimit)
    }

    @Test
    fun `failed first page open removes extraction workspace`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val workspace = File(context.cacheDir, "cbz_pages").apply { deleteRecursively() }
        val engine = CbzReaderEngine(context, ioDispatcher = dispatcher)
        val archive = kotlin.io.path.createTempFile("readflow-engine-invalid-", ".cbz").toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page-1.jpg"))
                zip.write("not an image".encodeToByteArray())
                zip.closeEntry()
            }
        }

        val result = runCatching { engine.openBook(Uri.fromFile(archive)) }

        assertTrue(result.exceptionOrNull() is java.io.IOException)
        assertFalse(workspace.walkTopDown().any { it != workspace })
        engine.close()
    }

    @Test
    fun `opening a comic removes page and source cache left by a killed process`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        CbzReaderEngine.resetCacheCleanupForTest()
        val stalePage = File(context.cacheDir, "cbz_pages/stale/page.png").apply {
            parentFile?.mkdirs()
            writeBytes(ONE_PIXEL_PNG)
        }
        val staleSource = File(context.cacheDir, "cbz_sources/stale.cbz").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val engine = CbzReaderEngine(context, ioDispatcher = dispatcher)

        engine.openBook(Uri.fromFile(tempCbz(rtl = false, pages = 2)))

        assertFalse(stalePage.exists())
        assertFalse(staleSource.exists())
        engine.close()
    }

    @Test
    fun `later engine open never deletes an active comic page cache`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val workspace = File(context.cacheDir, "cbz_pages").apply { deleteRecursively() }
        CbzReaderEngine.resetCacheCleanupForTest()
        val first = CbzReaderEngine(context, ioDispatcher = dispatcher)
        val second = CbzReaderEngine(context, ioDispatcher = dispatcher)

        first.openBook(Uri.fromFile(tempCbz(rtl = false, pages = 2)))
        val firstSessionDirectory = checkNotNull(workspace.listFiles()?.singleOrNull())
        val firstPreparedPage = checkNotNull(firstSessionDirectory.listFiles()?.firstOrNull())
        second.openBook(Uri.fromFile(tempCbz(rtl = false, pages = 2)))

        assertTrue(firstPreparedPage.isFile)
        first.close()
        second.close()
    }

    @Test
    fun `prepared page does not claim content before an attached tile is drawable`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = CbzReaderEngine(RuntimeEnvironment.getApplication(), ioDispatcher = dispatcher)
        val archive = tempCbz(rtl = false, pages = 2)
        engine.openBook(Uri.fromFile(archive))
        val page = engine.createPageView(0) as FrameLayout
        val progress = page.getChildAt(1) as ProgressBar

        testScheduler.advanceUntilIdle()

        assertEquals(View.VISIBLE, progress.visibility)
        engine.close()
    }

    @Test
    fun `comic page exposes one accessibility description owner`() {
        val engine = CbzReaderEngine(RuntimeEnvironment.getApplication(), ioDispatcher = dispatcher)

        val page = engine.createPageView(0) as FrameLayout
        val image = page.getChildAt(0) as ZoomImageView

        assertEquals("漫画第 1 页，共 1 页", page.contentDescription)
        assertNull(image.contentDescription)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, image.importantForAccessibility)
    }

    @Test
    fun `comic loading remains visible until a tile can actually be drawn`() {
        assertEquals(
            CbzPageContentState.LOADING,
            cbzPageContentState(
                decoderReady = false,
                loadRectReady = false,
                hasForegroundTile = false,
                allForegroundTilesFailed = false,
                hasRenderableTile = false,
            ),
        )
        assertEquals(
            CbzPageContentState.LOADING,
            cbzPageContentState(
                decoderReady = true,
                loadRectReady = true,
                hasForegroundTile = true,
                allForegroundTilesFailed = false,
                hasRenderableTile = false,
            ),
        )
        assertEquals(
            CbzPageContentState.CONTENT,
            cbzPageContentState(
                decoderReady = true,
                loadRectReady = true,
                hasForegroundTile = true,
                allForegroundTilesFailed = false,
                hasRenderableTile = true,
            ),
        )
        assertEquals(
            CbzPageContentState.ERROR,
            cbzPageContentState(
                decoderReady = true,
                loadRectReady = true,
                hasForegroundTile = true,
                allForegroundTilesFailed = true,
                hasRenderableTile = false,
            ),
        )
    }

    @Test
    fun `subsampling uses a smaller same-ratio geometry without a low quality bitmap preview`() {
        assertEquals(
            CbzSubsamplingGeometry(800, 1200),
            cbzSubsamplingGeometry(width = 1600, height = 2400),
        )
        assertNull(cbzSubsamplingGeometry(width = 1, height = 2400))
        assertNull(cbzSubsamplingGeometry(width = 1600, height = 1))
    }

    @Test
    fun `decoder failure log bridge reports only the ZoomImage decoder failure`() {
        val delegatedMessages = mutableListOf<String>()
        val delegate = object : Logger.Pipeline {
            override fun log(level: Logger.Level, tag: String, msg: String, tr: Throwable?) {
                delegatedMessages += msg
            }

            override fun flush() = Unit
        }
        var failures = 0
        val bridge = CbzZoomLogPipeline(delegate) { failures++ }

        bridge.log(
            Logger.Level.Debug,
            "ZoomImage",
            "Subsampling. resetTileDecoder:setImage. failed. IOException. 'page-1'",
            null,
        )
        bridge.log(Logger.Level.Debug, "ZoomImage", "unrelated debug", null)
        bridge.log(Logger.Level.Info, "ZoomImage", "visible info", null)

        assertEquals(1, failures)
        assertEquals(listOf("visible info"), delegatedMessages)
    }

    @Test
    fun `content archive copy is bounded before ZIP indexing`() {
        val output = ByteArrayOutputStream()

        val error = runCatching {
            copyCbzArchiveBounded(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                output = output,
                maxBytes = 3,
            )
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error?.message.orEmpty().contains("源文件"))
    }

    @Test
    fun `detached comic page re-enters loading when the same view is attached again`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val engine = CbzReaderEngine(activity, ioDispatcher = dispatcher)
        engine.openBook(Uri.fromFile(tempCbz(rtl = false, pages = 2)))
        val page = engine.createPageView(0) as FrameLayout
        val image = page.getChildAt(0) as ZoomImageView

        activity.setContentView(page)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(0), engine.activePageIndexesForTest())
        assertEquals(View.VISIBLE, (page.getChildAt(1) as ProgressBar).visibility)
        testScheduler.advanceUntilIdle()

        activity.setContentView(FrameLayout(activity))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.activePageIndexesForTest().isEmpty())
        assertNull(image.drawable)
        activity.setContentView(page)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(0), engine.activePageIndexesForTest())
        assertEquals(View.VISIBLE, (page.getChildAt(1) as ProgressBar).visibility)
        testScheduler.advanceUntilIdle()

        engine.close()
        activity.finish()
    }

    @Test
    fun `detached page cannot reattach while a replacement archive is closing`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val ioGate = GateDispatcher()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val engine = CbzReaderEngine(activity, ioDispatcher = ioGate)
        engine.openBook(Uri.fromFile(tempCbz(rtl = false, pages = 2)))
        val oldPage = engine.createPageView(0)
        activity.setContentView(oldPage)
        shadowOf(Looper.getMainLooper()).idle()
        activity.setContentView(FrameLayout(activity))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.activePageIndexesForTest().isEmpty())

        ioGate.block()
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.openBook(Uri.fromFile(tempCbz(rtl = false, pages = 2)))
        }
        assertFalse(replacement.isCompleted)

        activity.setContentView(oldPage)
        shadowOf(Looper.getMainLooper()).idle()

        val stalePageReattached = engine.activePageIndexesForTest().isNotEmpty()
        ioGate.release()
        replacement.join()
        assertFalse(stalePageReattached)
        engine.close()
        activity.finish()
    }

    @Test
    fun `distant page transition retains every page whose view is still active`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val engine = CbzReaderEngine(activity, ioDispatcher = dispatcher)
        val archive = tempCbz(rtl = false, pages = 6)
        engine.openBook(Uri.fromFile(archive))
        val attachedPages = FrameLayout(activity)
        attachedPages.addView(engine.createPageView(0))
        attachedPages.addView(engine.createPageView(5))
        activity.setContentView(attachedPages)
        testScheduler.advanceUntilIdle()

        engine.goTo(Locator(LocatorStrategy.Page(5, 6)))
        testScheduler.advanceUntilIdle()

        assertTrue(0 in engine.preparedPageIndexesForTest())
        assertTrue(5 in engine.preparedPageIndexesForTest())
        engine.close()
        activity.finish()
    }

    private fun tempCbz(rtl: Boolean, pages: Int): File =
        kotlin.io.path.createTempFile("readflow-engine-", ".cbz").toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                repeat(pages) { index ->
                    zip.putNextEntry(ZipEntry("page-${index + 1}.png"))
                    zip.write(ONE_PIXEL_PNG)
                    zip.closeEntry()
                }
                if (rtl) {
                    zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zip.write("<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>".encodeToByteArray())
                    zip.closeEntry()
                }
            }
        }

    private companion object {
        val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }

    private class GateDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        private var blocked = false

        fun block() {
            blocked = true
        }

        fun release() {
            blocked = false
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (blocked) queued.addLast(block) else block.run()
        }
    }
}
