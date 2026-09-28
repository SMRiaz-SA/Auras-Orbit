package com.lagradost.cloudstream3.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdaterNetworkTest {
    @Test
    fun failedCheckClearsStaleReleaseAndNonForcedRetryRecovers() = runBlocking {
        val response = AtomicReference(200 to releaseJson)
        val fixture = fixture(response)
        try {
            val checker = checker(fixture.address.port)

            checker.checkForUpdates()
            assertEquals("v0.3.0", checker.latestRelease.value?.tag_name)
            assertNull(checker.lastError.value)

            response.set(404 to "not found")
            checker.checkForUpdates(force = true)
            assertNull(checker.latestRelease.value)
            assertTrue(checker.lastError.value.orEmpty().contains("HTTP 404"))

            response.set(200 to releaseJson)
            checker.checkForUpdates(force = false)
            assertNotNull(checker.latestRelease.value)
            assertNull(checker.lastError.value)
        } finally {
            fixture.stop(0)
        }
    }

    @Test
    fun rateLimitMalformedPayloadAndOfflineErrorsAreVisibleAndRetryable() = runBlocking {
        val response = AtomicReference(429 to "rate limited")
        val fixture = fixture(response)
        val checker = checker(fixture.address.port)
        try {
            checker.checkForUpdates()
            assertTrue(checker.lastError.value.orEmpty().contains("HTTP 429"))

            response.set(200 to "not-json")
            checker.checkForUpdates()
            assertTrue(checker.lastError.value.orEmpty().isNotBlank())
            assertNull(checker.latestRelease.value)
        } finally {
            fixture.stop(0)
        }

        checker.checkForUpdates()
        assertTrue(checker.lastError.value.orEmpty().isNotBlank())
        assertNull(checker.latestRelease.value)
    }

    private fun checker(port: Int): ReleaseChecker = ReleaseChecker(
        client = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .build(),
        endpoint = { "http://127.0.0.1:$port/releases" },
        includePrereleases = { false },
        currentVersion = { "0.2.0.00" },
    )

    private fun fixture(response: AtomicReference<Pair<Int, String>>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/releases") { exchange ->
                val (status, body) = response.get()
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private companion object {
        const val releaseJson = """
            [{
              "tag_name":"v0.3.0",
              "name":"Auras Orbit 0.3.0",
              "body":"Fixture release",
              "html_url":"https://example.invalid/release",
              "published_at":"2026-09-27T00:00:00Z",
              "prerelease":false,
              "draft":false,
              "assets":[{"name":"Auras-Orbit-Portable-0.3.0.zip","browser_download_url":"https://example.invalid/app.zip"}]
            }]
        """
    }
}
