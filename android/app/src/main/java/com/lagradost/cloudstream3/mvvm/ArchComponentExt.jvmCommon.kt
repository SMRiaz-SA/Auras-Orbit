package com.lagradost.cloudstream3.mvvm

import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

private const val JVM_NETWORK_FAILURE = "Cannot connect to the server. Please try again later."
private const val JVM_TIMEOUT_FAILURE = "Connection timed out. Please try again later."
private const val JVM_OUTDATED_FAILURE = "The app or extension is outdated. Please update and try again."

/** JVM-specific safe mappings corresponding to the pinned jvmCommon implementation. */
fun <T> platformThrowAbleToResource(throwable: Throwable): Resource<T> {
    if (throwable is CancellationException) throw throwable

    return when {
        throwable is NoSuchMethodException ||
            throwable is NoSuchFieldException ||
            throwable is NoSuchMethodError ||
            throwable is NoSuchFieldError ||
            throwable.javaClass.name == "kotlin.reflect.full.NoSuchPropertyException" ->
            Resource.Failure(false, JVM_OUTDATED_FAILURE)

        throwable is SocketTimeoutException || throwable is InterruptedIOException ->
            Resource.Failure(true, JVM_TIMEOUT_FAILURE)

        throwable is UnknownHostException || throwable is SSLHandshakeException ->
            Resource.Failure(true, JVM_NETWORK_FAILURE)

        else -> safeFail(throwable)
    }
}
