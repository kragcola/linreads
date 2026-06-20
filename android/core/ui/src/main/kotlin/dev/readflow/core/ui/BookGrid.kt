package dev.readflow.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readflow.core.model.LibraryItem

/**
 * 自适应封面网格（设计文档 §3.2）。不写死列数，定义单本封面的目标宽度，让
 * 列数 = floor(可用宽 / 目标宽)，余量均分到间距。GridCells.Adaptive(minSize)
 * 自动算列数。封面宽高比固定 2:3。每行书"搁在"一道窄隔板沿上（ShelfBoard）。
 *
 * [LibraryItem] 可以是 Single(单本) 或 Bundle(合订)，网格统一对待，整齐对齐。
 * 设计文档 §2.1：封面是 hero，隔板退到背景，全部正面封面朝外。
 */
@Composable
fun BookGrid(
    items: List<LibraryItem>,
    onItemClick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = Dimens.coverTargetWidthPhone),
            contentPadding = PaddingValues(
                horizontal = Dimens.screenEdge,
                vertical = Dimens.spaceMd,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gridGapCompact),
            verticalArrangement = Arrangement.spacedBy(Dimens.gridGapCompact),
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth),
        ) {
            items(items, key = { it.key }) { item ->
                Column(modifier = Modifier.clickable { onItemClick(item) }) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(Dimens.coverAspectRatio)
                            .padding(bottom = Dimens.spaceXs),
                    ) {
                        when (item) {
                            is LibraryItem.Single -> {
                                BookCover(book = item.book)
                                // 在读书里夹书签（设计文档 §2.1：唯一记忆点）。
                                if (item.book.lastReadAt != null && item.book.progress > 0f) {
                                    PaperBookmark(
                                        book = item.book,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                            .padding(end = 8.dp),
                                    )
                                }
                            }
                            is LibraryItem.Bundle -> {
                                BundleStack(bundle = item.bundle)
                            }
                        }
                    }

                    // 书名/作者：紧凑宋体，最多 2 行截断（§3.4 密度）。
                    val title = when (item) {
                        is LibraryItem.Single -> item.book.title
                        is LibraryItem.Bundle -> item.bundle.name
                    }
                    Text(
                        text = title,
                        style = ReadflowType.bookTitle,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item is LibraryItem.Single) {
                        Text(
                            text = item.book.author,
                            style = ReadflowType.meta,
                            color = palette.inkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 每行底部一道隔板沿（ShelfBoard），书搁在上面。每本底部各画一道，视觉等效。
                    ShelfBoard(modifier = Modifier.padding(top = Dimens.spaceXs))
                }
            }
        }
    }
}

/** Stable key for LazyGrid items. */
private val LibraryItem.key: String
    get() = when (this) {
        is LibraryItem.Single -> "book:${book.id}"
        is LibraryItem.Bundle -> "bundle:${bundle.name}"
    }
