package dev.readflow.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

private val shelfFiberPositionsDp = floatArrayOf(3f, 5.5f)

/**
 * 克制窄隔板沿：用主题纸色与少量纤维细线暗示承托面，不抢封面视觉重心。
 */
@Composable
fun ShelfBoard(
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val hairline = 0.5.dp.toPx()

        drawRect(color = palette.paperDeep)

        drawLine(
            color = palette.paperShadow.copy(alpha = 0.20f),
            start = Offset(0f, hairline / 2f),
            end = Offset(size.width, hairline / 2f),
            strokeWidth = hairline,
        )

        shelfFiberPositionsDp.forEach { yDp ->
            drawLine(
                color = palette.paper.copy(alpha = 0.12f),
                start = Offset(0f, yDp.dp.toPx()),
                end = Offset(size.width, yDp.dp.toPx()),
                strokeWidth = hairline,
            )
        }

        drawLine(
            color = palette.paperShadow.copy(alpha = 0.14f),
            start = Offset(0f, size.height - hairline / 2f),
            end = Offset(size.width, size.height - hairline / 2f),
            strokeWidth = hairline,
        )
    }
}
