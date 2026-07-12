package dev.readflow.core.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Editorial display type paired with a compact sans-serif UI scale. */
object ReadflowType {
    val Serif = FontFamily.Serif
    val Sans = FontFamily.SansSerif

    val display = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    )

    val title = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    )

    val bookTitle = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    val meta = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )

    val sectionLabel = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    val ui = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    val body = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    )
}

internal val ReadflowTypography = Typography(
    displaySmall = ReadflowType.display,
    headlineMedium = ReadflowType.display,
    titleLarge = ReadflowType.title,
    titleMedium = ReadflowType.sectionLabel,
    titleSmall = ReadflowType.ui,
    bodyLarge = ReadflowType.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = ReadflowType.body,
    bodySmall = ReadflowType.meta,
    labelLarge = ReadflowType.ui,
    labelMedium = ReadflowType.meta.copy(fontWeight = FontWeight.Medium),
)
