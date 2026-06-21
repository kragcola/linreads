package dev.readflow.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 间距与触感 tokens（设计文档 §1.4 / §3.2）。 */
object Dimens {
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp

    /** 屏幕边距。 */
    val screenEdge = 18.dp

    /** 封面圆角（书近乎方角，大圆角=塑料感）。 */
    val coverCorner = 2.dp

    /** 卡片/纸面圆角。 */
    val surfaceCorner = 8.dp

    /** 触摸目标下限。 */
    val touchTarget = 48.dp

    // 自适应封面网格（§3.2）
    /** 目标封面宽：手机 116dp。GridCells.Adaptive 用此值算列数。 */
    val coverTargetWidthPhone = 116.dp
    /** 目标封面宽：平板 132dp。 */
    val coverTargetWidthTablet = 132.dp
    /** 封面宽高比固定 2:3。 */
    const val coverAspectRatio = 2f / 3f

    // 网格间距随断点放松（§3.2，透气优先）
    val gridGapCompact = 18.dp
    val gridGapMedium = 20.dp
    val gridGapExpanded = 24.dp

    /** Expanded 下书架内容区限宽居中（§3.2）。 */
    val maxContentWidth = 1200.dp
}

/** 当前主题下的纸/墨语义色（设计文档 §1.2）。日间纸+墨，夜间暖褐纸+暖白墨。 */
data class ReadflowPalette(
    val paper: Color,
    val paperDeep: Color,
    val paperShadow: Color,
    val ink: Color,
    val inkSoft: Color,
)

/** Theme-aware palette accessor — follows MaterialTheme, respecting app theme mode. */
val readflowPalette: ReadflowPalette
    @Composable
    @ReadOnlyComposable
    get() {
        val cs = MaterialTheme.colorScheme
        // onBackground is ink-colored: dark ink → light paper, light ink → dark paper
        val inkLuminance = (cs.onBackground.red + cs.onBackground.green + cs.onBackground.blue) / 3f
        val isDark = inkLuminance > 0.5f
        return if (isDark) {
            ReadflowPalette(
                paper = ReadflowColors.PaperNight,
                paperDeep = ReadflowColors.PaperNightDeep,
                paperShadow = Color(0xFF14110D),
                ink = cs.onBackground,
                inkSoft = cs.onSurfaceVariant,
            )
        } else {
            ReadflowPalette(
                paper = cs.background,
                paperDeep = cs.surfaceVariant,
                paperShadow = ReadflowColors.PaperShadow,
                ink = cs.onBackground,
                inkSoft = cs.onSurfaceVariant,
            )
        }
    }
