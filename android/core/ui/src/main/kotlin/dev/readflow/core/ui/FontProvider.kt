package dev.readflow.core.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置字体统一加载入口。
 * 思源宋体打包在 assets/fonts/ 下；文件缺失时回退系统 Serif，保证恒可运行。
 */
object FontProvider {

    private const val SOURCE_HAN_SERIF_ASSET = "fonts/SourceHanSerifCN-Regular.otf"

    @Volatile
    private var cachedSerif: Typeface? = null

    private val customCache = ConcurrentHashMap<String, Typeface>()

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

    /** filesDir/fonts/ 下的自定义字体目录。 */
    fun customFontsDir(context: Context): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    /**
     * 清洗为安全 basename：剥离任何路径分隔符 / 父目录引用，防路径穿越。
     * 非法（空 / 含分隔符 / "."/".." / 非 ttf,otf）返回 null。
     */
    fun sanitizeFontFileName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        if (base.contains('/') || base.contains('\\')) return null
        if (base.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return null
        return base
    }

    /** 按 fontId 解析 Typeface（View/Paint 路径）。任何失败回退系统 Serif。 */
    fun typefaceFor(context: Context, fontId: String): Typeface =
        when {
            fontId == "system" -> Typeface.SERIF
            fontId == "source_han" -> sourceHanSerif(context)
            fontId.startsWith("custom:") -> customTypeface(context, fontId.removePrefix("custom:"))
            else -> sourceHanSerif(context)
        }

    private fun customTypeface(context: Context, raw: String): Typeface {
        val name = sanitizeFontFileName(raw) ?: return Typeface.SERIF
        return customCache.getOrPut(name) {
            runCatching {
                val dir = customFontsDir(context)
                val file = File(dir, name)
                // 防穿越：解析后的真实路径必须仍在 fonts 目录内
                if (file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                    Typeface.createFromFile(file)
                } else {
                    null
                }
            }.getOrNull() ?: Typeface.SERIF
        }
    }

    /** 列出已导入的自定义字体文件名。 */
    fun listCustomFonts(context: Context): List<String> =
        customFontsDir(context).listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }
            ?.map { it.name }?.sorted().orEmpty()

    /** Compose FontFamily 版本。 */
    fun fontFamilyFor(context: Context, fontId: String): FontFamily =
        runCatching { FontFamily(ComposeTypeface(typefaceFor(context, fontId))) }
            .getOrDefault(FontFamily.Serif)
}
