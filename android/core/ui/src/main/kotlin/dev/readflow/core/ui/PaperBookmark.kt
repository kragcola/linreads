package dev.readflow.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookMeta

/**
 * 在读书里的歪纸书签（设计文档 §2.1：唯一记忆点，单点 boldness）。一张稍歪
 * 的纸条夹在封面里（-1.8°），旧布红色边沿，顶部露出封面外一小截。这是全屏
 * 唯一张力，周围（封面、隔板）全部克制，书签才跳出来。
 */
@Composable
fun PaperBookmark(
    book: BookMeta,
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    val clothRed = ReadflowColors.ClothRed

    Canvas(
        modifier = modifier
            .size(width = 18.dp, height = 56.dp)
            .offset(y = (-12).dp),
    ) {
        rotate(degrees = -1.8f, pivot = center) {
            // 纸条主体：纸色，微圆角。
            drawRoundRect(
                color = palette.paper,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            // 左右旧布红边沿（书签布边）。
            val edgeW = 1.2.dp.toPx()
            drawRect(
                color = clothRed.copy(alpha = 0.74f),
                topLeft = Offset(0f, 0f),
                size = Size(edgeW, size.height),
            )
            drawRect(
                color = clothRed.copy(alpha = 0.74f),
                topLeft = Offset(size.width - edgeW, 0f),
                size = Size(edgeW, size.height),
            )
            // 书签阴影（投在封面上）：底部暖灰渐隐。
            drawRect(
                color = Color.Black.copy(alpha = 0.08f),
                topLeft = Offset(0f, size.height * 0.6f),
                size = Size(size.width, size.height * 0.4f),
            )
        }
    }
}
