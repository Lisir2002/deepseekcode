package com.deepseek.coder.core

/**
 * Functional result type for domain layer operations.
 * Avoids throwing exceptions across boundaries for predictable error handling.
 */
sealed class Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>()
    data class Failure(val error: AppError) : Outcome<Nothing>()

    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    fun getOrNull(): T? = (this as? Success)?.value
}

/**
 * Unified error model across domain/data layers.
 * Http errors carry raw HTTP status for later UI mapping (e.g., 429 → rate-limit toast).
 */
sealed class AppError(
    open val message: String,
    open val cause: Throwable? = null
) {
    data class Network(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Http(val code: Int, override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Api(val type: String, override val message: String, val param: String? = null) : AppError(message)
    data class Storage(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Unauthorized(override val message: String = "Invalid or missing API Key") : AppError(message)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
}

fun <T> Result<T>.toOutcome(mapErr: (Throwable) -> AppError): Outcome<T> =
    fold(onSuccess = { Outcome.Success(it) }, onFailure = { Outcome.Failure(mapErr(it)) })
