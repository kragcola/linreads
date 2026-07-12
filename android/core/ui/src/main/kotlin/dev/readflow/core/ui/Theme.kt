package dev.readflow.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Contemporary Editorial palette: warm neutral surfaces, dark ink, and one restrained evergreen
 * accent. Reading presets remain independently selectable; these colours define app chrome.
 */
object ReadflowColors {
    val Paper = Color(0xFFF7F5EF)
    val PaperDeep = Color(0xFFECEFEA)
    val PaperShadow = Color(0xFFD7DDD8)
    val Surface = Color(0xFFFFFEFA)
    val Ink = Color(0xFF202522)
    val InkSoft = Color(0xFF626A65)
    val Evergreen = Color(0xFF176858)
    val EvergreenSoft = Color(0xFFD7EAE4)

    val PaperNight = Color(0xFF171B18)
    val PaperNightDeep = Color(0xFF242B27)
    val SurfaceNight = Color(0xFF1D221F)
    val InkNight = Color(0xFFE5E9E4)
    val InkNightSoft = Color(0xFFAEB7B1)
    val EvergreenNight = Color(0xFF83CDBA)

    val ClothRed = Color(0xFF7A403B)
    val ClothBlue = Color(0xFF354A57)
    val ClothGreen = Color(0xFF3E584A)
}

private val LightColors = lightColorScheme(
    background = ReadflowColors.Paper,
    onBackground = ReadflowColors.Ink,
    surface = ReadflowColors.Surface,
    onSurface = ReadflowColors.Ink,
    surfaceVariant = ReadflowColors.PaperDeep,
    onSurfaceVariant = ReadflowColors.InkSoft,
    surfaceContainerLowest = ReadflowColors.Surface,
    surfaceContainerLow = Color(0xFFF3F3EE),
    surfaceContainer = Color(0xFFEEEFEA),
    surfaceContainerHigh = Color(0xFFE7EAE5),
    surfaceContainerHighest = Color(0xFFDEE3DE),
    primary = ReadflowColors.Evergreen,
    onPrimary = Color.White,
    primaryContainer = ReadflowColors.EvergreenSoft,
    onPrimaryContainer = Color(0xFF0A4539),
    secondary = Color(0xFF53645E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5ECE8),
    onSecondaryContainer = Color(0xFF34443E),
    tertiary = Color(0xFF5D6650),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5EBD8),
    onTertiaryContainer = Color(0xFF3A432F),
    inverseSurface = Color(0xFF2C322E),
    inverseOnSurface = Color(0xFFF0F4F0),
    inversePrimary = ReadflowColors.EvergreenNight,
    outline = Color(0xFF7A837D),
    outlineVariant = Color(0xFFD6DBD7),
)

private val DarkColors = darkColorScheme(
    background = ReadflowColors.PaperNight,
    onBackground = ReadflowColors.InkNight,
    surface = ReadflowColors.SurfaceNight,
    onSurface = ReadflowColors.InkNight,
    surfaceVariant = ReadflowColors.PaperNightDeep,
    onSurfaceVariant = ReadflowColors.InkNightSoft,
    surfaceContainerLowest = Color(0xFF111512),
    surfaceContainerLow = Color(0xFF191E1B),
    surfaceContainer = Color(0xFF202622),
    surfaceContainerHigh = Color(0xFF282F2A),
    surfaceContainerHighest = Color(0xFF313934),
    primary = ReadflowColors.EvergreenNight,
    onPrimary = Color(0xFF083A31),
    primaryContainer = Color(0xFF164E43),
    onPrimaryContainer = Color(0xFFB8F0E1),
    secondary = Color(0xFFAFC9C0),
    onSecondary = Color(0xFF1B352E),
    secondaryContainer = Color(0xFF33463F),
    onSecondaryContainer = Color(0xFFD6EAE2),
    tertiary = Color(0xFFC1CAA9),
    onTertiary = Color(0xFF2C351F),
    tertiaryContainer = Color(0xFF444D37),
    onTertiaryContainer = Color(0xFFE1E9CA),
    inverseSurface = Color(0xFFE2E8E3),
    inverseOnSurface = Color(0xFF29312C),
    inversePrimary = ReadflowColors.Evergreen,
    outline = Color(0xFF89938D),
    outlineVariant = Color(0xFF3D4741),
)

private val SepiaColors = lightColorScheme(
    background = Color(0xFFF4EEDD),
    onBackground = Color(0xFF3A2E1A),
    surface = Color(0xFFF4EEDD),
    onSurface = Color(0xFF3A2E1A),
    surfaceVariant = Color(0xFFEBE0CA),
    onSurfaceVariant = Color(0xFF6B5A3E),
    surfaceContainerLowest = Color(0xFFFFF9E9),
    surfaceContainerLow = Color(0xFFF8F1E0),
    surfaceContainer = Color(0xFFF1E8D6),
    surfaceContainerHigh = Color(0xFFEADFC9),
    surfaceContainerHighest = Color(0xFFE2D5BB),
    primary = Color(0xFF6D5B32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDBF),
    onPrimaryContainer = Color(0xFF3A2E1A),
    secondary = Color(0xFF6C6250),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E0D1),
    onSecondaryContainer = Color(0xFF443C2F),
    inverseSurface = Color(0xFF3D3322),
    inverseOnSurface = Color(0xFFFCF4E3),
    inversePrimary = Color(0xFFD7C28C),
    outline = Color(0xFF6B5A3E),
    outlineVariant = Color(0xFF8E7A58),
)

private val ReadflowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
        shapes = ReadflowShapes,
        content = content,
    )
}
