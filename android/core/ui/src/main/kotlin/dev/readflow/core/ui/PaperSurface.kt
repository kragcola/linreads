package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

/**
 * 全局纸背景（设计文档 §1.1 材质优先）。落地手段：
 * - 底色 = 主纸色（日/夜各一档），不是纯色块。
 * - 叠一层极淡纤维噪点（drawBehind 程序化点绘，非贴大图），让大面积"纸"有微小明暗起伏。
 * - 顶部一抹极淡暖柔光，像漫射光打在纸上，避免死平。
 * 噪点 alpha 极低，不影响 ink/paper ≈ 11.5:1 对比度（§四 无障碍）。
 */
@Composable
fun PaperSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = readflowPalette
    // 固定种子 → 噪点稳定，不随重组闪烁（§2.1 不要随机抖动）。
    val flecks = remember { generateFlecks(seed = 4096, count = 280) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawPaper(palette, flecks) },
        content = content,
    )
}

private data class Fleck(val xFrac: Float, val yFrac: Float, val radius: Float, val dark: Boolean)

private fun generateFlecks(seed: Int, count: Int): List<Fleck> {
    val rnd = Random(seed)
    return List(count) {
        Fleck(
            xFrac = rnd.nextFloat(),
            yFrac = rnd.nextFloat(),
            radius = 0.5f + rnd.nextFloat() * 1.1f,
            dark = rnd.nextFloat() > 0.45f,
        )
    }
}

private fun DrawScope.drawPaper(palette: ReadflowPalette, flecks: List<Fleck>) {
    // 1) 主纸底色。
    drawRect(color = palette.paper)

    // 2) 顶部暖柔光：纸更亮的纤维高光，极淡，自上而下衰减。
    val warmHi = if (palette.paper.luminanceApprox() > 0.5f) Color.White else palette.inkSoft
    drawRect(
        color = warmHi.copy(alpha = 0.05f),
        size = size.copy(height = size.height * 0.5f),
    )

    // 3) 纤维噪点：暗粒(纸纹阴影) + 亮粒(纤维高光)，alpha 极低。
    for (f in flecks) {
        val color = if (f.dark) palette.paperShadow.copy(alpha = 0.10f)
        else Color.White.copy(alpha = 0.06f)
        drawCircle(
            color = color,
            radius = f.radius,
            center = Offset(f.xFrac * size.width, f.yFrac * size.height),
        )
    }
}

/** Cheap perceived-luminance check to decide whether the surface is light or dark. */
private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
