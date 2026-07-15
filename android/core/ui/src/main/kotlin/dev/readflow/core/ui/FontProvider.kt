package dev.readflow.core.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import dev.readflow.core.model.FontChoice
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 系统字体与用户导入字体的统一目录和加载入口。 */
object FontProvider {

    private val customCache = ConcurrentHashMap<String, Typeface>()

    val builtInChoices: List<FontChoice> = listOf(
        FontChoice.System,
        FontChoice.SystemSans,
        FontChoice.SystemMonospace,
    )

    fun availableChoices(customFontNames: List<String>): List<FontChoice> =
        builtInChoices + customFontNames.map(FontChoice::Custom)

    fun label(choice: FontChoice): String = when (choice) {
        FontChoice.System -> "系统衬线"
        FontChoice.SystemSans -> "系统无衬线"
        FontChoice.SystemMonospace -> "系统等宽"
        is FontChoice.Custom -> choice.fileName
    }

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

    /** 按 fontId 解析 Typeface（View/Paint 路径）。旧 ID 和失败项都安全回退系统衬线。 */
    fun typefaceFor(context: Context, fontId: String): Typeface =
        when (val choice = FontChoice.parse(fontId)) {
            FontChoice.System -> Typeface.SERIF
            FontChoice.SystemSans -> Typeface.SANS_SERIF
            FontChoice.SystemMonospace -> Typeface.MONOSPACE
            is FontChoice.Custom -> customTypeface(context, choice.fileName)
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
