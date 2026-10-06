package com.lagradost.cloudstream3.desktop.network

import com.lagradost.common.collections.BoundedLruCache
import com.lagradost.common.logging.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SettledEntry(
    val url: String,
    val html: String,
    val userAgent: String,
    val timestamp: Long = System.currentTimeMillis(),
)

object SettledPageCache {
    private const val TAG = "SettledPageCache"
    private const val TTL_MS = 60_000L // 60 seconds TTL
    private const val MAX_CACHED_HTML_CHARS = 512 * 1024

    private val cache = BoundedLruCache<String, SettledEntry>(16)
    private val cacheLock = Any()

    private fun normalizeUrl(url: String): String {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return url.trim().trimEnd('/')
        val scheme = httpUrl.scheme.lowercase()
        val host = httpUrl.host.lowercase()
        val port = if ((scheme == "http" && httpUrl.port == 80) || (scheme == "https" && httpUrl.port == 443)) "" else ":${httpUrl.port}"
        val encodedPath = httpUrl.encodedPath.trimEnd('/')
        val query = httpUrl.encodedQuery?.let { "?$it" } ?: ""
        return "$scheme://$host$port$encodedPath$query"
    }

    fun put(url: String, html: String, userAgent: String) {
        if (url.isBlank() || html.isBlank()) return
        if (html.length > MAX_CACHED_HTML_CHARS) {
            AppLogger.d("$TAG: Skipping oversized settled page for $url (length=${html.length})")
            return
        }

        // If the HTML looks like the browser's JSON viewer (<pre>{...}</pre>), do not cache it
        if (html.contains("<pre") && (html.contains("{\"") || html.contains("[{"))) {
            AppLogger.d("$TAG: Skipping caching browser JSON-in-DOM viewer wrapper for: $url")
            return
        }

        val normalized = normalizeUrl(url)
        AppLogger.d("$TAG: Caching settled HTML for $normalized (length=${html.length})")
        synchronized(cacheLock) {
            cache[normalized] = SettledEntry(url = normalized, html = html, userAgent = userAgent)
        }
    }

    /**
     * Consumes and removes a settled entry atomically for the exact URL.
     * This is intended for the post-clearance retry so that the HTML captured
     * during clearance is used exactly once and does not poison subsequent requests.
     */
    fun consume(url: String): SettledEntry? {
        val normalized = normalizeUrl(url)
        val entry = synchronized(cacheLock) { cache.remove(normalized) } ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            AppLogger.d("$TAG: Cache entry EXPIRED for $normalized during consume")
            return null
        }
        AppLogger.d("$TAG: Cache CONSUMED for $url (length=${entry.html.length})")
        return entry
    }

    fun get(url: String): SettledEntry? {
        val normalized = normalizeUrl(url)
        val entry = synchronized(cacheLock) {
            val cached = cache[normalized]
            if (cached != null && System.currentTimeMillis() - cached.timestamp > TTL_MS) {
                cache.remove(normalized)
                null
            } else {
                cached
            }
        } ?: run {
            AppLogger.d("$TAG: Cache MISS for $url (cached entries=${cache.size()})")
            return null
        }
        AppLogger.d("$TAG: Cache HIT for $url -> matched ${entry.url} (length=${entry.html.length})")
        return entry
    }

    fun remove(url: String) {
        synchronized(cacheLock) { cache.remove(normalizeUrl(url)) }
    }

    fun clear() {
        synchronized(cacheLock) { cache.clear() }
    }
}
