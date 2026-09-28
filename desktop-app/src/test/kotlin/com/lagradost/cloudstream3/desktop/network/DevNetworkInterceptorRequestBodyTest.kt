package com.lagradost.cloudstream3.desktop.network

import com.lagradost.common.net.NetworkTrafficBuffer
import com.sun.net.httpserver.HttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevNetworkInterceptorRequestBodyTest {
    @Test
    fun largeOneShotRequestBodyIsSentOnceAndNeverCapturedByDiagnostics() {
        val requestBytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val receivedBytes = AtomicReference<ByteArray?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange ->
            receivedBytes.set(exchange.requestBody.use { it.readBytes() })
            val response = "ok".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        val previousCaptureSetting = System.getProperty("auras.diagnostics.captureBodies")
        System.setProperty("auras.diagnostics.captureBodies", "false")
        NetworkTrafficBuffer.clear()
        try {
            val writes = AtomicInteger()
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength() = requestBytes.size.toLong()

                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) {
                    check(writes.incrementAndGet() == 1) { "One-shot request body was written more than once" }
                    sink.write(requestBytes)
                }
            }
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.address.port}/upload")
                .post(body)
                .build()

            OkHttpClient.Builder()
                .addInterceptor(DevNetworkInterceptor())
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body.string())
                }

            assertEquals(1, writes.get())
            assertContentEquals(requestBytes, receivedBytes.get())
            assertNull(NetworkTrafficBuffer.getSnapshot().single().requestBody)
        } finally {
            NetworkTrafficBuffer.clear()
            if (previousCaptureSetting == null) {
                System.clearProperty("auras.diagnostics.captureBodies")
            } else {
                System.setProperty("auras.diagnostics.captureBodies", previousCaptureSetting)
            }
            server.stop(0)
        }
    }
}
