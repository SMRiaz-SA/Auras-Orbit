package com.lagradost.runtime.executor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Owns one persistent worker session and starts a replacement after a crash or hard deadline. */
class RestartablePluginWorker(
    private val command: () -> List<String>,
    private val workingDirectory: File? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val maxResponseBytes: Int = 1024 * 1024,
    private val maxStderrBytes: Int = 256 * 1024,
    private val workerKey: String = "default",
) : Closeable {
    private val requestLock = Mutex()
    private val currentSession = AtomicReference<PluginWorkerSession?>()
    private val starts = AtomicLong()
    private val lastProcessId = AtomicLong(-1)
    private val leaseLock = Any()
    private var leases = 0

    /** Keep a worker resident across a logical operation, including preparation RPCs. */
    suspend fun <T> withLease(operation: suspend () -> T): T {
        synchronized(leaseLock) { leases++ }
        try {
            return operation()
        } finally {
            synchronized(leaseLock) { leases-- }
        }
    }

    val workerStarts: Long get() = starts.get()

    val processId: Long? get() = currentSession.get()?.processId

    val isAlive: Boolean get() = currentSession.get()?.isAlive == true

    val lastStartedProcessId: Long? get() = lastProcessId.get().takeIf { it >= 0 }

    suspend fun request(
        request: ByteArray,
        timeoutMs: Long,
        onIntermediateFrame: ((ByteArray) -> Boolean)? = null,
        onWorkerRequest: ((ByteArray) -> ByteArray?)? = null,
    ): ByteArray {
        require(timeoutMs in 1..PluginWorkerProcess.MAX_TIMEOUT_MS) { "Worker timeout is outside the allowed range" }
        val response = withTimeoutOrNull(timeoutMs) {
            withLease {
                requestLock.withLock {
                    val session = currentSession.get()?.takeIf(PluginWorkerSession::isAlive)
                        ?: launchReplacement()
                    try {
                        session.request(request, timeoutMs, onIntermediateFrame, onWorkerRequest)
                    } catch (failure: Throwable) {
                        currentSession.compareAndSet(session, null)
                        session.close()
                        throw failure
                    }
                }
            }
        }
        return response ?: throw PluginWorkerTimeoutException(lastProcessId.get(), timeoutMs)
    }

    suspend fun restart() {
        requestLock.withLock {
            currentSession.getAndSet(null)?.close()
        }
    }

    override fun close() {
        currentSession.getAndSet(null)?.close()
    }

    private suspend fun launchReplacement(): PluginWorkerSession {
        currentSession.getAndSet(null)?.close()
        val replacement = PluginWorkerSession.launch(
            command = command(),
            workingDirectory = workingDirectory,
            environment = environment,
            maxResponseBytes = maxResponseBytes,
            maxStderrBytes = maxStderrBytes,
            workerKey = workerKey,
            evictIfIdle = { evict ->
                synchronized(leaseLock) {
                    if (leases != 0) {
                        false
                    } else {
                        evict()
                        true
                    }
                }
            },
        )
        currentSession.set(replacement)
        lastProcessId.set(replacement.processId)
        starts.incrementAndGet()
        return replacement
    }
}
