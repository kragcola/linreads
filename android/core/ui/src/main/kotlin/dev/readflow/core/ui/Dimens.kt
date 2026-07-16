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
    val screenEdge = 20.dp

    /** 纸书封面使用直角。 */
    val coverCorner = 0.dp

    /** 卡片/纸面圆角。 */
    val surfaceCorner = 20.dp

    /** 触摸目标下限。 */
    val touchTarget = 48.dp

    /**
     * Compact (phone) minimum cover width for column packing.
     * Moon phone pitch uses ~110dp shelfCoverSize × 1.10 unit; we keep a slightly
     * larger min so two-up phone covers stay near the accepted ~156dp cell.
     */
    val coverMinWidth = 116.dp

    /**
     * Medium / Expanded minimum cover width so tablet columns do not over-pack
     * and shrink covers below phone scale (Moon tablet unit uses 136/138%).
     * See [libraryCoverMinWidthDp].
     */
    val coverMinWidthTablet = 132.dp

    /** 封面宽高比固定 70:100（Moon+），即 0.7f。 */
    const val coverAspectRatio = 0.7f

    /**
     * Moon+ shelf grid: MyCardViewGrid L/R 4dp each → 8dp inter-cover;
     * top 8dp + bottom 2dp → 10dp row gap. Fixed at all breakpoints.
     * Source: moonreader-unpacked/res/values/styles.xml `MyCardViewGrid`.
     */
    val gridGapHorizontal = 8.dp
    val gridGapVertical = 10.dp

    /** Expanded 下书架内容区限宽居中（§3.2）。 */
    val maxContentWidth = 1120.dp
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
