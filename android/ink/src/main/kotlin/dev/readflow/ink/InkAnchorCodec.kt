package dev.readflow.ink

import dev.readflow.core.model.InkAnchor
import kotlinx.serialization.json.Json

/**
 * Encode/decode [InkAnchor] ↔ (anchor_type, anchor_json) for Room storage (§6.3).
 * core:model must not depend on Room; this codec lives in :ink.
 */
object InkAnchorCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(anchor: InkAnchor): Pair<String, String> = when (anchor) {
        is InkAnchor.Page -> "page" to json.encodeToString(InkAnchor.Page.serializer(), anchor)
        is InkAnchor.Text -> "text" to json.encodeToString(InkAnchor.Text.serializer(), anchor)
    }

    fun decode(type: String, anchorJson: String): InkAnchor = when (type) {
        "page" -> json.decodeFromString(InkAnchor.Page.serializer(), anchorJson)
        "text" -> json.decodeFromString(InkAnchor.Text.serializer(), anchorJson)
        else -> throw IllegalArgumentException("Unknown InkAnchor type: $type")
    }
}
