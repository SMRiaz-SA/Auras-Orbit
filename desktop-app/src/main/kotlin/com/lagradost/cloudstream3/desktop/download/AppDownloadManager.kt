package com.lagradost.cloudstream3.desktop.download

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

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
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun getTask(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun isTaskActive(id: String): Boolean = _tasks.value.any { it.id == id && it.status == TaskStatus.RUNNING }

    private fun expectedReleaseDigest(downloadUrl: String): String {
        val uri = URI(downloadUrl)
        val segments = uri.path.trim('/').split('/')
        require(
            uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
                segments.size == 6 && segments[2] == "releases" && segments[3] == "latest" &&
                segments[4] == "download",
        ) { "Executable download must be a GitHub latest release asset" }

        val apiUrl = "https://api.github.com/repos/${segments[0]}/${segments[1]}/releases/latest"
        val request = Request.Builder().url(apiUrl).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
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
    }

    fun startDownload(
        id: String,
        title: String,
        url: String,
        targetFile: File,
        onComplete: ((File) -> Unit)? = null,
    ): Job {
        // Cancel existing task with the same ID if already running
        cancelDownload(id)

        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download.tmp")
        if (tempFile.exists()) tempFile.delete()

        var downloadJob: Job? = null
        val newTask = DownloadTask(
            id = id,
            title = title,
            targetFile = targetFile,
            tempFile = tempFile,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            status = TaskStatus.RUNNING,
        )

        _tasks.update { current ->
            current.filterNot { it.id == id } + newTask
        }

        downloadJob = scope.launch {
            var lastSampleTime = System.currentTimeMillis()
            var bytesSinceSample = 0L
            var currentSpeed = 0L

            try {
                AppLogger.i("AppDownloadManager: Starting download '$title' from $url")
                val expectedDigest = expectedReleaseDigest(url)
                val digest = MessageDigest.getInstance("SHA-256")
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} ${response.message}")
                    }
                    val body = response.body
                    val contentLength = body.contentLength()

                    updateTask(id) { it.copy(totalBytes = contentLength) }

                    val buffer = ByteArray(64 * 1024)
                    tempFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            var read: Int
                            var downloaded = 0L

                            while (input.read(buffer).also { read = it } != -1) {
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

                                    updateTask(id) {
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
                    AppLogger.i("AppDownloadManager: Successfully downloaded '$title' to ${targetFile.absolutePath}")

                    updateTask(id) {
                        it.copy(
                            bytesDownloaded = targetFile.length(),
                            status = TaskStatus.COMPLETED,
                            speedBytesPerSec = 0L,
                        )
                    }

                    onComplete?.invoke(targetFile)

                    // Auto-dismiss completed task after 4 seconds
                    delay(4000L)
                    dismissTask(id)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    AppLogger.i("AppDownloadManager: Download '$title' cancelled by user")
                    withContext(Dispatchers.IO) {
                        if (tempFile.exists()) tempFile.delete()
                    }
                    updateTask(id) { it.copy(status = TaskStatus.CANCELLED) }
                    delay(1500L)
                    dismissTask(id)
                } else {
                    AppLogger.e("AppDownloadManager: Download '$title' failed: ${e.message}", e)
                    withContext(Dispatchers.IO) {
                        if (tempFile.exists()) tempFile.delete()
                    }
                    updateTask(id) {
                        it.copy(
                            status = TaskStatus.FAILED,
                            errorMessage = e.message ?: "Download failed",
                            speedBytesPerSec = 0L,
                        )
                    }
                }
            }
        }

        updateTask(id) { it.copy(job = downloadJob) }
        return downloadJob
    }

    fun cancelDownload(id: String) {
        val task = getTask(id) ?: return
        task.job?.cancel()
        scope.launch(Dispatchers.IO) {
            if (task.tempFile.exists()) task.tempFile.delete()
        }
        updateTask(id) { it.copy(status = TaskStatus.CANCELLED) }
        scope.launch {
            delay(1000L)
            dismissTask(id)
        }
    }

    fun dismissTask(id: String) {
        _tasks.update { current -> current.filterNot { it.id == id } }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
