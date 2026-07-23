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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 系统字体与用户导入字体的统一目录和加载入口。 */
object FontProvider {

    private const val MAX_IMPORTED_FONT_BYTES = 64L * 1024L * 1024L
    private const val PENDING_DELETE_SUFFIX = ".readflow-delete"
    private val customCache = ConcurrentHashMap<String, Typeface>()
    private val fontStorageLock = Any()
    private val activeDeletionTombstones = mutableSetOf<String>()

    class ImportedFontDeletion internal constructor(
        internal val fileName: String,
        internal val original: File,
        internal val tombstone: File?,
    )

    /** Content identity is independent of the name supplied by a document provider. */
    internal fun findDuplicateFontFile(dir: File, candidate: File): File? {
        if (!candidate.isFile) return null
        val length = candidate.length()
        val candidateDigest = sha256(candidate)
        return dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in setOf("ttf", "otf") &&
                file.length() == length && file != candidate
        }
            ?.sortedBy(File::getName)
            ?.firstOrNull { existing -> sha256(existing).contentEquals(candidateDigest) }
    }

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
    fun customFontsDir(context: Context): File {
        val dir = File(context.filesDir, "fonts")
        synchronized(fontStorageLock) {
            dir.mkdirs()
            recoverInterruptedFontDeletions(dir)
        }
        return dir
    }

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

    private fun customTypefaceOrNull(context: Context, raw: String): Typeface? =
        synchronized(fontStorageLock) {
        val name = sanitizeFontFileName(raw) ?: return null
        customCache[name]?.let { return it }
        val dir = customFontsDir(context)
        val file = File(dir, name)
        val loaded = runCatching {
            // 防穿越：解析后的真实路径必须仍在 fonts 目录内
            if (file.isFile && file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                Typeface.createFromFile(file)
            } else null
        }.getOrNull() ?: return null
        return customCache.putIfAbsent(name, loaded) ?: loaded
    }

    /** 列出已导入的自定义字体文件名。 */
    fun listCustomFonts(context: Context): List<String> {
        val dir = customFontsDir(context)
        return synchronized(fontStorageLock) {
            dir.listFiles { file ->
                file.extension.lowercase() in setOf("ttf", "otf") &&
                    pendingDeletionFile(dir, file.name).canonicalPath !in activeDeletionTombstones
            }?.map { it.name }?.sorted().orEmpty()
        }
    }

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
        val staging = File.createTempFile("font-", ".part", dir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> copyFontWithLimit(input, output) }
            } ?: throw IllegalArgumentException("无法读取所选字体文件")
            require(staging.length() > 0L) { "字体文件为空" }
            val validatedTypeface = runCatching { Typeface.createFromFile(staging) }
                .getOrElse { throw IllegalArgumentException("所选文件不是可用字体") }
            val choice = synchronized(fontStorageLock) {
                val duplicate = findDuplicateFontFile(dir, staging)
                val storedName: String
                val storedTypeface: Typeface
                if (duplicate != null) {
                    storedName = duplicate.name
                    storedTypeface = customCache[storedName] ?: Typeface.createFromFile(duplicate)
                } else {
                    storedName = uniqueFontFileName(dir, safeName)
                    moveFontAtomically(staging, File(dir, storedName))
                    storedTypeface = validatedTypeface
                }
                customCache[storedName] = storedTypeface
                FontChoice.Custom(storedName)
            }
            choice
        } finally {
            staging.delete()
        }
    }

    /**
     * Atomically hides one font before durable settings cleanup. Interrupted stages are restored
     * the next time the font directory opens, so a process death cannot strand a referenced id.
     */
    fun stageImportedFontDeletion(
        context: Context,
        choice: FontChoice.Custom,
    ): Result<ImportedFontDeletion> =
        stageImportedFontDeletion(customFontsDir(context.applicationContext), choice)

    internal fun stageImportedFontDeletion(
        dir: File,
        choice: FontChoice.Custom,
    ): Result<ImportedFontDeletion> = runCatching {
        val name = sanitizeFontFileName(choice.fileName)
            ?: throw IllegalArgumentException("字体文件名无效")
        synchronized(fontStorageLock) {
            dir.mkdirs()
            recoverInterruptedFontDeletions(dir)
        }
        val file = File(dir, name)
        require(file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            "字体文件路径无效"
        }
        synchronized(fontStorageLock) {
            val tombstone = pendingDeletionFile(dir, name)
            require(!tombstone.exists()) { "字体正等待删除，请稍后重试" }
            if (!file.exists()) {
                customCache.remove(name)
                return@synchronized ImportedFontDeletion(name, file, tombstone = null)
            }
            moveFontAtomically(file, tombstone)
            activeDeletionTombstones += tombstone.canonicalPath
            customCache.remove(name)
            ImportedFontDeletion(name, file, tombstone)
        }
    }

    /** Finalizes a staged deletion after settings references were cleared successfully. */
    fun commitImportedFontDeletion(deletion: ImportedFontDeletion): Result<Unit> {
        return runCatching {
            synchronized(fontStorageLock) {
                val tombstone = deletion.tombstone ?: return@synchronized
                if (tombstone.exists() && !tombstone.delete()) {
                    throw IllegalStateException("无法删除字体文件")
                }
                activeDeletionTombstones -= tombstone.canonicalPath
                customCache.remove(deletion.fileName)
            }
        }
    }

    /** Restores a staged file when durable settings cleanup fails or the UI operation is cancelled. */
    fun rollbackImportedFontDeletion(deletion: ImportedFontDeletion): Result<Unit> = runCatching {
        synchronized(fontStorageLock) {
            val tombstone = deletion.tombstone ?: return@synchronized
            try {
                if (tombstone.exists()) {
                    if (deletion.original.exists()) {
                        if (!tombstone.delete()) throw IllegalStateException("无法恢复字体文件")
                    } else {
                        moveFontAtomically(tombstone, deletion.original)
                    }
                }
            } finally {
                activeDeletionTombstones -= tombstone.canonicalPath
                customCache.remove(deletion.fileName)
            }
        }
    }

    /** Immediate compatibility entry; coordinated reader deletion uses stage/commit explicitly. */
    fun deleteImportedFont(context: Context, choice: FontChoice.Custom): Result<Unit> {
        val staged = stageImportedFontDeletion(context, choice)
        val deletion = staged.getOrElse { return Result.failure(it) }
        return commitImportedFontDeletion(deletion)
    }

    /**
     * Completes a deletion recorded durably by settings without restoring its tombstone first.
     * Failures keep the tombstone hidden for the rest of this process and are retried next launch.
     */
    fun finalizePendingImportedFontDeletion(
        context: Context,
        choice: FontChoice.Custom,
    ): Result<Unit> = finalizePendingImportedFontDeletion(
        File(context.applicationContext.filesDir, "fonts"),
        choice,
    )

    internal fun finalizePendingImportedFontDeletion(
        dir: File,
        choice: FontChoice.Custom,
    ): Result<Unit> = runCatching {
        val name = sanitizeFontFileName(choice.fileName)
            ?: throw IllegalArgumentException("字体文件名无效")
        synchronized(fontStorageLock) {
            dir.mkdirs()
            val original = File(dir, name)
            require(original.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                "字体文件路径无效"
            }
            val tombstone = pendingDeletionFile(dir, name)
            activeDeletionTombstones += tombstone.canonicalPath
            if (original.exists()) {
                if (tombstone.exists()) {
                    if (!original.delete()) throw IllegalStateException("无法隐藏待删除字体")
                } else {
                    moveFontAtomically(original, tombstone)
                }
            }
            if (tombstone.exists() && !tombstone.delete()) {
                throw IllegalStateException("无法删除字体文件")
            }
            activeDeletionTombstones -= tombstone.canonicalPath
            customCache.remove(name)
        }
    }

    /** Restores tombstones that have no durable pending-deletion ledger entry. */
    fun recoverInterruptedFontDeletions(context: Context): Result<Unit> = runCatching {
        val dir = File(context.applicationContext.filesDir, "fonts")
        synchronized(fontStorageLock) {
            dir.mkdirs()
            recoverInterruptedFontDeletions(dir)
        }
    }

    private fun pendingDeletionFile(dir: File, name: String): File =
        File(dir, ".$name$PENDING_DELETE_SUFFIX")

    internal fun recoverInterruptedFontDeletions(dir: File) {
        dir.listFiles { file -> file.isFile && file.name.endsWith(PENDING_DELETE_SUFFIX) }
            ?.forEach { tombstone ->
                if (tombstone.canonicalPath in activeDeletionTombstones) return@forEach
                val originalName = tombstone.name
                    .removeSuffix(PENDING_DELETE_SUFFIX)
                    .removePrefix(".")
                val safeName = sanitizeFontFileName(originalName)
                if (safeName == null) {
                    tombstone.delete()
                    return@forEach
                }
                val original = File(dir, safeName)
                if (original.exists()) {
                    tombstone.delete()
                } else {
                    moveFontAtomically(tombstone, original)
                }
                customCache.remove(safeName)
            }
    }

    private fun moveFontAtomically(staging: File, destination: File) {
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
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun uniqueFontFileName(dir: File, requested: String): String {
        if (!fontFileNameIsReserved(dir, requested)) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot + 1).lowercase() else "ttf"
        for (index in 2..999) {
            val candidate = "$stem ($index).$extension"
            if (!fontFileNameIsReserved(dir, candidate)) return candidate
        }
        return "$stem-${System.currentTimeMillis()}.$extension"
    }

    private fun fontFileNameIsReserved(dir: File, name: String): Boolean =
        File(dir, name).exists() || pendingDeletionFile(dir, name).exists()

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
