package com.onyx.browser.core.common

/**
 * Lightweight domain result wrapper for asynchronous repository and engine operations.
 */
sealed interface OnyxResult<out T> {
    data class Success<T>(val data: T) : OnyxResult<T>
    data class Error(
        val exception: Throwable,
        val message: String? = exception.message
    ) : OnyxResult<Nothing>
    data object Loading : OnyxResult<Nothing>

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error, Loading -> null
    }
}

inline fun <T, R> OnyxResult<T>.map(transform: (T) -> R): OnyxResult<R> = when (this) {
    is OnyxResult.Success -> OnyxResult.Success(transform(data))
    is OnyxResult.Error -> this
    is OnyxResult.Loading -> OnyxResult.Loading
}
