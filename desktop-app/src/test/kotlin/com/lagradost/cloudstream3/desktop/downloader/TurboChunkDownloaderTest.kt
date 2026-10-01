package com.lagradost.cloudstream3.desktop.downloader

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurboChunkDownloaderTest {
    @Test
    fun ignoredWorkerRangesRestartWithoutCorruptingFile() = runBlocking {
        val bytes = ByteArray(5_100_001) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            if (call.requestHeaders.getFirst("Range") == "bytes=0-0") {
                call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                call.sendResponseHeaders(206, 1)
                call.responseBody.use { it.write(bytes, 0, 1) }
            } else {
                call.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { call.responseBody.use { it.write(bytes) } }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            part.writeBytes(ByteArray(bytes.size))
            val result = TurboChunkDownloader(maxWorkers = 2).download(
                "http://127.0.0.1:${server.address.port}/media",
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )
            assertTrue(result)
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedWorkerContentRangesRestartAsAFullStream() = runBlocking {
        val bytes = ByteArray(5_100_001) { (it % 239).toByte() }
        var fullStreamRequests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            val range = call.requestHeaders.getFirst("Range")
            when {
                range == "bytes=0-0" -> {
                    call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                    call.sendResponseHeaders(206, 1)
                    call.responseBody.use { it.write(bytes, 0, 1) }
                }
                range != null -> {
                    val start = range.substringAfter("bytes=").substringBefore('-').toLong()
                    val end = range.substringAfter('-').toLong()
                    call.responseHeaders.add("Content-Range", "bytes ${start + 1}-$end/${bytes.size}")
                    call.sendResponseHeaders(206, -1)
                    call.close()
                }
                else -> {
                    fullStreamRequests++
                    call.sendResponseHeaders(200, bytes.size.toLong())
                    call.responseBody.use { it.write(bytes) }
                }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-range-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val result = TurboChunkDownloader(maxWorkers = 2).download(
                "http://127.0.0.1:${server.address.port}/media",
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertEquals(1, fullStreamRequests)
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledDownloadClosesTheInFlightHttpBodyPromptly() = runBlocking {
        val totalBytes = 2_000_000
        val fullRequestStarted = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        val responseClosed = CountDownLatch(1)
        val responseBytes = 50_000_000L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            if (call.requestHeaders.getFirst("Range") == "bytes=0-0") {
                call.responseHeaders.add("Content-Range", "bytes 0-0/$totalBytes")
                call.sendResponseHeaders(206, 1)
                call.responseBody.use { it.write(1) }
            } else {
                fullRequestStarted.countDown()
                call.sendResponseHeaders(200, responseBytes)
                try {
                    call.responseBody.use { output ->
                        val chunk = ByteArray(64 * 1024)
                        var written = 0L
                        while (written < responseBytes) {
                            val count = minOf(chunk.size.toLong(), responseBytes - written).toInt()
                            output.write(chunk, 0, count)
                            output.flush()
                            written += count
                            if (written == count.toLong()) bodyStarted.countDown()
                            Thread.sleep(10)
                        }
                    }
                } catch (_: Exception) {
                    // Closing the client call is expected to unblock this write.
                } finally {
                    responseClosed.countDown()
                }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-cancel-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val job = launch(Dispatchers.IO) {
                TurboChunkDownloader(maxWorkers = 2).download(
                    "http://127.0.0.1:${server.address.port}/media",
                    emptyMap(),
                    java.io.File(directory, "media.bin"),
                    part,
                    totalBytes.toLong(),
                    { _, _, _ -> },
                    { false },
                )
            }

            assertTrue(
                fullRequestStarted.await(5, TimeUnit.SECONDS),
                "full download request did not reach the fixture",
            )
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS), "fixture did not begin the streaming response")
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(responseClosed.await(2, TimeUnit.SECONDS))
            assertTrue(!java.io.File(part.path + ".complete").exists())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun parallelResumeRejectsProgressFromDifferentWorkerGeometry() = runBlocking {
        val bytes = ByteArray(5_100_001) { (it % 223).toByte() }
        val workerRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            val range = call.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                call.sendResponseHeaders(206, 1)
                call.responseBody.use { it.write(bytes, 0, 1) }
            } else {
                workerRequests.incrementAndGet()
                val start = range.substringAfter("bytes=").substringBefore('-').toInt()
                val end = range.substringAfter('-').toInt()
                call.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                call.sendResponseHeaders(206, (end - start + 1).toLong())
                call.responseBody.use { it.write(bytes, start, end - start + 1) }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-geometry-test").toFile()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media"
            val part = java.io.File(directory, "media.part").apply { writeBytes(ByteArray(bytes.size)) }
            val identity = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$url|\"fixture\"|${bytes.size}|2|true".toByteArray())
                .joinToString("") { "%02x".format(it) }
            java.io.File(part.path + ".identity").writeText(identity)
            java.io.File(part.path + ".download.meta").writeText(
                "TOTAL:${bytes.size}\nWORKERS:3\n0:2550001\n1:2550000\n",
            )

            val result = TurboChunkDownloader(maxWorkers = 2).download(
                url,
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertEquals(2, workerRequests.get())
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun fullLengthTempFileNeedsACompletionMarkerToBeTrusted() = runBlocking {
        val bytes = ByteArray(2_000_000) { (it % 227).toByte() }
        var fullStreamRequests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            if (call.requestHeaders.getFirst("Range") == "bytes=0-0") {
                call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                call.sendResponseHeaders(206, 1)
                call.responseBody.use { it.write(bytes, 0, 1) }
            } else {
                fullStreamRequests++
                call.sendResponseHeaders(200, bytes.size.toLong())
                call.responseBody.use { it.write(bytes) }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-completion-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val downloader = TurboChunkDownloader(maxWorkers = 2)
            val url = "http://127.0.0.1:${server.address.port}/media"
            suspend fun download() = downloader.download(
                url,
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(download())
            assertTrue(java.io.File(part.path + ".complete").isFile)
            assertTrue(download())
            assertEquals(1, fullStreamRequests)

            java.io.File(part.path + ".complete").delete()
            part.writeBytes(ByteArray(bytes.size))
            assertTrue(download())
            assertEquals(2, fullStreamRequests)
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedSingleStreamBodyIsDiscardedBeforeRetry() = runBlocking {
        val bytes = ByteArray(2_000_000) { (it % 211).toByte() }
        val fullRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            if (call.requestHeaders.getFirst("Range") == "bytes=0-0") {
                call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                call.sendResponseHeaders(206, 1)
                call.responseBody.use { it.write(bytes, 0, 1) }
            } else {
                val oversized = fullRequests.incrementAndGet() == 1
                call.sendResponseHeaders(200, (bytes.size + if (oversized) 1 else 0).toLong())
                call.responseBody.use { output ->
                    output.write(bytes)
                    if (oversized) output.write(0)
                }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-size-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val result = TurboChunkDownloader(maxWorkers = 2).download(
                "http://127.0.0.1:${server.address.port}/media",
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertEquals(2, fullRequests.get())
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun parallelHttp416FallsBackToOneFullStream() = runBlocking {
        val bytes = ByteArray(5_100_001) { (it % 197).toByte() }
        val rejectedRange = java.util.concurrent.atomic.AtomicBoolean(false)
        val fullRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"fixture\"")
            val range = call.requestHeaders.getFirst("Range")
            when {
                range == "bytes=0-0" -> {
                    call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                    call.sendResponseHeaders(206, 1)
                    call.responseBody.use { it.write(bytes, 0, 1) }
                }
                range != null && rejectedRange.compareAndSet(false, true) -> {
                    call.responseHeaders.add("Content-Range", "bytes */${bytes.size}")
                    call.sendResponseHeaders(416, -1)
                }
                range != null -> {
                    val start = range.substringAfter("bytes=").substringBefore('-').toInt()
                    val end = range.substringAfter('-').toInt()
                    call.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                    call.sendResponseHeaders(206, (end - start + 1).toLong())
                    call.responseBody.use { it.write(bytes, start, end - start + 1) }
                }
                else -> {
                    fullRequests.incrementAndGet()
                    call.sendResponseHeaders(200, bytes.size.toLong())
                    call.responseBody.use { it.write(bytes) }
                }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-416-parallel-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val result = TurboChunkDownloader(maxWorkers = 2).download(
                "http://127.0.0.1:${server.address.port}/media",
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertTrue(rejectedRange.get())
            assertEquals(1, fullRequests.get())
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun resumedSingleStreamHttp416RestartsFromZero() = runBlocking {
        val bytes = ByteArray(2_000_000) { (it % 193).toByte() }
        val partialLength = 512_000
        val range416 = java.util.concurrent.atomic.AtomicInteger()
        val fullRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"resume-fixture\"")
            val range = call.requestHeaders.getFirst("Range")
            when {
                range == "bytes=0-0" -> {
                    call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                    call.sendResponseHeaders(206, 1)
                    call.responseBody.use { it.write(bytes, 0, 1) }
                }
                range != null -> {
                    range416.incrementAndGet()
                    call.responseHeaders.add("Content-Range", "bytes */${bytes.size}")
                    call.sendResponseHeaders(416, -1)
                }
                else -> {
                    fullRequests.incrementAndGet()
                    call.sendResponseHeaders(200, bytes.size.toLong())
                    call.responseBody.use { it.write(bytes) }
                }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-416-resume-test").toFile()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media"
            val part = java.io.File(directory, "media.part").apply { writeBytes(bytes.copyOf(partialLength)) }
            val identity = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$url|\"resume-fixture\"|${bytes.size}|2|true".toByteArray())
                .joinToString("") { "%02x".format(it) }
            java.io.File(part.path + ".identity").writeText(identity)

            val result = TurboChunkDownloader(maxWorkers = 2).download(
                url,
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertEquals(1, range416.get())
            assertEquals(1, fullRequests.get())
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun changedEntityDuringParallelRangesRestartsAsOneConsistentStream() = runBlocking {
        val newEntity = ByteArray(5_100_001) { (it % 181).toByte() }
        val rangeRequests = java.util.concurrent.atomic.AtomicInteger()
        val fullRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            val range = call.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                call.responseHeaders.add("ETag", "\"old-entity\"")
                call.responseHeaders.add("Content-Range", "bytes 0-0/${newEntity.size}")
                call.sendResponseHeaders(206, 1)
                call.responseBody.use { it.write(newEntity, 0, 1) }
            } else if (range != null) {
                rangeRequests.incrementAndGet()
                call.responseHeaders.add("ETag", "\"new-entity\"")
                val start = range.substringAfter("bytes=").substringBefore('-').toInt()
                val end = range.substringAfter('-').toInt()
                call.responseHeaders.add("Content-Range", "bytes $start-$end/${newEntity.size}")
                call.sendResponseHeaders(206, (end - start + 1).toLong())
                call.responseBody.use { it.write(newEntity, start, end - start + 1) }
            } else {
                fullRequests.incrementAndGet()
                call.responseHeaders.add("ETag", "\"new-entity\"")
                call.sendResponseHeaders(200, newEntity.size.toLong())
                call.responseBody.use { it.write(newEntity) }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-entity-change-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val result = TurboChunkDownloader(maxWorkers = 2).download(
                "http://127.0.0.1:${server.address.port}/media",
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                newEntity.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertTrue(rangeRequests.get() > 0)
            assertEquals(1, fullRequests.get())
            assertContentEquals(newEntity, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun truncatedChunkedSingleStreamResumesFromTheReceivedByteCount() = runBlocking {
        val bytes = ByteArray(2_000_000) { (it % 179).toByte() }
        val partialLength = bytes.size / 2
        val fullRequests = java.util.concurrent.atomic.AtomicInteger()
        val resumeRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { call ->
            call.responseHeaders.add("ETag", "\"chunked-fixture\"")
            val range = call.requestHeaders.getFirst("Range")
            when {
                range == "bytes=0-0" -> {
                    call.responseHeaders.add("Content-Range", "bytes 0-0/${bytes.size}")
                    call.sendResponseHeaders(206, 1)
                    call.responseBody.use { it.write(bytes, 0, 1) }
                }
                range != null -> {
                    resumeRequests.incrementAndGet()
                    val start = range.substringAfter("bytes=").substringBefore('-').toInt()
                    call.responseHeaders.add("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    call.sendResponseHeaders(206, (bytes.size - start).toLong())
                    call.responseBody.use { it.write(bytes, start, bytes.size - start) }
                }
                else -> {
                    fullRequests.incrementAndGet()
                    call.sendResponseHeaders(200, 0)
                    call.responseBody.use { it.write(bytes, 0, partialLength) }
                }
            }
            call.close()
        }
        server.start()
        val directory = Files.createTempDirectory("orbit-download-chunked-truncate-test").toFile()
        try {
            val part = java.io.File(directory, "media.part")
            val result = TurboChunkDownloader(maxWorkers = 2).download(
                "http://127.0.0.1:${server.address.port}/media",
                emptyMap(),
                java.io.File(directory, "media.bin"),
                part,
                bytes.size.toLong(),
                { _, _, _ -> },
                { false },
            )

            assertTrue(result)
            assertEquals(1, fullRequests.get())
            assertEquals(1, resumeRequests.get())
            assertContentEquals(bytes, part.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
