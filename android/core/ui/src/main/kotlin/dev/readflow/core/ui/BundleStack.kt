package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.readflow.core.model.BookBundle

/**
 * 合订文件夹：叠放封面，层数 = min(书数,4)，向右下露边，无"N本"字（§2.1.2）。
 * 顶层 = 该组最上面一本的封面正面朝外；底下各层取后续书封面的边，向右下
 * 错开 + 渐深阴影，模拟摞着的书。厚度即数量的直觉表达。与单本书同尺寸整齐
 * 并排（靠层叠的边区分单本 vs 一叠）。
 */
@Composable
fun BundleStack(
    bundle: BookBundle,
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    val layerCount = minOf(bundle.count, 4)
    val offsetStep = 4.dp

    Box(modifier = modifier) {
        // 底层向顶层：索引越大越在前，顶层(index=0)绘制在最上。
        for (i in (layerCount - 1) downTo 0) {
            val book = bundle.topBooks.getOrNull(i) ?: continue
            val xOff = offsetStep * (layerCount - 1 - i)
            val yOff = offsetStep * (layerCount - 1 - i)
            val shadowAlpha = 0.12f + (layerCount - 1 - i) * 0.04f

            Box(
                modifier = Modifier
                    .offset(x = xOff, y = yOff)
                    .clip(RoundedCornerShape(Dimens.coverCorner))
                    .drawWithContent {
                        drawContent()
                        // 层间阴影：下面的层投影更深，暗示厚度。
                        if (i < layerCount - 1) {
                            drawRect(Color.Black.copy(alpha = shadowAlpha))
                        }
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
                    // 无封面：素封面占位（复用 clothColorFor 逻辑）。
                    val clothColor = clothColorFor(book.id)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind { drawRect(clothColor) },
                    )
                }
            }
        }
    }
}
