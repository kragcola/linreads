package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** Pure, serializable error value. Goes into ReaderState / Room / sync / backup (§7.3). */
@Serializable
data class ReadflowError(
    val kind: Kind,
    val message: String,
    val code: Int? = null,            // Network HTTP code
    val resourceType: String? = null, // NotFound
    val id: String? = null,           // NotFound
    val format: String? = null,       // Unsupported
) {
    enum class Kind { NETWORK, DATABASE, PARSE, NOT_FOUND, UNSUPPORTED, AUTH, IO, UNKNOWN }

    companion object {
        fun network(code: Int?, message: String) = ReadflowError(Kind.NETWORK, message, code = code)
        fun parse(message: String) = ReadflowError(Kind.PARSE, message)
        fun notFound(type: String, id: String) =
            ReadflowError(Kind.NOT_FOUND, "$type not found: $id", resourceType = type, id = id)
        fun unsupported(format: String) =
            ReadflowError(Kind.UNSUPPORTED, "Unsupported format: $format", format = format)
        fun auth() = ReadflowError(Kind.AUTH, "Authentication failed")
        fun io(message: String) = ReadflowError(Kind.IO, message)
        fun unknown(message: String = "An unexpected error occurred") = ReadflowError(Kind.UNKNOWN, message)
    }
}

/** Runtime-only carrier. NEVER persisted/serialized. Keeps the original cause for logs/crash. */
class ReadflowException(
    val error: ReadflowError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)
