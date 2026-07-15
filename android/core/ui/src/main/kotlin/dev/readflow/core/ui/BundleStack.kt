package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookBundle

internal fun bundleCoverFraction(layerCount: Int): Float = when (layerCount.coerceIn(1, 4)) {
    1 -> 1.0f
    2 -> 0.94f
    3 -> 0.90f
    else -> 0.86f
}

/**
 * Semantic stack depth for [topBooks] index.
 * Depth 0 = front (offset 0, no scrim). Higher depth = farther back.
 * Supported layers: 1..4. Draw loop still paints high depth first, then index 0 last.
 */
internal fun bundleLayerDepth(topBookIndex: Int, layerCount: Int): Int {
    val n = layerCount.coerceIn(1, 4)
    return topBookIndex.coerceIn(0, n - 1)
}

/** Full-rect black scrim alpha for a stack layer; coefficient 0.18 per back layer. */
internal fun bundleLayerScrimAlpha(layerDepth: Int): Float = layerDepth * 0.18f

/**
 * 合订文件夹：顶层封面正面朝外，底层渐暗并向右下错开。
 * 1本=填满；2本=94%+6%露边；3本=90%+5%；4本=86%+4.7%。
 * 尺寸基于容器百分比，平板/手机效果一致。
 */
@Composable
fun BundleStack(
    bundle: BookBundle,
    modifier: Modifier = Modifier,
) {
    val layerCount = minOf(bundle.count, 4)

    // coverFraction: 顶层封面占容器的比例；剩余空间均分给底层露边偏移
    val coverFraction = bundleCoverFraction(layerCount)

    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        val h = maxHeight
        val coverW = w * coverFraction
        val coverH = h * coverFraction
        // 露出的边缘总量 = 1 - coverFraction，均匀分配给 (layerCount-1) 个间隔
        val stepW = if (layerCount > 1) (w - coverW) / (layerCount - 1) else 0.dp
        val stepH = if (layerCount > 1) (h - coverH) / (layerCount - 1) else 0.dp

        // 底层 → 顶层（index=0为顶层，最后绘制）
        for (i in (layerCount - 1) downTo 0) {
            val book = bundle.topBooks.getOrNull(i) ?: continue
            val layerDepth = bundleLayerDepth(topBookIndex = i, layerCount = layerCount)
            val xOff = stepW * layerDepth
            val yOff = stepH * layerDepth
            // 越底层越暗
            val shadowAlpha = bundleLayerScrimAlpha(layerDepth)

            Box(
                modifier = Modifier
                    .offset(x = xOff, y = yOff)
                    .size(coverW, coverH)
                    .drawWithContent {
                        drawContent()
                        if (shadowAlpha > 0f) drawRect(Color.Black.copy(alpha = shadowAlpha))
                    },
            ) {
                BookCover(
                    book = book,
                    showProgress = false,
                    showMaterialDepth = i == 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
