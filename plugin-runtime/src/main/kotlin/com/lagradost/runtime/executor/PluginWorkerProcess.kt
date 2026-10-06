package com.lagradost.runtime.executor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Result from a one-shot, disposable plugin worker process. */
data class PluginWorkerResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val elapsedMs: Long,
)

class PluginWorkerTimeoutException(
    val processId: Long,
    val timeoutMs: Long,
) : TimeoutException("Plugin worker $processId exceeded its ${timeoutMs}ms deadline")

class PluginWorkerOutputLimitException(
    val streamName: String,
    val processId: Long,
    val limitBytes: Int,
) : IllegalStateException("Plugin worker $processId exceeded the $streamName output limit of $limitBytes bytes")

/**
 * Runs a command without a shell, with bounded input/output, bounded concurrency, and a hard
 * process deadline. Cancellation and timeout forcibly destroy the worker and its descendants.
 *
 * This is the process primitive for the provider RPC adapter. Existing provider call sites do
 * not use it yet; SafePluginInvoker still runs provider objects in the host JVM.
 */
object PluginWorkerProcess {
    private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    private const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024
    internal const val MAX_TIMEOUT_MS = 10 * 60 * 1000L
    private const val KILL_GRACE_MS = 250L
    private const val DESCENDANT_EXIT_WAIT_MS = 2_000L
    internal val blockingIoExecutor = Dispatchers.IO.asExecutor()

    suspend fun execute(
        command: List<String>,
        request: ByteArray = ByteArray(0),
        timeoutMs: Long,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        maxStdoutBytes: Int = 1024 * 1024,
        maxStderrBytes: Int = 256 * 1024,
        workerKey: String = "default",
    ): PluginWorkerResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty() && command.all(String::isNotBlank)) { "Worker command must not be empty" }
        require(timeoutMs in 1..MAX_TIMEOUT_MS) { "Worker timeout is outside the allowed range" }
        require(request.size <= MAX_REQUEST_BYTES) { "Worker request exceeds $MAX_REQUEST_BYTES bytes" }
        require(maxStdoutBytes in 1..MAX_CAPTURE_BYTES) { "Invalid stdout capture limit" }
        require(maxStderrBytes in 1..MAX_CAPTURE_BYTES) { "Invalid stderr capture limit" }
        require(workingDirectory == null || workingDirectory.isDirectory) { "Worker directory must exist" }

        val admission = PluginWorkerCapacity.acquire(workerKey)
        var process: Process? = null
        var stdoutDrain: CompletableFuture<CapturedOutput>? = null
        var stderrDrain: CompletableFuture<CapturedOutput>? = null
        var requestWriter: CompletableFuture<Unit>? = null
        val startedAt = System.nanoTime()
        try {
            val builder = ProcessBuilder(command)
            if (workingDirectory != null) builder.directory(workingDirectory)
            builder.environment().putAll(environment)
            process = builder.start()
            val worker = process
            val workerId = worker.pid()

            stdoutDrain = CompletableFuture.supplyAsync(
                { drain(worker.inputStream, maxStdoutBytes) { destroyTree(worker) } },
                blockingIoExecutor,
            )
            stderrDrain = CompletableFuture.supplyAsync(
                { drain(worker.errorStream, maxStderrBytes) { destroyTree(worker) } },
                blockingIoExecutor,
            )
            val writer = CompletableFuture.supplyAsync({
                worker.outputStream.use { output -> output.write(request) }
                Unit
            }, blockingIoExecutor)
            requestWriter = writer

            if (!worker.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                destroyTree(worker)
                throw PluginWorkerTimeoutException(workerId, timeoutMs)
            }

            val stdout = stdoutDrain.get(2, TimeUnit.SECONDS)
            val stderr = stderrDrain.get(2, TimeUnit.SECONDS)
            if (stdout.exceededLimit) throw PluginWorkerOutputLimitException("stdout", workerId, maxStdoutBytes)
            if (stderr.exceededLimit) throw PluginWorkerOutputLimitException("stderr", workerId, maxStderrBytes)
            writer.get(2, TimeUnit.SECONDS)

            PluginWorkerResult(
                exitCode = worker.exitValue(),
                stdout = stdout.bytes,
                stderr = stderr.bytes,
                elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            )
        } catch (failure: Throwable) {
            process?.let(::destroyTree)
            requestWriter?.cancel(true)
            stdoutDrain?.cancel(true)
            stderrDrain?.cancel(true)
            throw failure
        } finally {
            process?.let { worker ->
                runCatching { worker.outputStream.close() }
                runCatching { worker.inputStream.close() }
                runCatching { worker.errorStream.close() }
                if (worker.isAlive) destroyTree(worker)
            }
            admission.close()
        }
    }

    private data class CapturedOutput(val bytes: ByteArray, val exceededLimit: Boolean)

    private fun drain(input: InputStream, maxBytes: Int, onLimitExceeded: () -> Unit): CapturedOutput {
        val captured = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var exceededLimit = false
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val remaining = maxBytes - captured.size()
                if (remaining > 0) captured.write(buffer, 0, minOf(remaining, read))
                if (read > remaining && !exceededLimit) {
                    exceededLimit = true
                    onLimitExceeded()
                }
            }
        }
        return CapturedOutput(captured.toByteArray(), exceededLimit)
    }

    internal fun destroyTree(process: Process) {
        val handle = process.toHandle()
        val descendants = runCatching {
            handle.descendants().use { descendants ->
                descendants.toList().asReversed()
            }
        }.getOrDefault(emptyList())
        descendants.forEach { child -> runCatching { child.destroyForcibly() } }
        awaitExit(descendants, DESCENDANT_EXIT_WAIT_MS)
        descendants.filter(ProcessHandle::isAlive).forEach { child -> runCatching { child.destroyForcibly() } }
        awaitExit(descendants, KILL_GRACE_MS)

        runCatching { process.destroy() }
        runCatching { process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS) }
    }

    private fun awaitExit(processes: List<ProcessHandle>, timeoutMs: Long) {
        val exits = processes.asSequence()
            .filter(ProcessHandle::isAlive)
            .map(ProcessHandle::onExit)
            .toList()
        if (exits.isEmpty()) return
        runCatching {
            CompletableFuture.allOf(*exits.toTypedArray()).get(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }
}

internal object PluginWorkerCapacity {
    private const val MAX_WORKERS = 4
    private const val MAX_WORKERS_PER_PLUGIN = 3
    private val total = Semaphore(MAX_WORKERS)
    private val lanes = ConcurrentHashMap<String, Lane>()
    private val lanesLock = Any()

    suspend fun acquire(workerKey: String, reclaimIdle: (() -> Unit)? = null): Permit {
        require(workerKey.isNotBlank() && workerKey.length <= 512) { "Invalid worker key" }
        val lane = synchronized(lanesLock) {
            lanes.getOrPut(workerKey) { Lane(Semaphore(MAX_WORKERS_PER_PLUGIN)) }.also { it.references++ }
        }
        var laneAcquired = false
        try {
            lane.admission.acquire()
            laneAcquired = true
            if (reclaimIdle == null) {
                total.acquire()
            } else {
                while (!total.tryAcquire()) {
                    reclaimIdle()
                    kotlinx.coroutines.delay(25)
                }
            }
            return Permit(workerKey, lane)
        } catch (failure: Throwable) {
            if (laneAcquired) lane.admission.release()
            releaseReference(workerKey, lane)
            throw failure
        }
    }

    private fun releaseReference(workerKey: String, lane: Lane) {
        synchronized(lanesLock) {
            lane.references--
            if (lane.references == 0) lanes.remove(workerKey, lane)
        }
    }

    internal class Lane(val admission: Semaphore, var references: Int = 0)

    class Permit internal constructor(
        private val workerKey: String,
        private val lane: Lane,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            total.release()
            lane.admission.release()
            releaseReference(workerKey, lane)
        }
    }
}
