package dev.readflow.render.animate

import android.content.Context
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PageTransitionHost
import dev.readflow.render.api.PageTransitionHostFactory

class DefaultPageTransitionHostFactory(
    private val context: Context,
) : PageTransitionHostFactory {

    override fun paged(transition: TransitionType): PageTransitionHost =
        ViewPagerTransitionHost(context, transition)

    override fun continuous(): PageTransitionHost = NoTransitionHost(context)
}
