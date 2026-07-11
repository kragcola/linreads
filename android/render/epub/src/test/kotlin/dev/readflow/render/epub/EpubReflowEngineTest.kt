package dev.readflow.render.epub

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ThemeMode
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightRange
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class EpubReflowEngineTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `paged runtime can use compose text layout measurement source`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-measured.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>First compose measured paragraph.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val slice = findTaggedSlice(page)

        assertEquals(EpubPageMeasurement.ComposeTextLayoutResult, slice?.measurement)
    }

    @Test
    fun `paged runtime uses real compose text layout measurement by default`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("default-compose-measured.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Default runtime Compose measurement paragraph wraps into visual text layout lines for pagination.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val slice = findTaggedSlice(page)

        assertEquals(EpubPageMeasurement.ComposeTextLayoutResult, slice?.measurement)
    }

    @Test
    fun `paged runtime renders text pages with compose view host`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-page-host.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Compose hosted EPUB paged text.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertNotNull(findView(page, ComposeView::class.java))
    }

    @Test
    fun `flow view host leaves paper texture owned by the flow surface`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("flow-host-paper.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Flow host paper background.</p><p>Next page text.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        val initialFlowBackground = flowView.background

        assertEquals("flow host must not paint a second, differently tiled paper texture", null, host.background)
        assertNotNull("flow view must paint the live paper texture", initialFlowBackground)

        engine.setTheme(ThemeMode.DARK)

        assertEquals("theme change must keep the host transparent so press/turn layers reveal the same paper", null, host.background)
        assertNotNull("theme change must keep flow paper background", flowView.background)
        assertTrue("flow background should refresh with theme", flowView.background !== initialFlowBackground)
    }

    @Test
    fun `flow image scheduling is pending before the posted scheduler attaches drawables`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("flow-image-scheduling.epub")
        writeImageEpub(epub)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView

        assertTrue(
            "the initial reveal gate must treat not-yet-attached image work as pending; " +
                "otherwise settle can reveal before AsyncDrawableScheduler runs",
            flowView.pendingDecodesProvider?.invoke() == true,
        )
    }

    @Test
    fun `open book can publish restored locator as the first resolved epub position`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("open-restored-locator.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one paragraph.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two target paragraph.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val restored = Locator(
            LocatorStrategy.Section(
                spineIndex = 1,
                elementIndex = 1,
                charOffset = 8,
            ),
            totalProgression = 0.5f,
        )
        val emittedSections = mutableListOf<LocatorStrategy.Section>()
        val locatorJob = launch {
            engine.currentLocator.collect { locator ->
                (locator.strategy as? LocatorStrategy.Section)?.let(emittedSections::add)
            }
        }

        (engine as InitialLocatorAwareReaderEngine).setInitialLocator(restored)
        val opened = engine.openBook(Uri.fromFile(epub))
        locatorJob.cancel()
        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section

        assertEquals("openBook should return the resolved restored locator", 1, (opened.strategy as? LocatorStrategy.Section)?.elementIndex)
        assertTrue(
            "openBook must not publish the book-start locator before the restored locator: $emittedSections",
            emittedSections.none { it.spineIndex == 0 && it.elementIndex == 0 },
        )
        assertEquals("currentLocator must not briefly publish the first spine", 1, strategy?.elementIndex)
        assertEquals("restored spine charOffset should be preserved", 8, strategy?.charOffset)
    }

    @Test
    fun `flow restored open view reveals immediately when parked and layout settled`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val paragraphs = List(260) { index -> "restore paragraph ${index + 1}" }
        val targetParagraph = 189
        val epub = tempDir.newFile("flow-restored-open-hidden.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to paragraphs.joinToString(
                separator = "",
                prefix = "<html><body>",
                postfix = "</body></html>",
            ) { paragraph -> "<p>$paragraph</p>" },
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

        engine.setFontSize(32f)
        engine.setLineSpacing(1.8f)
        engine.setMode(ReadingMode.PAGED)
        engine.setInitialLocator(
            Locator(
                LocatorStrategy.Section(
                    spineIndex = 0,
                    elementIndex = targetParagraph,
                    charOffset = 0,
                ),
            ),
        )
        engine.openBook(Uri.fromFile(epub))
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        val content = flowView.getChildAt(0)

        // With no images to decode, the stability gate passes immediately after positioning.
        // Content is revealed at the parked restore position in the first painted frame.
        activity.addContentView(host, ViewGroup.LayoutParams(360, 80))
        host.measure(exactly(360), exactly(80))
        host.layout(0, 0, 360, 80)

        shadowOf(Looper.getMainLooper()).idle()
        host.measure(exactly(360), exactly(80))
        host.layout(0, 0, 360, 80)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "restore target should be parked when revealed: scrollY=${flowView.scrollY}, " +
                "layoutHeight=${flowView.textView.layout?.height}, viewHeight=${flowView.height}, targetParagraph=$targetParagraph",
            flowView.scrollY > 0,
        )

        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)

        assertEquals("content should reveal at the parked restore position", 1f, content.alpha)
    }

    @Test
    fun `open book honors pre-applied paged typography before publishing restored position`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstParagraph = (1..120).joinToString(separator = "") { "a" }
        val secondParagraph = "second paragraph"
        val epub = tempDir.newFile("open-preconfigured-paged.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$firstParagraph</p><p>$secondParagraph</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
            flowEngineEnabled = false,
        )

        engine.setFontSize(32f)
        engine.setLineSpacing(1.8f)
        engine.setTheme(ThemeMode.DARK)
        engine.setMode(ReadingMode.PAGED)
        engine.setInitialLocator(Locator(LocatorStrategy.Page(index = 1, total = 20)))
        val opened = engine.openBook(Uri.fromFile(epub))

        val strategy = opened.strategy as? LocatorStrategy.Section
        assertEquals("pre-open PAGED mode must survive openBook", dev.readflow.render.api.PagingKind.PAGED, engine.pagingKind.value)
        assertTrue(
            "pre-open typography should be used for pagination before the first locator is published",
            engine.pageCount.value > 2,
        )
        assertEquals(0, strategy?.elementIndex)
        assertTrue("restored page should land inside the first paragraph after pre-open pagination", (strategy?.charOffset ?: 0) > 0)
    }

    @Test
    fun `open book translates restored page locator through pagination instead of paragraph index`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstParagraph = (1..360).joinToString(separator = "") { "a" }
        val secondParagraph = "second paragraph should not be the page restore target"
        val epub = tempDir.newFile("open-restored-page-locator.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$firstParagraph</p><p>$secondParagraph</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
            flowEngineEnabled = true,
        )
        engine.setInitialLocator(Locator(LocatorStrategy.Page(index = 2, total = 20)))

        val opened = engine.openBook(Uri.fromFile(epub))

        val strategy = opened.strategy as? LocatorStrategy.Section
        assertEquals(
            "restored Page locators from older paged engines must be translated through page slices, not treated as paragraph indexes",
            0,
            strategy?.elementIndex,
        )
        assertTrue(
            "page 2 should restore inside the first long paragraph, not at its head: $strategy",
            (strategy?.charOffset ?: 0) > 0,
        )
    }

    @Test
    fun `open book translates restored page locator by progression across spines`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val paragraph = (1..200).joinToString(separator = "") { "a" }
        val epub = tempDir.newFile("open-restored-page-progression.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$paragraph</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>$paragraph</p></body></html>",
            "OEBPS/ch3.xhtml" to "<html><body><p>$paragraph</p></body></html>",
            "OEBPS/ch4.xhtml" to "<html><body><p>$paragraph</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
            flowEngineEnabled = true,
        )
        engine.setInitialLocator(
            Locator(
                LocatorStrategy.Page(index = 1, total = 2),
                totalProgression = 0.5f,
            ),
        )

        val opened = engine.openBook(Uri.fromFile(epub))

        val strategy = opened.strategy as? LocatorStrategy.Section
        assertEquals(
            "restored Page locators with whole-book progression must land by progress, not by raw page index",
            2,
            strategy?.elementIndex,
        )
        assertTrue(
            "progression-based restore should prefetch and measure the target spine before resolving: $strategy",
            (strategy?.charOffset ?: 0) > 0,
        )
    }

    @Test
    fun `open book builds page slices from cached text without loading cold spines`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("open-cached-page-slices.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two.</p></body></html>",
            "OEBPS/ch3.xhtml" to "<html><body><p>Chapter three must stay cold.</p></body></html>",
            "OEBPS/ch4.xhtml" to "<html><body><p>Chapter four must stay cold.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)

        engine.openBook(Uri.fromFile(epub))

        val book = engine.lazyBookForTest()
        assertEquals("initial prefetch should load the opened spine", 1, book?.loadCount(0))
        assertEquals("initial prefetch may warm the next spine", 1, book?.loadCount(1))
        assertEquals("building page slices must not parse cold spine 3", 0, book?.loadCount(2))
        assertEquals("building page slices must not parse cold spine 4", 0, book?.loadCount(3))
    }

    @Test
    fun `flow goTo translates page locator through pagination instead of paragraph index`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstParagraph = (1..360).joinToString(separator = "") { "a" }
        val secondParagraph = "second paragraph should not be the page goTo target"
        val epub = tempDir.newFile("flow-goto-page-locator.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$firstParagraph</p><p>$secondParagraph</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
            flowEngineEnabled = true,
        )
        engine.openBook(Uri.fromFile(epub))

        engine.goTo(Locator(LocatorStrategy.Page(index = 2, total = 20)))

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals(
            "flow goTo(Page) must use page slices, not paragraph indexes, for old remote/local progress",
            0,
            strategy?.elementIndex,
        )
        assertTrue(
            "page 2 should land inside the first long paragraph: $strategy",
            (strategy?.charOffset ?: 0) > 0,
        )
    }

    @Test
    fun `flow mode switch to paged preserves paragraph offset anchor`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val paragraphs = List(4) { paragraphIndex ->
            (1..180).joinToString(separator = " ") { token -> "p${paragraphIndex}w$token" }
        }
        val targetParagraph = 2
        val targetLocalOffset = paragraphs[targetParagraph].indexOf("p${targetParagraph}w150")
        val targetSpineOffset = paragraphs.take(targetParagraph).sumOf { it.length } + targetLocalOffset
        val targetParagraphHead = paragraphs.take(targetParagraph).sumOf { it.length }
        val epub = tempDir.newFile("flow-mode-offset-anchor.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to paragraphs.joinToString(
                separator = "",
                prefix = "<html><body>",
                postfix = "</body></html>",
            ) { paragraph -> "<p>${paragraph.replace(" ", "<br/>")}</p>" },
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

        engine.openBook(Uri.fromFile(epub))
        engine.setFontSize(32f)
        engine.setLineSpacing(1.8f)
        val host = engine.createView() as FrameLayout
        activity.addContentView(host, ViewGroup.LayoutParams(360, 80))
        host.measure(exactly(360), exactly(80))
        host.layout(0, 0, 360, 80)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        engine.goTo(
            Locator(
                LocatorStrategy.Section(
                    spineIndex = 0,
                    elementIndex = targetParagraph,
                    charOffset = targetSpineOffset,
                ),
            ),
        )
        val before = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertTrue("test must start inside the target paragraph", (before?.charOffset ?: 0) > targetParagraphHead)
        val beforeCharOffset = before?.charOffset ?: 0

        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()

        val after = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        val afterCharOffset = after?.charOffset ?: 0
        assertTrue(
            "switching SCROLL->PAGED must not throw the flow anchor back to the paragraph head: before=$before after=$after",
            afterCharOffset > targetParagraphHead && afterCharOffset >= beforeCharOffset - 100,
        )
    }

    @Test
    fun `flow mode switch to paged lands on target anchor without posted correction jump`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val paragraphs = List(5) { paragraphIndex ->
            (1..220).joinToString(separator = " ") { token -> "p${paragraphIndex}w$token" }
        }
        val targetParagraph = 3
        val targetLocalOffset = paragraphs[targetParagraph].indexOf("p${targetParagraph}w180")
        val targetSpineOffset = paragraphs.take(targetParagraph).sumOf { it.length } + targetLocalOffset
        val epub = tempDir.newFile("flow-mode-no-posted-jump.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to paragraphs.joinToString(
                separator = "",
                prefix = "<html><body>",
                postfix = "</body></html>",
            ) { paragraph -> "<p>${paragraph.replace(" ", "<br/>")}</p>" },
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

        engine.openBook(Uri.fromFile(epub))
        engine.setFontSize(32f)
        engine.setLineSpacing(1.8f)
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        activity.addContentView(host, ViewGroup.LayoutParams(360, 80))
        host.measure(exactly(360), exactly(80))
        host.layout(0, 0, 360, 80)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        engine.setMode(ReadingMode.SCROLL)
        shadowOf(Looper.getMainLooper()).idle()
        engine.goTo(
            Locator(
                LocatorStrategy.Section(
                    spineIndex = 0,
                    elementIndex = targetParagraph,
                    charOffset = targetSpineOffset,
                ),
            ),
        )
        assertTrue("test must start from a deep continuous-scroll position", flowView.scrollY > 0)

        engine.setMode(ReadingMode.PAGED)
        val immediateScrollY = flowView.scrollY

        assertTrue(
            "SCROLL->PAGED must not synchronously jump back to the old paged anchor before a posted correction",
            immediateScrollY > 0,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "SCROLL->PAGED should not need a later posted correction to reach the target anchor",
            immediateScrollY,
            flowView.scrollY,
        )
    }

    @Test
    fun `flow mode switch to paged samples live scroll anchor when locator lags`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val paragraphs = List(5) { paragraphIndex ->
            (1..220).joinToString(separator = " ") { token -> "p${paragraphIndex}w$token" }
        }
        val epub = tempDir.newFile("flow-mode-live-anchor.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to paragraphs.joinToString(
                separator = "",
                prefix = "<html><body>",
                postfix = "</body></html>",
            ) { paragraph -> "<p>${paragraph.replace(" ", "<br/>")}</p>" },
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

        engine.openBook(Uri.fromFile(epub))
        engine.setFontSize(32f)
        engine.setLineSpacing(1.8f)
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        activity.addContentView(host, ViewGroup.LayoutParams(360, 80))
        host.measure(exactly(360), exactly(80))
        host.layout(0, 0, 360, 80)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        engine.setMode(ReadingMode.SCROLL)
        shadowOf(Looper.getMainLooper()).idle()
        val layout = requireNotNull(flowView.textView.layout)
        val maxScroll = (flowView.getChildAt(0).height - flowView.height).coerceAtLeast(0)
        assertTrue("test document must be scrollable enough to expose stale-locator drift", maxScroll > 300)
        val liveLine = layout.getLineForVertical(maxScroll / 2)
        val liveTop = layout.getLineTop(liveLine)
        val liveOffset = layout.getLineStart(liveLine)
        assertTrue("test must sample a deep live offset", liveOffset > 500)
        flowView.scrollTo(0, liveTop)
        assertTrue(
            "test must leave the engine locator stale at book start",
            (engine.currentLocator.value.totalProgression ?: 0f) < 0.05f,
        )

        engine.setMode(ReadingMode.PAGED)

        val expectedPageTop = flowView.pageTopPxAt(flowView.currentPageIndex())
        val currentTopOffset = flowView.topLayoutOffset()
        assertEquals("SCROLL->PAGED should park on the live viewport's nearest page", expectedPageTop, flowView.scrollY)
        assertTrue(
            "mode switch should sample live top offset instead of stale locator: live=$liveOffset actual=$currentTopOffset",
            currentTopOffset >= liveOffset - 120,
        )
    }

    @Test
    fun `flow same mode update does not re-anchor visible paged scroll`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val paragraphs = List(4) { paragraphIndex ->
            (1..220).joinToString(separator = " ") { token -> "p${paragraphIndex}w$token" }
        }
        val epub = tempDir.newFile("flow-same-mode-no-reanchor.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to paragraphs.joinToString(
                separator = "",
                prefix = "<html><body>",
                postfix = "</body></html>",
            ) { paragraph -> "<p>${paragraph.replace(" ", "<br/>")}</p>" },
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

        engine.openBook(Uri.fromFile(epub))
        engine.setFontSize(32f)
        engine.setLineSpacing(1.8f)
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        activity.addContentView(host, ViewGroup.LayoutParams(360, 80))
        host.measure(exactly(360), exactly(80))
        host.layout(0, 0, 360, 80)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        engine.setMode(ReadingMode.PAGED)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("test document must have multiple pages", flowView.pageCount() > 3)
        val canonicalTop = flowView.pageTopPxAt(1) ?: error("missing page top")
        val nonCanonicalTop = canonicalTop + 17
        flowView.scrollTo(0, nonCanonicalTop)
        assertEquals("test must start from a visible temporary scroll offset", nonCanonicalTop, flowView.scrollY)

        engine.setMode(ReadingMode.PAGED)

        assertEquals(
            "re-selecting the current PAGED mode must not make the page look like it converted from scroll again",
            nonCanonicalTop,
            flowView.scrollY,
        )
    }

    @Test
    fun `flow same typography setting updates do not hide visible content`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("flow-same-line-spacing.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                    <p>${(1..160).joinToString(" ") { "line$it" }}</p>
                    <p>${(1..160).joinToString(" ") { "tail$it" }}</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

        engine.openBook(Uri.fromFile(epub))
        engine.setFontSize(28f)
        engine.setLineSpacing(1.8f)
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        val contentLayer = flowView.getChildAt(0) as FrameLayout
        activity.addContentView(host, ViewGroup.LayoutParams(360, 220))
        host.measure(exactly(360), exactly(220))
        host.layout(0, 0, 360, 220)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        assertEquals(1f, contentLayer.alpha)
        val visibleScrollY = flowView.scrollY
        fun assertStillVisible(reason: String) {
            assertEquals(reason, 1f, contentLayer.alpha)
            assertEquals(visibleScrollY, flowView.scrollY)
        }

        engine.setFontSize(28f)

        assertStillVisible(
            "watchSettings can replay the current font size after the page is visible; that no-op must not restart reveal",
        )

        engine.setLineSpacing(1.8f)

        assertStillVisible(
            "watchSettings can replay the current line spacing after the page is visible; that no-op must not restart reveal",
        )

        engine.setTheme(ThemeMode.SYSTEM)

        assertStillVisible("current theme replay must not rebuild and reveal visible EPUB flow content")

        engine.setPageFlipStyle(PageFlipStyle.SLIDE)

        assertStillVisible("current page flip style replay must not disturb visible EPUB flow content")

        engine.setSerifFont(true)

        assertStillVisible("current legacy serif font replay must not disturb visible EPUB flow content")

        engine.setFont("source_han")

        assertStillVisible(
            "watchSettings can also replay the current font choice after the page is visible",
        )
    }

    @Test
    fun `paged runtime returns compose view as text page root`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-page-root.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Root ComposeView EPUB paged text.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertTrue(page is ComposeView)
        assertEquals(null, page.contentDescription)
        assertEquals("第 1 页，共 1 页", page.stateDescription)
        assertEquals("第 1 页，共 1 页", page.getTag(R.id.epub_compose_page_progress_description))
        assertEquals(true, page.getTag(R.id.epub_compose_page_root_delegates_accessibility_to_text))
        assertNotNull(page.tag as? EpubPageSlice)
    }

    @Test
    fun `paged runtime sizes image only cover as full page image`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("cover-image-sizing.epub")
        writeEpub(
            epub,
            "OEBPS/cover.xhtml" to "<html><body><img src=\"images/cover.png\" alt=\"Cover art\"/></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val imageView = findView(page, ImageView::class.java)

        assertNotNull(imageView)
        assertEquals(EpubImagePlacement.FullPage, imageView?.getTag(R.id.epub_image_placement))
    }

    @Test
    fun `paged runtime keeps inline marker images from being enlarged to full page`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("inline-marker-image-sizing.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Scene text.</p><img src=\"images/marker.png\" alt=\"Marker\"/><p>More text.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val imagePage = (0 until engine.pageCount.value)
            .map { engine.createPageView(it) }
            .first { findView(it, ImageView::class.java) != null }
        val imageView = findView(imagePage, ImageView::class.java)

        assertNotNull(imageView)
        assertEquals(EpubImagePlacement.Inline, imageView?.getTag(R.id.epub_image_placement))
    }

    @Test
    fun `paged runtime exposes compose text surface state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose surfaced EPUB paged text."
        val epub = tempDir.newFile("compose-text-surface.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertTrue(page is ComposeView)
        assertEquals(text, page.getTag(R.id.epub_compose_text_surface))
    }

    @Test
    fun `paged runtime renders adjacent short paragraphs on one text page`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("short-paragraphs-one-page.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>First short sentence.</p>
                  <p>Second short sentence.</p>
                  <p>Third short sentence.</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val slice = findTaggedSlice(page)

        assertEquals(1, engine.pageCount.value)
        assertEquals(0, slice?.paragraphIndex)
        assertEquals(2, slice?.endParagraphIndex)
        assertEquals(
            "First short sentence.\n\nSecond short sentence.\n\nThird short sentence.",
            page.getTag(R.id.epub_compose_text_surface),
        )
    }

    @Test
    fun `paged runtime maps compose selection across packed paragraphs`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val first = "First short sentence."
        val second = "Second short sentence."
        val third = "Third short sentence."
        val epub = tempDir.newFile("packed-paragraph-cross-selection.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>$first</p>
                  <p>$second</p>
                  <p>$third</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val pageText = page.getTag(R.id.epub_compose_text_surface) as String
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        val selectionStart = pageText.indexOf(first)
        val selectionEnd = pageText.indexOf("\n\n$third")

        assertNotNull(callback)
        callback?.invoke(selectionStart, selectionEnd)
        val selection = engine.currentTextSelection.value

        assertEquals("$first\n\n$second", selection?.selectedText)
        assertEquals(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 0),
            selection?.start?.strategy,
        )
        assertEquals(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 1, charOffset = first.length + second.length),
            selection?.end?.strategy,
        )
        assertEquals(selectionStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(
            ReaderTextHighlightRange(selectionStart, selectionEnd, EpubComposeSelectionHighlightColor),
            page.getTag(R.id.epub_compose_text_selection_highlight_range),
        )
    }

    @Test
    fun `paged runtime packs dialogue micro paragraphs without one sentence pages`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val lines = listOf(
            "“醒了吗？”",
            "“嗯。”",
            "窗外雨声很轻。",
            "她把灯按暗。",
            "“再睡一会儿。”",
            "“好。”",
        )
        val epub = tempDir.newFile("dialogue-micro-paragraphs.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to lines.joinToString(
                prefix = "<html><body>",
                separator = "",
                postfix = "</body></html>",
            ) { line -> "<p>$line</p>" },
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val slice = findTaggedSlice(page)

        assertEquals(1, engine.pageCount.value)
        assertEquals(0, slice?.paragraphIndex)
        assertEquals(lines.lastIndex, slice?.endParagraphIndex)
        assertEquals(lines.joinToString(separator = "\n\n"), page.getTag(R.id.epub_compose_text_surface))
    }

    @Test
    fun `paged runtime packs linked text with following plain paragraphs and keeps links`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("linked-paragraph-packs-with-plain-text.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p><a href="https://example.com/note">Tap note</a></p>
                  <p>Plain follow.</p>
                  <p>Plain tail.</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val links = page.getTag(R.id.epub_compose_text_links) as? List<EpubTextLink>

        // 链接段落不再独占一页，与后续正文合并（非必要不分页），链接元数据保留并重映射。
        assertEquals(1, engine.pageCount.value)
        assertEquals("Tap note\n\nPlain follow.\n\nPlain tail.", page.getTag(R.id.epub_compose_text_surface))
        assertTrue(links.orEmpty().isNotEmpty())
        assertEquals(0, links!!.first().start)
        assertEquals("Tap note".length, links.first().end)
    }

    @Test
    fun `paged runtime does not merge short text across chapter spines`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("short-chapters-stay-on-separate-pages.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one end.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two start.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val firstPage = engine.createPageView(0)
        val secondPage = engine.createPageView(1)

        assertEquals(2, engine.pageCount.value)
        assertEquals("Chapter one end.", firstPage.getTag(R.id.epub_compose_text_surface))
        assertEquals("Chapter two start.", secondPage.getTag(R.id.epub_compose_text_surface))
    }

    @Test
    fun `flow next across short chapter boundary starts slide animation`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("short-chapter-boundary-slide.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one end.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two start.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)

        engine.setPageFlipStyle(PageFlipStyle.SLIDE)
        engine.setMode(ReadingMode.PAGED)
        engine.openBook(Uri.fromFile(epub))
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        activity.addContentView(host, ViewGroup.LayoutParams(360, 140))
        host.measure(exactly(360), exactly(140))
        host.layout(0, 0, 360, 140)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("test fixture should start with a single short page", 1, flowView.pageCount())

        engine.goToAdjacentPage(1)
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        val animator = flowView.privateField("flipAnimator")
        val pending = flowView.privateField("pendingBoundaryPageTurn")
        assertEquals("the boundary turn should land on the next spine", 1, strategy?.spineIndex)
        assertEquals("the captured outgoing page shot should be consumed by the target chapter", null, pending)
        assertNotNull(
            "cross-spine page turn should be driven by the same slide animator as in-chapter turns",
            animator,
        )
    }

    @Test
    fun `flow NONE turn crosses a short chapter boundary with an opaque owner throughout`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("short-chapter-boundary-none.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one end.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two start.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)

        engine.setPageFlipStyle(PageFlipStyle.NONE)
        engine.setMode(ReadingMode.PAGED)
        engine.openBook(Uri.fromFile(epub))
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        activity.addContentView(host, ViewGroup.LayoutParams(360, 140))
        host.measure(exactly(360), exactly(140))
        host.layout(0, 0, 360, 140)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("test fixture should start with one short page", 1, flowView.pageCount())
        assertEquals("the initial live chapter must own the viewport", 1f, flowView.getChildAt(0).alpha)

        engine.goToAdjacentPage(1)

        assertTrue("NONE must still install the adjacent chapter", flowView.textView.text.toString().contains("Chapter two start."))
        val continuityOwner = flowView.privateField("conversionSnapshotDrawable")
        val continuityAlpha = continuityOwner?.let { owner ->
            owner.javaClass.getDeclaredField("alphaValue")
                .apply { isAccessible = true }
                .get(owner) as Int
        }
        val continuityBitmap = continuityOwner?.let { owner ->
            owner.javaClass.getDeclaredField("bitmap")
                .apply { isAccessible = true }
                .get(owner) as? Bitmap
        }
        assertTrue(
            "the target may stay hidden only while a live opaque continuity owner covers it",
            flowView.getChildAt(0).alpha == 1f ||
                (
                    continuityAlpha == 255 &&
                        continuityBitmap != null &&
                        !continuityBitmap.isRecycled
                    ),
        )
        assertNull("NONE must not start a page-turn animator", flowView.privateField("flipAnimator"))

        shadowOf(Looper.getMainLooper()).idle()

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals("NONE must settle on the adjacent spine", 1, strategy?.spineIndex)
        assertEquals("the stable target must fully own the viewport", 1f, flowView.getChildAt(0).alpha)
        assertNull("the continuity owner must retire after the target is stable", flowView.privateField("conversionSnapshotDrawable"))
        assertNull("NONE must remain animation-free", flowView.privateField("flipAnimator"))
    }

    @Test
    fun `flow boundary snapshot failure rejects the turn and a later retry can cross`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("short-chapter-boundary-snapshot-failure.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one end.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two start.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val engine = EpubReflowEngine(context, flowEngineEnabled = true)

        engine.setPageFlipStyle(PageFlipStyle.SLIDE)
        engine.setMode(ReadingMode.PAGED)
        engine.openBook(Uri.fromFile(epub))
        val host = engine.createView() as FrameLayout
        val flowView = host.getChildAt(0) as EpubFlowView
        activity.addContentView(host, ViewGroup.LayoutParams(360, 140))
        host.measure(exactly(360), exactly(140))
        host.layout(0, 0, 360, 140)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("test fixture should start with one short page", 1, flowView.pageCount())
        assertEquals("the initial live chapter must own the viewport", 1f, flowView.getChildAt(0).alpha)
        assertNull(flowView.privateField("conversionSnapshotDrawable"))
        val paper = flowView.background
        flowView.background = FailNextDrawDrawable(paper)
        assertFalse("fixture must force boundary page-shot preparation to fail", flowView.prepareBoundaryPageTurn(1))

        flowView.background = FailNextDrawDrawable(paper)
        engine.goToAdjacentPage(1)

        val rejectedStrategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertTrue("the rejected turn must retain chapter one", flowView.textView.text.toString().contains("Chapter one end."))
        assertFalse("the failed request must not install chapter two", flowView.textView.text.toString().contains("Chapter two start."))
        assertEquals("the rejected turn must keep the chapter-one locator", 0, rejectedStrategy?.spineIndex)
        assertEquals("the current live page must remain the visible owner", 1f, flowView.getChildAt(0).alpha)
        assertNull("a failed preparation must not leave a pending boundary transaction", flowView.privateField("pendingBoundaryPageTurn"))
        assertNull("a failed preparation must not install an empty continuity cover", flowView.privateField("conversionSnapshotDrawable"))

        flowView.background = paper
        engine.goToAdjacentPage(1)
        shadowOf(Looper.getMainLooper()).idle()

        val retriedStrategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals("a later healthy request must still cross to chapter two", 1, retriedStrategy?.spineIndex)
        assertTrue(flowView.textView.text.toString().contains("Chapter two start."))
    }

    @Test
    fun `paged runtime packs short list items without one item pages`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("short-list-items-one-page.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <ul>
                    <li>First</li>
                    <li>Second</li>
                    <li>Third</li>
                  </ul>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        val slice = findTaggedSlice(page)

        assertEquals(1, engine.pageCount.value)
        assertEquals(EpubPageTextStyle(kind = EpubTextKind.ListItem), slice?.textStyle)
        assertEquals("• First\n\n• Second\n\n• Third", page.getTag(R.id.epub_compose_text_surface))
    }

    @Test
    fun `paged runtime packs short paragraphs in uncached later spines`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val chapters = (1..6).map { chapterIndex ->
            val chapter = chapterIndex.toString().padStart(2, '0')
            val heading = "Short chapter $chapter"
            val markers = (1..10).map { markerIndex ->
                "chapter-$chapter-${markerIndex.toString().padStart(2, '0')}"
            }
            val body = buildString {
                appendLine("<h2>$heading</h2>")
                markers.forEachIndexed { index, marker ->
                    appendLine(
                        when (index % 3) {
                            0 -> "<p>$marker plain.</p>"
                            1 -> "<ul><li>$marker list.</li></ul>"
                            else -> "<blockquote><p>$marker quote.</p></blockquote>"
                        },
                    )
                }
            }
            "OEBPS/chapter-$chapterIndex.xhtml" to "<html><body>$body</body></html>"
        }
        val epub = tempDir.newFile("later-spines-packed.epub")
        writeEpub(epub, *chapters.toTypedArray())
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)

        assertTrue(engine.pageCount.value < 30)
        val pageTexts = (0 until engine.pageCount.value).map { pageIndex ->
            engine.createPageView(pageIndex).getTag(R.id.epub_compose_text_surface) as String
        }
        val laterBodyText = pageTexts.first { text -> text.contains("chapter-03-01") }

        assertTrue(laterBodyText.contains("chapter-03-01"))
        assertTrue(Regex("chapter-03-\\d{2}").findAll(laterBodyText).count() >= 2)
        assertTrue(
            pageTexts
                .filter { text -> text.contains("chapter-03-") }
                .all { text -> Regex("chapter-03-\\d{2}").findAll(text).count() >= 2 },
        )
    }

    @Test
    fun `paged runtime makes compose text visible while selection overlay stays non visual`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Visible Compose EPUB paged text."
        val epub = tempDir.newFile("visible-compose-text-surface.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertTrue(page is ComposeView)
        assertEquals(true, page.getTag(R.id.epub_compose_text_surface_visible))
        assertEquals(false, page.getTag(R.id.epub_selection_overlay_visible))
    }

    @Test
    fun `paged runtime exposes compose text selection container state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Selectable Compose EPUB paged text."
        val epub = tempDir.newFile("compose-selection-container.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(true, page.getTag(R.id.epub_compose_text_selection_enabled))
        assertEquals(false, page.getTag(R.id.epub_selection_overlay_visible))
    }

    @Test
    fun `paged runtime exposes compose text selection callback bridge`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose callback selection for paged EPUB text."
        val selectionStart = text.indexOf("callback")
        val selectionEnd = selectionStart + "callback".length
        val epub = tempDir.newFile("compose-selection-callback.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertNotNull(callback)
        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(selectionStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(
            ReaderTextHighlightRange(selectionStart, selectionEnd, EpubComposeSelectionHighlightColor),
            page.getTag(R.id.epub_compose_text_selection_highlight_range),
        )
        assertEquals("callback", engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime mirrors trimmed compose callback selection range into compose state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Alpha - beta"
        val selectionStart = text.indexOf("-")
        val selectionEnd = text.length
        val trimmedStart = text.indexOf("beta")
        val epub = tempDir.newFile("compose-selection-trimmed-callback.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertNotNull(callback)
        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(trimmedStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(
            ReaderTextHighlightRange(trimmedStart, selectionEnd, EpubComposeSelectionHighlightColor),
            page.getTag(R.id.epub_compose_text_selection_highlight_range),
        )
        assertEquals("beta", engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime keeps compose callback selection on code point boundaries`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "\uD840\uDC00文 selection"
        val epub = tempDir.newFile("compose-selection-codepoint-boundary.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertNotNull(callback)
        callback?.invoke(0, 1)

        assertEquals(0 to 2, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals("\uD840\uDC00", engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime keeps compose callback selection on combining mark boundaries`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val selectedText = "e\u0301"
        val text = "Caf${selectedText} selection"
        val selectionStart = text.indexOf(selectedText)
        val epub = tempDir.newFile("compose-selection-combining-mark-boundary.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertNotNull(callback)
        callback?.invoke(selectionStart, selectionStart + 1)

        assertEquals(
            selectionStart to selectionStart + selectedText.length,
            page.getTag(R.id.epub_compose_text_selection_range),
        )
        assertEquals(selectedText, engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime updates compose selection without selection aware text view bridge`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose owns paged EPUB selection without a view bridge."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val reboundSelectionStart = text.indexOf("Compose")
        val reboundSelectionEnd = reboundSelectionStart + "Compose".length
        val epub = tempDir.newFile("compose-selection-without-view-bridge.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
        assertNotNull(callback)
        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(selectionStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals("selection", engine.currentTextSelection.value?.selectedText)

        engine.setTextAnnotations(emptyList())
        @Suppress("UNCHECKED_CAST")
        val reboundCallback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        reboundCallback?.invoke(reboundSelectionStart, reboundSelectionEnd)

        assertEquals(reboundSelectionStart to reboundSelectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals("Compose", engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime rebinds active compose page to rebuilt line spacing slice`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Reflowed Compose pages should stop using stale slices after line spacing changes."
        val epub = tempDir.newFile("compose-rebuilt-line-spacing-slice.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val activePage = engine.createPageView(0)
        val originalSurface = activePage.getTag(R.id.epub_compose_text_surface) as? String

        engine.setLineSpacing(20f)
        val freshPage = engine.createPageView(0)
        val reboundSurface = activePage.getTag(R.id.epub_compose_text_surface) as? String
        val freshSurface = freshPage.getTag(R.id.epub_compose_text_surface) as? String

        assertTrue(originalSurface.orEmpty().length > freshSurface.orEmpty().length)
        assertEquals(freshPage.tag, activePage.tag)
        assertEquals(freshSurface, reboundSurface)
    }

    @Test
    fun `paged runtime clears page local compose selection when line spacing rebuilds slices`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Selected Compose text should not survive as stale page local offsets after repagination."
        val selectionStart = text.indexOf("Compose")
        val selectionEnd = selectionStart + "Compose".length
        val epub = tempDir.newFile("compose-selection-cleared-after-repagination.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        callback?.invoke(selectionStart, selectionEnd)
        engine.setLineSpacing(20f)

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `paged runtime clears compose selection when page navigation leaves active page`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Selection should clear when paged navigation moves to the next EPUB page. " +
            (1..240).joinToString(separator = "") { index -> ('a' + (index % 26)).toString() }
        val selectionStart = text.indexOf("Selection")
        val selectionEnd = selectionStart + "Selection".length
        val epub = tempDir.newFile("compose-selection-cleared-after-page-navigation.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        assertTrue(engine.pageCount.value > 1)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        callback?.invoke(selectionStart, selectionEnd)
        engine.goTo(Locator(LocatorStrategy.Page(1, engine.pageCount.value)))

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `scroll runtime does not request paged navigation for section goTo`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("scroll-goto-no-paged-request.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>First paragraph.</p><p>Second paragraph.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)
        val requestedPages = mutableListOf<Int>()

        engine.openBook(Uri.fromFile(epub))
        engine.setPageRequestCallback { requestedPages += it }
        engine.goTo(Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 1, charOffset = 0)))

        assertTrue(requestedPages.isEmpty())
        val strategy = engine.currentLocator.value.strategy as LocatorStrategy.Section
        assertEquals(1, strategy.elementIndex)
    }

    @Test
    fun `paged runtime retires active compose text page when repagination maps page to image slice`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = (1..30).joinToString(separator = " ") { index -> "word$index" }
        val epub = tempDir.newFile("compose-text-page-rebound-to-image.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p><img src=\"cover.png\" alt=\"Cover\"/></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        engine.setLineSpacing(40f)
        assertTrue(engine.pageCount.value > 2)
        val activeTextPage = engine.createPageView(1)
        @Suppress("UNCHECKED_CAST")
        val callback = activeTextPage.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        callback?.invoke(0, 1)

        engine.setLineSpacing(0.1f)
        val freshImagePage = engine.createPageView(1)

        assertNotNull(findView(freshImagePage, ImageView::class.java))
        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(false, activeTextPage.getTag(R.id.epub_compose_text_surface_visible))
        assertEquals(false, activeTextPage.getTag(R.id.epub_compose_text_selection_enabled))
        assertEquals(null, activeTextPage.getTag(R.id.epub_compose_text_selection_callback))
        assertEquals(null, activeTextPage.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, activeTextPage.getTag(R.id.epub_compose_text_selection_highlight_range))
        assertEquals(null, activeTextPage.getTag(R.id.epub_compose_page_progress_description))
        assertEquals(false, activeTextPage.getTag(R.id.epub_compose_page_root_delegates_accessibility_to_text))
        assertEquals(null, activeTextPage.stateDescription)
    }

    @Test
    fun `paged runtime updates compose page progress after line spacing rebuilds page count`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = (1..120).joinToString(separator = "") { index -> ('a' + (index % 26)).toString() }
        val epub = tempDir.newFile("compose-progress-updated-after-repagination.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val activePage = engine.createPageView(0)
        val originalProgress = activePage.getTag(R.id.epub_compose_page_progress_description) as? String

        engine.setLineSpacing(20f)
        val freshPage = engine.createPageView(0)
        val freshProgress = freshPage.getTag(R.id.epub_compose_page_progress_description) as? String

        assertTrue(originalProgress != freshProgress)
        assertEquals(freshProgress, activePage.getTag(R.id.epub_compose_page_progress_description))
        assertEquals(freshPage.stateDescription, activePage.stateDescription)
    }

    @Test
    fun `paged runtime clears compose selection state for blank compose selection`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Alpha beta"
        val selectionStart = text.indexOf(" ")
        val selectionEnd = selectionStart + 1
        val epub = tempDir.newFile("compose-blank-selection.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `paged runtime exposes compose text gesture selection wiring`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose gesture selection for paged EPUB text."
        val epub = tempDir.newFile("compose-selection-gesture.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(true, page.getTag(R.id.epub_compose_text_gesture_selection_wired))
        assertEquals(true, page.getTag(R.id.epub_compose_text_long_press_selection_wired))
        assertEquals(true, page.getTag(R.id.epub_compose_text_selection_enabled))
        assertNotNull(page.getTag(R.id.epub_compose_text_selection_callback))
    }

    @Test
    fun `paged runtime avoids selection view bridge while compose gesture text owns foreground`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose gesture foreground for paged EPUB text."
        val epub = tempDir.newFile("compose-selection-foreground.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
        assertEquals(false, page.getTag(R.id.epub_selection_overlay_behind_compose_text))
        assertEquals(false, page.getTag(R.id.epub_selection_bridge_hosted_in_compose_tree))
        assertEquals(true, page.getTag(R.id.epub_compose_text_gesture_selection_wired))
    }

    @Test
    fun `paged runtime exposes compose text semantics without transparent overlay accessibility`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Accessible Compose text for paged EPUB reading."
        val epub = tempDir.newFile("compose-text-accessibility.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
        assertEquals(true, page.getTag(R.id.epub_compose_text_semantics_exposed))
        assertEquals(true, page.getTag(R.id.epub_selection_overlay_accessibility_hidden))
    }

    @Test
    fun `paged runtime keeps selection view bridge absent after compose text rebind`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Accessible rebind Compose text for paged EPUB reading."
        val highlightStart = text.indexOf("rebind")
        val highlightEnd = highlightStart + "rebind".length
        val epub = tempDir.newFile("compose-text-accessibility-rebind.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "highlight-rebind",
                    start = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightStart,
                        ),
                    ),
                    end = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightEnd,
                        ),
                    ),
                    selectedText = "rebind",
                    note = null,
                    color = 0x66FFCC00,
                ),
            ),
        )

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
        assertEquals(true, page.getTag(R.id.epub_selection_overlay_accessibility_hidden))
    }

    @Test
    fun `paged runtime keeps selection view bridge out of touch and focus after compose text rebind`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Bridge only Compose selection overlay for paged EPUB reading."
        val highlightStart = text.indexOf("Bridge")
        val highlightEnd = highlightStart + "Bridge".length
        val epub = tempDir.newFile("compose-text-overlay-touch-bridge.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "highlight-bridge",
                    start = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightStart,
                        ),
                    ),
                    end = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightEnd,
                        ),
                    ),
                    selectedText = "Bridge",
                    note = null,
                    color = 0x66FFCC00,
                ),
            ),
        )

        assertEquals(true, page.getTag(R.id.epub_compose_text_gesture_selection_wired))
        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
    }

    @Test
    fun `paged runtime keeps selection view bridge out of native selection while compose callback selects text`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose callback owns paged EPUB selection while the overlay stays inert."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-with-inert-overlay.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
        assertNotNull(callback)

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(selectionStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals("selection", engine.currentTextSelection.value?.selectedText)
        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
    }

    @Test
    fun `paged runtime keeps selection view bridge absent while compose surface owns content after rebind`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose surface owns paged EPUB text while the overlay stays empty."
        val highlightStart = text.indexOf("surface")
        val highlightEnd = highlightStart + "surface".length
        val selectionStart = text.indexOf("Compose")
        val selectionEnd = selectionStart + "Compose".length
        val epub = tempDir.newFile("compose-text-with-empty-overlay.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))
        assertEquals(text, page.getTag(R.id.epub_compose_text_surface))

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "highlight-empty-overlay",
                    start = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightStart,
                        ),
                    ),
                    end = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightEnd,
                        ),
                    ),
                    selectedText = "surface",
                    note = null,
                    color = 0x66FFCC00,
                ),
            ),
        )

        assertEquals(text, page.getTag(R.id.epub_compose_text_surface))
        assertEquals(null, page.getTag(R.id.epub_selection_overlay_view))

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(selectionStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals("Compose", engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime exposes compose text highlight ranges`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose highlighted EPUB paged text."
        val highlightStart = text.indexOf("highlighted")
        val highlightEnd = highlightStart + "highlighted".length
        val epub = tempDir.newFile("compose-highlighted-text-surface.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "highlight-1",
                    start = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightStart,
                        ),
                    ),
                    end = Locator(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 0,
                            charOffset = highlightEnd,
                        ),
                    ),
                    selectedText = "highlighted",
                    note = null,
                    color = 0x66FFCC00,
                ),
            ),
        )
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(
            listOf(ReaderTextHighlightRange(highlightStart, highlightEnd, 0x66FFCC00)),
            page.getTag(R.id.epub_compose_text_highlight_ranges),
        )
    }

    @Test
    fun `paged runtime restores highlight ranges across packed paragraphs`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val first = "First short sentence."
        val second = "Second short sentence."
        val third = "Third short sentence."
        val epub = tempDir.newFile("packed-paragraph-cross-highlight.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>$first</p>
                  <p>$second</p>
                  <p>$third</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
                listOf(EpubTextLayoutLineRange(0, text.length))
            },
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "packed-cross-highlight",
                    start = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 0)),
                    end = Locator(
                        LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = 1,
                            charOffset = first.length + second.length,
                        ),
                    ),
                    selectedText = "$first\n\n$second",
                    note = null,
                    color = 0x66FFCC00,
                ),
            ),
        )
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(
            listOf(
                ReaderTextHighlightRange(0, first.length, 0x66FFCC00),
                ReaderTextHighlightRange(first.length + 2, first.length + 2 + second.length, 0x66FFCC00),
            ),
            page.getTag(R.id.epub_compose_text_highlight_ranges),
        )
    }

    @Test
    fun `paged runtime exposes compose text style and layout state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose blockquote EPUB paged text."
        val epub = tempDir.newFile("compose-layout-state.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><blockquote>$text</blockquote></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(
            EpubPageTextStyle(kind = EpubTextKind.Blockquote),
            page.getTag(R.id.epub_compose_text_style),
        )
        assertEquals(
            EpubComposeTextLayout(
                paddingStartDp = 46,
                paddingEndDp = 28,
                paddingTopDp = 24,
                paddingBottomDp = 24,
                horizontalScroll = false,
            ),
            page.getTag(R.id.epub_compose_text_layout),
        )
    }

    @Test
    fun `paged runtime exposes compose text link ranges`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-text-links.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#n1">note</a>.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to "<html><body><p id=\"n1\">Linked note.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals(
            listOf(EpubTextLink(start = 9, end = 13, href = "OEBPS/notes.xhtml#n1", isExternal = false)),
            page.getTag(R.id.epub_compose_text_links),
        )
    }

    @Test
    fun `paged runtime exposes compose link callback that navigates internal links`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-link-callback.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#n1">note</a>.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to "<html><body><p id=\"n1\">Linked note.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)
        val requestedPages = mutableListOf<Int>()

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        engine.setPageRequestCallback { requestedPages += it }
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_link_callback) as? (EpubTextLink) -> Unit
        @Suppress("UNCHECKED_CAST")
        val links = page.getTag(R.id.epub_compose_text_links) as? List<EpubTextLink>

        assertNotNull(callback)
        assertEquals(true, page.getTag(R.id.epub_compose_text_link_tap_wired))

        callback?.invoke(links.orEmpty().single())

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals(1, strategy?.elementIndex)
        assertEquals(listOf(1), requestedPages)
    }

    @Test
    fun `paged runtime exposes compose url annotations for inline links`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-link-annotations.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#n1">note</a> and <a href="https://example.com">site</a>.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to "<html><body><p id=\"n1\">Linked note.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        val annotatedText = page.getTag(R.id.epub_compose_text_annotated_string) as? AnnotatedString
        assertNotNull(annotatedText)
        assertEquals("Read the note and site.", annotatedText?.text)

        val annotations = annotatedText
            ?.getStringAnnotations(tag = "URL", start = 0, end = annotatedText.length)
            .orEmpty()
        assertEquals(2, annotations.size)
        assertEquals("OEBPS/notes.xhtml#n1", annotations[0].item)
        assertEquals(9, annotations[0].start)
        assertEquals(13, annotations[0].end)
        assertEquals("https://example.com", annotations[1].item)
        assertEquals(18, annotations[1].start)
        assertEquals(22, annotations[1].end)

        val linkSpans = annotatedText
            ?.spanStyles
            .orEmpty()
            .filter { it.item.textDecoration == TextDecoration.Underline }
        assertEquals(2, linkSpans.size)
        assertEquals(9, linkSpans[0].start)
        assertEquals(13, linkSpans[0].end)
        assertEquals(18, linkSpans[1].start)
        assertEquals(22, linkSpans[1].end)
    }

    @Test
    fun `paged runtime exposes compose link annotations for inline links`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-link-annotations-native.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#n1">note</a> and <a href="https://example.com">site</a>.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to "<html><body><p id=\"n1\">Linked note.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        val annotatedText = page.getTag(R.id.epub_compose_text_annotated_string) as? AnnotatedString
        assertNotNull(annotatedText)

        val linkAnnotations = annotatedText
            ?.getLinkAnnotations(start = 0, end = annotatedText.length)
            .orEmpty()
        assertEquals(2, linkAnnotations.size)
        assertEquals(9, linkAnnotations[0].start)
        assertEquals(13, linkAnnotations[0].end)
        assertEquals("OEBPS/notes.xhtml#n1", (linkAnnotations[0].item as? LinkAnnotation.Url)?.url)
        assertEquals(TextDecoration.Underline, linkAnnotations[0].item.styles?.style?.textDecoration)
        assertEquals(18, linkAnnotations[1].start)
        assertEquals(22, linkAnnotations[1].end)
        assertEquals("https://example.com", (linkAnnotations[1].item as? LinkAnnotation.Url)?.url)
        assertEquals(TextDecoration.Underline, linkAnnotations[1].item.styles?.style?.textDecoration)
    }

    @Test
    fun `paged runtime exposes compose link annotation listener for internal links`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-link-annotation-listener.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#n1">note</a>.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to "<html><body><p id=\"n1\">Linked note.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)
        val requestedPages = mutableListOf<Int>()

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        engine.setPageRequestCallback { requestedPages += it }
        val page = engine.createPageView(0)

        val annotatedText = page.getTag(R.id.epub_compose_text_annotated_string) as? AnnotatedString
        assertNotNull(annotatedText)
        val linkAnnotation = annotatedText
            ?.getLinkAnnotations(start = 0, end = annotatedText.length)
            .orEmpty()
            .single()
            .item as? LinkAnnotation.Url
        assertNotNull(linkAnnotation?.linkInteractionListener)

        linkAnnotation?.linkInteractionListener?.onClick(linkAnnotation)

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals(1, strategy?.elementIndex)
        assertEquals(listOf(1), requestedPages)
    }

    @Test
    fun `paged runtime internal links can target image fragments`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-link-image-fragment.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#scene">scene</a>.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to """
                <html><body>
                  <p>Q</p>
                  <img id="scene" src="scene.png" alt="Scene"/>
                  <p>R</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )
        val requestedPages = mutableListOf<Int>()

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        engine.setPageRequestCallback { requestedPages += it }
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_link_callback) as? (EpubTextLink) -> Unit
        @Suppress("UNCHECKED_CAST")
        val links = page.getTag(R.id.epub_compose_text_links) as? List<EpubTextLink>

        callback?.invoke(links.orEmpty().single())

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals(1, strategy?.spineIndex)
        assertEquals(1, strategy?.elementIndex)
        assertEquals(1, strategy?.charOffset)
        val textPageIndex = engine.pageIndexForLocator(
            Locator(
                LocatorStrategy.Section(
                    spineIndex = 1,
                    elementIndex = 1,
                    charOffset = 0,
                ),
            ),
        )
        val imagePageIndex = engine.pageIndexForLocator(engine.currentLocator.value)
        assertTrue(imagePageIndex > textPageIndex)
        assertEquals(listOf(imagePageIndex), requestedPages)
        assertNull(findView(engine.createPageView(textPageIndex), ImageView::class.java))
        assertNotNull(findView(engine.createPageView(imagePageIndex), ImageView::class.java))
    }

    @Test
    fun `paged runtime starts on image only cover spine without blank text page`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("image-only-cover-spine.epub")
        writeEpub(
            epub,
            "OEBPS/cover.xhtml" to """
                <html><body>
                  <img id="cover-art" src="cover.png" alt="Cover art"/>
                </body></html>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Chapter one text.</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)

        val strategy = engine.currentLocator.value.strategy as? LocatorStrategy.Section
        assertEquals(0, strategy?.spineIndex)
        assertEquals(0, strategy?.elementIndex)
        assertEquals(0, strategy?.charOffset)
        assertNotNull(findView(engine.createPageView(0), ImageView::class.java))
        assertNull(findView(engine.createPageView(1), ImageView::class.java))
    }

    @Test
    fun `paged runtime ignores stale compose link activation after switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-stale-link-activation.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Visit the <a href="https://example.com">site</a>.</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_link_callback) as? (EpubTextLink) -> Unit
        @Suppress("UNCHECKED_CAST")
        val links = page.getTag(R.id.epub_compose_text_links) as? List<EpubTextLink>
        val annotatedText = page.getTag(R.id.epub_compose_text_annotated_string) as? AnnotatedString
        val linkAnnotation = annotatedText
            ?.getLinkAnnotations(start = 0, end = annotatedText.length)
            .orEmpty()
            .single()
            .item as? LinkAnnotation.Url

        assertNotNull(callback)
        assertNotNull(linkAnnotation?.linkInteractionListener)

        engine.setMode(ReadingMode.SCROLL)
        callback?.invoke(links.orEmpty().single())
        linkAnnotation?.linkInteractionListener?.onClick(linkAnnotation)

        assertEquals(null, shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `paged runtime clears compose selection when link navigation leaves active page`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-link-selection-cleanup.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Read the <a href="notes.xhtml#n1">note</a> after selecting text.</p>
                </body></html>
            """.trimIndent(),
            "OEBPS/notes.xhtml" to "<html><body><p id=\"n1\">Linked note.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val selectionCallback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        @Suppress("UNCHECKED_CAST")
        val linkCallback = page.getTag(R.id.epub_compose_text_link_callback) as? (EpubTextLink) -> Unit
        @Suppress("UNCHECKED_CAST")
        val links = page.getTag(R.id.epub_compose_text_links) as? List<EpubTextLink>

        selectionCallback?.invoke(0, 4)
        assertEquals("Read", engine.currentTextSelection.value?.selectedText)

        linkCallback?.invoke(links.orEmpty().single())

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `paged runtime clears compose selection when external link opens intent`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-external-link-selection-cleanup.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>Visit the <a href="https://example.com">site</a> after selecting text.</p>
                </body></html>
            """.trimIndent(),
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val selectionCallback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        @Suppress("UNCHECKED_CAST")
        val linkCallback = page.getTag(R.id.epub_compose_text_link_callback) as? (EpubTextLink) -> Unit
        @Suppress("UNCHECKED_CAST")
        val links = page.getTag(R.id.epub_compose_text_links) as? List<EpubTextLink>

        selectionCallback?.invoke(0, 5)
        assertEquals("Visit", engine.currentTextSelection.value?.selectedText)

        linkCallback?.invoke(links.orEmpty().single())

        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://example.com"), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `paged runtime mirrors compose callback selection range into compose state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose selection state for paged EPUB text."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-state.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        assertNotNull(callback)
        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(selectionStart to selectionEnd, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals("selection", engine.currentTextSelection.value?.selectedText)
    }

    @Test
    fun `paged runtime exposes compose selection highlight range`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose selection highlight for paged EPUB text."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-highlight.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(
            ReaderTextHighlightRange(selectionStart, selectionEnd, EpubComposeSelectionHighlightColor),
            page.getTag(R.id.epub_compose_text_selection_highlight_range),
        )
    }

    @Test
    fun `paged runtime clears compose selection range with engine selection`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose selection clearing for paged EPUB text."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-clear.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit

        callback?.invoke(selectionStart, selectionEnd)
        engine.clearTextSelection()

        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
        assertEquals(null, engine.currentTextSelection.value)
    }

    @Test
    fun `paged runtime clears compose selection when opening another book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstText = "Compose selection must not leak into the next EPUB book."
        val selectionStart = firstText.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val firstEpub = tempDir.newFile("compose-selection-first-book.epub")
        val secondEpub = tempDir.newFile("compose-selection-second-book.epub")
        writeEpub(
            firstEpub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$firstText</p></body></html>",
        )
        writeEpub(
            secondEpub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Second book has a clean selection state.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(firstEpub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        callback?.invoke(selectionStart, selectionEnd)

        engine.openBook(Uri.fromFile(secondEpub))

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(null, engine.currentTextSelection.value)
    }

    @Test
    fun `paged runtime clears compose text page state when closing book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose selection must not survive after closing the EPUB book."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-close-book.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        callback?.invoke(selectionStart, selectionEnd)

        engine.close()

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(false, page.getTag(R.id.epub_compose_text_surface_visible))
        assertEquals(false, page.getTag(R.id.epub_compose_text_selection_enabled))
        assertEquals(false, page.getTag(R.id.epub_compose_text_semantics_exposed))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_callback))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(null, engine.currentTextSelection.value)
    }

    @Test
    fun `paged runtime clears book progress state when closing book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("close-clears-book-progress.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Chapter one text.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>Chapter two text.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)

        assertTrue(engine.pageCount.value > 0)
        assertTrue(engine.tableOfContents.value.isNotEmpty())
        assertTrue(engine.currentLocator.value.strategy is LocatorStrategy.Section)

        engine.close()

        assertEquals(0, engine.pageCount.value)
        assertEquals(LocatorStrategy.Unknown, engine.currentLocator.value.strategy)
        assertEquals(0, engine.chapterInfo.value.currentIndex)
        assertEquals(1, engine.chapterInfo.value.totalChapters)
        assertEquals("", engine.chapterInfo.value.currentTitle)
        assertEquals(0f, engine.chapterInfo.value.progressInChapter)
        assertTrue(engine.tableOfContents.value.isEmpty())
    }

    @Test
    fun `paged runtime clears compose selection when switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose selection must not survive when the EPUB reader switches modes."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-mode-switch.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        callback?.invoke(selectionStart, selectionEnd)

        engine.setMode(ReadingMode.SCROLL)

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `paged runtime ignores stale compose selection callback after switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose selection callback must not write after switching to scroll mode."
        val selectionStart = text.indexOf("selection")
        val selectionEnd = selectionStart + "selection".length
        val epub = tempDir.newFile("compose-selection-stale-mode-callback.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        callback?.invoke(selectionStart, selectionEnd)

        engine.setMode(ReadingMode.SCROLL)
        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))
    }

    @Test
    fun `paged runtime clears compose page progress semantics when switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("compose-page-progress-mode-switch.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Compose page progress should retire with paged text.</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)

        assertEquals("第 1 页，共 1 页", page.getTag(R.id.epub_compose_page_progress_description))
        assertEquals(true, page.getTag(R.id.epub_compose_page_root_delegates_accessibility_to_text))
        assertEquals("第 1 页，共 1 页", page.stateDescription)

        engine.setMode(ReadingMode.SCROLL)

        assertEquals(null, page.getTag(R.id.epub_compose_page_progress_description))
        assertEquals(false, page.getTag(R.id.epub_compose_page_root_delegates_accessibility_to_text))
        assertEquals(null, page.stateDescription)
    }

    @Test
    fun `paged runtime clears compose text page state when switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val text = "Compose text page state must not survive after switching to scroll mode."
        val selectionStart = text.indexOf("text")
        val selectionEnd = selectionStart + "text".length
        val epub = tempDir.newFile("compose-page-state-mode-switch.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>$text</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val page = engine.createPageView(0)
        @Suppress("UNCHECKED_CAST")
        val callback = page.getTag(R.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        callback?.invoke(selectionStart, selectionEnd)

        engine.setMode(ReadingMode.SCROLL)

        assertEquals(null, engine.currentTextSelection.value)
        assertEquals(false, page.getTag(R.id.epub_compose_text_surface_visible))
        assertEquals(false, page.getTag(R.id.epub_compose_text_selection_enabled))
        assertEquals(false, page.getTag(R.id.epub_compose_text_gesture_selection_wired))
        assertEquals(false, page.getTag(R.id.epub_compose_text_long_press_selection_wired))
        assertEquals(false, page.getTag(R.id.epub_compose_text_semantics_exposed))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_callback))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_range))
        assertEquals(null, page.getTag(R.id.epub_compose_text_selection_highlight_range))

        callback?.invoke(selectionStart, selectionEnd)

        assertEquals(null, engine.currentTextSelection.value)
    }

    @Test
    fun `paged runtime clears image page accessibility state when switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("image-page-state-mode-switch.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Text before the image.</p><img src=\"cover.png\" alt=\"Cover art\"/></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val imagePage = (0 until engine.pageCount.value)
            .map { pageIndex -> engine.createPageView(pageIndex) }
            .first { page -> findView(page, ImageView::class.java) != null }
        val imageView = findView(imagePage, ImageView::class.java)

        assertNotNull(imageView)
        assertTrue(imageView?.contentDescription?.contains("Cover art") == true)

        engine.setMode(ReadingMode.SCROLL)

        assertEquals(null, imageView?.contentDescription)
    }

    @Test
    fun `paged runtime clears image page drawable state when switching to scroll mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val epub = tempDir.newFile("image-page-drawable-mode-switch.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to "<html><body><p>Text before the image.</p><img src=\"cover.png\" alt=\"Cover art\"/></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(context, flowEngineEnabled = false)

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val imagePage = (0 until engine.pageCount.value)
            .map { pageIndex -> engine.createPageView(pageIndex) }
            .first { page -> findView(page, ImageView::class.java) != null }
        val imageView = findView(imagePage, ImageView::class.java)
        imageView?.setImageDrawable(ColorDrawable(0xFFFF0000.toInt()))

        assertNotNull(imageView?.drawable)

        engine.setMode(ReadingMode.SCROLL)

        assertEquals(null, imageView?.drawable)
    }

    @Test
    fun `paged runtime updates active image page progress after page count changes`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val trailingText = (1..160).joinToString(separator = " ") { index -> "tail$index" }
        val epub = tempDir.newFile("image-page-progress-after-repagination.epub")
        writeEpub(
            epub,
            "OEBPS/ch1.xhtml" to
                "<html><body><p>G</p><img src=\"cover.png\" alt=\"Cover art\"/><p>$trailingText</p></body></html>",
        )
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = EpubReflowEngine(
            context = context,
            pageLineMeasurer = oneCharacterPerLineMeasurer(),
        )

        engine.openBook(Uri.fromFile(epub))
        engine.setMode(ReadingMode.PAGED)
        val activeImagePage = engine.createPageView(1)
        val activeImageView = findView(activeImagePage, ImageView::class.java)
        val originalDescription = activeImageView?.contentDescription?.toString()

        engine.setLineSpacing(20f)
        val freshImagePage = engine.createPageView(1)
        val freshImageView = findView(freshImagePage, ImageView::class.java)

        assertNotNull(activeImageView)
        assertNotNull(freshImageView)
        assertTrue(originalDescription != freshImageView?.contentDescription?.toString())
        assertEquals(freshImageView?.contentDescription, activeImageView?.contentDescription)
    }

    private fun findTaggedSlice(view: android.view.View): EpubPageSlice? {
        (view.tag as? EpubPageSlice)?.let { return it }
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findTaggedSlice(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun exactly(size: Int): Int =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)

    private fun <T : android.view.View> findView(view: android.view.View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findView(group.getChildAt(index), type)?.let { return it }
        }
        return null
    }

    private fun oneCharacterPerLineMeasurer() =
        EpubPageLineMeasurer.ComposeTextLayoutResult { text: String, _: Int, _: EpubPageTextStyle ->
            text.indices.map { index -> EpubTextLayoutLineRange(index, index + 1) }
        }

    private class FailNextDrawDrawable(
        private val delegate: Drawable?,
    ) : Drawable() {
        private var failNext = true

        override fun draw(canvas: Canvas) {
            if (failNext) {
                failNext = false
                error("boundary snapshot draw failure")
            }
            delegate?.bounds = bounds
            delegate?.draw(canvas)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun EpubReflowEngine.lazyBookForTest(): EpubLazyBook? {
        val field = EpubReflowEngine::class.java.getDeclaredField("lazyBook")
        field.isAccessible = true
        return field.get(this) as? EpubLazyBook
    }

    private fun EpubFlowView.privateField(name: String): Any? =
        EpubFlowView::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)

    private fun writeEpub(file: File, vararg spineEntries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            add(
                "META-INF/container.xml",
                """
                    <container>
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent(),
            )
            add(
                "OEBPS/content.opf",
                buildString {
                    appendLine("<package version=\"3.0\">")
                    appendLine("  <manifest>")
                    spineEntries.forEachIndexed { index, (path, _) ->
                        appendLine("    <item id=\"c$index\" href=\"${path.removePrefix("OEBPS/")}\" media-type=\"application/xhtml+xml\"/>")
                    }
                    appendLine("  </manifest>")
                    appendLine("  <spine>")
                    spineEntries.forEachIndexed { index, _ ->
                        appendLine("    <itemref idref=\"c$index\"/>")
                    }
                    appendLine("  </spine>")
                    appendLine("</package>")
                },
            )
            spineEntries.forEach { (path, content) -> add(path, content) }
        }
    }

    private fun writeImageEpub(file: File) {
        val imageBytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).let { bitmap ->
                try {
                    bitmap.eraseColor(0xFF1A8F5D.toInt())
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }

            fun addText(path: String, content: String) = add(path, content.toByteArray(Charsets.UTF_8))

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
                        <item id="c0" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="image" href="image.png" media-type="image/png"/>
                      </manifest>
                      <spine><itemref idref="c0"/></spine>
                    </package>
                """.trimIndent(),
            )
            addText(
                "OEBPS/ch1.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><p>Before image.</p><img src="image.png"/><p>After image.</p></body>
                    </html>
                """.trimIndent(),
            )
            add("OEBPS/image.png", imageBytes)
        }
    }
}
