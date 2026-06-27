package dev.readflow.core.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/**
 * 内置字体统一加载入口。
 * 思源宋体打包在 assets/fonts/ 下；文件缺失时回退系统 Serif，保证恒可运行。
 */
object FontProvider {

    private const val SOURCE_HAN_SERIF_ASSET = "fonts/SourceHanSerifCN-Regular.otf"

    @Volatile
    private var cachedSerif: Typeface? = null

    /** 思源宋体 Typeface（用于 View/Paint 路径）。缺失时回退 Typeface.SERIF。 */
    fun sourceHanSerif(context: Context): Typeface {
        cachedSerif?.let { return it }
        val tf = runCatching {
            Typeface.createFromAsset(context.assets, SOURCE_HAN_SERIF_ASSET)
        }.getOrNull() ?: Typeface.SERIF
        cachedSerif = tf
        return tf
    }

    /** 思源宋体 Compose FontFamily（用于 EPUB Compose 路径）。缺失时回退 FontFamily.Serif。 */
    fun sourceHanSerifFamily(context: Context): FontFamily =
        runCatching { FontFamily(ComposeTypeface(sourceHanSerif(context))) }
            .getOrDefault(FontFamily.Serif)
}
