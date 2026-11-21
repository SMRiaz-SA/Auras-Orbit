package com.lagradost.cloudstream3.mvvm

import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ERROR_TAG = "ApiError"
private const val GENERIC_FAILURE = "Request failed. Please try again later."
private const val NETWORK_FAILURE = "Cannot connect to the server. Please try again later."
private const val TIMEOUT_FAILURE = "Connection timed out. Please try again later."
private const val OUTDATED_FAILURE = "The app or extension is outdated. Please update and try again."

/** Logs a fixed diagnostic only. Throwable details can contain URLs, credentials, or headers. */
fun logError(throwable: Throwable) {
    // Keep the parameter for JVM/API compatibility, but never pass it to the logger.
    Log.d(ERROR_TAG, "A protected request failed.")
}

/** Converts a failure into a user-safe resource. Cancellation remains a control signal. */
fun <T> safeFail(throwable: Throwable): Resource<T> {
    if (throwable is CancellationException) throw throwable
    return Resource.Failure(false, GENERIC_FAILURE)
}

/** Maps known failures to generic messages without exposing exception data. */
fun <T> throwAbleToResource(throwable: Throwable): Resource<T> {
    if (throwable is CancellationException) throw throwable

    return when (throwable) {
        is ErrorLoadingException -> Resource.Failure(true, NETWORK_FAILURE)
        is NotImplementedError -> Resource.Failure(false, "This operation is not implemented.")
        else -> platformThrowAbleToResource(throwable)
    }
}

/** Runs provider work on IO and returns only sanitized failure information. */
@AnyThread
suspend fun <T> safeApiCall(
    @WorkerThread apiCall: suspend () -> T,
): Resource<T> = withContext(Dispatchers.IO) {
    try {
        Resource.Success(apiCall())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        logError(throwable)
        throwAbleToResource(throwable)
    }
}
