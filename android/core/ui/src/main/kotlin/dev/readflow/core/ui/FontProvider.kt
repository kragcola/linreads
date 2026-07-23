package dev.readflow.core.ui

import android.content.Context
import android.net.Uri
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import dev.readflow.core.model.FontChoice
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** 系统字体与用户导入字体的统一目录和加载入口。 */
object FontProvider {

    private const val MAX_IMPORTED_FONT_BYTES = 64L * 1024L * 1024L
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

    /** 用户侧字体名称：不暴露 font id，也不要求用户理解文件扩展名。 */
    fun displayName(choice: FontChoice): String = when (choice) {
        FontChoice.System -> "系统衬线"
        FontChoice.SystemSans -> "系统无衬线"
        FontChoice.SystemMonospace -> "系统等宽"
        is FontChoice.Custom -> choice.fileName
            .substringBeforeLast('.', choice.fileName)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "已导入字体" }
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
        typefaceForOrNull(context, fontId) ?: Typeface.SERIF

    /**
     * Resolves a font without hiding a missing imported file. EPUB CSS resolution uses the
     * nullable result to continue to an embedded face or the reader default.
     */
    fun typefaceForOrNull(context: Context, fontId: String): Typeface? =
        when (val choice = FontChoice.parse(fontId)) {
            FontChoice.System -> Typeface.SERIF
            FontChoice.SystemSans -> Typeface.SANS_SERIF
            FontChoice.SystemMonospace -> Typeface.MONOSPACE
            is FontChoice.Custom -> customTypefaceOrNull(context, choice.fileName)
        }

    private fun customTypefaceOrNull(context: Context, raw: String): Typeface? {
        val name = sanitizeFontFileName(raw) ?: return null
        customCache[name]?.let { return it }
        val loaded = runCatching {
            val dir = customFontsDir(context)
            val file = File(dir, name)
            // 防穿越：解析后的真实路径必须仍在 fonts 目录内
            if (file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                Typeface.createFromFile(file)
            } else {
                null
            }
        }.getOrNull() ?: return null
        return customCache.putIfAbsent(name, loaded) ?: loaded
    }

    /** 列出已导入的自定义字体文件名。 */
    fun listCustomFonts(context: Context): List<String> =
        customFontsDir(context).listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }
            ?.map { it.name }?.sorted().orEmpty()

    /**
     * Copies a user-selected TTF/OTF into the private font directory and validates it before
     * making it visible. The caller should run this on Dispatchers.IO.
     *
     * The returned choice uses the stored file name for backward-compatible IDs; the UI uses
     * [displayName] so readers never need to type or know that ID.
     */
    fun importFont(context: Context, uri: Uri): Result<FontChoice.Custom> = runCatching {
        val resolver = context.contentResolver
        val rawName = resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment
        val safeName = sanitizeFontFileName(rawName.orEmpty())
            ?: throw IllegalArgumentException("请选择 TTF 或 OTF 字体文件")
        val dir = customFontsDir(context.applicationContext)
        val storedName = uniqueFontFileName(dir, safeName)
        val destination = File(dir, storedName)
        val staging = File.createTempFile("font-", ".part", dir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> copyFontWithLimit(input, output) }
            } ?: throw IllegalArgumentException("无法读取所选字体文件")
            require(staging.length() > 0L) { "字体文件为空" }
            val validatedTypeface = runCatching { Typeface.createFromFile(staging) }
                .getOrElse { throw IllegalArgumentException("所选文件不是可用字体") }
            try {
                Files.move(
                    staging.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staging.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            customCache[storedName] = validatedTypeface
            FontChoice.Custom(storedName)
        } finally {
            staging.delete()
        }
    }

    private fun uniqueFontFileName(dir: File, requested: String): String {
        if (!File(dir, requested).exists()) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot + 1).lowercase() else "ttf"
        for (index in 2..999) {
            val candidate = "$stem ($index).$extension"
            if (!File(dir, candidate).exists()) return candidate
        }
        return "$stem-${System.currentTimeMillis()}.$extension"
    }

    internal fun copyFontWithLimit(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_IMPORTED_FONT_BYTES,
    ): Long {
        require(maxBytes > 0L)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            copied += count
            require(copied <= maxBytes) { "字体文件过大，最大支持 64 MB" }
            output.write(buffer, 0, count)
        }
        return copied
    }

    /** Compose FontFamily 版本。 */
    fun fontFamilyFor(context: Context, fontId: String): FontFamily =
        runCatching { FontFamily(ComposeTypeface(typefaceFor(context, fontId))) }
            .getOrDefault(FontFamily.Serif)
}
