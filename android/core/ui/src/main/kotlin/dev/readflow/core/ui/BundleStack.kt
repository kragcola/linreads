package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.readflow.core.model.BookBundle

/**
 * 合订文件夹：顶层封面正面朝外，底层渐暗并向右下错开。
 * 1本=填满；2本=88%+12%露边；3本=84%+8%；4本=80%+6.7%。
 * 尺寸基于容器百分比，平板/手机效果一致。
 */
@Composable
fun BundleStack(
    bundle: BookBundle,
    modifier: Modifier = Modifier,
) {
    val layerCount = minOf(bundle.count, 4)

    // coverFraction: 顶层封面占容器的比例；剩余空间均分给底层露边偏移
    val coverFraction = when (layerCount) {
        1 -> 1.0f
        2 -> 0.88f
        3 -> 0.84f
        else -> 0.80f
    }

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
            val layerDepth = layerCount - 1 - i   // 0=顶层, layerCount-1=最底层
            val xOff = stepW * layerDepth
            val yOff = stepH * layerDepth
            // 越底层越暗
            val shadowAlpha = layerDepth * 0.18f

            Box(
                modifier = Modifier
                    .offset(x = xOff, y = yOff)
                    .size(coverW, coverH)
                    .clip(RoundedCornerShape(Dimens.coverCorner))
                    .drawWithContent {
                        drawContent()
                        if (shadowAlpha > 0f) drawRect(Color.Black.copy(alpha = shadowAlpha))
                    },
            ) {
                if (book.coverUrl != null) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = "${book.title} 封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val clothColor = clothColorFor(book.id)
                    Box(modifier = Modifier.fillMaxSize().drawBehind { drawRect(clothColor) })
                }
            }
        }
    }
}
