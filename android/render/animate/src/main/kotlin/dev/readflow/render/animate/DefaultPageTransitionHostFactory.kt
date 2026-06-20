package dev.readflow.render.animate

import android.content.Context
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PageTransitionHost
import dev.readflow.render.api.PageTransitionHostFactory

/**
 * Minimal slice factory: continuous() returns [NoTransitionHost]. paged() falls back
 * to NoTransitionHost too until SlideFade/Curl hosts (§5.6 Phase 1/2) are implemented.
 */
class DefaultPageTransitionHostFactory(
    private val context: Context,
) : PageTransitionHostFactory {

    override fun paged(transition: TransitionType): PageTransitionHost = NoTransitionHost(context)

    override fun continuous(): PageTransitionHost = NoTransitionHost(context)
}
