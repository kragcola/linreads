package dev.readflow.render.epub

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

internal const val EPUB_MAX_PACKAGE_ENTRY_BYTES = 512 * 1024
internal const val EPUB_MAX_TOC_ENTRY_BYTES = 512 * 1024
internal const val EPUB_MAX_SPINE_ENTRY_BYTES = 2 * 1024 * 1024
internal const val EPUB_MAX_STYLESHEET_ENTRY_BYTES = 1024 * 1024
internal const val EPUB_MAX_ZIP_ENTRIES = 10_000
internal const val EPUB_MAX_DOM_DEPTH = 96

internal fun epubSafeZipPath(path: String): String? {
    val trimmed = path.trim().trimStart('/')
    if (trimmed.isEmpty() || trimmed.indexOf('\u0000') >= 0 || trimmed.indexOf('\\') >= 0) return null
    val segments = mutableListOf<String>()
    trimmed.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (segments.isEmpty()) return null
                segments.removeAt(segments.lastIndex)
            }
            else -> segments += segment
        }
    }
    return segments.joinToString("/").takeIf { it.isNotEmpty() }
}

internal fun epubSafeResolvePath(baseDir: String, href: String): String? {
    val hrefPath = epubHrefPath(href)
    val rawPath = when {
        hrefPath.isEmpty() -> baseDir
        hrefPath.startsWith("/") -> hrefPath.trimStart('/')
        baseDir.isEmpty() -> hrefPath
        else -> "$baseDir/$hrefPath"
    }
    return epubSafeZipPath(rawPath)
}

internal fun readEpubZipText(
    zip: ZipFile,
    path: String,
    maxBytes: Int,
    sanitizeXml: Boolean = true,
): String? {
    val bytes = readEpubZipBytes(zip, path, maxBytes) ?: return null
    val text = bytes.toString(Charsets.UTF_8)
    return if (sanitizeXml) sanitizeEpubXml(text) else text
}

/** Bounded zip entry bytes for offline assets (styles, fonts). Path is sanitized. */
internal fun readEpubZipBytes(
    zip: ZipFile,
    path: String,
    maxBytes: Int,
): ByteArray? {
    val safePath = epubSafeZipPath(path) ?: return null
    val entry = zip.getEntry(safePath) ?: return null
    if (entry.isDirectory || entry.size > maxBytes) return null
    return zip.getInputStream(entry).use { input -> input.readBoundedBytes(maxBytes) }
}

internal fun sanitizeEpubXml(text: String): String? {
    if (ENTITY_DECLARATION.containsMatchIn(text)) return null
    return DOCTYPE_DECLARATION.replace(text, "")
}

internal inline fun <T> epubParserGuard(defaultValue: T, block: () -> T): T =
    try {
        block()
    } catch (_: StackOverflowError) {
        defaultValue
    } catch (_: OutOfMemoryError) {
        defaultValue
    } catch (_: RuntimeException) {
        defaultValue
    } catch (_: Exception) {
        defaultValue
    }

private fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray? {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private val ENTITY_DECLARATION = Regex("<!\\s*ENTITY\\b", RegexOption.IGNORE_CASE)
private val DOCTYPE_DECLARATION = Regex(
    "<!\\s*DOCTYPE\\b[^>]*(\\[[\\s\\S]*?]\\s*)?>",
    RegexOption.IGNORE_CASE,
)
