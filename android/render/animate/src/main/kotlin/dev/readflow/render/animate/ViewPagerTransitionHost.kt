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
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.SelfPagingReaderEngine
import kotlinx.coroutines.CoroutineScope
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
    private var engine: ReaderEngine? = null
    private var pagedEngine: PagedReaderEngine? = null
    private var selfPagingEngine: SelfPagingReaderEngine? = null
    private var pageCountJob: Job? = null
    private var onPageSettled: ((pageIndex: Int) -> Unit)? = null
    private var transitionType: TransitionType = transition

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            // Self-paging engines own their paging + locator inside one static view; the ViewPager is
            // just a single-slot container with input disabled. ViewPager2 still fires onPageSelected(0)
            // on adapter attach, so without this guard we'd clobber the restored position with
            // goTo(Page(0, total)). Let the engine drive its own locator instead.
            if (selfPagingEngine != null) return
            val activeEngine = engine ?: return
            val total = activeEngine.pageCount.value
            if (total <= 0) return
            scope.launch {
                activeEngine.goTo(pageLocator(position, total))
            }
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                onPageSettled?.invoke(pager.currentItem)
            }
        }
    }

    init {
        pager.registerOnPageChangeCallback(pageCallback)
        setTransition(transition)
    }

    override fun hostView(): View = pager

    override fun bind(engine: ReaderEngine) {
        pageCountJob?.cancel()
        pageCountJob = null
        pagedEngine?.setPageRequestCallback(null)
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
            pager.isUserInputEnabled = false
            pager.adapter = SingleViewAdapter(engine.createView())
            return
        }
        pager.isUserInputEnabled = true
        val fixedPageEngine = engine as? PagedReaderEngine
        pagedEngine = fixedPageEngine
        if (fixedPageEngine == null) {
            pager.adapter = SingleViewAdapter(engine.createView())
            return
        }
        fixedPageEngine.setPageRequestCallback { pageIndex ->
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
            pager.setCurrentItem(initial, false)
        }
        pageCountJob = scope.launch {
            fixedPageEngine.pageCount.collect { pageCount ->
                if (pager.adapter !== adapter) return@collect
                adapter.refreshPageCount()
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
        val lastIndex = (pager.adapter?.itemCount ?: 0) - 1
        if (lastIndex < 0) return
        val target = (pager.currentItem + 1).coerceAtMost(lastIndex)
        if (target != pager.currentItem) {
            pager.setCurrentItem(target, transitionType != TransitionType.NONE)
        }
    }

    override suspend fun previous() {
        selfPagingEngine?.let { it.goToAdjacentPage(-1); return }
        val target = (pager.currentItem - 1).coerceAtLeast(0)
        if (target != pager.currentItem) {
            pager.setCurrentItem(target, transitionType != TransitionType.NONE)
        }
    }

    override fun setOnPageSettled(callback: (pageIndex: Int) -> Unit) {
        onPageSettled = callback
    }

    override fun unbind() {
        pageCountJob?.cancel()
        pageCountJob = null
        pagedEngine?.setPageRequestCallback(null)
        pager.isUserInputEnabled = true
        pager.adapter = null
        pagedEngine = null
        selfPagingEngine = null
        engine = null
        scope.cancel()
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
        override fun getItemCount(): Int = engine.pageCount.value

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.container.removeAllViews()
            holder.container.addView(engine.createPageView(position), matchParentLayoutParams())
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.container.removeAllViews()
        }

        fun refreshPageCount() {
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
