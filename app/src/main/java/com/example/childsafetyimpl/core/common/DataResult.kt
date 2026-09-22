package com.example.childsafetyimpl.core.common

/**
 * Local analogue of the network result wrapper described in CLAUDE.md.
 *
 * There is no backend, but the layering is the same: repositories emit a flow of
 * these so the UI can distinguish loading from empty from failed. [Unavailable] is the addition
 * this product needs, because "Android will not tell us" is a distinct outcome from "it went
 * wrong", and the UI has to say so rather than showing a plausible zero.
 */
sealed interface DataResult<out T> {

    data object Loading : DataResult<Nothing>

    data class Success<T>(val data: T) : DataResult<T>

    data class Error(val message: String, val cause: Throwable? = null) : DataResult<Nothing>

    /** The capability is blocked by a missing grant or by the platform itself. */
    data class Unavailable(
        val reason: String,
        val resolution: Resolution = Resolution.NONE,
    ) : DataResult<Nothing>

    enum class Resolution { NONE, GRANT_PERMISSION, ENABLE_SERVICE, DEVICE_MANAGEMENT }
}

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(data))
    is DataResult.Error -> this
    is DataResult.Unavailable -> this
    DataResult.Loading -> DataResult.Loading
}

fun <T> DataResult<T>.dataOrNull(): T? = (this as? DataResult.Success)?.data
