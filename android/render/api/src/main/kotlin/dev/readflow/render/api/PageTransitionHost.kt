package dev.readflow.render.api

import android.view.View
import dev.readflow.core.model.TransitionType

/**
 * Wraps a pageable engine session so page transitions work without forcing every
 * format into one ViewPager2 (§5.6). Continuous formats (TXT/MD) use a no-op host.
 * The host is transport-only; ReaderViewModel trusts engine.currentLocator.
 */
interface PageTransitionHost {
    fun hostView(): View
    fun bind(engine: ReaderEngine)
    fun setTransition(type: TransitionType)
    fun setOffscreenPageLimit(limit: Int)
    suspend fun next()
    suspend fun previous()
    fun setOnPageSettled(callback: (pageIndex: Int) -> Unit)
    fun unbind()
}

/** Produces the host an engine's [PagingKind] requires (§9.3 F10, interface in :render:api). */
interface PageTransitionHostFactory {
    fun paged(transition: TransitionType): PageTransitionHost
    fun continuous(): PageTransitionHost
}
