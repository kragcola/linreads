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

    private class FakePagedEngine(
        private val context: Context,
        initialPageCount: Int,
    ) : PagedReaderEngine {
        private val locatorState = MutableStateFlow(
            Locator(LocatorStrategy.Page(index = 0, total = initialPageCount)),
        )

        val pageCountState = MutableStateFlow(initialPageCount)

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
