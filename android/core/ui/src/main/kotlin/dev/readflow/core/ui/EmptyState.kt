package dev.readflow.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 书架空态（设计文档 §2.2）。一格空木隔板 + 一句话 + 墨线框双按钮，不卖惨不插画。
 */
@Composable
fun EmptyState(
    onOpenOnlineLibrary: () -> Unit,
    onImportLocal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            // 空隔板沿（暗示"书架在，但还没书"）。
            ShelfBoard(modifier = Modifier.padding(horizontal = 48.dp))

            Spacer(modifier = Modifier.height(Dimens.spaceSm))

            Text(
                "还没有书",
                style = ReadflowType.sectionLabel,
                color = palette.ink,
            )
            Text(
                "从在线书库下载，或导入本地文件",
                style = ReadflowType.meta,
                color = palette.inkSoft,
            )

            Spacer(modifier = Modifier.height(Dimens.spaceMd))

            // 墨线框按钮（设计文档 §2.2：墨线框，无填充亮色）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
            ) {
                OutlinedButton(
                    onClick = onOpenOnlineLibrary,
                    border = BorderStroke(1.dp, palette.ink),
                ) {
                    Text("在线书库", color = palette.ink, style = ReadflowType.ui)
                }
                OutlinedButton(
                    onClick = onImportLocal,
                    border = BorderStroke(1.dp, palette.ink),
                ) {
                    Text("导入本地", color = palette.ink, style = ReadflowType.ui)
                }
            }
        }
    }
}
