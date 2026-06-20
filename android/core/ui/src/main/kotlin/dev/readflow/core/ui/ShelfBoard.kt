package dev.readflow.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 克制窄隔板沿（设计文档 §2.1 / v3.4 修正：去厚木纹，改 4px 窄板沿 + 书底柔影）。
 * 隔板不是视觉主角——只作"书搁在面上"的实体暗示，不抢封面的视觉重心。
 * 质感靠"书在面上投影"传达，不靠木头本身。参考静读天下保留实体感但去厚重。
 */
@Composable
fun ShelfBoard(
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    // 窄板沿色：纸深一档（paperDeep）+ 一点暖。
    val boardColor = Color(0xFFD4C9AE)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val boardH = 4.dp.toPx()
        val shadowH = 4.dp.toPx()

        // 书底柔影：顶部渐隐（书搁在板上的投影），比板本身更重要。
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x22000000),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = shadowH,
            ),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, shadowH),
        )

        // 窄板沿：4px 暖色细板，底部微暗前缘（暗示板面）。
        drawRect(
            color = boardColor,
            topLeft = Offset(0f, shadowH),
            size = Size(size.width, boardH),
        )
        // 前缘线：极淡暗线，给板一点立体感但不喧宾。
        drawRect(
            color = Color(0x18000000),
            topLeft = Offset(0f, shadowH + boardH - 0.5.dp.toPx()),
            size = Size(size.width, 0.5.dp.toPx()),
        )
    }
}
