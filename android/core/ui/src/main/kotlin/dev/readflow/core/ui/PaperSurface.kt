package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background

/**
 * App-shell background. Reading pages own their optional paper texture; navigation surfaces stay
 * quiet and flat so covers and typography carry the hierarchy.
 */
@Composable
fun PaperSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = readflowPalette
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.paper),
        content = content,
    )
}
