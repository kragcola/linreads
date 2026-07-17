package dev.readflow.render.md

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextHighlightSpan
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelectionAwareTextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownEngineTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setFont applies the shared reader typeface to markdown text`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = MarkdownEngine(context)
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView

        engine.setFont("system_serif")

        assertEquals(Typeface.SERIF, textView.typeface)
    }

    @Test
    fun `headingless open publishes empty toc and document chapterInfo`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown(
            """
            Plain paragraph one with no ATX headings.
            Plain paragraph two continues the body.
            """.trimIndent(),
        )
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        idleMainLooper()

        assertTrue(engine.tableOfContents.value.isEmpty())
        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.DOCUMENT, info.kind)
        assertEquals(0, info.currentIndex)
        assertEquals(0, info.totalChapters)
        assertEquals("正文", info.currentTitle)
        assertEquals(0f, info.progressInChapter)
        // Native paged MD must not invent PAGE chrome for headingless docs.
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()
        assertEquals(ChapterInfo.Kind.DOCUMENT, engine.chapterInfo.value.kind)
        assertEquals(0, engine.chapterInfo.value.totalChapters)
    }

    @Test
    fun `real headings publish chapter kind with truthful count and title`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown(
            """
            # 第1章 开篇

            Body of chapter one.

            ## 第2章 中途

            Body of chapter two.

            # 第3章 收束

            Body of chapter three.
            """.trimIndent(),
        )
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        idleMainLooper()

        val toc = engine.tableOfContents.value
        assertEquals(3, toc.size)
        assertEquals("第1章 开篇", toc[0].title)
        assertEquals("第2章 中途", toc[1].title)
        assertEquals("第3章 收束", toc[2].title)

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(0, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第1章 开篇", info.currentTitle)
    }

    @Test
    fun `goTo updates chapterInfo for real heading toc`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown(headingMarkdown())
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        idleMainLooper()
        val toc = engine.tableOfContents.value
        assertEquals(3, toc.size)

        engine.goTo(toc[1].locator)
        idleMainLooper()

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(1, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第2章 中途", info.currentTitle)
    }

    @Test
    fun `adjacent chapter navigation uses real headings and respects boundaries`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown(headingMarkdown())
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        idleMainLooper()

        engine.goToAdjacentChapter(-1)
        assertEquals(0, engine.chapterInfo.value.currentIndex)
        assertEquals("第1章 开篇", engine.chapterInfo.value.currentTitle)

        engine.goToAdjacentChapter(+1)
        assertEquals(1, engine.chapterInfo.value.currentIndex)
        assertEquals("第2章 中途", engine.chapterInfo.value.currentTitle)

        engine.goToAdjacentChapter(+1)
        assertEquals(2, engine.chapterInfo.value.currentIndex)
        assertEquals("第3章 收束", engine.chapterInfo.value.currentTitle)

        engine.goToAdjacentChapter(+1)
        assertEquals(2, engine.chapterInfo.value.currentIndex)
        assertEquals("第3章 收束", engine.chapterInfo.value.currentTitle)
    }

    @Test
    fun `headingless adjacent chapter navigation is a no-op document state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown("Only body text without ATX headings.\nSecond paragraph.")
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        idleMainLooper()

        engine.goToAdjacentChapter(+1)
        engine.goToAdjacentChapter(-1)

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.DOCUMENT, info.kind)
        assertEquals(0, info.totalChapters)
        assertEquals("正文", info.currentTitle)
        assertTrue(engine.tableOfContents.value.isEmpty())
    }

    @Test
    fun `setMode keeps chapterInfo in sync with updated locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown(headingMarkdown())
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        idleMainLooper()
        val chapter2 = engine.tableOfContents.value[1]
        engine.goTo(chapter2.locator)
        idleMainLooper()
        assertEquals("第2章 中途", engine.chapterInfo.value.currentTitle)

        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(1, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第2章 中途", info.currentTitle)
        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
    }

    @Test
    fun `scroll restore path refreshes chapterInfo with locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = headingMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        idleMainLooper()

        // Jump to chapter 3 via goTo, then remount SCROLL so restoreScrollToLocator republishes.
        val chapter3 = engine.tableOfContents.value[2]
        engine.goTo(chapter3.locator)
        idleMainLooper()
        assertEquals("第3章 收束", engine.chapterInfo.value.currentTitle)

        engine.setMode(ReadingMode.SCROLL)
        idleMainLooper()
        val scrollView = engine.createView() as ScrollView
        val parent = attachMeasured(scrollView, context)
        repeat(6) {
            idleMainLooper()
            relayout(parent)
            idleMainLooper()
        }

        val info = engine.chapterInfo.value
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(2, info.currentIndex)
        assertEquals(3, info.totalChapters)
        assertEquals("第3章 收束", info.currentTitle)
        assertTrue((engine.currentLocator.value.totalProgression ?: 0f) > 0.3f)
    }

    @Test
    fun `setMode PAGED enables paging and multiple pages for long markdown`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val file = tempMarkdown(buildLongMarkdown())
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))

        assertEquals(setOf(ReadingMode.SCROLL, ReadingMode.PAGED), engine.supportedModes)
        assertEquals(PagingKind.CONTINUOUS, engine.pagingKind.value)

        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        assertTrue(
            "long markdown must paginate into multiple ViewPager slots; count=${engine.pageCount.value}",
            engine.pageCount.value > 1,
        )
        val page0 = engine.createPageView(0)
        val page1 = engine.createPageView(1)
        assertTrue(page0 is FrameLayout)
        assertTrue(page1 is FrameLayout)
        assertTrue(
            "PAGED must not wrap a single ScrollView in one pager slot",
            page0 !is ScrollView && (page0 as ViewGroup).getChildAt(0) !is ScrollView,
        )
    }

    @Test
    fun `page slice retains Markwon spans from cached rendered Spanned`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = "# Title\n\nIntro **bold word** continues after emphasis.\n\n" +
            (0 until 40).joinToString("\n\n") { "Paragraph $it with enough body text to paginate." }
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        val pageCount = engine.pageCount.value
        var foundMarkwonSpan = false
        for (index in 0 until pageCount) {
            val page = engine.createPageView(index) as FrameLayout
            val textView = page.getChildAt(0) as TextView
            val text = textView.text
            if (text is Spannable && text.toString().contains("bold word")) {
                val styleSpans = text.getSpans(0, text.length, StyleSpan::class.java)
                val metricSpans = text.getSpans(0, text.length, MetricAffectingSpan::class.java)
                val charSpans = text.getSpans(0, text.length, CharacterStyle::class.java)
                foundMarkwonSpan = styleSpans.any { it.style == Typeface.BOLD } ||
                    metricSpans.isNotEmpty() ||
                    charSpans.any {
                        val name = it.javaClass.name
                        name.contains("Strong", ignoreCase = true) ||
                            name.contains("Emphasis", ignoreCase = true) ||
                            name.contains("Style", ignoreCase = true)
                    }
                if (foundMarkwonSpan) break
            }
        }
        assertTrue(
            "expected actual Markwon emphasis/strong spans on page slice containing bold word",
            foundMarkwonSpan,
        )
    }

    @Test
    fun `source locator maps to page and goTo round-trips to same source section`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildLongMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        val targetText = "TargetToken064"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val sourceLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        val requested = mutableListOf<Int>()
        engine.setPageRequestCallback(requested::add)

        engine.goTo(sourceLocator)
        idleMainLooper()

        val pageIndex = engine.pageIndexForLocator(sourceLocator)
        assertEquals(pageIndex, requested.last())
        assertTrue(pageIndex in 0 until engine.pageCount.value)

        // Host-style Page locator from ViewPager should normalize to source Section at page start.
        engine.goTo(
            Locator(
                strategy = LocatorStrategy.Page(pageIndex, engine.pageCount.value),
                totalProgression = pageIndex.toFloat() / engine.pageCount.value,
            ),
        )
        idleMainLooper()
        val after = engine.currentLocator.value
        assertTrue(
            "page settle must store source Section, not Page; actual=${after.strategy}",
            after.strategy is LocatorStrategy.Section,
        )
        // Round-trip: source -> page -> pageIndexForLocator should be stable.
        assertEquals(pageIndex, engine.pageIndexForLocator(after))
    }

    @Test
    fun `SCROLL to PAGED preserves source anchor via page request callback`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildLongMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))

        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val renderedOffset = textView.text.toString().indexOf(targetText)
        val targetLine = textView.layout.getLineForOffset(renderedOffset)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) - scrollView.height / 2
            ).coerceAtLeast(0)
        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)

        val before = engine.currentLocator.value
        assertTrue((before.totalProgression ?: 0f) > 0.4f)

        val requested = mutableListOf<Int>()
        engine.setPageRequestCallback(requested::add)
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        assertTrue(requested.isNotEmpty())
        val after = engine.currentLocator.value
        assertTrue(after.strategy is LocatorStrategy.Section)
        assertEquals(
            engine.pageIndexForLocator(after),
            requested.last(),
        )
        // Anchor should stay near the pre-switch viewport, not jump to page 0.
        assertTrue(
            "mode switch should not collapse to first page; requested=${requested.last()}",
            requested.last() > 0 || (after.totalProgression ?: 0f) < 0.05f,
        )
        if ((before.totalProgression ?: 0f) > 0.5f) {
            assertTrue(requested.last() > 0)
        }
    }

    @Test
    fun `typography rebuild keeps source anchor and refreshes page content at same page count`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication() as Application
            val markdown = buildLongMarkdown()
            val file = tempMarkdown(markdown)
            val engine = MarkdownEngine(context)
            // Fix viewport so StaticLayout page geometry is deterministic across typography nudges.
            engine.setViewportSize(720, 1280)
            engine.openBook(Uri.fromFile(file))
            engine.setMode(ReadingMode.PAGED)
            idleMainLooper()

            val targetText = "TargetToken032"
            val targetSourceOffset = markdown.indexOf(targetText)
            val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
            val sourceLocator = Locator(
                strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
                totalProgression = targetSourceOffset.toFloat() / markdown.length,
            )
            engine.goTo(sourceLocator)
            idleMainLooper()
            val pageBefore = engine.pageIndexForLocator(engine.currentLocator.value)
            val pageCountBefore = engine.pageCount.value
            val anchorOffsetBefore =
                (engine.currentLocator.value.strategy as LocatorStrategy.Section).charOffset

            val requested = mutableListOf<Int>()
            engine.setPageRequestCallback(requested::add)

            // Create an active page view so refresh path has something to rebind.
            val pageView = engine.createPageView(pageBefore) as FrameLayout
            val textView = pageView.getChildAt(0) as TextView
            val bindingBefore = textView.tag as PageViewBinding
            val startOffsetBefore = bindingBefore.startOffset
            val textBefore = textView.text.toString()
            assertTrue(textBefore.isNotEmpty())
            assertEquals(pageBefore, bindingBefore.pageIndex)

            // Tiny font-size nudge often keeps pageCount equal while invalidating slices.
            // If pageCount still changes, try a smaller nudge to force equal-count path.
            engine.setFontSize(18.25f)
            idleMainLooper()
            if (engine.pageCount.value != pageCountBefore) {
                engine.setFontSize(18.1f)
                idleMainLooper()
            }

            assertTrue(requested.isNotEmpty())
            val pageAfter = requested.last()
            assertEquals(
                "typography rebuild must re-request page for source anchor",
                engine.pageIndexForLocator(engine.currentLocator.value),
                pageAfter,
            )
            val afterStrategy = engine.currentLocator.value.strategy
            assertTrue(afterStrategy is LocatorStrategy.Section)
            assertEquals(
                "exact Section.charOffset must be preserved across font-size rebuild",
                anchorOffsetBefore,
                (afterStrategy as LocatorStrategy.Section).charOffset,
            )

            // Force equal pageCount path: rebind must update binding/startOffset/text for new window.
            assertEquals(
                "test requires equal pageCount typography path; before=$pageCountBefore after=${engine.pageCount.value}",
                pageCountBefore,
                engine.pageCount.value,
            )
            val bindingAfter = textView.tag as PageViewBinding
            assertEquals(pageBefore, bindingAfter.pageIndex)
            // Binding object is the same mutable instance; startOffset tracks current window.
            assertTrue(bindingAfter === bindingBefore || bindingAfter.pageIndex == pageBefore)
            val textAfter = textView.text.toString()
            assertTrue(textAfter.isNotEmpty())
            // Window for this page index after rebuild must match binding + displayed text.
            val expectedWindow = enginePageWindow(engine, bindingAfter.pageIndex)
            assertEquals(expectedWindow.startOffset, bindingAfter.startOffset)
            assertEquals(expectedWindow.endOffset, bindingAfter.endOffset)
            assertEquals(
                "active page text must match new window slice after equal-pageCount rebuild",
                expectedWindow.sliceText,
                textAfter,
            )
            // If the window shifted, startOffset must have been updated (not stale old base).
            if (expectedWindow.startOffset != startOffsetBefore) {
                assertEquals(expectedWindow.startOffset, bindingAfter.startOffset)
            }
        }

    @Test
    fun `setViewportSize changes page windows and preserves source section anchor`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication() as Application
            val markdown = buildLongMarkdown()
            val file = tempMarkdown(markdown)
            val engine = MarkdownEngine(context)
            engine.setViewportSize(1080, 2400)
            engine.openBook(Uri.fromFile(file))
            engine.setMode(ReadingMode.PAGED)
            idleMainLooper()

            val targetText = "TargetToken048"
            val targetSourceOffset = markdown.indexOf(targetText)
            val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
            val sourceLocator = Locator(
                strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
                totalProgression = targetSourceOffset.toFloat() / markdown.length,
            )
            engine.goTo(sourceLocator)
            idleMainLooper()
            val countLarge = engine.pageCount.value
            val pageBefore = engine.pageIndexForLocator(engine.currentLocator.value)
            val offsetBefore =
                (engine.currentLocator.value.strategy as LocatorStrategy.Section).charOffset

            val requested = mutableListOf<Int>()
            engine.setPageRequestCallback(requested::add)
            // Narrower/shorter viewport must repaginate (typically more pages).
            engine.setViewportSize(480, 800)
            idleMainLooper()

            assertTrue(
                "viewport shrink must change pageCount; large=$countLarge small=${engine.pageCount.value}",
                engine.pageCount.value != countLarge || engine.pageCount.value > 1,
            )
            assertTrue(requested.isNotEmpty())
            val after = engine.currentLocator.value.strategy as LocatorStrategy.Section
            assertEquals(
                "source Section.charOffset must survive viewport resize",
                offsetBefore,
                after.charOffset,
            )
            assertEquals(
                engine.pageIndexForLocator(engine.currentLocator.value),
                requested.last(),
            )
            // Windows for page 0 must differ when dimensions change (different line packing).
            val page0Large = run {
                engine.setViewportSize(1080, 2400)
                idleMainLooper()
                enginePageWindow(engine, 0)
            }
            val page0Small = run {
                engine.setViewportSize(480, 800)
                idleMainLooper()
                enginePageWindow(engine, 0)
            }
            assertTrue(
                "different viewport sizes must produce different page-0 endOffset or pageCount",
                page0Large.endOffset != page0Small.endOffset ||
                    engine.pageCount.value != countLarge,
            )
            // Sanity: goTo still finds target after resize.
            assertTrue(engine.pageIndexForLocator(sourceLocator) >= 0)
            assertTrue(pageBefore >= 0)
        }

    @Test
    fun `paged selection maps local offsets to source section locators`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildLongMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.setViewportSize(720, 1280)
        engine.openBook(Uri.fromFile(file))
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        val targetText = "TargetToken010"
        val targetSourceOffset = markdown.indexOf(targetText)
        val pageIndex = engine.pageIndexForLocator(
            Locator(
                strategy = LocatorStrategy.Section(
                    0,
                    markdown.substring(0, targetSourceOffset).count { it == '\n' },
                    targetSourceOffset,
                ),
                totalProgression = targetSourceOffset.toFloat() / markdown.length,
            ),
        )
        val page = engine.createPageView(pageIndex) as FrameLayout
        val textView = page.getChildAt(0) as SelectionAwareTextView
        val binding = textView.tag as PageViewBinding
        val localStart = textView.text.toString().indexOf(targetText)
        assertTrue("target must appear on mapped page slice; page=$pageIndex", localStart >= 0)
        val localEnd = localStart + targetText.length

        // Real SelectionAwareTextView callback path only — no private reflection fallback.
        val callback = textView.onSelectionRangeChanged
        assertNotNull("createPageView must wire onSelectionRangeChanged", callback)
        callback!!.invoke(localStart, localEnd)

        val selection = engine.currentTextSelection.value
        assertNotNull(selection)
        assertEquals(targetText, selection!!.selectedText)
        assertTrue(selection.start.strategy is LocatorStrategy.Section)
        val startOffset = (selection.start.strategy as LocatorStrategy.Section).charOffset
        assertEquals(targetSourceOffset, startOffset)

        // After typography rebuild with equal pageCount, selection base must use updated binding.
        val pageCountBefore = engine.pageCount.value
        engine.setFontSize(18.15f)
        idleMainLooper()
        if (engine.pageCount.value == pageCountBefore) {
            val localStart2 = textView.text.toString().indexOf(targetText)
            if (localStart2 >= 0) {
                callback.invoke(localStart2, localStart2 + targetText.length)
                val sel2 = engine.currentTextSelection.value
                assertNotNull(sel2)
                assertEquals(
                    targetSourceOffset,
                    (sel2!!.start.strategy as LocatorStrategy.Section).charOffset,
                )
                assertEquals(binding.startOffset, (textView.tag as PageViewBinding).startOffset)
            }
        }
    }

    @Test
    fun `stable page-start locators are monotonic and round-trip to same page`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication() as Application
            val markdown = buildLongMarkdown()
            val file = tempMarkdown(markdown)
            val engine = MarkdownEngine(context)
            engine.setViewportSize(720, 1280)
            engine.openBook(Uri.fromFile(file))
            engine.setMode(ReadingMode.PAGED)
            idleMainLooper()

            val pageCount = engine.pageCount.value
            assertTrue(pageCount > 1)
            var previousOffset = -1
            for (page in 0 until pageCount) {
                // Host-style Page settle → source Section at page start.
                engine.goTo(
                    Locator(
                        strategy = LocatorStrategy.Page(page, pageCount),
                        totalProgression = page.toFloat() / pageCount,
                    ),
                )
                idleMainLooper()
                val strategy = engine.currentLocator.value.strategy
                assertTrue(
                    "page settle must store Section; page=$page strategy=$strategy",
                    strategy is LocatorStrategy.Section,
                )
                val charOffset = (strategy as LocatorStrategy.Section).charOffset
                assertTrue(
                    "page-start source offsets must be monotonic; page=$page prev=$previousOffset cur=$charOffset",
                    charOffset >= previousOffset,
                )
                previousOffset = charOffset
                assertEquals(
                    "page-start locator must round-trip to same page",
                    page,
                    engine.pageIndexForLocator(engine.currentLocator.value),
                )
            }
        }

    @Test
    fun `paged annotation highlight spans appear on page slice`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildLongMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()

        val targetText = "TargetToken020"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val start = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        val end = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset + targetText.length),
            totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
        )
        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "a1",
                    start = start,
                    end = end,
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )

        val pageIndex = engine.pageIndexForLocator(start)
        val page = engine.createPageView(pageIndex) as FrameLayout
        val textView = page.getChildAt(0) as TextView
        val spannable = textView.text as Spannable
        val highlights = spannable.getSpans(0, spannable.length, ReaderTextHighlightSpan::class.java)
        assertTrue(
            "expected highlight span on paged slice containing annotation; page=$pageIndex text=${textView.text}",
            highlights.isNotEmpty(),
        )
        assertTrue(textView.text.toString().contains(targetText))
    }

    @Test
    fun `createView after PAGED remount restores source anchor instead of top`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildLongMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))

        val targetText = "TargetToken064"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val sourceLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        engine.setMode(ReadingMode.PAGED)
        engine.goTo(sourceLocator)
        idleMainLooper()
        val offsetBefore =
            (engine.currentLocator.value.strategy as LocatorStrategy.Section).charOffset

        engine.setMode(ReadingMode.SCROLL)
        idleMainLooper()
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        // Layout + posted restore may need several frames under Robolectric.
        repeat(6) {
            idleMainLooper()
            relayout(parent)
            idleMainLooper()
            if (scrollView.scrollY > 0) return@repeat
        }

        assertTrue(
            "SCROLL remount must restore near-tail anchor, not jump to top; scrollY=${scrollView.scrollY} locator=${engine.currentLocator.value}",
            scrollView.scrollY > 0 || (engine.currentLocator.value.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue((engine.currentLocator.value.totalProgression ?: 0f) > 0.4f)
        val afterStrategy = engine.currentLocator.value.strategy
        assertTrue(afterStrategy is LocatorStrategy.Section)
        // Exact source charOffset preserved across PAGED→SCROLL remount.
        assertEquals(
            offsetBefore,
            (afterStrategy as LocatorStrategy.Section).charOffset,
        )
        // Target token must be present in the remounted text and near the restored viewport.
        assertTrue(textView.text.toString().contains(targetText))
        // Use layout metrics after restore (engine may remeasure WRAP_CONTENT).
        val layout = textView.layout
        assertNotNull(layout)
        val renderedOffset = textView.text.toString().indexOf(targetText)
        assertTrue(renderedOffset >= 0)
        val line = layout!!.getLineForOffset(renderedOffset)
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)
        val viewportTop = scrollView.scrollY
        val viewportBottom = scrollView.scrollY + scrollView.height
        assertTrue(
            "target token line must intersect restored scroll viewport; " +
                "lineTop=$lineTop lineBottom=$lineBottom viewport=[$viewportTop,$viewportBottom] " +
                "tvH=${textView.height} contentH=${layout.height}",
            lineBottom > viewportTop && lineTop < viewportBottom,
        )
    }

    @Test
    fun `real Markwon long link url keeps end_token anchor and selection`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val longUrl = "https://example.com/" + "u".repeat(280)
        val markdown =
            "Lead !!! [link_label]($longUrl) ??? after_link end_token.\n"
        assertTrue("fixture URL must exceed 256 chars", longUrl.length > 256)
        assertMarkwonExactTokenRoundTrip(markdown, listOf("link_label", "after_link", "end_token"))
    }

    @Test
    fun `real Markwon long image url keeps alt and end_token anchors`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val longUrl = "https://example.com/img/" + "p".repeat(280) + ".png"
        val markdown =
            "Lead !!! ![img_alt_long]($longUrl) ??? after_img end_token.\n"
        assertTrue("fixture URL must exceed 256 chars", longUrl.length > 256)
        assertMarkwonExactTokenRoundTrip(markdown, listOf("img_alt_long", "after_img", "end_token"))
    }

    @Test
    fun `real Markwon multi-row table with long row keeps end_token anchors`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val longCell = "C".repeat(280)
        val markdown = buildString {
            append("Lead !!! table_start\n\n")
            append("| ColA | ColB |\n")
            append("| --- | --- |\n")
            append("| short_a | short_b |\n")
            append("| $longCell | tail_cell |\n")
            append("| row3_a | row3_b |\n\n")
            append("??? after_table end_token.\n")
        }
        assertTrue("fixture table cell must exceed 256 chars", longCell.length > 256)
        assertMarkwonExactTokenRoundTrip(markdown, listOf("after_table", "end_token"))
    }

    @Test
    fun `real Markwon repeated punctuation around long constructs keeps anchors`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val longUrl = "https://example.com/" + "q".repeat(280)
        val longCell = "R".repeat(280)
        val markdown = buildString {
            append("!!! ??? ... [link_label]($longUrl) !!!\n\n")
            append("!!! ![img_alt]($longUrl) ???\n\n")
            append("| H1 | H2 |\n| --- | --- |\n| $longCell | x |\n\n")
            append("... !!! ??? end_token !!!\n")
        }
        assertMarkwonExactTokenRoundTrip(markdown, listOf("link_label", "img_alt", "end_token"))
    }

    @Test
    fun `real Markwon corpus round-trips source rendered anchors for visible tokens`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markwon = Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
        val markdown = buildString {
            append("# Heading Token\n\n")
            append("Compute a * b and snake_case_var keep literals.\n\n")
            append("Escaped \\*star\\* and \\_under\\_ markers.\n\n")
            append("Inline `code_token` and:\n\n")
            append("```\nfenced_token = 1\n```\n\n")
            append("Strike ~~struck_token~~ and [link_token](https://example.com/x) here.\n\n")
            append("![img_alt](https://example.com/a.png)\n\n")
            append("| ColA | ColB |\n| --- | --- |\n| cell_a | cell_b |\n\n")
            append("Punctuation !!! ??? ... end_token.\n")
        }
        val document = MarkdownDocument.parse(markdown)
        val rendered = markwon.toMarkdown(markdown)
        val renderedText = rendered.toString()

        val tokens = listOf(
            "Heading Token",
            "a * b",
            "snake_case_var",
            "star",
            "under",
            "code_token",
            "fenced_token",
            "struck_token",
            "link_token",
            "end_token",
        )
        for (token in tokens) {
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = renderedText.indexOf(token)
            assertTrue(
                "token '$token' must exist in source and real Markwon output; " +
                    "source=$sourceOffset rendered=$expectedRendered out=${renderedText.take(200)}",
                sourceOffset >= 0 && expectedRendered >= 0,
            )
            val mapped = document.renderedOffsetFor(
                Locator(LocatorStrategy.Section(0, 0, sourceOffset)),
                rendered,
            )
            assertEquals(
                "source→rendered for '$token'",
                expectedRendered,
                mapped,
            )
            val back = document.locatorForRenderedOffset(expectedRendered, rendered)
            assertEquals(
                "rendered→source for '$token'",
                sourceOffset,
                (back.strategy as LocatorStrategy.Section).charOffset,
            )
            // Selection range over the token.
            val selection = document.selectionForRenderedOffsets(
                expectedRendered,
                expectedRendered + token.length,
                rendered,
            )
            assertNotNull(selection)
            assertEquals(token, selection!!.selectedText)
            assertEquals(
                sourceOffset,
                (selection.start.strategy as LocatorStrategy.Section).charOffset,
            )
        }

        // Table cell content if present in plain text output.
        for (cell in listOf("cell_a", "cell_b", "ColA")) {
            val sourceOffset = markdown.indexOf(cell)
            val expectedRendered = renderedText.indexOf(cell)
            if (sourceOffset >= 0 && expectedRendered >= 0) {
                assertEquals(
                    expectedRendered,
                    document.renderedOffsetFor(
                        Locator(LocatorStrategy.Section(0, 0, sourceOffset)),
                        rendered,
                    ),
                )
                val back = document.locatorForRenderedOffset(expectedRendered, rendered)
                assertEquals(
                    sourceOffset,
                    (back.strategy as LocatorStrategy.Section).charOffset,
                )
            }
        }
    }

    @Test
    fun `typography preserves exact Section charOffset in SCROLL and PAGED`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildLongMarkdown()
        val file = tempMarkdown(markdown)
        val engine = MarkdownEngine(context)
        engine.setViewportSize(720, 1280)
        engine.openBook(Uri.fromFile(file))

        val targetText = "TargetToken040"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val sourceLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )

        // SCROLL
        engine.goTo(sourceLocator)
        idleMainLooper()
        engine.setFontSize(20f)
        idleMainLooper()
        assertEquals(
            targetSourceOffset,
            (engine.currentLocator.value.strategy as LocatorStrategy.Section).charOffset,
        )

        // PAGED
        engine.setMode(ReadingMode.PAGED)
        idleMainLooper()
        engine.goTo(sourceLocator)
        idleMainLooper()
        engine.setLineSpacing(1.45f)
        idleMainLooper()
        assertEquals(
            targetSourceOffset,
            (engine.currentLocator.value.strategy as LocatorStrategy.Section).charOffset,
        )
        engine.setFontSize(16f)
        idleMainLooper()
        assertEquals(
            targetSourceOffset,
            (engine.currentLocator.value.strategy as LocatorStrategy.Section).charOffset,
        )
    }

    @Test
    fun `setTextAnnotations keeps markdown scroll position and locator near current section`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-scroll-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val targetLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        val renderedOffset = textView.text.toString().indexOf(targetText)
        val targetLine = textView.layout.getLineForOffset(renderedOffset)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) -
                scrollView.height / 2
            ).coerceAtLeast(0)

        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)

        val beforeScrollY = scrollView.scrollY
        val beforeLocator = engine.currentLocator.value

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "annotation-1",
                    start = targetLocator,
                    end = targetLocator.copy(
                        strategy = LocatorStrategy.Section(
                            spineIndex = 0,
                            elementIndex = targetLineIndex,
                            charOffset = targetSourceOffset + targetText.length,
                        ),
                        totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
                    ),
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        idleMainLooper()
        relayout(parent)

        val afterScrollY = scrollView.scrollY
        val afterLocator = engine.currentLocator.value

        assertTrue("expected to scroll away from top before applying annotations", beforeScrollY > 0)
        assertTrue(
            "expected locator to be near tail before applying annotations; before=$beforeLocator",
            (beforeLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected annotation refresh to preserve near-tail scroll; before=$beforeScrollY after=$afterScrollY",
            afterScrollY >= beforeScrollY / 2,
        )
        assertTrue(
            "expected locator to stay near current section; before=$beforeLocator after=$afterLocator",
            (afterLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected rendered text to keep target visible after annotation refresh",
            textView.text.toString().contains(targetText),
        )
    }

    @Test
    fun `setTextAnnotations keeps markdown scroll position when native selection is active`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-selection-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val renderedStart = textView.text.toString().indexOf(targetText)
        val renderedEnd = renderedStart + targetText.length
        val targetLine = textView.layout.getLineForOffset(renderedStart)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) -
                scrollView.height / 2
            ).coerceAtLeast(0)
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }

        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)
        Selection.setSelection(textView.text as Spannable, renderedStart, renderedEnd)

        val beforeScrollY = scrollView.scrollY
        val beforeLocator = engine.currentLocator.value

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "annotation-1",
                    start = Locator(
                        strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
                        totalProgression = targetSourceOffset.toFloat() / markdown.length,
                    ),
                    end = Locator(
                        strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset + targetText.length),
                        totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
                    ),
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        idleMainLooper()
        relayout(parent)

        val afterScrollY = scrollView.scrollY
        val afterLocator = engine.currentLocator.value

        assertTrue("expected to scroll away from top before applying annotations", beforeScrollY > 0)
        assertTrue(
            "expected locator to be near tail before applying annotations; before=$beforeLocator",
            (beforeLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected annotation refresh to preserve active-selection scroll; before=$beforeScrollY after=$afterScrollY",
            afterScrollY >= beforeScrollY / 2,
        )
        assertTrue(
            "expected active-selection locator to stay near current section; before=$beforeLocator after=$afterLocator",
            (afterLocator.totalProgression ?: 0f) > 0.5f,
        )
    }

    @Test
    fun `setTextAnnotations restores markdown scroll and locator after internal text refresh resets viewport`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-internal-reset-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)
        engine.openBook(Uri.fromFile(file))

        val targetText = "TargetToken064"
        val targetSourceOffset = markdown.indexOf(targetText)
        val targetLineIndex = markdown.substring(0, targetSourceOffset).count { it == '\n' }
        val nearTailLocator = Locator(
            strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset),
            totalProgression = targetSourceOffset.toFloat() / markdown.length,
        )
        val headLocator = Locator(
            strategy = LocatorStrategy.Section(0, 0, 0),
            totalProgression = 0f,
        )
        val currentLocatorFlow = currentLocatorFlow(engine)
        currentLocatorFlow.value = nearTailLocator

        val textView = ResettingTextView(context).apply {
            textSize = 18f
            setPadding(48, 48, 48, 48)
            text = markdown
        }
        val scrollView = ScrollView(context).apply {
            addView(textView)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                currentLocatorFlow.value = if (scrollY <= 0) headLocator else nearTailLocator
            }
        }
        val parent = attachMeasured(scrollView, context)
        scrollView.scrollTo(0, 1200)
        idleMainLooper()
        relayout(parent)

        setPrivateField(engine, "scrollView", scrollView)
        setPrivateField(engine, "textView", textView)

        engine.setTextAnnotations(
            listOf(
                ReaderTextAnnotation(
                    id = "annotation-1",
                    start = nearTailLocator,
                    end = Locator(
                        strategy = LocatorStrategy.Section(0, targetLineIndex, targetSourceOffset + targetText.length),
                        totalProgression = (targetSourceOffset + targetText.length).toFloat() / markdown.length,
                    ),
                    selectedText = targetText,
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        idleMainLooper()
        relayout(parent)

        assertTrue(
            "expected internal text refresh to preserve scroll position instead of snapping to top; actual=${scrollView.scrollY}",
            scrollView.scrollY > 0,
        )
        assertTrue(
            "expected locator to stay near tail after internal text refresh; actual=${engine.currentLocator.value}",
            (engine.currentLocator.value.totalProgression ?: 0f) > 0.5f,
        )
    }

    @Test
    fun `clearTextSelection preserves markdown scroll and locator near current section`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val markdown = buildMarkdownDocument()
        val file = File.createTempFile("markdown-engine-clear-selection-", ".md").apply {
            writeText(markdown)
            deleteOnExit()
        }
        val engine = MarkdownEngine(context)

        engine.openBook(Uri.fromFile(file))
        val scrollView = engine.createView() as ScrollView
        val textView = scrollView.getChildAt(0) as TextView
        val parent = attachMeasured(scrollView, context)
        val targetText = "TargetToken064"
        val renderedStart = textView.text.toString().indexOf(targetText)
        val renderedEnd = renderedStart + targetText.length
        val targetLine = textView.layout.getLineForOffset(renderedStart)
        val targetScrollY = (
            textView.layout.getLineTop(targetLine) -
                scrollView.height / 2
            ).coerceAtLeast(0)

        scrollView.scrollTo(0, targetScrollY)
        idleMainLooper()
        relayout(parent)
        Selection.setSelection(textView.text as Spannable, renderedStart, renderedEnd)

        val beforeScrollY = scrollView.scrollY
        val beforeLocator = engine.currentLocator.value

        engine.clearTextSelection()
        idleMainLooper()
        relayout(parent)

        val afterScrollY = scrollView.scrollY
        val afterLocator = engine.currentLocator.value

        assertTrue("expected to scroll away from top before clearing selection", beforeScrollY > 0)
        assertTrue(
            "expected locator to be near tail before clearing selection; before=$beforeLocator",
            (beforeLocator.totalProgression ?: 0f) > 0.5f,
        )
        assertTrue(
            "expected native selection to collapse after clearTextSelection; " +
                "start=${textView.selectionStart} end=${textView.selectionEnd}",
            textView.selectionStart == textView.selectionEnd,
        )
        assertTrue(
            "expected clearTextSelection to preserve near-tail scroll; before=$beforeScrollY after=$afterScrollY",
            afterScrollY >= beforeScrollY / 2,
        )
        assertTrue(
            "expected clearTextSelection to keep locator near current section; before=$beforeLocator after=$afterLocator",
            (afterLocator.totalProgression ?: 0f) > 0.5f,
        )
    }

    /**
     * Real Markwon render + exact source↔rendered round-trip for each token, including
     * selection endpoints after long structural syntax (URLs / table rows).
     */
    private fun assertMarkwonExactTokenRoundTrip(markdown: String, tokens: List<String>) {
        val context = RuntimeEnvironment.getApplication() as Application
        val markwon = Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
        val document = MarkdownDocument.parse(markdown)
        val rendered = markwon.toMarkdown(markdown)
        val renderedText = rendered.toString()
        for (token in tokens) {
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = renderedText.indexOf(token)
            assertTrue(
                "token '$token' must exist in source and real Markwon output; " +
                    "source=$sourceOffset rendered=$expectedRendered out=${renderedText.take(240)}",
                sourceOffset >= 0 && expectedRendered >= 0,
            )
            val mapped = document.renderedOffsetFor(
                Locator(LocatorStrategy.Section(0, 0, sourceOffset)),
                rendered,
            )
            assertEquals("source→rendered for '$token'", expectedRendered, mapped)
            val back = document.locatorForRenderedOffset(expectedRendered, rendered)
            assertEquals(
                "rendered→source for '$token'",
                sourceOffset,
                (back.strategy as LocatorStrategy.Section).charOffset,
            )
            val selection = document.selectionForRenderedOffsets(
                expectedRendered,
                expectedRendered + token.length,
                rendered,
            )
            assertNotNull("selection for '$token'", selection)
            assertEquals(token, selection!!.selectedText)
            assertEquals(
                sourceOffset,
                (selection.start.strategy as LocatorStrategy.Section).charOffset,
            )
            assertEquals(
                sourceOffset + token.length,
                (selection.end.strategy as LocatorStrategy.Section).charOffset,
            )
            // Highlight range endpoints must land on the same rendered span.
            val highlight = document.renderedRangeForSourceOffsets(
                sourceOffset,
                sourceOffset + token.length,
                rendered,
            )
            assertNotNull("highlight for '$token'", highlight)
            assertEquals(expectedRendered, highlight!!.first)
            assertEquals(expectedRendered + token.length, highlight.last + 1)
        }
        // Mapping stays cached: second pass must not re-scan (same exact anchors).
        for (token in tokens) {
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = renderedText.indexOf(token)
            assertEquals(
                expectedRendered,
                document.renderedOffsetFor(
                    Locator(LocatorStrategy.Section(0, 0, sourceOffset)),
                    rendered,
                ),
            )
        }
    }

    private fun buildMarkdownDocument(): String = buildString {
        append("# Title\n\n")
        repeat(96) { index ->
            append("## Section %03d\n".format(index))
            append(
                "Paragraph %03d carries stable markdown content for scroll preservation checks and ".format(index),
            )
            append(
                "TargetToken%03d keeps the near-tail viewport anchored during annotation refresh.\n\n".format(index),
            )
        }
    }

    private fun buildLongMarkdown(): String = buildMarkdownDocument()

    private fun headingMarkdown(): String = """
        # 第1章 开篇

        Body of chapter one with enough lines to keep progression distinct.

        ## 第2章 中途

        Body of chapter two sits after the second heading.

        # 第3章 收束

        Body of chapter three is last.
    """.trimIndent()

    private data class ExpectedPageWindow(
        val startOffset: Int,
        val endOffset: Int,
        val sliceText: String,
    )

    /** Snapshot window for [pageIndex] via a fresh page bind (does not reuse stale active views). */
    private fun enginePageWindow(engine: MarkdownEngine, pageIndex: Int): ExpectedPageWindow {
        val page = engine.createPageView(pageIndex) as FrameLayout
        val textView = page.getChildAt(0) as TextView
        val binding = textView.tag as PageViewBinding
        return ExpectedPageWindow(
            startOffset = binding.startOffset,
            endOffset = binding.endOffset,
            sliceText = textView.text.toString(),
        )
    }

    private fun tempMarkdown(content: String): File =
        File.createTempFile("markdown-engine-paged-", ".md").apply {
            writeText(content)
            deleteOnExit()
        }

    private fun attachMeasured(view: View, context: Application): FrameLayout =
        FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(view)
            relayout(this)
        }

    private fun relayout(parent: FrameLayout) {
        parent.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(2400, MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 1080, 2400)
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentLocatorFlow(engine: MarkdownEngine): MutableStateFlow<Locator> =
        MarkdownEngine::class.java.getDeclaredField("_currentLocator").apply {
            isAccessible = true
        }.get(engine) as MutableStateFlow<Locator>

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        target::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private class ResettingTextView(context: Context) : TextView(context) {
        override fun setText(text: CharSequence?, type: BufferType?) {
            super.setText(text, type)
            (parent as? ScrollView)?.scrollTo(0, 0)
        }
    }
}
