package com.lagradost.cloudstream3.desktop.download

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DownloadTask(
    val id: String,
    val title: String,
    val targetFile: File,
    val tempFile: File,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: TaskStatus = TaskStatus.RUNNING,
    val errorMessage: String? = null,
    val job: Job? = null,
    val generationId: String = UUID.randomUUID().toString(),
) {
    val progress: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else -1f

    val percent: Int
        get() = if (totalBytes > 0L) (progress * 100).toInt() else 0

    val speedFormatted: String
        get() = when {
            speedBytesPerSec >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB/s", speedBytesPerSec.toFloat() / (1024f * 1024f))
            speedBytesPerSec >= 1024 -> String.format(Locale.ROOT, "%.0f KB/s", speedBytesPerSec.toFloat() / 1024f)
            else -> "$speedBytesPerSec B/s"
        }

    val downloadedMB: String
        get() = String.format(Locale.ROOT, "%.1f MB", bytesDownloaded.toFloat() / (1024f * 1024f))

    val totalMB: String
        get() = if (totalBytes > 0L) String.format(Locale.ROOT, "%.1f MB", totalBytes.toFloat() / (1024f * 1024f)) else "Unknown"

    val etaSeconds: Long
        get() = if (speedBytesPerSec > 0 && totalBytes > bytesDownloaded) {
            (totalBytes - bytesDownloaded) / speedBytesPerSec
        } else {
            0L
        }

    val etaFormatted: String
        get() = when {
            etaSeconds <= 0 -> ""
            etaSeconds < 60 -> "${etaSeconds}s remaining"
            else -> "${etaSeconds / 60}m ${etaSeconds % 60}s remaining"
        }
}

object AppDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskLock = Any()
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun getTask(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun isTaskActive(id: String): Boolean = _tasks.value.any { it.id == id && it.status == TaskStatus.RUNNING }

    private fun expectedReleaseDigest(downloadUrl: String, id: String, generationId: String): String {
        val uri = URI(downloadUrl)
        val segments = uri.path.trim('/').split('/')
        require(
            uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
                segments.size == 6 && segments[2] == "releases" && segments[3] == "latest" &&
                segments[4] == "download",
        ) { "Executable download must be a GitHub latest release asset" }

        val apiUrl = "https://api.github.com/repos/${segments[0]}/${segments[1]}/releases/latest"
        val request = Request.Builder().url(apiUrl).header("Accept", "application/vnd.github+json").build()
        val call = client.newCall(request)
        registerCall(id, generationId, call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Could not verify release asset: HTTP ${response.code}")
                val body = response.body
                val assets = mapper.readTree(body.string()).path("assets")
                val asset = assets.firstOrNull { it.path("name").asText() == segments[5] }
                    ?: throw IllegalStateException("Release asset not found in GitHub metadata")
                val digest = asset.path("digest").asText()
                val match = Regex("sha256:([0-9a-fA-F]{64})").matchEntire(digest)
                    ?: throw IllegalStateException("Release asset has no SHA-256 digest")
                return match.groupValues[1].lowercase()
            }
        } finally {
            unregisterCall(generationId, call)
        }
    }

    fun startDownload(
        id: String,
        title: String,
        url: String,
        targetFile: File,
        onComplete: ((File) -> Unit)? = null,
    ): Job {
        targetFile.parentFile?.mkdirs()
        val generationId = UUID.randomUUID().toString()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.$generationId.download.tmp")
        var downloadJob: Job? = null

        synchronized(taskLock) {
            cancelCurrentTaskLocked(id)
            val createdJob = scope.launch(start = CoroutineStart.LAZY) {
                val attemptJob = coroutineContext[Job]
                    ?: error("Download attempt coroutine has no Job")
                var lastSampleTime = System.currentTimeMillis()
                var bytesSinceSample = 0L
                var currentSpeed = 0L

                try {
                    AppLogger.i("AppDownloadManager: Starting download '$title' from $url")
                    val expectedDigest = expectedReleaseDigest(url, id, generationId)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val request = Request.Builder().url(url).build()
                    val call = client.newCall(request)
                    registerCall(id, generationId, call)
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IllegalStateException("HTTP ${response.code} ${response.message}")
                            }
                            val body = response.body
                            val contentLength = body.contentLength()

                            updateRunningTask(id, generationId) { it.copy(totalBytes = contentLength) }

                            val buffer = ByteArray(64 * 1024)
                            tempFile.outputStream().use { output ->
                                body.byteStream().use { input ->
                                    var read: Int
                                    var downloaded = 0L

                                    while (input.read(buffer).also { read = it } != -1) {
                                        currentCoroutineContext().ensureActive()
                                        output.write(buffer, 0, read)
                                        digest.update(buffer, 0, read)
                                        downloaded += read
                                        bytesSinceSample += read

                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastSampleTime
                                        if (elapsed >= 500) {
                                            currentSpeed = (bytesSinceSample * 1000L) / elapsed
                                            lastSampleTime = now
                                            bytesSinceSample = 0L

                                            updateRunningTask(id, generationId) {
                                                it.copy(
                                                    bytesDownloaded = downloaded,
                                                    speedBytesPerSec = currentSpeed,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (contentLength > 0 && tempFile.length() != contentLength) {
                                throw IllegalStateException("Downloaded file incomplete (${tempFile.length()} / $contentLength bytes)")
                            }
                            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
                            if (actualDigest != expectedDigest) {
                                throw IllegalStateException("Release asset SHA-256 mismatch")
                            }

                            synchronized(taskLock) {
                                val current = findTask(id, generationId)
                                if (current?.status != TaskStatus.RUNNING || !attemptJob.isActive) {
                                    throw CancellationException("Download attempt is no longer current")
                                }

                                try {
                                    Files.move(
                                        tempFile.toPath(),
                                        targetFile.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE,
                                    )
                                } catch (_: AtomicMoveNotSupportedException) {
                                    Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                }

                                targetFile.setExecutable(true)
                                updateRunningTask(id, generationId) {
                                    it.copy(
                                        bytesDownloaded = targetFile.length(),
                                        status = TaskStatus.COMPLETED,
                                        speedBytesPerSec = 0L,
                                    )
                                }
                                AppLogger.i("AppDownloadManager: Successfully downloaded '$title' to ${targetFile.absolutePath}")
                                try {
                                    onComplete?.invoke(targetFile)
                                } catch (callbackError: Exception) {
                                    AppLogger.e(
                                        "AppDownloadManager: Completion callback failed for '$title': ${callbackError.message}",
                                        callbackError,
                                    )
                                }
                            }

                            // Auto-dismiss completed task after 4 seconds.
                            delay(4000L)
                            dismissTask(id, generationId)
                        }
                    } finally {
                        unregisterCall(generationId, call)
                    }
                } catch (e: Exception) {
                    val cancelled = e is CancellationException || !currentCoroutineContext().isActive
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (tempFile.exists()) tempFile.delete()
                    }
                    if (cancelled) {
                        AppLogger.i("AppDownloadManager: Download '$title' cancelled by user")
                        updateRunningTask(id, generationId) { it.copy(status = TaskStatus.CANCELLED, speedBytesPerSec = 0L) }
                        scheduleDismiss(id, generationId, 1500L)
                    } else {
                        AppLogger.e("AppDownloadManager: Download '$title' failed: ${e.message}", e)
                        updateRunningTask(id, generationId) {
                            it.copy(
                                status = TaskStatus.FAILED,
                                errorMessage = e.message ?: "Download failed",
                                speedBytesPerSec = 0L,
                            )
                        }
                    }
                }
            }

            createdJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) cancelCalls(generationId)
            }
            downloadJob = createdJob

            val newTask = DownloadTask(
                id = id,
                title = title,
                targetFile = targetFile,
                tempFile = tempFile,
                generationId = generationId,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                status = TaskStatus.RUNNING,
                job = createdJob,
            )
            _tasks.update { current ->
                current.filterNot { it.id == id } + newTask
            }
        }

        // The lazy job is published on its task before it can run, so an immediate cancel
        // always has a Job and can close any registered HTTP call.
        val job = checkNotNull(downloadJob)
        job.start()
        return job
    }

    fun cancelDownload(id: String) {
        synchronized(taskLock) {
            cancelCurrentTaskLocked(id)
        }
    }

    private fun cancelCurrentTaskLocked(id: String) {
        val task = findTask(id) ?: return
        task.job?.cancel()
        cancelCalls(task.generationId)
        scope.launch(Dispatchers.IO) {
            if (task.tempFile.exists()) task.tempFile.delete()
        }
        updateTask(id, task.generationId) {
            it.copy(status = TaskStatus.CANCELLED, speedBytesPerSec = 0L)
        }
        scheduleDismiss(id, task.generationId, 1000L)
    }

    fun dismissTask(id: String) {
        _tasks.update { current -> current.filterNot { it.id == id } }
    }

    private fun dismissTask(id: String, generationId: String) {
        _tasks.update { current ->
            current.filterNot { it.id == id && it.generationId == generationId }
        }
    }

    private fun scheduleDismiss(id: String, generationId: String, delayMs: Long) {
        scope.launch {
            delay(delayMs)
            dismissTask(id, generationId)
        }
    }

    private fun findTask(id: String, generationId: String? = null): DownloadTask? =
        _tasks.value.firstOrNull { it.id == id && (generationId == null || it.generationId == generationId) }

    private fun updateTask(id: String, generationId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id && it.generationId == generationId) transform(it) else it }
        }
    }

    private fun updateRunningTask(id: String, generationId: String, transform: (DownloadTask) -> DownloadTask) {
        updateTask(id, generationId) { task ->
            if (task.status == TaskStatus.RUNNING) transform(task) else task
        }
    }

    private fun registerCall(id: String, generationId: String, call: Call) {
        synchronized(taskLock) {
            val task = findTask(id, generationId)
                ?: throw CancellationException("Download attempt is no longer current")
            if (task.status != TaskStatus.RUNNING) {
                throw CancellationException("Download attempt is no longer active")
            }
            val taskJob = task.job
                ?: throw CancellationException("Download attempt has no job")
            if (!taskJob.isActive) throw CancellationException("Download attempt is no longer active")
            val calls = activeCalls.computeIfAbsent(generationId) { ConcurrentHashMap.newKeySet<Call>() }
            calls.add(call)
            if (!taskJob.isActive) {
                calls.remove(call)
                if (calls.isEmpty()) activeCalls.remove(generationId, calls)
                call.cancel()
                throw CancellationException("Download attempt was cancelled")
            }
        }
    }

    private fun unregisterCall(generationId: String, call: Call) {
        activeCalls.computeIfPresent(generationId) { _, calls ->
            calls.remove(call)
            if (calls.isEmpty()) null else calls
        }
    }

    private fun cancelCalls(generationId: String) {
        activeCalls[generationId]?.toList()?.forEach(Call::cancel)
    }

}
