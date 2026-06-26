package dev.readflow.extensions.api

import android.net.Uri
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadedAsset
import dev.readflow.core.model.ReadflowResult

interface LocalBookImporter {
    suspend fun import(
        uri: Uri,
        mimeType: String? = null,
    ): ReadflowResult<Pair<BookMeta, DownloadedAsset>>
}
