package com.onyx.browser.core.common

/**
 * Lightweight domain result wrapper for asynchronous repository and engine operations.
 */
sealed interface ResultState<out T> {
    data class Success<T>(val data: T) : ResultState<T>
    data class Error(
        val exception: Throwable,
        val message: String? = exception.message
    ) : ResultState<Nothing>
    data object Loading : ResultState<Nothing>

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error, Loading -> null
    }
}

inline fun <T, R> ResultState<T>.map(transform: (T) -> R): ResultState<R> = when (this) {
    is ResultState.Success -> ResultState.Success(transform(data))
    is ResultState.Error -> this
    is ResultState.Loading -> ResultState.Loading
}

typealias OnyxResult<T> = ResultState<T>

