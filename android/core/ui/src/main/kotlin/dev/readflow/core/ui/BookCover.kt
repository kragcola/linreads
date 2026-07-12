package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.readflow.core.model.BookMeta

/**
 * 封面朝外（设计文档 §2.1）。有 coverUrl → 加载图 + 磨损暗角；无封面 → 素封面
 * （旧布/纸底色 + 居中烫印书名作者 + 边缘暗角，§2.1.1）。底部一道细墨进度条
 * （§2.1：进度=封面底部细进度条，不占额外行）。圆角 2dp（书近方角）。
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .drawBehind {
                        drawRect(Color.Black.copy(alpha = 0.62f))
                        drawRect(
                            color = ReadflowColors.EvergreenNight,
                            size = size.copy(width = size.width * book.progress.coerceIn(0f, 1f)),
                        )
                    },
            )
        }
    }
}

/** 无封面 fallback：旧布底色 + 居中烫印书名/作者，看上去是朴素精装本（§2.1.1）。 */
@Composable
private fun PlainStampedCover(
    book: BookMeta,
    clothColor: Color,
) {
    val titleStamp = ReadflowColors.InkNight.copy(alpha = 0.92f)
    val authorStamp = ReadflowColors.InkNight.copy(alpha = 0.90f)
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
        Text(
            text = book.author,
            style = ReadflowType.meta.copy(color = authorStamp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp, start = 8.dp, end = 8.dp),
        )
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
