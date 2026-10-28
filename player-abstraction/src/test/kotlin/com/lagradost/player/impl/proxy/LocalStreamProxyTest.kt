package com.lagradost.player.impl.proxy

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun initSegmentCacheIsScopedToAuthenticatedSessionForSameUrl() {
        val authRequests = AtomicInteger()
        val payloadA = ftyp("AAAA")
        val payloadB = ftyp("BBBB")
        val mediaPayloadA = mdat("alpha-media".toByteArray(Charsets.US_ASCII))
        val mediaPayloadB = mdat("beta-media".toByteArray(Charsets.US_ASCII))
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            val respondByAuthorization: com.sun.net.httpserver.HttpHandler = { exchange ->
                authRequests.incrementAndGet()
                val payload = when (exchange.requestHeaders.getFirst("Authorization")) {
                    "Bearer alpha" -> if (exchange.requestURI.path == "/init.mp4") payloadA else mediaPayloadA
                    "Bearer beta" -> if (exchange.requestURI.path == "/init.mp4") payloadB else mediaPayloadB
                    else -> ByteArray(0)
                }
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            createContext("/init.mp4", respondByAuthorization)
            createContext("/media.mp4", respondByAuthorization)
            start()
        }
        val alphaSession = LocalStreamProxy.registerSession(mapOf("Authorization" to "Bearer alpha"))
        val betaSession = LocalStreamProxy.registerSession(mapOf("Authorization" to "Bearer beta"))
        val initUrl = "http://127.0.0.1:${origin.address.port}/init.mp4"
        val mediaUrl = "http://127.0.0.1:${origin.address.port}/media.mp4"
        val kid = "00".repeat(16)
        val key = "11".repeat(16)
        try {
            val alpha = request(LocalStreamProxy.buildProxyUrl(alphaSession, initUrl, action = "init_decrypt"))
            val beta = request(LocalStreamProxy.buildProxyUrl(betaSession, initUrl, action = "init_decrypt"))
            val alphaMedia = request(LocalStreamProxy.buildProxyUrl(alphaSession, mediaUrl, action = "decrypt") + "&kid=$kid&k=$key")
            val betaMedia = request(LocalStreamProxy.buildProxyUrl(betaSession, mediaUrl, action = "decrypt") + "&kid=$kid&k=$key")

            assertEquals(HttpURLConnection.HTTP_OK, alpha.first)
            assertEquals(HttpURLConnection.HTTP_OK, beta.first)
            assertContentEquals(payloadA, alpha.second)
            assertContentEquals(payloadB, beta.second)
            assertEquals(HttpURLConnection.HTTP_OK, alphaMedia.first)
            assertEquals(HttpURLConnection.HTTP_OK, betaMedia.first)
            assertContentEquals(mediaPayloadA, alphaMedia.second)
            assertContentEquals(mediaPayloadB, betaMedia.second)
            assertEquals(4, authRequests.get(), "each authenticated session must fetch its own init and media content")
        } finally {
            LocalStreamProxy.unregisterSession(alphaSession)
            LocalStreamProxy.unregisterSession(betaSession)
            origin.stop(0)
        }
    }

    @Test
    fun initSegmentCacheEnforcesByteBudgetAndExpiry() {
        var now = 0L
        val cache = BoundedTtlByteArrayCache<String>(maxBytes = 4, ttlMillis = 10) { now }
        cache.put("first", byteArrayOf(1, 2))
        cache.put("second", byteArrayOf(3, 4))
        assertContentEquals(byteArrayOf(1, 2), cache.get("first")) // make first most recently used

        cache.put("third", byteArrayOf(5, 6))
        assertNull(cache.get("second"), "least-recently-used entry should be evicted to stay within budget")
        assertEquals(4L, cache.byteSize())
        cache.put("oversized", byteArrayOf(7, 8, 9, 10, 11))
        assertNull(cache.get("oversized"), "an entry larger than the whole budget must not be cached")
        assertEquals(4L, cache.byteSize())

        now = 10L
        cache.put("fresh", byteArrayOf(12)) // writes also purge expired entries
        assertEquals(1, cache.size())
        assertEquals(1L, cache.byteSize())
        now = 20L
        assertNull(cache.get("fresh"), "expired entries must not be returned")
        assertEquals(0, cache.size())
        assertEquals(0L, cache.byteSize())
    }

    @Test
    fun stoppingProxyClearsInitSegmentCache() {
        @Suppress("UNCHECKED_CAST")
        val cache = LocalStreamProxy::class.java.getDeclaredField("initSegmentCache").let { field ->
            field.isAccessible = true
            field.get(LocalStreamProxy) as BoundedTtlByteArrayCache<Any>
        }
        cache.clear()
        cache.put("raw-fixture", byteArrayOf(1, 2, 3))
        assertEquals(1, cache.size())

        try {
            LocalStreamProxy.stop()
            assertEquals(0, cache.size())
        } finally {
            LocalStreamProxy.start()
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

    private fun ftyp(brand: String): ByteArray = ByteBuffer.allocate(12)
        .putInt(12)
        .put("ftyp".toByteArray(Charsets.US_ASCII))
        .put(brand.toByteArray(Charsets.US_ASCII))
        .array()

    private fun mdat(payload: ByteArray): ByteArray = ByteBuffer.allocate(8 + payload.size)
        .putInt(8 + payload.size)
        .put("mdat".toByteArray(Charsets.US_ASCII))
        .put(payload)
        .array()

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
