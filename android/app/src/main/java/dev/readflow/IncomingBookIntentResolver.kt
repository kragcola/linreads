package dev.readflow

import android.content.ClipData
import android.content.Intent
import android.net.Uri

internal object IncomingBookIntentResolver {
    fun resolve(
        action: String?,
        mimeType: String?,
        dataUri: String?,
        streamUris: List<String>,
        clipDataUris: List<String>,
    ): String? {
        if (action !in SUPPORTED_ACTIONS) return null

        val candidates = when (action) {
            ACTION_VIEW -> listOfNotNull(dataUri) + streamUris + clipDataUris
            ACTION_SEND, ACTION_SEND_MULTIPLE -> streamUris + clipDataUris + listOfNotNull(dataUri)
            else -> emptyList()
        }

        return candidates.firstOrNull { isSupportedBookUri(it, mimeType) }
    }

    private fun isSupportedBookUri(uri: String, mimeType: String?): Boolean {
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedMime in SUPPORTED_MIME_TYPES) return true

        val path = uri.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ACTION_SEND = "android.intent.action.SEND"
    private const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    private val SUPPORTED_ACTIONS = setOf(ACTION_VIEW, ACTION_SEND, ACTION_SEND_MULTIPLE)
    private val SUPPORTED_EXTENSIONS = setOf("txt", "epub", "pdf", "md", "markdown")
    private val SUPPORTED_MIME_TYPES = setOf(
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/epub+zip",
        "application/pdf",
    )
}

internal fun Intent.extractIncomingBookUri(): Uri? {
    val uriString = IncomingBookIntentResolver.resolve(
        action = action,
        mimeType = type,
        dataUri = data?.toString(),
        streamUris = streamUriStrings(),
        clipDataUris = clipData?.uriStrings().orEmpty(),
    )
    return uriString?.let(Uri::parse)
}

@Suppress("DEPRECATION")
private fun Intent.streamUriStrings(): List<String> {
    val stream = extras?.get(Intent.EXTRA_STREAM) ?: return emptyList()
    return when (stream) {
        is Uri -> listOf(stream.toString())
        is ArrayList<*> -> stream.filterIsInstance<Uri>().map { it.toString() }
        else -> emptyList()
    }
}

private fun ClipData.uriStrings(): List<String> =
    (0 until itemCount).mapNotNull { index -> getItemAt(index).uri?.toString() }
