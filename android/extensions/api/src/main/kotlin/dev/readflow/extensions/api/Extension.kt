package dev.readflow.extensions.api

import kotlinx.coroutines.CoroutineScope

/** Identity/metadata for a first-party compile-time extension (§8.1). */
data class ExtensionMeta(
    val id: String,
    val name: String,
    val description: String = "",
)

/**
 * First-party compile-time extension SPI (§8.1). Discovered via Koin multibind,
 * NOT ServiceLoader (§8.2). Phase 1 scaffold: lifecycle surface only.
 */
interface Extension {
    val meta: ExtensionMeta
    suspend fun onAttach(scope: CoroutineScope, context: ExtensionContext)
    suspend fun onDetach()
}

/** Sandbox handed to an extension on attach. Carries no document View reference (R12). */
interface ExtensionContext {
    val eventBus: ReaderEventBus
}
