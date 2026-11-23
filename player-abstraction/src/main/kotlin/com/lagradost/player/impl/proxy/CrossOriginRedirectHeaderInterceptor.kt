package com.lagradost.player.impl.proxy

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/**
 * Prevents headers supplied for a source URL from being carried to a different origin by
 * OkHttp's automatic redirect handling. Same-origin redirects retain the original headers.
 */
class CrossOriginRedirectHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalUrl = chain.call().request().url
        if (request.url.hasSameOriginAs(originalUrl)) return chain.proceed(request)

        val safeHeaders = request.headers.newBuilder()
        request.headers.names().forEach { name ->
            if (name.lowercase(Locale.ROOT) !in SAFE_CROSS_ORIGIN_HEADERS) {
                safeHeaders.removeAll(name)
            }
        }

        request.header("Referer")?.let { referer ->
            val sanitized = navigationOrigin(referer)
            if (sanitized == null) {
                safeHeaders.removeAll("Referer")
            } else {
                safeHeaders.set("Referer", sanitized)
            }
        }
        request.header("Origin")?.let { origin ->
            val sanitized = if (origin.equals("null", ignoreCase = true)) "null" else navigationOrigin(origin)?.removeSuffix("/")
            if (sanitized == null) {
                safeHeaders.removeAll("Origin")
            } else {
                safeHeaders.set("Origin", sanitized)
            }
        }
        // Network interceptors run after OkHttp has generated Host, so stripping that
        // header would make the redirected request invalid. Rebuild it from the new URL.
        safeHeaders.set("Host", request.url.hostHeaderValue())

        return chain.proceed(request.newBuilder().headers(safeHeaders.build()).build())
    }

    private fun navigationOrigin(value: String): String? = value.toHttpUrlOrNull()
        ?.withoutPathAndCredentials()
        ?.toString()

    private fun HttpUrl.withoutPathAndCredentials(): HttpUrl = newBuilder()
        .username("")
        .password("")
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()

    private fun HttpUrl.hasSameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun HttpUrl.hostHeaderValue(): String {
        val hostValue = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (isHttps) 443 else 80
        return if (port == defaultPort) hostValue else "$hostValue:$port"
    }

    private companion object {
        // These headers support normal resource navigation and ranged media requests. Host
        // is rebuilt from the redirected URL; provider-specific credentials are dropped.
        // Referer and Origin are separately reduced to the origin above.
        val SAFE_CROSS_ORIGIN_HEADERS = setOf(
            "accept",
            "accept-encoding",
            "accept-language",
            "host",
            "if-range",
            "range",
            "user-agent",
            "referer",
            "origin",
        )
    }
}
