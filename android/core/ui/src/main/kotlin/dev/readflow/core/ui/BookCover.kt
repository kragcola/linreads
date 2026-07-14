package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import dev.readflow.core.model.BookMeta
import kotlin.math.roundToInt

/**
 * 封面朝外（设计文档 §2.1）。有 coverUrl → 加载图 + 磨损暗角；无封面 → 素封面
 * （旧布/纸底色 + 居中烫印书名 + 边缘暗角，§2.1.1）。阅读进度使用封面右下角
 * 的紧凑圆形百分比，避免在卡片文字区重复显示。圆角保持接近纸书的克制比例。
 */
@Composable
fun BookCover(
    book: BookMeta,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
) {
    val clothColor = remember(book.id) { clothColorFor(book.id) }

    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(Dimens.coverCorner),
                ambientColor = Color.Black.copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.20f),
            )
            .clip(RoundedCornerShape(Dimens.coverCorner))
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0x20000000)),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.maxDimension * 0.72f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (book.coverUrl != null) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = "${book.title} 封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlainStampedCover(book, clothColor)
        }

        if (showProgress && book.progress > 0f) {
            BookProgressGauge(
                progress = book.progress,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun BookProgressGauge(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val percent = (safeProgress * 100f).roundToInt()
    Box(
        modifier = modifier
            .size(34.dp)
            .shadow(2.dp, CircleShape)
            .drawBehind {
                drawCircle(Color(0xC91B201D))
                val inset = 3.dp.toPx()
                val strokeWidth = 2.4.dp.toPx()
                val arcSize = Size(
                    width = size.width - inset * 2f,
                    height = size.height - inset * 2f,
                )
                val arcTopLeft = Offset(inset, inset)
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                drawArc(
                    color = Color.White.copy(alpha = 0.24f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = stroke,
                )
                drawArc(
                    color = ReadflowColors.EvergreenNight,
                    startAngle = -90f,
                    sweepAngle = safeProgress * 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
            .clearAndSetSemantics {
                contentDescription = "阅读进度 $percent%"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 无封面 fallback：旧布底色 + 居中烫印书名，看上去是朴素精装本（§2.1.1）。 */
@Composable
private fun PlainStampedCover(
    book: BookMeta,
    clothColor: Color,
) {
    val titleStamp = ReadflowColors.InkNight.copy(alpha = 0.92f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(clothColor)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.title,
                style = ReadflowType.bookTitle.copy(color = titleStamp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Stable cloth color per book id (设计文档 §1.2 cloth.* 封面材质色，不做 UI 强调). */
internal fun clothColorFor(id: String): Color {
    val palette = listOf(
        ReadflowColors.ClothRed,
        ReadflowColors.ClothBlue,
        ReadflowColors.ClothGreen,
    )
    val idx = (id.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}
