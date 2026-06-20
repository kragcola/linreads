package dev.readflow.core.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排印 tokens（设计文档 §1.3）。旧书气质 = 克制，不靠超大字号/特殊字重充设计感。
 * - 正文/书名：宋体（FontFamily.Serif 占位，思源宋体打包后替换），不戏剧化 type scale。
 * - UI 文字（按钮/标签/导航）：黑体（FontFamily.SansSerif），安静。
 * 思源宋体未打包前用系统 serif；待 res/font 落地后改 FontFamily(Font(R.font.source_han_serif))。
 */
object ReadflowType {
    val Serif = FontFamily.Serif
    val Sans = FontFamily.SansSerif

    /** 书名（书架卡片下方 / 书名页）：22sp Medium 宋体。 */
    val title = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    )

    /** 书架封面下方书名：紧凑宋体，最多 2 行截断。 */
    val bookTitle = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )

    /** 作者 / 次要元信息：淡墨宋体。 */
    val meta = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** 分组标题（"在读""书架"）：宋体，朴素。 */
    val sectionLabel = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    /** UI 文字（按钮/导航/标签）：黑体 14sp，安静。 */
    val ui = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

/** Bridge ReadflowType into a Material3 [Typography] for MaterialTheme defaults. */
internal val ReadflowTypography = Typography(
    titleLarge = ReadflowType.title,
    titleMedium = ReadflowType.sectionLabel,
    bodyMedium = ReadflowType.bookTitle,
    bodySmall = ReadflowType.meta,
    labelLarge = ReadflowType.ui,
)
