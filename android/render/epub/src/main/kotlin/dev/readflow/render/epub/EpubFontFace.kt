package dev.readflow.render.epub

import android.graphics.Typeface
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One EPUB `@font-face` declaration after path normalization.
 * [family] is the CSS family name (unquoted, trimmed). [srcPath] is the zip-relative font path.
 */
internal data class EpubFontFace(
    val family: String,
    val srcPath: String,
    val weight: Int? = null,
    val style: String? = null,
)

/**
 * Book-scoped map of CSS family → zip-relative font path.
 * First declared face for a family wins (deterministic for a given CSS load order).
 */
internal data class EpubBookFontMap(
    val facesByFamily: Map<String, EpubFontFace>,
) {
    fun faceForFamily(cssFontFamily: String?): EpubFontFace? {
        if (cssFontFamily.isNullOrBlank()) return null
        for (token in splitCssFontFamilyList(cssFontFamily)) {
            val key = normalizeFontFamilyKey(token) ?: continue
            if (key in GENERIC_FONT_FAMILIES) continue
            facesByFamily[key]?.let { return it }
        }
        return null
    }

    companion object {
        val EMPTY = EpubBookFontMap(emptyMap())
    }
}

/** Generic CSS families — never treated as embedded book faces. */
internal val GENERIC_FONT_FAMILIES: Set<String> = setOf(
    "serif",
    "sans-serif",
    "monospace",
    "cursive",
    "fantasy",
    "system-ui",
    "ui-serif",
    "ui-sans-serif",
    "ui-monospace",
    "ui-rounded",
    "emoji",
    "math",
    "fangsong",
)

/**
 * Parses `@font-face { ... }` blocks from a CSS string. Only `url(...)` local sources are kept;
 * remote `http(s)` sources and `local(...)` names are ignored for security and offline determinism.
 */
internal fun parseEpubFontFaces(
    css: String,
    resourceBaseDir: String,
    maxFaces: Int = MAX_FONT_FACES,
): List<EpubFontFace> {
    if (css.isBlank() || maxFaces <= 0) return emptyList()
    val cleaned = FONT_FACE_COMMENTS.replace(css, "")
    val out = ArrayList<EpubFontFace>(minOf(8, maxFaces))
    for (match in FONT_FACE_BLOCK.findAll(cleaned)) {
        if (out.size >= maxFaces) break
        val body = match.groupValues[1]
        val decls = parseFontFaceDeclarations(body)
        val familyRaw = decls["font-family"] ?: continue
        val family = normalizeFontFamilyKey(familyRaw) ?: continue
        if (family in GENERIC_FONT_FAMILIES) continue
        val src = decls["src"] ?: continue
        val href = firstLocalFontUrl(src) ?: continue
        val path = epubSafeResolvePath(resourceBaseDir, href) ?: continue
        if (!path.substringAfterLast('.', "").lowercase().let { it in FONT_EXTENSIONS }) continue
        out += EpubFontFace(
            family = family,
            srcPath = path,
            weight = decls["font-weight"]?.toIntOrNull(),
            style = decls["font-style"]?.lowercase(),
        )
    }
    return out
}

/**
 * Builds a deterministic book map: first face per normalized family wins.
 */
internal fun epubBookFontMapFromFaces(faces: List<EpubFontFace>): EpubBookFontMap {
    if (faces.isEmpty()) return EpubBookFontMap.EMPTY
    val map = LinkedHashMap<String, EpubFontFace>()
    for (face in faces) {
        val key = normalizeFontFamilyKey(face.family) ?: continue
        if (key !in map) map[key] = face
    }
    return EpubBookFontMap(map)
}

/**
 * Resolves a CSS `font-family` list against [bookFonts]. Returns the book face when valid;
 * otherwise null so the caller can fall back to the user-selected / system typeface.
 */
internal fun resolveCssFontFamily(
    cssFontFamily: String?,
    bookFonts: EpubBookFontMap,
): EpubFontFace? = bookFonts.faceForFamily(cssFontFamily)

/**
 * Resolves a CSS `font-family` list against layered replacements and embedded faces.
 *
 * Priority per token: [bookReplacements] > [globalReplacements] > embedded [bookFonts] >
 * (null → caller uses user/system default). Missing replacement typefaces fall through
 * safely without throwing.
 */
internal fun resolveEpubCssTypeface(
    cssFontFamily: String?,
    bookReplacements: Map<String, String>,
    globalReplacements: Map<String, String>,
    bookFonts: EpubBookFontMap,
    replacementResolver: (String) -> Typeface?,
    embeddedResolver: (EpubFontFace) -> Typeface?,
): Typeface? {
    val layered = mergeReplacementLayers(bookReplacements, globalReplacements)
    return resolveEpubCssTypeface(
        cssFontFamily = cssFontFamily,
        replacements = layered,
        bookFonts = bookFonts,
        replacementResolver = replacementResolver,
        embeddedResolver = embeddedResolver,
    )
}

/**
 * Resolves a CSS `font-family` list against a pre-merged [replacements] map (book already
 * folded over global) and [bookFonts]. Returns the book face when valid;
 * otherwise null so the caller can fall back to the user-selected / system typeface.
 *
 * Priority: exact replacement → Moon-style base replacement → embedded face.
 */
internal fun resolveEpubCssTypeface(
    cssFontFamily: String?,
    replacements: Map<String, String>,
    bookFonts: EpubBookFontMap,
    replacementResolver: (String) -> Typeface?,
    embeddedResolver: (EpubFontFace) -> Typeface?,
): Typeface? {
    for (token in splitCssFontFamilyList(cssFontFamily.orEmpty())) {
        val key = normalizeFontFamilyKey(token) ?: continue
        if (key in GENERIC_FONT_FAMILIES) continue
        // Exact replacement first; if mapped but resolver returns null, still try Moon base key.
        replacements[key]?.let { replacementId ->
            replacementResolver(replacementId)?.let { return it }
        }
        // MoonReader-compatible base key for replacement lookup only (never for embedded faces).
        val baseKey = moonStyleBaseFontReplacementKey(key)
        if (baseKey != null && baseKey != key && baseKey.isNotEmpty()) {
            replacements[baseKey]?.let { replacementId ->
                replacementResolver(replacementId)?.let { return it }
            }
        }
        bookFonts.facesByFamily[key]?.let { face ->
            embeddedResolver(face)?.let { return it }
        }
    }
    return null
}

/** Book map entries override global for the same canonical family key. */
internal fun mergeReplacementLayers(
    bookReplacements: Map<String, String>,
    globalReplacements: Map<String, String>,
): Map<String, String> {
    if (bookReplacements.isEmpty()) return globalReplacements
    if (globalReplacements.isEmpty()) return bookReplacements
    return globalReplacements + bookReplacements
}

/**
 * MoonReader-style base family key for replacement map lookup only.
 * Matches A.getFontNameWithoutStyle after T.getOnlyFilename: strip trailing extension-like
 * segment, then strip at first -regular / -bold / -italic (else-if order).
 * Returns a non-null key when extension stripping and/or style-suffix stripping changes the
 * input (including extension-only cases such as `story.ttf` → `story`). Returns null only when
 * the key is unchanged after both steps (caller should skip duplicate lookup).
 */
internal fun moonStyleBaseFontReplacementKey(normalizedKey: String): String? {
    if (normalizedKey.isEmpty()) return null
    // T.getOnlyFilename: drop final ".ext" segment when present (filename semantics on the token).
    var base = normalizedKey
    val dot = base.lastIndexOf('.')
    if (dot > 0) {
        base = base.substring(0, dot)
    }
    // A.getFontNameWithoutStyle else-if order: -regular, else -bold, else -italic.
    val styleMarkers = listOf("-regular", "-bold", "-italic")
    for (marker in styleMarkers) {
        val idx = base.indexOf(marker)
        if (idx >= 0) {
            base = base.substring(0, idx)
            break
        }
    }
    return base.takeIf { it.isNotEmpty() && it != normalizedKey }
}

/**
 * Loads and caches Typefaces for book-scoped faces. [bytesForPath] must only serve in-zip paths.
 * Invalid / missing bytes fall back to null (caller uses user/system font).
 *
 * Misses are memoized so a missing entry is not re-read from the zip on every span paint.
 * ConcurrentHashMap cannot store null, so misses use a sentinel boolean set.
 */
internal class EpubBookFontTypefaceCache(
    private val cacheRoot: File?,
    private val bookCacheKey: String,
    private val bytesForPath: (String) -> ByteArray?,
    private val typefaceFromFile: (File) -> Typeface? = { file ->
        runCatching { Typeface.createFromFile(file) }.getOrNull()
    },
) {
    private val cache = ConcurrentHashMap<String, Typeface>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    constructor(bytesForPath: (String) -> ByteArray?) : this(
        cacheRoot = null,
        bookCacheKey = "memory-only",
        bytesForPath = bytesForPath,
    )

    fun typefaceFor(face: EpubFontFace): Typeface? {
        val key = face.srcPath
        return cache[key]
    }

    /** Loads one face outside measure/draw; callers must invoke this from an IO dispatcher. */
    suspend fun prewarm(face: EpubFontFace): Boolean =
        withContext(Dispatchers.IO) { prewarmBlocking(face) }

    @Synchronized
    private fun prewarmBlocking(face: EpubFontFace): Boolean {
        val key = face.srcPath
        if (cache.containsKey(key) || key in misses) return false
        val fontFile = cachedFontFile(face)
        val loaded = fontFile?.let { file -> runCatching { typefaceFromFile(file) }.getOrNull() }
        if (loaded == null) {
            fontFile?.delete()
            misses.add(key)
            return false
        }
        cache[key] = loaded
        return true
    }

    suspend fun prewarm(faces: Collection<EpubFontFace>): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        faces.asSequence()
            .distinctBy { it.srcPath }
            .take(MAX_FONT_FACES)
            .forEach { face -> changed = prewarmBlocking(face) || changed }
        changed
    }

    fun clear() {
        cache.clear()
        misses.clear()
    }

    private fun cachedFontFile(face: EpubFontFace): File? {
        val root = cacheRoot?.canonicalFile ?: return null
        val safePath = epubSafeResolvePath("", face.srcPath) ?: return null
        val extension = safePath.substringAfterLast('.', "").lowercase()
        if (extension !in FONT_EXTENSIONS) return null
        val bookDir = File(root, sha256Hex(bookCacheKey)).canonicalFile
        if (!bookDir.isWithin(root)) return null
        if (!bookDir.exists() && !bookDir.mkdirs()) return null
        val target = File(bookDir, "${sha256Hex(safePath)}.$extension").canonicalFile
        if (target.parentFile != bookDir || !target.isWithin(root)) return null
        if (target.isFile && target.length() in 1..MAX_FONT_BYTES.toLong()) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val bytes = bytesForPath(safePath) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_FONT_BYTES) return null
        val temp = File(bookDir, ".${target.name}.${System.nanoTime().toString(16)}.tmp").canonicalFile
        if (temp.parentFile != bookDir || !temp.isWithin(root)) return null
        return runCatching {
            temp.outputStream().buffered().use { output -> output.write(bytes) }
            if (target.exists() && !target.delete()) return@runCatching null
            if (!temp.renameTo(target)) return@runCatching null
            target.setLastModified(System.currentTimeMillis())
            pruneEpubFontDiskCache(root, target)
            target
        }.getOrNull().also { temp.delete() }
    }
}

internal fun epubFontBookCacheKey(file: File): String = buildString {
    append(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
    append(':')
    append(file.length())
    append(':')
    append(file.lastModified())
}

private fun File.isWithin(root: File): Boolean {
    val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
    return path == root.path || path.startsWith(rootPath)
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

@Synchronized
private fun pruneEpubFontDiskCache(root: File, preserve: File) {
    val files = root.walkTopDown().filter { it.isFile && it != preserve }.toList()
    var total = files.sumOf { it.length() } + preserve.length()
    if (total <= MAX_FONT_DISK_CACHE_BYTES) return
    files.sortedBy { it.lastModified() }.forEach { file ->
        if (total <= MAX_FONT_DISK_CACHE_BYTES) return@forEach
        val length = file.length()
        if (file.delete()) total -= length
    }
    root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { directory ->
        if (directory.list().isNullOrEmpty()) directory.delete()
    }
}

internal fun normalizeFontFamilyKey(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    if (
        (s.startsWith('"') && s.endsWith('"') && s.length >= 2) ||
        (s.startsWith('\'') && s.endsWith('\'') && s.length >= 2)
    ) {
        s = s.substring(1, s.length - 1).trim()
    }
    if (s.isEmpty()) return null
    // Collapse internal whitespace to a single space (Settings / DataStore parity).
    s = WHITESPACE_RUN.replace(s, " ")
    if (s.isEmpty()) return null
    return s.lowercase()
}

internal fun splitCssFontFamilyList(raw: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var quote: Char? = null
    raw.forEachIndexed { index, char ->
        when {
            quote != null && char == quote -> quote = null
            quote != null -> Unit
            char == '\'' || char == '"' -> quote = char
            char == ',' -> {
                result += raw.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    result += raw.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}

private fun parseFontFaceDeclarations(body: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (part in body.split(';')) {
        val colon = part.indexOf(':')
        if (colon <= 0) continue
        val name = part.substring(0, colon).trim().lowercase()
        val value = part.substring(colon + 1).trim()
        if (name.isEmpty() || value.isEmpty()) continue
        map[name] = value
    }
    return map
}

private fun firstLocalFontUrl(src: String): String? {
    for (match in FONT_URL.findAll(src)) {
        val href = match.groupValues[1].trim()
        if (href.isEmpty()) continue
        val lower = href.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) {
            continue
        }
        return href
    }
    return null
}

private const val MAX_FONT_FACES = 32
private const val MAX_FONT_BYTES = 8 * 1024 * 1024
private const val MAX_FONT_DISK_CACHE_BYTES = 96L * 1024L * 1024L
// Android Typeface.createFromFile loads TTF/OTF/TTC only. WOFF/WOFF2 require a decoder dependency
// that is intentionally out of scope; advertise only formats the platform can load.
private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
private val FONT_FACE_COMMENTS = Regex("/\\*[\\s\\S]*?\\*/")
private val WHITESPACE_RUN = Regex("\\s+")
private val FONT_FACE_BLOCK = Regex(
    "@font-face\\s*\\{([^{}]*)\\}",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val FONT_URL = Regex(
    "url\\(\\s*['\"]?([^'\")\\s]+)['\"]?\\s*\\)",
    RegexOption.IGNORE_CASE,
)
