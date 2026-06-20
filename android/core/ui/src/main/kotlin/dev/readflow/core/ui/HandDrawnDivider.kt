package dev.readflow.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * 手绘感分隔线（设计文档 §2.1：手作不完美）。一道略不规则的细墨线，不是
 * 1px 数学直线。用在分组标题下（"在读""书架"），给气质一点手作温度。
 */
@Composable
fun HandDrawnDivider(
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    // 固定种子 → 线形稳定，不随重组抖动。
    val waviness = remember { Random(1024).nextFloat() * 0.6f + 0.7f }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        val path = Path().apply {
            moveTo(0f, size.height / 2f)
            var x = 0f
            val step = size.width / 40f
            while (x < size.width) {
                val yJitter = (Math.sin((x / size.width) * Math.PI * 6.0) * waviness).toFloat()
                lineTo(x, size.height / 2f + yJitter)
                x += step
            }
            lineTo(size.width, size.height / 2f)
        }
        drawPath(
            path = path,
            color = palette.inkSoft.copy(alpha = 0.28f),
            style = Stroke(width = 0.8.dp.toPx()),
        )
    }
}
