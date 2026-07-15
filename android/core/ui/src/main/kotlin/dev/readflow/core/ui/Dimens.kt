package dev.readflow.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.material3.MaterialTheme
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
    val screenEdge = 16.dp

    /** 纸书封面使用直角。 */
    val coverCorner = 0.dp

    /** 卡片/纸面圆角。 */
    val surfaceCorner = 20.dp

    /** 触摸目标下限。 */
    val touchTarget = 48.dp

    /** 紧凑屏幕允许的最小封面宽，确保横屏手机可增加列数。 */
    val coverMinWidthCompact = 144.dp

    /** 中大屏允许的最小封面宽，避免平板封面反而小于手机。 */
    val coverMinWidth = 160.dp

    /** 封面宽高比固定 2:3。 */
    const val coverAspectRatio = 2f / 3f

    // Moon+ 的网格卡片净空约 8–12dp；宽屏逐档放松但不牺牲封面尺寸。
    val gridGapCompact = 12.dp
    val gridGapMedium = 16.dp
    val gridGapExpanded = 20.dp
    val gridRowGap = 20.dp

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
        val isDark = java.lang.Float.compare(inkLuminance, 0.5f) > 0
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
