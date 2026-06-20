package dev.readflow.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 纸 + 墨 近单色调色（设计文档 §1.2）。UI 本身只有纸和墨，不设彩色强调；
 * cloth.* 是封面材质色，不进 UI 主题。日间是"白天的纸"，夜间是"暖台灯下的旧书纸"
 * （暖褐压暗，不做冷黑/藏青壳）。对比度 ink/paper ≈ 11.5:1，远超 AA。
 */
object ReadflowColors {
    // 日间：纸 + 墨
    val Paper = Color(0xFFEDE6D6)
    val PaperDeep = Color(0xFFE2D9C4)
    val PaperShadow = Color(0xFFC9BFA6)
    val Ink = Color(0xFF2A2620)
    val InkSoft = Color(0xFF6E665A)

    // 夜间：暖褐纸 + 暖白墨
    val PaperNight = Color(0xFF2A2620)
    val PaperNightDeep = Color(0xFF211E19)
    val InkNight = Color(0xFFD8CFBC)
    val InkNightSoft = Color(0xFF9A9082)

    // 封面材质色（仅 BookCover/书签用，不做 UI 强调）
    val ClothRed = Color(0xFF8A4A3C)
    val ClothBlue = Color(0xFF3E4A52)
    val ClothGreen = Color(0xFF4A5240)
}

private val LightColors = lightColorScheme(
    background = ReadflowColors.Paper,
    onBackground = ReadflowColors.Ink,
    surface = ReadflowColors.Paper,
    onSurface = ReadflowColors.Ink,
    surfaceVariant = ReadflowColors.PaperDeep,
    onSurfaceVariant = ReadflowColors.InkSoft,
    primary = ReadflowColors.Ink,
    onPrimary = ReadflowColors.Paper,
    outline = ReadflowColors.InkSoft,
)

private val DarkColors = darkColorScheme(
    background = ReadflowColors.PaperNight,
    onBackground = ReadflowColors.InkNight,
    surface = ReadflowColors.PaperNight,
    onSurface = ReadflowColors.InkNight,
    surfaceVariant = ReadflowColors.PaperNightDeep,
    onSurfaceVariant = ReadflowColors.InkNightSoft,
    primary = ReadflowColors.InkNight,
    onPrimary = ReadflowColors.PaperNight,
    outline = ReadflowColors.InkNightSoft,
)

private val SepiaColors = lightColorScheme(
    background = Color(0xFFF4EEDD),
    onBackground = Color(0xFF3A2E1A),
    surface = Color(0xFFF4EEDD),
    onSurface = Color(0xFF3A2E1A),
    surfaceVariant = Color(0xFFEBE0CA),
    onSurfaceVariant = Color(0xFF6B5A3E),
    primary = Color(0xFF3A2E1A),
    onPrimary = Color(0xFFF4EEDD),
    outline = Color(0xFF6B5A3E),
)

@Composable
fun ReadflowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    sepiaTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when {
            sepiaTheme -> SepiaColors
            darkTheme -> DarkColors
            else -> LightColors
        },
        typography = ReadflowTypography,
        content = content,
    )
}
