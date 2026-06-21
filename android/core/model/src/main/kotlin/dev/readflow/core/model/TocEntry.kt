package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** A navigable table-of-contents entry exposed by a reader engine. */
@Serializable
data class TocEntry(
    val title: String,
    val locator: Locator,
    val level: Int = 0,
)
