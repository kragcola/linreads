package dev.readflow.render.animate

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PageTransitionHost
import dev.readflow.render.api.ReaderEngine

/**
 * Host for CONTINUOUS engines (TXT scroll, MD). The engine's own scrolling View is
 * the page surface; this host just mounts it and is a no-op for paging (§5.6).
 */
class NoTransitionHost(
    private val context: Context,
) : PageTransitionHost {

    private val container = FrameLayout(context)
    private var engine: ReaderEngine? = null

    override fun hostView(): View = container

    override fun bind(engine: ReaderEngine) {
        this.engine = engine
        container.removeAllViews()
        container.addView(engine.createView())
    }

    override fun setTransition(type: TransitionType) { /* no-op: continuous scroll */ }
    override fun setOffscreenPageLimit(limit: Int) { /* no-op */ }
    override suspend fun next() { /* no-op: scrolling is user-driven */ }
    override suspend fun previous() { /* no-op */ }
    override fun setOnPageSettled(callback: (pageIndex: Int) -> Unit) { /* no-op */ }

    override fun unbind() {
        container.removeAllViews()
        engine = null
    }
}
