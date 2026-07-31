package dev.readflow.core.model

/**
 * Formats that have a registered, base-install local reader.
 *
 * Every local admission path must use this contract so importing/scanning a file cannot create a
 * library item that deterministically fails with `NoEngineException`.
 */
object LocalReadingCapabilities {
    val formats: Set<BookFormat> = setOf(
        BookFormat.EPUB,
        BookFormat.PDF,
        BookFormat.TXT,
        BookFormat.MD,
        BookFormat.CBZ,
    )

    private val extensions: Map<String, BookFormat> = mapOf(
        "epub" to BookFormat.EPUB,
        "pdf" to BookFormat.PDF,
        "txt" to BookFormat.TXT,
        "md" to BookFormat.MD,
        "markdown" to BookFormat.MD,
        "cbz" to BookFormat.CBZ,
    )

    private val mimeTypes: Map<String, BookFormat> = mapOf(
        "application/epub+zip" to BookFormat.EPUB,
        "application/pdf" to BookFormat.PDF,
        "text/plain" to BookFormat.TXT,
        "text/markdown" to BookFormat.MD,
        "text/x-markdown" to BookFormat.MD,
        "application/vnd.comicbook+zip" to BookFormat.CBZ,
        "application/x-cbz" to BookFormat.CBZ,
        "application/cbz" to BookFormat.CBZ,
    )

    val pickerMimeTypes: Array<String>
        get() = mimeTypes.keys.toTypedArray()

    fun formatForExtension(extension: String?): BookFormat? =
        extensions[extension.orEmpty().trim().removePrefix(".").lowercase()]

    fun formatForMimeType(mimeType: String?): BookFormat? =
        mimeTypes[mimeType?.substringBefore(';')?.trim()?.lowercase()]

    fun extensionForMimeType(mimeType: String?): String? = when (formatForMimeType(mimeType)) {
        BookFormat.EPUB -> "epub"
        BookFormat.PDF -> "pdf"
        BookFormat.TXT -> "txt"
        BookFormat.MD -> "md"
        BookFormat.CBZ -> "cbz"
        else -> null
    }

    fun supportsExtension(extension: String?): Boolean = formatForExtension(extension) != null

    fun supportsMimeType(mimeType: String?): Boolean = formatForMimeType(mimeType) != null
}
