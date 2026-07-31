package dev.readflow.render.animate

import android.app.Application
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.DirectionalPagedReaderEngine
import dev.readflow.render.api.PageReadingDirection
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelfPagingReaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ViewPagerTransitionHostTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `paged host refreshes adapter when engine page count changes`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(context, initialPageCount = 1)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(engine)
        val adapter = pager.adapter
        assertNotNull(adapter)
        var refreshCount = 0
        adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                refreshCount += 1
            }
        })

        engine.pageCountState.value = 3
        runCurrent()

        assertEquals(1, refreshCount)
        assertEquals(3, adapter?.itemCount)
        host.unbind()
    }

    @Test
    fun `paged host ignores initial page count replay after adapter binding`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(context, initialPageCount = 3)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(engine)
        val adapter = checkNotNull(pager.adapter)
        var refreshCount = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                refreshCount += 1
            }
        })

        runCurrent()

        assertEquals(
            "StateFlow's initial replay must not recreate every retained page",
            0,
            refreshCount,
        )
        assertEquals(3, adapter.itemCount)
        host.unbind()
    }

    @Test
    fun `paged host refreshes adapter after unbind and rebind`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(FakePagedEngine(context, initialPageCount = 1))
        host.unbind()

        val reboundEngine = FakePagedEngine(context, initialPageCount = 1)
        host.bind(reboundEngine)
        val adapter = pager.adapter
        assertNotNull(adapter)
        var refreshCount = 0
        adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                refreshCount += 1
            }
        })

        reboundEngine.pageCountState.value = 2
        runCurrent()

        assertEquals(1, refreshCount)
        assertEquals(2, adapter?.itemCount)
        host.unbind()
    }

    @Test
    fun `paged host clears old page request callback when rebound`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val firstEngine = FakePagedEngine(context, initialPageCount = 3)
        val secondEngine = FakePagedEngine(context, initialPageCount = 3)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(firstEngine)
        host.bind(secondEngine)

        firstEngine.requestPage(2)
        runCurrent()

        assertEquals(0, pager.currentItem)
        host.unbind()
    }

    @Test
    fun `paged host ignores stale selection while rebinding a fresh engine`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val firstEngine = FakePagedEngine(context, initialPageCount = 4)
        val secondEngine = FakePagedEngine(context, initialPageCount = 4)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(firstEngine)
        pager.setCurrentItem(1, false)
        host.bind(secondEngine)

        val callback = ViewPagerTransitionHost::class.java
            .getDeclaredField("pageCallback")
            .apply { isAccessible = true }
            .get(host) as ViewPager2.OnPageChangeCallback
        callback.onPageSelected(1)
        runCurrent()

        pager.measure(
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 640, 960)
        runCurrent()

        assertEquals("a rebind must return to the new engine's page zero", 0, pager.currentItem)
        assertEquals(
            LocatorStrategy.Page(0, 4),
            secondEngine.currentLocator.value.strategy,
        )
        host.unbind()
    }

    @Test
    fun `paged host keeps initial guard when stale adapter reports settling during rebind`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val firstEngine = FakePagedEngine(context, initialPageCount = 4, initialPageIndex = 1)
        val secondEngine = FakePagedEngine(context, initialPageCount = 4, initialPageIndex = 3)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(firstEngine)
        host.bind(secondEngine)

        val callback = ViewPagerTransitionHost::class.java
            .getDeclaredField("pageCallback")
            .apply { isAccessible = true }
            .get(host) as ViewPager2.OnPageChangeCallback
        // ViewPager2 may deliver an already queued callback from the detached adapter after the
        // new adapter has been installed. It must not release the new binding's initial guard.
        callback.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_SETTLING)
        callback.onPageSelected(1)
        callback.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_IDLE)

        pager.measure(
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 640, 960)
        runCurrent()

        assertEquals(3, pager.currentItem)
        assertEquals(LocatorStrategy.Page(3, 4), secondEngine.currentLocator.value.strategy)
        host.unbind()
    }

    @Test
    fun `initial page guard settles the expected idle page and reports onPageSettled exactly once`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val context = RuntimeEnvironment.getApplication() as Application
            val engine = FakePagedEngine(context, initialPageCount = 3, initialPageIndex = 1)
            val host = ViewPagerTransitionHost(context, TransitionType.NONE)
            val pager = host.hostView() as ViewPager2
            val settledPages = mutableListOf<Int>()
            host.setOnPageSettled(settledPages::add)

            host.bind(engine)
            val callback = ViewPagerTransitionHost::class.java
                .getDeclaredField("pageCallback")
                .apply { isAccessible = true }
                .get(host) as ViewPager2.OnPageChangeCallback

            // Simulate the queued ViewPager2 callbacks that arrive while the initial-page
            // guard is still active. The idle event must release the guard AND report the
            // expected page exactly once — never zero and never duplicated by the release path.
            callback.onPageSelected(1)
            callback.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_IDLE)

            assertEquals(1, pager.currentItem)
            assertEquals(
                "initial guard must settle and report the expected idle page exactly once",
                listOf(1),
                settledPages,
            )

            // A later genuine idle event still reports through the normal path.
            callback.onPageSelected(1)
            callback.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_IDLE)
            assertEquals(
                "a later settle must keep reporting through the normal path",
                listOf(1, 1),
                settledPages,
            )
            host.unbind()
        }

    @Test
    fun `self paging engine is mounted directly without ViewPager adapter`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakeSelfPagingEngine(context)
        val host = ViewPagerTransitionHost(context, TransitionType.CURL)

        host.bind(engine)

        val hostView = host.hostView()
        assertFalse(
            "self-paging EPUB should not be routed through ViewPager2 adapter/layout callbacks",
            hostView is ViewPager2,
        )
        val container = hostView as ViewGroup
        assertEquals(1, container.childCount)
        assertEquals(engine.view, container.getChildAt(0))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, engine.view.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, engine.view.layoutParams.height)

        host.next()
        host.previous()

        assertEquals(listOf(1, -1), engine.adjacentDeltas)
        host.unbind()
    }

    @Test
    fun `paged host reports actual ViewPager viewport size to engine after layout`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(context, initialPageCount = 2)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2

        host.bind(engine)
        // Layout the pager to a known non-displayMetrics size.
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 640, 960)
        runCurrent()

        assertEquals(
            "host must report laid-out width, not ignore layout",
            640,
            engine.lastViewportWidth,
        )
        assertEquals(960, engine.lastViewportHeight)

        // Resize (rotation / container change).
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 400, 700)
        runCurrent()

        assertEquals(400, engine.lastViewportWidth)
        assertEquals(700, engine.lastViewportHeight)
        assertTrue(
            "viewport reports must include both sizes",
            engine.viewportReports.any { it.first == 640 && it.second == 960 } &&
                engine.viewportReports.any { it.first == 400 && it.second == 700 },
        )
        host.unbind()
        // After unbind, further layout must not call into unbound engine.
        val reportsAfterUnbind = engine.viewportReports.size
        pager.layout(0, 0, 300, 500)
        assertEquals(reportsAfterUnbind, engine.viewportReports.size)
    }

    @Test
    fun `paged host does not recreate holders for equal-count viewport layout`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(context, initialPageCount = 2)
        val host = ViewPagerTransitionHost(context, TransitionType.NONE)
        val pager = host.hostView() as ViewPager2
        host.bind(engine)
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 720, 1200)
        runCurrent()
        val adapter = checkNotNull(pager.adapter)
        var refreshCount = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                refreshCount += 1
            }
        })

        pager.measure(
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 700, 1100)
        runCurrent()

        assertTrue(engine.viewportReports.any { it == 700 to 1100 })
        assertEquals(
            "the engine refreshes active equal-count pages in place",
            0,
            refreshCount,
        )
        host.unbind()
    }

    @Test
    fun `paged host retains exactly one neighbour for responsive turns`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val host = ViewPagerTransitionHost(context, TransitionType.SLIDE)
        val pager = host.hostView() as ViewPager2

        host.bind(FakePagedEngine(context, initialPageCount = 5))

        assertEquals(
            "current plus one bounded neighbour on each side avoids blank turns without an unbounded bitmap cache",
            1,
            pager.offscreenPageLimit,
        )
        host.unbind()
    }

    @Test
    fun `paged host honors an engine-specific bounded retention preference`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val host = ViewPagerTransitionHost(context, TransitionType.SLIDE)
        val pager = host.hostView() as ViewPager2

        host.bind(
            FakePagedEngine(
                context = context,
                initialPageCount = 8,
                preferredOffscreenPageLimit = 2,
            ),
        )

        assertEquals(2, pager.offscreenPageLimit)
        host.unbind()
    }

    @Test
    fun `curl host makes a restored nonzero page visible on its first layout`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(
            context = context,
            initialPageCount = 12,
            initialPageIndex = 4,
        )
        val host = ViewPagerTransitionHost(context, TransitionType.CURL)
        val pager = host.hostView() as ViewPager2

        host.bind(engine)
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 720, 1200)
        runCurrent()

        val recycler = pager.getChildAt(0) as RecyclerView
        val restoredPage = checkNotNull(recycler.findViewHolderForAdapterPosition(4)).itemView
        assertEquals(4, pager.currentItem)
        assertEquals(ViewPager2.SCROLL_STATE_IDLE, pager.scrollState)
        assertEquals(1f, restoredPage.alpha, 0.001f)
        assertEquals(0f, restoredPage.rotationY, 0.001f)
        host.unbind()
    }

    @Test
    fun `paged adapter clears a recycled holder transform before binding current content`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(
            context = context,
            initialPageCount = 12,
            initialPageIndex = 4,
        )
        val host = ViewPagerTransitionHost(context, TransitionType.CURL)
        val pager = host.hostView() as ViewPager2

        host.bind(engine)
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 720, 1200)
        runCurrent()

        val recycler = pager.getChildAt(0) as RecyclerView
        val holder = checkNotNull(recycler.findViewHolderForAdapterPosition(4))
        holder.itemView.alpha = 0f
        holder.itemView.rotationY = 45f

        @Suppress("UNCHECKED_CAST")
        (checkNotNull(pager.adapter) as RecyclerView.Adapter<RecyclerView.ViewHolder>)
            .onBindViewHolder(holder, 4)

        assertEquals(1f, holder.itemView.alpha, 0.001f)
        assertEquals(0f, holder.itemView.rotationY, 0.001f)
        host.unbind()
    }

    @Test
    fun `curl host repairs a stale current-page transform when pager is idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Application
        val engine = FakePagedEngine(
            context = context,
            initialPageCount = 12,
            initialPageIndex = 4,
        )
        val host = ViewPagerTransitionHost(context, TransitionType.CURL)
        val pager = host.hostView() as ViewPager2

        host.bind(engine)
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 720, 1200)
        runCurrent()
        val recycler = pager.getChildAt(0) as RecyclerView
        val currentPage = checkNotNull(recycler.findViewHolderForAdapterPosition(4)).itemView
        currentPage.alpha = 0f
        currentPage.rotationY = 45f

        val callback = ViewPagerTransitionHost::class.java
            .getDeclaredField("pageCallback")
            .apply { isAccessible = true }
            .get(host) as ViewPager2.OnPageChangeCallback
        callback.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_IDLE)

        assertEquals(1f, currentPage.alpha, 0.001f)
        assertEquals(0f, currentPage.rotationY, 0.001f)
        host.unbind()
    }

    @Test
    fun `page reading direction maps to Android layout direction`() {
        assertEquals(
            View.LAYOUT_DIRECTION_LTR,
            pageLayoutDirection(PageReadingDirection.LEFT_TO_RIGHT),
        )
        assertEquals(
            View.LAYOUT_DIRECTION_RTL,
            pageLayoutDirection(PageReadingDirection.RIGHT_TO_LEFT),
        )
    }

    private class FakePagedEngine(
        private val context: Context,
        initialPageCount: Int,
        initialPageIndex: Int = 0,
        override val preferredOffscreenPageLimit: Int = 1,
        override val pageReadingDirection: StateFlow<PageReadingDirection> =
            MutableStateFlow(PageReadingDirection.LEFT_TO_RIGHT),
    ) : PagedReaderEngine, DirectionalPagedReaderEngine {
        private val locatorState = MutableStateFlow(
            Locator(LocatorStrategy.Page(index = initialPageIndex, total = initialPageCount)),
        )

        val pageCountState = MutableStateFlow(initialPageCount)
        val viewportReports = mutableListOf<Pair<Int, Int>>()
        val lastViewportWidth: Int get() = viewportReports.lastOrNull()?.first ?: 0
        val lastViewportHeight: Int get() = viewportReports.lastOrNull()?.second ?: 0

        override val id: String = "fake-paged"
        override val format: BookFormat = BookFormat.EPUB
        override val priority: Int = 0
        override val pagingKind: StateFlow<PagingKind> = MutableStateFlow(PagingKind.PAGED)
        override val supportsSearch: Boolean = false
        override val currentLocator: StateFlow<Locator> = locatorState
        override val pageCount: StateFlow<Int> = pageCountState

        override suspend fun supports(uri: Uri): Boolean = true

        override suspend fun openBook(uri: Uri): Locator = locatorState.value

        override fun createView(): View = View(context)

        override fun createPageView(pageIndex: Int): View = View(context)

        private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null

        override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
            pageRequestCallback = callback
        }

        override suspend fun setViewportSize(widthPx: Int, heightPx: Int) {
            viewportReports += widthPx to heightPx
        }

        fun requestPage(pageIndex: Int) {
            pageRequestCallback?.invoke(pageIndex)
        }

        override suspend fun close() = Unit

        override suspend fun goTo(locator: Locator) {
            locatorState.value = locator
        }

        override suspend fun setFontSize(sp: Float) = Unit

        override suspend fun setMode(mode: ReadingMode) = Unit
    }

    private class FakeSelfPagingEngine(
        private val context: Context,
    ) : SelfPagingReaderEngine {
        val view = View(context)
        val adjacentDeltas = mutableListOf<Int>()
        private val locatorState = MutableStateFlow(Locator(LocatorStrategy.Page(index = 0, total = 1)))

        override val id: String = "fake-self-paging"
        override val format: BookFormat = BookFormat.EPUB
        override val priority: Int = 0
        override val pagingKind: StateFlow<PagingKind> = MutableStateFlow(PagingKind.PAGED)
        override val supportsSearch: Boolean = false
        override val currentLocator: StateFlow<Locator> = locatorState
        override val pageCount: StateFlow<Int> = MutableStateFlow(1)
        override val selfPagingActive: Boolean = true

        override suspend fun supports(uri: Uri): Boolean = true

        override suspend fun openBook(uri: Uri): Locator = locatorState.value

        override fun createView(): View = view

        override suspend fun close() = Unit

        override suspend fun goTo(locator: Locator) {
            locatorState.value = locator
        }

        override suspend fun setFontSize(sp: Float) = Unit

        override suspend fun setMode(mode: ReadingMode) = Unit

        override suspend fun goToAdjacentPage(delta: Int) {
            adjacentDeltas += delta
        }
    }
}
