package com.lagradost.runtime.executor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded worker admission. Cancellation releases the caller and interrupts blocking I/O.
 * This is not a process sandbox: code that ignores interruption can retain a worker.
 */
internal object PluginBlockingBoundary {
    private val executor = ThreadPoolExecutor(
        4,
        32,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        java.util.concurrent.ThreadFactory { work -> Thread(work, "plugin-call").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    suspend fun <T> call(loader: ClassLoader, block: suspend CoroutineScope.() -> T): T = suspendCancellableCoroutine { continuation ->
        val task = FutureTask<Unit> {
            try {
                val value = runBlocking(PluginClassLoaderElement(loader)) { block() }
                continuation.resume(value)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            task.cancel(true)
            executor.remove(task)
        }
        try {
            executor.execute(task)
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            continuation.resumeWithException(error)
        }
    }
}
