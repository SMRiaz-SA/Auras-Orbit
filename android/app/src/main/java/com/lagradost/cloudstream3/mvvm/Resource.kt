package com.lagradost.cloudstream3.mvvm

import kotlinx.coroutines.CancellationException

/** Result state used by Cloudstream callers. Failure text is always safe for display. */
sealed class Resource<out T> {
    data class Success<out T>(val value: T) : Resource<T>()

    data class Failure(
        val isNetworkError: Boolean,
        val errorString: String,
    ) : Resource<Nothing>()

    data class Loading(val url: String? = null) : Resource<Nothing>()

    companion object {
        /** Maps success values as-is; failures are sanitized and cancellation is rethrown. */
        fun <T> fromResult(result: Result<T>): Resource<T> = result.fold(
            onSuccess = { Success(it) },
            onFailure = { throwable ->
                if (throwable is CancellationException) throw throwable
                throwAbleToResource(throwable)
            },
        )
    }
}
