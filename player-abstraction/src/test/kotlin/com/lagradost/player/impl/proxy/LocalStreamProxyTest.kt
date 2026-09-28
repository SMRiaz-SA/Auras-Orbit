package com.lagradost.player.impl.proxy

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalStreamProxyTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            LocalStreamProxy.start()
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            LocalStreamProxy.stop()
        }
    }

    @Test
    fun testServerStartsAndAssignsPort() {
        assertTrue(LocalStreamProxy.port > 0, "Server should assign a valid port > 0")
    }

    @Test
    fun testSessionRegistration() {
        val headers = mapOf("Authorization" to "Bearer test_token")
        val sessionId = LocalStreamProxy.registerSession(headers)

        assertTrue(sessionId.isNotEmpty(), "Session ID should not be empty")

        // Build URL
        val url = "https://example.com/video.m3u8"
        val proxyUrl = LocalStreamProxy.buildProxyUrl(sessionId, url)

        val encodedUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))

        assertEquals(
            "http://127.0.0.1:${LocalStreamProxy.port}/proxy?s=$sessionId&u=$encodedUrl",
            proxyUrl,
            "Proxy URL should be correctly formatted with base64 encoded URL",
        )
    }

    @Test
    fun proxyRequiresValidSessionAndUrlAndRevokedSessionsStopWorking() {
        val unknownSessionUrl = LocalStreamProxy.buildProxyUrl("unknown-capability", "http://127.0.0.1:1/video")
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, request(unknownSessionUrl).first)

        val sessionId = LocalStreamProxy.registerSession(emptyMap())
        val malformedBase64Url = "http://127.0.0.1:${LocalStreamProxy.port}/proxy?s=$sessionId&u=%25%25%25"
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, request(malformedBase64Url).first)
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, request(LocalStreamProxy.buildProxyUrl(sessionId, "file:///tmp/video")).first)

        assertTrue(LocalStreamProxy.unregisterSession(sessionId))
        assertFalse(LocalStreamProxy.unregisterSession(sessionId))
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, request(LocalStreamProxy.buildProxyUrl(sessionId, "http://127.0.0.1:1/video")).first)
    }

    @Test
    fun validSessionStreamsAndRedirectLoopsTerminateWithinBoundedRetries() {
        val redirectCount = AtomicInteger()
        val payload = "local-video-fixture".toByteArray()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/video") { exchange ->
                exchange.responseHeaders.add("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            createContext("/loop") { exchange ->
                redirectCount.incrementAndGet()
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/loop")
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1)
                exchange.close()
            }
            start()
        }
        val sessionId = LocalStreamProxy.registerSession(emptyMap())
        try {
            val originUrl = "http://127.0.0.1:${origin.address.port}"
            val (status, body) = request(LocalStreamProxy.buildProxyUrl(sessionId, "$originUrl/video"))
            assertEquals(HttpURLConnection.HTTP_OK, status)
            assertContentEquals(payload, body)

            val redirectUrl = LocalStreamProxy.buildProxyUrl(sessionId, "$originUrl/loop")
            assertEquals(HttpURLConnection.HTTP_INTERNAL_ERROR, request(redirectUrl).first)
            assertTrue(redirectCount.get() in 1..84, "Redirect retry count must remain bounded, got ${redirectCount.get()}")
        } finally {
            LocalStreamProxy.unregisterSession(sessionId)
            origin.stop(0)
        }
    }

    private fun request(url: String): Pair<Int, ByteArray> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        return try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            status to body
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun testResolveUrlTokenInheritance() {
        val base = "https://cdn.example.com/hls/master.m3u8?t=token123&s=999&expires=3600"

        // Case 1: Relative URL without query parameters
        val relRes = LocalStreamProxy.resolveUrl(base, "seg-1.ts")
        assertEquals("https://cdn.example.com/hls/seg-1.ts?t=token123&s=999&expires=3600", relRes)

        // Case 2: Absolute URL without query parameters (was previously failing and causing 403s on secondary tracks)
        val absRes = LocalStreamProxy.resolveUrl(base, "https://cdn.example.com/hls/seg-1-audio2.ts")
        assertEquals("https://cdn.example.com/hls/seg-1-audio2.ts?t=token123&s=999&expires=3600", absRes)

        // Case 3: Relative URL that already has a minor query parameter (e.g. asn=55836)
        val partialRes = LocalStreamProxy.resolveUrl(base, "seg-2.ts?asn=55836")
        assertEquals("https://cdn.example.com/hls/seg-2.ts?asn=55836&t=token123&s=999&expires=3600", partialRes)

        // Case 4: Cross-domain absolute URL should NOT inherit tokens for security
        val crossRes = LocalStreamProxy.resolveUrl(base, "https://analytics.tracker.com/event")
        assertEquals("https://analytics.tracker.com/event", crossRes)
    }

    @Test
    fun testCleanupSegmentCache() {
        LocalStreamProxy.cleanupSegmentCache()
        // Method should execute without exceptions
        assertTrue(true)
    }
}
