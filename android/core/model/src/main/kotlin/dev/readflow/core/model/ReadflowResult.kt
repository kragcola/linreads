package dev.readflow.core.model

/**
 * Monadic result type carrying a pure [ReadflowError] on failure (§7.3).
 * Not @Serializable itself — it's a transport type, not a persisted model.
 */
sealed interface ReadflowResult<out T> {
    data class Success<T>(val value: T) : ReadflowResult<T>
    data class Failure(val error: ReadflowError) : ReadflowResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw ReadflowException(error)
    }
}

inline fun <T, R> ReadflowResult<T>.map(transform: (T) -> R): ReadflowResult<R> = when (this) {
    is ReadflowResult.Success -> ReadflowResult.Success(transform(value))
    is ReadflowResult.Failure -> this
}
