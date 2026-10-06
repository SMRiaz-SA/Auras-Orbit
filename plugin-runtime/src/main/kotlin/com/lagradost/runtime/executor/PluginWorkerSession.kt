package com.lagradost.runtime.executor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded framed-IPC connection to one restartable plugin worker process. */
class PluginWorkerSession private constructor(
    private val process: Process,
    private val maxResponseBytes: Int,
    private val maxStderrBytes: Int,
    private val admission: PluginWorkerCapacity.Permit,
    private val evictIfIdle: (((() -> Unit)) -> Boolean)?,
) : Closeable {
    companion object {
        private val residentSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<PluginWorkerSession>()
        private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
        private const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024

        suspend fun launch(
            command: List<String>,
            workingDirectory: File? = null,
            environment: Map<String, String> = emptyMap(),
            maxResponseBytes: Int = 1024 * 1024,
            maxStderrBytes: Int = 256 * 1024,
            workerKey: String = "default",
            evictIfIdle: (((() -> Unit)) -> Boolean)? = null,
        ): PluginWorkerSession = withContext(Dispatchers.IO) {
            require(command.isNotEmpty() && command.all(String::isNotBlank)) { "Worker command must not be empty" }
            require(maxResponseBytes in 1..MAX_CAPTURE_BYTES) { "Invalid response limit" }
            require(maxStderrBytes in 1..MAX_CAPTURE_BYTES) { "Invalid stderr capture limit" }
            require(workingDirectory == null || workingDirectory.isDirectory) { "Worker directory must exist" }

            val admission = PluginWorkerCapacity.acquire(
                workerKey,
                if (evictIfIdle == null) {
                    null
                } else {
                    {
                        residentSessions.sortedBy { it.lastUsedNanos }.firstOrNull { session ->
                            session.evictIfIdle?.invoke { session.close() } == true
                        }
                    }
                },
            )
            try {
                val builder = ProcessBuilder(command)
                if (workingDirectory != null) builder.directory(workingDirectory)
                builder.environment().putAll(environment)
                PluginWorkerSession(builder.start(), maxResponseBytes, maxStderrBytes, admission, evictIfIdle).also {
                    residentSessions.add(it)
                }
            } catch (failure: Throwable) {
                admission.close()
                throw failure
            }
        }
    }

    private val input = DataInputStream(process.inputStream)
    private val output = DataOutputStream(process.outputStream)
    private val requestMutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile private var lastUsedNanos = System.nanoTime()
    private val stderrCapture = ByteArrayOutputStream(minOf(maxStderrBytes, 8192))
    private val stderrExceeded = AtomicBoolean(false)
    private val outputLock = Any()
    private val stderrDrain = CompletableFuture.runAsync {
        drainStderr(process.errorStream)
    }

    val processId: Long get() = process.pid()

    val isAlive: Boolean get() = !closed.get() && process.isAlive

    private val stderrText: String
        get() = synchronized(stderrCapture) { stderrCapture.toString(StandardCharsets.UTF_8).trim() }

    /**
     * Sends one size-prefixed request and waits for one size-prefixed response. Each request has
     * its own hard deadline; expiry or cancellation kills this session so its owner can replace it.
     */
    suspend fun request(
        request: ByteArray,
        timeoutMs: Long,
        onIntermediateFrame: ((ByteArray) -> Boolean)? = null,
        onWorkerRequest: ((ByteArray) -> ByteArray?)? = null,
    ): ByteArray = requestMutex.withLock {
        require(request.size <= MAX_REQUEST_BYTES) { "Worker request exceeds $MAX_REQUEST_BYTES bytes" }
        require(timeoutMs in 1..PluginWorkerProcess.MAX_TIMEOUT_MS) { "Worker timeout is outside the allowed range" }
        check(isAlive) { "Plugin worker session is not alive" }

        val writer = CompletableFuture.runAsync {
            synchronized(outputLock) {
                output.writeInt(request.size)
                output.write(request)
                output.flush()
            }
        }
        val reader = CompletableFuture.supplyAsync { readFinalFrame(onIntermediateFrame, onWorkerRequest) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

        try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    awaitBeforeDeadline(writer, deadline)
                    awaitBeforeDeadline(reader, deadline)
                }
            }
        } catch (_: TimeoutException) {
            writer.cancel(true)
            reader.cancel(true)
            close()
            throw PluginWorkerTimeoutException(processId, timeoutMs)
        } catch (cancelled: CancellationException) {
            writer.cancel(true)
            reader.cancel(true)
            close()
            throw cancelled
        } catch (failure: Throwable) {
            writer.cancel(true)
            reader.cancel(true)
            close()
            if (stderrExceeded.get()) throw PluginWorkerOutputLimitException("stderr", processId, maxStderrBytes)
            if (failure is EOFException) {
                throw PluginWorkerExitedException(processId, stderrText, failure)
            }
            throw unwrap(failure)
        } finally {
            lastUsedNanos = System.nanoTime()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        residentSessions.remove(this)
        process.let(PluginWorkerProcess::destroyTree)
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { process.errorStream.close() }
        stderrDrain.cancel(true)
        admission.close()
    }

    private fun readFrame(): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > maxResponseBytes) {
            PluginWorkerProcess.destroyTree(process)
            throw PluginWorkerOutputLimitException("stdout", processId, maxResponseBytes)
        }
        val response = ByteArray(size)
        input.readFully(response)
        return response
    }

    private fun readFinalFrame(
        onIntermediateFrame: ((ByteArray) -> Boolean)?,
        onWorkerRequest: ((ByteArray) -> ByteArray?)?,
    ): ByteArray {
        while (true) {
            val frame = readFrame()
            if (onIntermediateFrame?.invoke(frame) == true) continue
            val reply = onWorkerRequest?.invoke(frame)
            if (reply == null) return frame
            require(reply.size <= MAX_REQUEST_BYTES) { "Host reply exceeds $MAX_REQUEST_BYTES bytes" }
            synchronized(outputLock) {
                output.writeInt(reply.size)
                output.write(reply)
                output.flush()
            }
        }
    }

    private fun drainStderr(stream: InputStream) {
        val buffer = ByteArray(8192)
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) return
                synchronized(stderrCapture) {
                    val remaining = maxStderrBytes - stderrCapture.size()
                    if (remaining > 0) stderrCapture.write(buffer, 0, minOf(remaining, read))
                    if (read > remaining && stderrExceeded.compareAndSet(false, true)) {
                        PluginWorkerProcess.destroyTree(process)
                    }
                }
            }
        }
    }

    private fun <T> awaitBeforeDeadline(future: CompletableFuture<T>, deadlineNanos: Long): T {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw TimeoutException("Plugin worker request exceeded its deadline")
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (failure: ExecutionException) {
            throw unwrap(failure)
        }
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is ExecutionException && failure.cause != null) failure.cause!! else failure
}

class PluginWorkerExitedException(
    val processId: Long,
    stderr: String,
    cause: Throwable,
) : IllegalStateException(
    buildString {
        append("Plugin worker ").append(processId).append(" exited before replying")
        if (stderr.isNotBlank()) append(": ").append(stderr.take(4_000))
    },
    cause,
)
