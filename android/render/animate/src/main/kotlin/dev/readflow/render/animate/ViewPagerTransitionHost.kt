package dev.readflow.render.animate

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PageTransitionHost
import dev.readflow.render.api.DirectionalPagedReaderEngine
import dev.readflow.render.api.PageReadingDirection
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.SelfPagingReaderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ViewPagerTransitionHost(
    context: Context,
    transition: TransitionType,
) : PageTransitionHost {

    private var scope = viewPagerHostScope()
    private val pager = ViewPager2(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    private val selfPagingContainer = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    private var engine: ReaderEngine? = null
    private var pagedEngine: PagedReaderEngine? = null
    private var selfPagingEngine: SelfPagingReaderEngine? = null
    private var pageCountJob: Job? = null
    private var pageDirectionJob: Job? = null
    private var viewportJob: Job? = null
    private var onPageSettled: ((pageIndex: Int) -> Unit)? = null
    private var transitionType: TransitionType = transition
    private var lastReportedViewportWidth: Int = 0
    private var lastReportedViewportHeight: Int = 0
    private var bindGeneration: Long = 0L
    private var suppressPageCallbacks: Boolean = false
    private var initialPageGuard: Int? = null

    private val viewportLayoutListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (width > 0 && height > 0 && (width != oldWidth || height != oldHeight ||
                    width != lastReportedViewportWidth || height != lastReportedViewportHeight)
            ) {
                reportViewportSize(width, height)
            }
        }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            // Self-paging engines own their paging + locator inside one static view. Let the engine
            // drive its own locator instead of reacting to stale ViewPager2 callbacks.
            if (selfPagingEngine != null || suppressPageCallbacks) return
            initialPageGuard?.let { expected ->
                if (position != expected && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    pager.setCurrentItem(expected, false)
                }
                return
            }
            val activeEngine = engine ?: return
            val total = activeEngine.pageCount.value
            if (total <= 0) return
            val callbackGeneration = bindGeneration
            scope.launch {
                if (
                    callbackGeneration != bindGeneration ||
                    suppressPageCallbacks ||
                    engine !== activeEngine
                ) return@launch
                activeEngine.goTo(pageLocator(position, total))
            }
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (
                state == ViewPager2.SCROLL_STATE_DRAGGING ||
                state == ViewPager2.SCROLL_STATE_SETTLING
            ) {
                // A callback queued by the previous adapter can arrive after bind() installs a
                // new adapter. Keep the new binding guarded until its expected page is confirmed
                // at idle; explicit next()/previous() calls release the guard intentionally.
                return
            }
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                restoreCurrentPageTransform()
                val expected = initialPageGuard
                if (expected != null) {
                    if (pager.currentItem != expected) {
                        pager.setCurrentItem(expected, false)
                    } else {
                        releaseInitialPageGuard()
                    }
                    return
                }
                if (!suppressPageCallbacks) onPageSettled?.invoke(pager.currentItem)
            }
        }
    }

    init {
        pager.offscreenPageLimit = DEFAULT_OFFSCREEN_PAGE_LIMIT
        pager.registerOnPageChangeCallback(pageCallback)
        pager.addOnLayoutChangeListener(viewportLayoutListener)
        setTransition(transition)
    }

    override fun hostView(): View =
        if (selfPagingEngine != null) selfPagingContainer else pager

    override fun bind(engine: ReaderEngine) {
        val currentBindGeneration = ++bindGeneration
        suppressPageCallbacks = true
        initialPageGuard = null
        pageCountJob?.cancel()
        pageCountJob = null
        pageDirectionJob?.cancel()
        pageDirectionJob = null
        viewportJob?.cancel()
        viewportJob = null
        lastReportedViewportWidth = 0
        lastReportedViewportHeight = 0
        pagedEngine?.setPageRequestCallback(null)
        pager.adapter = null
        selfPagingContainer.removeAllViews()
        if (!scope.isActive) {
            scope = viewPagerHostScope()
        }
        this.engine = engine
        // Self-paging engines (continuous-flow EPUB) own their own paging/gestures inside a single
        // view — attach once, no per-page ViewPager2 slots, and delegate page turns to the engine.
        val selfPaging = (engine as? SelfPagingReaderEngine)?.takeIf { it.selfPagingActive }
        selfPagingEngine = selfPaging
        if (selfPaging != null) {
            pagedEngine = null
            val view = engine.createView()
            (view.parent as? ViewGroup)?.removeView(view)
            selfPagingContainer.addView(view, matchParentLayoutParams())
            suppressPageCallbacks = false
            return
        }
        pager.isUserInputEnabled = true
        val fixedPageEngine = engine as? PagedReaderEngine
        pagedEngine = fixedPageEngine
        if (fixedPageEngine == null) {
            pager.adapter = SingleViewAdapter(engine.createView())
            return
        }
        pager.offscreenPageLimit = fixedPageEngine.preferredOffscreenPageLimit.coerceAtLeast(1)
        val directionalEngine = fixedPageEngine as? DirectionalPagedReaderEngine
        applyPageReadingDirection(
            directionalEngine?.pageReadingDirection?.value ?: PageReadingDirection.LEFT_TO_RIGHT,
        )
        if (directionalEngine != null) {
            pageDirectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                directionalEngine.pageReadingDirection.collect(::applyPageReadingDirection)
            }
        }
        fixedPageEngine.setPageRequestCallback { pageIndex ->
            if (
                currentBindGeneration != bindGeneration ||
                suppressPageCallbacks ||
                pagedEngine !== fixedPageEngine
            ) return@setPageRequestCallback
            val lastIndex = (pager.adapter?.itemCount ?: 0) - 1
            if (lastIndex < 0) return@setPageRequestCallback
            val target = pageIndex.coerceIn(0, lastIndex)
            if (pager.currentItem != target) {
                pager.setCurrentItem(target, transitionType != TransitionType.NONE)
            }
        }
        val adapter = PagedEngineAdapter(fixedPageEngine)
        pager.adapter = adapter
        val total = engine.pageCount.value
        if (total > 0) {
            val initial = fixedPageEngine.pageIndexForLocator(engine.currentLocator.value)
            initialPageGuard = initial
            pager.setCurrentItem(initial, false)
            pager.post {
                if (
                    currentBindGeneration != bindGeneration ||
                    pagedEngine !== fixedPageEngine
                ) return@post
                // ViewPager2 may dispatch a queued selection from the previous adapter after the
                // first setCurrentItem call. Reassert the engine's semantic start while callbacks
                // are still suppressed, then publish the binding as interactive.
                pager.setCurrentItem(initial, false)
                if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) restoreCurrentPageTransform()
                pager.post {
                    if (
                        currentBindGeneration != bindGeneration ||
                        pagedEngine !== fixedPageEngine
                    ) return@post
                    if (
                        pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                        pager.currentItem == initial
                    ) {
                        releaseInitialPageGuard()
                    } else {
                        pager.setCurrentItem(initial, false)
                    }
                }
            }
        } else {
            suppressPageCallbacks = false
        }
        // Report actual pager viewport once layout has a size (and again on size changes).
        if (pager.width > 0 && pager.height > 0) {
            reportViewportSize(pager.width, pager.height)
        } else {
            pager.post {
                if (pagedEngine === fixedPageEngine && pager.width > 0 && pager.height > 0) {
                    reportViewportSize(pager.width, pager.height)
                }
            }
        }
        pageCountJob = scope.launch {
            fixedPageEngine.pageCount.collect { pageCount ->
                if (pager.adapter !== adapter) return@collect
                adapter.refreshPageCount(pageCount)
                clampCurrentItemTo(pageCount)
            }
        }
    }

    override fun setTransition(type: TransitionType) {
        transitionType = type
        pager.setPageTransformer(
            when (type) {
                TransitionType.CURL -> CurlPageTransformer
                TransitionType.FADE -> FadePageTransformer
                TransitionType.SLIDE, TransitionType.NONE -> ResetPageTransformer
            },
        )
    }

    override fun setOffscreenPageLimit(limit: Int) {
        pager.offscreenPageLimit = if (limit <= 0) {
            ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        } else {
            limit
        }
    }

    override suspend fun next() {
        selfPagingEngine?.let { it.goToAdjacentPage(1); return }
        initialPageGuard = null
        suppressPageCallbacks = false
        val lastIndex = (pager.adapter?.itemCount ?: 0) - 1
        if (lastIndex < 0) return
        val target = (pager.currentItem + 1).coerceAtMost(lastIndex)
        if (target != pager.currentItem) {
            pager.setCurrentItem(target, transitionType != TransitionType.NONE)
        }
    }

    override suspend fun previous() {
        selfPagingEngine?.let { it.goToAdjacentPage(-1); return }
        initialPageGuard = null
        suppressPageCallbacks = false
        val target = (pager.currentItem - 1).coerceAtLeast(0)
        if (target != pager.currentItem) {
            pager.setCurrentItem(target, transitionType != TransitionType.NONE)
        }
    }

    override fun setOnPageSettled(callback: (pageIndex: Int) -> Unit) {
        onPageSettled = callback
    }

    override fun unbind() {
        bindGeneration++
        suppressPageCallbacks = true
        initialPageGuard = null
        pageCountJob?.cancel()
        pageCountJob = null
        pageDirectionJob?.cancel()
        pageDirectionJob = null
        viewportJob?.cancel()
        viewportJob = null
        pagedEngine?.setPageRequestCallback(null)
        lastReportedViewportWidth = 0
        lastReportedViewportHeight = 0
        pager.isUserInputEnabled = true
        pager.offscreenPageLimit = DEFAULT_OFFSCREEN_PAGE_LIMIT
        applyPageReadingDirection(PageReadingDirection.LEFT_TO_RIGHT)
        pager.adapter = null
        selfPagingContainer.removeAllViews()
        pagedEngine = null
        selfPagingEngine = null
        engine = null
        scope.cancel()
    }

    private fun reportViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == lastReportedViewportWidth && heightPx == lastReportedViewportHeight) return
        lastReportedViewportWidth = widthPx
        lastReportedViewportHeight = heightPx
        val activeEngine = pagedEngine ?: return
        viewportJob?.cancel()
        viewportJob = scope.launch {
            activeEngine.setViewportSize(widthPx, heightPx)
        }
    }

    private fun applyPageReadingDirection(direction: PageReadingDirection) {
        val layoutDirection = pageLayoutDirection(direction)
        pager.layoutDirection = layoutDirection
        pager.getChildAt(0)?.layoutDirection = layoutDirection
        pager.requestLayout()
    }

    private fun restoreCurrentPageTransform() {
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return
        recycler.findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView
            ?.resetPageTransform()
    }

    /**
     * Publish the binding as interactive and report the initial settle exactly once.
     * Both the ViewPager2 idle callback and the bind() post chain may observe the guarded
     * page at idle; the guard null-check makes the release idempotent so a host never sees
     * zero or duplicate settle callbacks for the initial page.
     */
    private fun releaseInitialPageGuard() {
        if (initialPageGuard == null) return
        initialPageGuard = null
        suppressPageCallbacks = false
        onPageSettled?.invoke(pager.currentItem)
    }

    private fun clampCurrentItemTo(pageCount: Int) {
        if (pageCount <= 0) return
        val target = pager.currentItem.coerceAtMost(pageCount - 1)
        if (target != pager.currentItem) {
            pager.setCurrentItem(target, false)
        }
    }

    private class PagedEngineAdapter(
        private val engine: PagedReaderEngine,
    ) : RecyclerView.Adapter<PageHolder>() {
        private var pageCount = engine.pageCount.value

        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            // PageTransformer properties live on the recycled holder, not its content. A holder
            // rebound after the last scroll callback must never inherit a hidden neighbour state.
            holder.container.resetPageTransform()
            holder.container.removeAllViews()
            holder.container.addView(engine.createPageView(position), matchParentLayoutParams())
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.container.resetPageTransform()
            holder.container.removeAllViews()
        }

        fun refreshPageCount(newPageCount: Int) {
            if (pageCount == newPageCount) return
            pageCount = newPageCount
            notifyDataSetChanged()
        }
    }

    private class SingleViewAdapter(
        private val view: View,
    ) : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            (view.parent as? ViewGroup)?.removeView(view)
            holder.container.removeAllViews()
            holder.container.addView(view, matchParentLayoutParams())
        }
    }

    private class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private object FadePageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.resetPageTransform()
            page.translationX = -position * page.width
            page.alpha = (1f - kotlin.math.abs(position)).coerceIn(0f, 1f)
        }
    }

    private object CurlPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.applyCurlTransform(curlTransformFor(position))
        }
    }

    private object ResetPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.resetPageTransform()
        }
    }

    private companion object {
        const val DEFAULT_OFFSCREEN_PAGE_LIMIT = 1
    }
}

internal fun pageLayoutDirection(direction: PageReadingDirection): Int = when (direction) {
    PageReadingDirection.LEFT_TO_RIGHT -> View.LAYOUT_DIRECTION_LTR
    PageReadingDirection.RIGHT_TO_LEFT -> View.LAYOUT_DIRECTION_RTL
}

private fun View.applyCurlTransform(values: PageTransformValues) {
    alpha = values.alpha
    translationX = 0f
    rotationX = 0f
    rotationY = values.rotationY
    scaleX = 1f
    scaleY = 1f
    pivotX = width * values.pivotXFraction
    pivotY = height * values.pivotYFraction
    cameraDistance = resources.displayMetrics.density * values.cameraDistance
}

private fun View.resetPageTransform() {
    alpha = 1f
    translationX = 0f
    rotationX = 0f
    rotationY = 0f
    scaleX = 1f
    scaleY = 1f
    pivotX = width * 0.5f
    pivotY = height * 0.5f
}

private fun pageLocator(index: Int, total: Int): Locator {
    val safeTotal = total.coerceAtLeast(1)
    val safeIndex = index.coerceIn(0, safeTotal - 1)
    val progression = safeIndex.toFloat() / safeTotal
    return Locator(
        strategy = LocatorStrategy.Page(safeIndex, safeTotal),
        progression = progression,
        totalProgression = progression,
    )
}

private fun matchParentLayoutParams(): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

private fun viewPagerHostScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
