package com.lagradost.cloudstream3.desktop.downloader

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellableContinuation
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TurboChunkDownloader(
    private val maxWorkers: Int = 8,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    data class ProbeResult(
        val totalBytes: Long,
        val supportsRange: Boolean,
        val contentType: String?,
        val validator: String? = null,
    )

    suspend fun probe(url: String, headers: Map<String, String>): ProbeResult {
        try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            reqBuilder.header("Range", "bytes=0-0").header("Accept-Encoding", "identity")

            return executeCancellable(reqBuilder.build()) { res ->
                val contentRange = res.header("Content-Range")
                val contentLength = res.header("Content-Length")?.toLongOrNull() ?: 0L
                val parsedContentRange = parseContentRange(contentRange)
                // Read at most one byte so small probe responses complete cleanly and
                // servers do not remain blocked writing a body that we never consume.
                res.body.byteStream().use { it.read() }

                val totalBytes = if (res.code == 206) {
                    parsedContentRange?.total ?: 0L
                } else {
                    contentLength
                }

                val supportsRange = res.code == 206 &&
                    parsedContentRange?.let { it.start == 0L && it.end == 0L } == true
                ProbeResult(
                    totalBytes = totalBytes,
                    supportsRange = supportsRange && totalBytes > 1_000_000L,
                    contentType = res.header("Content-Type"),
                    validator = extractUsableValidator(res),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("Probe failed: ${e.message}")
            return ProbeResult(0L, false, null)
        }
    }

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        destinationFile: File,
        tempPartFile: File,
        totalBytesEstimated: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesSec: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val probe = probe(url, headers)
        val knownTotalBytes = probe.totalBytes.takeIf { it > 0L }
        val totalBytes = knownTotalBytes ?: totalBytesEstimated

        destinationFile.parentFile?.mkdirs()
        tempPartFile.parentFile?.mkdirs()

        // Resume only the same validated entity with the same layout. File length alone
        // cannot establish progress for a preallocated segmented download.
        val identityFile = File(tempPartFile.path + ".identity")
        val identityTotalBytes = knownTotalBytes ?: 0L
        val identity = downloadIdentity(url, probe.validator, identityTotalBytes, probe.supportsRange)
        val completionFile = File(tempPartFile.path + ".complete")
        if (probe.validator == null || identityFile.takeIf { it.isFile }?.readText() != identity) {
            java.io.RandomAccessFile(tempPartFile, "rw").use { it.setLength(0) }
            File(tempPartFile.path + ".download.meta").delete()
            File(tempPartFile.path + ".offsets").delete()
            completionFile.delete()
        }
        identityFile.writeText(identity)
        // Byte ranges are only safe to combine when every response can be tied to the
        // entity observed by the probe. Content length and valid ranges alone do not
        // prevent the resource from changing between requests.
        val useParallel = knownTotalBytes != null && probe.supportsRange &&
            probe.validator != null && totalBytes > 5_000_000L

        val completionMarker = runCatching { readCompletionMarker(completionFile) }.getOrNull()
        val hasValidCompletionMarker = completionMarker != null &&
            completionMarker.identity == identity &&
            completionMarker.totalBytes == tempPartFile.length() &&
            (knownTotalBytes == null || knownTotalBytes == tempPartFile.length())
        if (hasValidCompletionMarker && tempPartFile.isFile && tempPartFile.length() > 0L) {
            onProgress(tempPartFile.length(), totalBytes.coerceAtLeast(tempPartFile.length()), 0L)
            return@withContext true
        }

        completionFile.delete()
        // A full-sized temp file may be a sparse pre-allocation left by a crash. Only the
        // completion marker, written after byte-count validation, can certify completion.
        if (
            !useParallel && knownTotalBytes != null && tempPartFile.isFile &&
            tempPartFile.length() == knownTotalBytes
        ) {
            java.io.RandomAccessFile(tempPartFile, "rw").use { it.setLength(0) }
            File(tempPartFile.path + ".download.meta").delete()
            File(tempPartFile.path + ".offsets").delete()
        }

        val downloadHeaders = headers.toMutableMap().apply {
            keys.filter { it.equals("If-Range", ignoreCase = true) || it.equals("Range", ignoreCase = true) }
                .toList()
                .forEach { remove(it) }
            put("Accept-Encoding", "identity")
            probe.validator?.let { put("If-Range", it) }
        }

        val speedTracker = SpeedTracker()
        var completionValidator = probe.validator
        var completionTotalBytes = totalBytes

        val downloadOk = try {
            if (useParallel) {
                AppLogger.i("Starting parallel $maxWorkers-connection download for ${totalBytes / 1024 / 1024} MB ($url)")
                downloadParallel(
                    url = url,
                    headers = downloadHeaders,
                    tempFile = tempPartFile,
                    totalBytes = totalBytes,
                    numWorkers = maxWorkers,
                    speedTracker = speedTracker,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
            } else {
                AppLogger.i("Downloading as one progressive stream without validated parallel ranges ($url)")
                val streamResult = downloadSingleStream(
                    url = url,
                    headers = downloadHeaders,
                    tempFile = tempPartFile,
                    identityFile = identityFile,
                    knownTotalBytes = knownTotalBytes,
                    totalBytes = totalBytes,
                    speedTracker = speedTracker,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                if (streamResult.completed) {
                    completionValidator = streamResult.validator
                    completionTotalBytes = streamResult.totalBytes
                }
                streamResult.completed
            }
        } catch (_: RangeRejectedException) {
            java.io.RandomAccessFile(tempPartFile, "rw").use { it.setLength(0) }
            File(tempPartFile.path + ".download.meta").delete()
            File(tempPartFile.path + ".offsets").delete()
            val streamResult = downloadSingleStream(
                url = url,
                headers = downloadHeaders,
                tempFile = tempPartFile,
                identityFile = identityFile,
                knownTotalBytes = knownTotalBytes,
                totalBytes = totalBytes,
                speedTracker = speedTracker,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            if (streamResult.completed) {
                completionValidator = streamResult.validator
                completionTotalBytes = streamResult.totalBytes
            }
            streamResult.completed
        }

        if (isCancelled() || !downloadOk) {
            return@withContext false
        }

        if (
            tempPartFile.exists() && tempPartFile.length() > 0L &&
            (completionTotalBytes <= 0L || tempPartFile.length() == completionTotalBytes)
        ) {
            AppLogger.i("Download writing completed -> ${tempPartFile.absolutePath} (${tempPartFile.length() / 1024 / 1024} MB)")
            ensureActive()
            onProgress(tempPartFile.length(), completionTotalBytes.coerceAtLeast(tempPartFile.length()), speedTracker.getCurrentSpeed())
            // A full-response fallback can observe a newer entity than the probe.
            // Associate the completion marker with the response that supplied the bytes.
            val completionIdentityTotalBytes = when {
                knownTotalBytes == null -> 0L
                completionValidator == probe.validator -> knownTotalBytes
                else -> completionTotalBytes
            }
            val completionIdentity = downloadIdentity(
                url,
                completionValidator,
                completionIdentityTotalBytes,
                probe.supportsRange,
            )
            identityFile.writeText(completionIdentity)
            if (completionValidator != null) {
                writeCompletionMarker(completionFile, completionIdentity, tempPartFile.length())
            } else {
                completionFile.delete()
            }
            true
        } else {
            false
        }
    }

    private suspend fun downloadParallel(
        url: String,
        headers: Map<String, String>,
        tempFile: File,
        totalBytes: Long,
        numWorkers: Int,
        speedTracker: SpeedTracker,
        onProgress: (Long, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean = coroutineScope {
        // Clean up any legacy chunk directory from older versions
        val legacyChunkDir = File(tempFile.parentFile, "${tempFile.name}.chunks")
        if (legacyChunkDir.exists()) {
            legacyChunkDir.deleteRecursively()
        }

        val stateFile = File(tempFile.parentFile, "${tempFile.name}.download.meta")
        val legacyStateFile = File(tempFile.parentFile, "${tempFile.name}.offsets")
        val chunkSize = (totalBytes + numWorkers - 1) / numWorkers

        // Ensure destination parent exists
        tempFile.parentFile?.mkdirs()

        // Pre-allocate file directly to exact length using RandomAccessFile to ensure physical expansion
        try {
            if (!tempFile.exists() || tempFile.length() < totalBytes) {
                java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.setLength(totalBytes)
                }
            }
        } catch (e: Exception) {
            AppLogger.w("File pre-allocation via RandomAccessFile failed: ${e.message}")
        }

        val channel = FileChannel.open(
            tempFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )

        // Restore worker progress from state file if resuming
        val workerProgress = Array(numWorkers) { AtomicLong(0L) }
        if (legacyStateFile.exists()) legacyStateFile.delete()
        val stateLines = runCatching { stateFile.takeIf { it.isFile }?.readLines().orEmpty() }.getOrDefault(emptyList())
        val stateGeometryMatches = stateLines.contains("TOTAL:$totalBytes") && stateLines.contains("WORKERS:$numWorkers")
        if (stateGeometryMatches && tempFile.exists() && tempFile.length() >= totalBytes) {
            try {
                stateLines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("TOTAL:") || trimmed.startsWith("WORKERS:")) {
                        // Header metadata
                        return@forEach
                    }
                    val parts = trimmed.split(":")
                    if (parts.size == 2) {
                        val idx = parts[0].toIntOrNull()
                        val bytes = parts[1].toLongOrNull()
                        if (idx != null && bytes != null && idx in 0 until numWorkers) {
                            val startByte = idx * chunkSize
                            val endByte = ((idx + 1) * chunkSize - 1).coerceAtMost(totalBytes - 1)
                            val expectedLength = (endByte - startByte + 1).coerceAtLeast(0L)
                            workerProgress[idx].set(bytes.coerceIn(0L, expectedLength))
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("Failed to read download state: ${e.message}")
            }
        } else if (stateFile.exists()) {
            AppLogger.w("Discarding download progress with mismatched worker geometry or file size")
            stateFile.delete()
        }

        val initialBytes = (0 until numWorkers).sumOf { workerProgress[it].get() }
        val downloadedTotal = AtomicLong(initialBytes)
        val hasError = AtomicBoolean(false)
        val lastStateSaveTime = AtomicLong(System.currentTimeMillis())

        onProgress(initialBytes.coerceAtMost(totalBytes), totalBytes, 0L)

        val saveState = {
            try {
                val tmpStateFile = File(tempFile.parentFile, "${stateFile.name}.tmp")
                val lines = buildString {
                    appendLine("TOTAL:$totalBytes")
                    appendLine("WORKERS:$numWorkers")
                    for (i in 0 until numWorkers) {
                        appendLine("$i:${workerProgress[i].get()}")
                    }
                }
                tmpStateFile.writeText(lines)
                if (tmpStateFile.exists()) {
                    if (stateFile.exists()) stateFile.delete()
                    tmpStateFile.renameTo(stateFile)
                }
            } catch (e: Exception) {
                AppLogger.w("Failed to save download state: ${e.message}")
            }
        }

        val workers = (0 until numWorkers).map { workerIdx ->
            val startByte = workerIdx * chunkSize
            val endByte = ((workerIdx + 1) * chunkSize - 1).coerceAtMost(totalBytes - 1)
            val expectedChunkLength = (endByte - startByte + 1).coerceAtLeast(0L)

            launch(Dispatchers.IO) {
                if (startByte > endByte || isCancelled() || hasError.get()) return@launch

                val alreadyDownloaded = workerProgress[workerIdx].get()
                if (alreadyDownloaded >= expectedChunkLength) {
                    return@launch
                }

                var attempts = 0
                val maxAttempts = 5
                while (attempts < maxAttempts && !isCancelled() && !hasError.get()) {
                    val currentOffset = workerProgress[workerIdx].get()
                    val requestStartByte = startByte + currentOffset
                    if (requestStartByte > endByte) {
                        break
                    }

                    try {
                        val reqBuilder = Request.Builder().url(url)
                        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                        reqBuilder.addHeader("Range", "bytes=$requestStartByte-$endByte")

                        executeCancellable(reqBuilder.build()) { response ->
                            if (response.code == 416) {
                                throw RangeRejectedException()
                            }
                            if (response.code !in 200..299) {
                                throw IllegalStateException("Thread $workerIdx HTTP ${response.code}")
                            }
                            if (
                                response.code != 206 ||
                                response.header("Content-Range") != "bytes $requestStartByte-$endByte/$totalBytes" ||
                                rangeValidatorMismatch(headers, response)
                            ) {
                                throw RangeRejectedException()
                            }

                            val body = response.body
                            val buffer = ByteArray(64 * 1024)
                            val byteBuffer = ByteBuffer.wrap(buffer)
                            var writePos = requestStartByte

                            body.byteStream().use { input ->
                                while (!isCancelled() && !hasError.get()) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    if (writePos + read > endByte + 1) throw RangeRejectedException()

                                    byteBuffer.position(0)
                                    byteBuffer.limit(read)
                                    var writtenTotal = 0
                                    while (byteBuffer.hasRemaining()) {
                                        val written = channel.write(byteBuffer, writePos + writtenTotal)
                                        writtenTotal += written
                                    }

                                    writePos += read
                                    workerProgress[workerIdx].addAndGet(read.toLong())
                                    val total = downloadedTotal.addAndGet(read.toLong())
                                    speedTracker.record(read.toLong())

                                    // Periodic state flush every 1 second
                                    val now = System.currentTimeMillis()
                                    val prev = lastStateSaveTime.get()
                                    if (now - prev >= 1000L && lastStateSaveTime.compareAndSet(prev, now)) {
                                        saveState()
                                    }

                                    onProgress(
                                        total.coerceAtMost(totalBytes),
                                        totalBytes,
                                        speedTracker.getCurrentSpeed(),
                                    )
                                }
                            }
                        }
                        break // Success on this worker
                    } catch (e: RangeRejectedException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        attempts++
                        AppLogger.w("Thread $workerIdx attempt $attempts failed: ${e.message}")
                        if (attempts >= maxAttempts) {
                            hasError.set(true)
                        }
                        delay(1000L * attempts)
                    }
                }
            }
        }

        try {
            workers.joinAll()
        } finally {
            withContext(NonCancellable) {
                try {
                    channel.force(true)
                } catch (_: Exception) {}
                try {
                    channel.close()
                } catch (_: Exception) {}
                saveState()
            }
        }

        if (isCancelled() || hasError.get()) {
            return@coroutineScope false
        }

        // Verify all chunks completed in-place
        val allChunksValid = (0 until numWorkers).all { workerIdx ->
            val startByte = workerIdx * chunkSize
            val endByte = ((workerIdx + 1) * chunkSize - 1).coerceAtMost(totalBytes - 1)
            val expectedChunkLength = (endByte - startByte + 1).coerceAtLeast(0L)
            workerProgress[workerIdx].get() == expectedChunkLength
        }

        if (!allChunksValid) {
            AppLogger.w("Download validation failed: some chunks incomplete")
            return@coroutineScope false
        }

        // Download completed in-place: zero assembly time, zero disk duplication
        AppLogger.i("Download completed directly in-place -> ${tempFile.name} ($totalBytes bytes)")
        stateFile.delete()
        legacyStateFile.delete()
        true
    }

    private suspend fun downloadSingleStream(
        url: String,
        headers: Map<String, String>,
        tempFile: File,
        identityFile: File,
        knownTotalBytes: Long?,
        totalBytes: Long,
        speedTracker: SpeedTracker,
        onProgress: (Long, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): SingleStreamResult = withContext(Dispatchers.IO) {
        var resumeValidator = expectedValidator(headers)
        var resumeExpectedTotalBytes = knownTotalBytes
        if (resumeValidator == null && tempFile.exists() && tempFile.length() > 0L) {
            // Without a validator, a partial file cannot be proven to belong to the
            // response we are about to request. Start over and never append retries.
            FileOutputStream(tempFile, false).use { }
        }
        val existingBytes = if (resumeValidator != null && tempFile.exists()) tempFile.length() else 0L

        // Emit initial progress immediately on startup or resume
        if (existingBytes > 0L) {
            onProgress(
                existingBytes.coerceAtMost(totalBytes),
                if (totalBytes > 0) totalBytes else existingBytes,
                0L,
            )
        }

        var attempts = 0
        val maxAttempts = 5
        var responseValidator: String? = null
        var responseTotalBytes = totalBytes

        while (attempts < maxAttempts && !isCancelled()) {
            val currentExisting = if (resumeValidator != null && tempFile.exists()) tempFile.length() else 0L
            if (resumeValidator == null && tempFile.exists() && tempFile.length() > 0L) {
                FileOutputStream(tempFile, false).use { }
            }
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                if (!k.equals("If-Range", ignoreCase = true) && !k.equals("Range", ignoreCase = true)) {
                    reqBuilder.addHeader(k, v)
                }
            }
            resumeValidator?.let { reqBuilder.header("If-Range", it) }
            if (resumeValidator != null && currentExisting > 0L) {
                reqBuilder.addHeader("Range", "bytes=$currentExisting-")
            }

            try {
                val responseResult = executeCancellable(reqBuilder.build()) { response ->
                    if (response.code == 416 && currentExisting > 0L) {
                        throw RangeRejectedException()
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download HTTP ${response.code}")
                    }
                    val isPartial = response.code == 206
                    val currentResponseValidator = extractUsableValidator(response)
                    val declaredContentLength = response.header("Content-Length")
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0L }
                    var expectedResponseLength: Long? = declaredContentLength
                    var observedTotalLength: Long? = declaredContentLength
                    if (isPartial) {
                        if (resumeValidator == null || currentExisting <= 0L) throw RangeRejectedException()
                        val range = parseContentRange(response.header("Content-Range"))
                            ?: throw RangeRejectedException()
                        val expectedTotal = resumeExpectedTotalBytes ?: range.total
                        if (
                            range.start != currentExisting ||
                            range.total != expectedTotal ||
                            range.end != expectedTotal - 1L ||
                            rangeValidatorMismatch(resumeValidator, response)
                        ) {
                            throw RangeRejectedException()
                        }
                        val rangeLength = range.end - range.start + 1L
                        if (declaredContentLength != null && declaredContentLength != rangeLength) {
                            throw RangeRejectedException()
                        }
                        expectedResponseLength = rangeLength
                        observedTotalLength = range.total
                    } else if (
                        resumeExpectedTotalBytes != null && currentResponseValidator != null &&
                        currentResponseValidator == resumeValidator
                    ) {
                        // A matching strong ETag makes the known entity size authoritative,
                        // including when a full response declares an inconsistent length.
                        expectedResponseLength = resumeExpectedTotalBytes
                        observedTotalLength = resumeExpectedTotalBytes
                    }
                    if (!isPartial) {
                        // A full response can be the server's If-Range fallback after the
                        // entity changed. Its bytes replace the partial file, so subsequent
                        // retries must resume against this response's validator and size,
                        // never the validator/length from the earlier probe.
                        if (currentResponseValidator != resumeValidator) {
                            // A restart must not trust this partial response under the old
                            // probe identity. The current validator remains in memory only;
                            // successful completion writes its exact identity later.
                            writeIdentityAtomically(identityFile, INCOMPLETE_RESPONSE_IDENTITY)
                        }
                        resumeValidator = currentResponseValidator
                        resumeExpectedTotalBytes = observedTotalLength.takeIf { currentResponseValidator != null }
                    }
                    val append = isPartial && currentExisting > 0L
                    val body = response.body
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = if (append) currentExisting else 0L
                    var responseBytesRead = 0L
                    val responseLengthLimit = expectedResponseLength

                    FileOutputStream(tempFile, append).use { output ->
                        body.byteStream().use { input ->
                            while (!isCancelled()) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                if (
                                    responseLengthLimit != null &&
                                    (
                                        responseBytesRead > responseLengthLimit ||
                                            read.toLong() > responseLengthLimit - responseBytesRead
                                        )
                                ) {
                                    output.channel.truncate(0L)
                                    throw IOException("Response body exceeded its expected length")
                                }
                                output.write(buffer, 0, read)
                                responseBytesRead += read.toLong()
                                downloaded += read
                                speedTracker.record(read.toLong())

                                val progressTotal = if (isPartial) {
                                    totalBytes.takeIf { it > 0L } ?: observedTotalLength ?: downloaded
                                } else {
                                    expectedResponseLength ?: totalBytes
                                }
                                onProgress(
                                    downloaded,
                                    progressTotal.coerceAtLeast(downloaded),
                                    speedTracker.getCurrentSpeed(),
                                )
                            }
                            output.flush()
                        }
                    }
                    SingleStreamResponse(
                        validator = currentResponseValidator,
                        totalBytes = observedTotalLength ?: responseBytesRead,
                        bodyLengthMatches = expectedResponseLength == null || responseBytesRead == expectedResponseLength,
                    )
                }
                responseValidator = responseResult.validator
                responseTotalBytes = responseResult.totalBytes
                val complete = !isCancelled() && responseResult.bodyLengthMatches &&
                    tempFile.exists() && tempFile.length() > 0L && tempFile.length() == responseTotalBytes
                if (complete) return@withContext SingleStreamResult(true, responseValidator, responseTotalBytes)

                attempts++
                AppLogger.w("Single stream response ended before the expected size (attempt $attempts/$maxAttempts)")
                if (attempts < maxAttempts) delay(1000L * attempts)
            } catch (e: RangeRejectedException) {
                attempts++
                if (currentExisting > 0L) {
                    FileOutputStream(tempFile, false).use { }
                    AppLogger.w("Resume range was rejected; restarting from byte zero")
                } else {
                    AppLogger.w("Download range was rejected (attempt $attempts/$maxAttempts)")
                }
                if (attempts < maxAttempts) delay(1000L * attempts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts++
                AppLogger.w("Single stream download attempt $attempts failed: ${e.message}")
                delay(1000L * attempts)
            }
        }
        SingleStreamResult(false, null, responseTotalBytes)
    }

    private data class SingleStreamResponse(
        val validator: String?,
        val totalBytes: Long,
        val bodyLengthMatches: Boolean,
    )

    private data class SingleStreamResult(val completed: Boolean, val validator: String?, val totalBytes: Long)

    private class RangeRejectedException : java.io.IOException("Server ignored or changed the requested byte range")

    private fun rangeValidatorMismatch(headers: Map<String, String>, response: Response): Boolean =
        rangeValidatorMismatch(expectedValidator(headers), response)

    private fun rangeValidatorMismatch(expectedValidator: String?, response: Response): Boolean {
        if (expectedValidator == null) return false
        val responseValidator = extractUsableValidator(response)
        return responseValidator != expectedValidator
    }

    private fun expectedValidator(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("If-Range", ignoreCase = true) }?.value

    private fun extractUsableValidator(response: Response): String? =
        response.header("ETag")?.trim()?.takeIf(::isStrongETag)

    private fun downloadIdentity(url: String, validator: String?, totalBytes: Long, supportsRange: Boolean): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$url|$validator|$totalBytes|$maxWorkers|$supportsRange".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun isStrongETag(value: String): Boolean =
        value.length >= 2 && value.first() == '"' && value.last() == '"' &&
            value.substring(1, value.lastIndex).all {
                it == '\u0021' || it in '\u0023'..'\u007E' || it in '\u0080'..'\u00FF'
            }

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let { CONTENT_RANGE.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return ContentRange(start, end, total).takeIf { start <= end && end < total }
    }

    private suspend fun <T> executeCancellable(request: Request, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation: CancellableContinuation<T> ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) {
                                runCatching { continuation.resumeWithException(e) }
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (!continuation.isActive) {
                                response.close()
                                return
                            }
                            val value = try {
                                response.use(consume)
                            } catch (e: Throwable) {
                                if (continuation.isActive) {
                                    runCatching { continuation.resumeWithException(e) }
                                }
                                return
                            }
                            if (continuation.isActive) {
                                runCatching { continuation.resume(value) }
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                if (continuation.isActive) {
                    runCatching { continuation.resumeWithException(e) }
                }
            }
        }

    private data class CompletionMarker(val identity: String, val totalBytes: Long)

    private fun readCompletionMarker(marker: File): CompletionMarker? {
        if (!marker.isFile) return null
        val lines = marker.readLines()
        if (lines.size != 2) return null
        val totalBytes = lines[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return CompletionMarker(lines[0], totalBytes)
    }

    private fun writeCompletionMarker(marker: File, identity: String, totalBytes: Long) {
        val temporary = File(marker.parentFile, "${marker.name}.tmp")
        temporary.writeText("$identity\n$totalBytes")
        try {
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeIdentityAtomically(identityFile: File, identity: String) {
        val temporary = File(identityFile.parentFile, "${identityFile.name}.tmp")
        temporary.writeText(identity)
        try {
            Files.move(
                temporary.toPath(),
                identityFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), identityFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class SpeedTracker {
        private var lastTime = System.currentTimeMillis()
        private var bytesSinceLast = 0L
        private var currentSpeed = 0L

        @Synchronized
        fun record(bytes: Long) {
            bytesSinceLast += bytes
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            if (elapsed >= 500) {
                currentSpeed = (bytesSinceLast * 1000) / elapsed
                bytesSinceLast = 0L
                lastTime = now
            }
        }

        @Synchronized
        fun getCurrentSpeed(): Long = currentSpeed
    }

    private companion object {
        const val INCOMPLETE_RESPONSE_IDENTITY = "incomplete-response"
        val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
    }
}
