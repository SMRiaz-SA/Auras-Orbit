package com.lagradost.player.impl.proxy

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossOriginRedirectHeaderInterceptorTest {
    @Test
    fun crossOriginRedirectDropsCredentialsAndReducesNavigationHeaders() {
        var receivedHeaders: Map<String, String?> = emptyMap()
        var destinationHits = 0
        val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/media") { exchange ->
                destinationHits++
                receivedHeaders = mapOf(
                    "authorization" to exchange.requestHeaders.getFirst("Authorization"),
                    "cookie" to exchange.requestHeaders.getFirst("Cookie"),
                    "x-api-key" to exchange.requestHeaders.getFirst("X-Api-Key"),
                    "referer" to exchange.requestHeaders.getFirst("Referer"),
                    "origin" to exchange.requestHeaders.getFirst("Origin"),
                    "host" to exchange.requestHeaders.getFirst("Host"),
                    "range" to exchange.requestHeaders.getFirst("Range"),
                    "user-agent" to exchange.requestHeaders.getFirst("User-Agent"),
                )
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${destination.address.port}/media")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }

        try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .addNetworkInterceptor(CrossOriginRedirectHeaderInterceptor())
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:${source.address.port}/redirect")
                .header("Authorization", "Bearer provider-secret")
                .header("Cookie", "provider-session=secret")
                .header("X-Api-Key", "provider-api-secret")
                .header("Referer", "https://user:secret@example.org/watch/path?token=secret")
                .header("Origin", "https://example.org/account/path?token=secret/")
                .header("Range", "bytes=10-")
                .header("User-Agent", "Orbit-Test")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code, "final URL ${response.request.url}; destination hits $destinationHits")
                assertEquals("ok", response.body.string())
            }

            assertEquals(1, destinationHits)
            assertEquals(null, receivedHeaders["authorization"])
            assertEquals(null, receivedHeaders["cookie"])
            assertEquals(null, receivedHeaders["x-api-key"])
            assertEquals("https://example.org/", receivedHeaders["referer"])
            assertEquals("https://example.org", receivedHeaders["origin"])
            assertEquals("127.0.0.1:${destination.address.port}", receivedHeaders["host"])
            assertEquals("bytes=10-", receivedHeaders["range"])
            assertEquals("Orbit-Test", receivedHeaders["user-agent"])
        } finally {
            source.stop(0)
            destination.stop(0)
        }
    }

    @Test
    fun sameOriginRedirectRetainsProviderHeaders() {
        var receivedApiKey: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/start") { exchange ->
                exchange.responseHeaders.add("Location", "/media")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/media") { exchange ->
                receivedApiKey = exchange.requestHeaders.getFirst("X-Api-Key")
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }

        try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .addNetworkInterceptor(CrossOriginRedirectHeaderInterceptor())
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.address.port}/start")
                .header("X-Api-Key", "same-origin-secret")
                .build()

            client.newCall(request).execute().use { response -> assertTrue(response.isSuccessful) }
            assertEquals("same-origin-secret", receivedApiKey)
        } finally {
            server.stop(0)
        }
    }
}
