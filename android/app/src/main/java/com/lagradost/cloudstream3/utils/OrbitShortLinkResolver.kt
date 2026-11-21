@file:JvmName("OrbitShortLinkResolver")

package com.lagradost.cloudstream3.utils

import android.util.Base64
import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.MainActivityKt
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val MAX_RESOLUTION_HOPS = 10
private const val MAX_HTTP_GETS = 10
private const val MAX_REDIRECT_CODES = 10
private const val REQUEST_TIMEOUT_SECONDS = 8L
private const val RESOLUTION_DEADLINE_MILLIS = 30_000L
private const val MAX_TEXT_BYTES = 512 * 1024L
private const val MAX_EXTRACTOR_POST_BYTES = 16 * 1024L
private const val MAX_DOOD_PAGE_BYTES = 256 * 1024L
private const val MAX_DOOD_PASS_MD5_BYTES = 4 * 1024L
private const val MAX_PUBLIC_PLAYLIST_BYTES = 256 * 1024L
private const val MAX_PUBLIC_PLAYLIST_URL_CHARS = 4_096
private const val MAX_PUBLIC_PLAYLIST_HEADER_COUNT = 32
private const val MAX_PUBLIC_PLAYLIST_HEADER_CHARS = 16 * 1024
private const val MAX_RESOLVER_URL_CHARS = 4_096
private const val MAX_FILEMOON_WEBVIEW_HTTP_REQUESTS = 48
private const val MAX_FILEMOON_WEBVIEW_RESOURCE_BYTES = 2L * 1024L * 1024L
private const val MAX_FILEMOON_WEBVIEW_TOTAL_BYTES = 12L * 1024L * 1024L
private const val MAX_GMPLAYER_URL_CHARS = 4_096
private const val MAX_GMPLAYER_RESPONSE_BYTES = 64 * 1024L
private const val MAX_GMPLAYER_REFERER_CHARS = 4_096
private const val MAX_VIDSTACK_API_URL_CHARS = 2_048
private const val MAX_VIDSTACK_RESPONSE_BYTES = 256 * 1024L
private const val MAX_VIDSTACK_REQUEST_MILLIS = 20_000L
private const val MAX_VOE_PAGE_RESPONSE_BYTES = 512 * 1024L
private const val MAX_VOE_PAGE_REQUEST_MILLIS = 25_000L
private const val MAX_VOE_PAGE_URL_CHARS = 4_096
private const val MAX_VIDHIDE_PAGE_RESPONSE_BYTES = 512 * 1024L
private const val MAX_VIDHIDE_PAGE_REQUEST_MILLIS = 25_000L
private const val MAX_VIDHIDE_PAGE_URL_CHARS = 4_096
private const val MP4UPLOAD_ALLOWED_DOMAIN = "mp4upload.com"
private val FILEMOON_ALLOWED_HOSTS = setOf("filemoon.to", "filemoon.in", "filemoon.sx")
private val VIDSTACK_ALLOWED_HOSTS = setOf("vidstack.io", "server1.uns.bio")
private val VOE_ALLOWED_HOSTS = setOf(
    "voe.sx", "donaldlineelse.com", "charlestoughrace.com", "tubelessceliolymph.com",
    "simpulumlamerop.com", "urochsunloath.com", "nathanfromsubject.com", "yip.su",
    "metagnathtuggers.com",
)
private class VoePlaylistCookiesDisabled : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VoePlaylistCookiesDisabled>
}
private val VOE_PLAYLIST_COOKIES_DISABLED = VoePlaylistCookiesDisabled()
private val ODNOKLASSNIKI_ALLOWED_HOSTS = setOf(
    "ok.ru", "www.ok.ru", "odnoklassniki.ru", "www.odnoklassniki.ru",
    "m.ok.ru", "m.odnoklassniki.ru",
)
private const val DNS_LOOKUP_WORKERS = 2
private const val DNS_LOOKUP_QUEUE_CAPACITY = 8
private const val PLAYBACK_DNS_TIMEOUT_MILLIS = 5_000L
private const val MAX_PLAYBACK_DNS_ADDRESSES = 16

/** A page response fetched through a one-extraction, DNS-pinned request budget. */
internal data class PinnedExtractorResponse(
    val url: String,
    val code: Int,
    val body: String,
)

/**
 * Fetches one public extractor page through HttpBudget. Each redirect is revalidated and pinned
 * before its request; cookies are disabled and response bytes, URL length, redirects, and time are
 * bounded by the same transport used by the Orbit URL resolvers.
 */
internal suspend fun getPinnedExtractorResponse(
    url: String,
    headers: Map<String, String> = emptyMap(),
    interceptor: Interceptor? = null,
): PinnedExtractorResponse {
    val response = HttpBudget(
        allowCookies = false,
        maxRequests = MAX_HTTP_GETS,
        maxUrlChars = MAX_RESOLVER_URL_CHARS,
    ).get(
        url,
        allowRedirects = true,
        requestHeaders = requireSafeExtractorHeaders(headers),
        interceptor = interceptor,
    )
    return PinnedExtractorResponse(response.url, response.code, response.boundedText(MAX_TEXT_BYTES))
}

/** Posts the pinned extractor's form fields without cookies or automatic redirects. */
internal suspend fun postPinnedExtractorForm(
    url: String,
    fields: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
): PinnedExtractorResponse {
    val response = HttpBudget(
        allowCookies = false,
        maxRequests = 1,
        maxUrlChars = MAX_RESOLVER_URL_CHARS,
    ).postFormOnce(url, requireSafeExtractorHeaders(headers), requireSafeExtractorFields(fields))
    return PinnedExtractorResponse(response.url, response.code, response.boundedText(MAX_TEXT_BYTES))
}

/** Posts the pinned extractor's bounded JSON body without cookies or automatic redirects. */
internal suspend fun postPinnedExtractorJson(
    url: String,
    body: RequestBody,
    headers: Map<String, String> = emptyMap(),
): PinnedExtractorResponse {
    if (body.contentLength() !in 0L..MAX_EXTRACTOR_POST_BYTES) throw UnsafeResolverTarget()
    val response = HttpBudget(
        allowCookies = false,
        maxRequests = 1,
        maxUrlChars = MAX_RESOLVER_URL_CHARS,
    ).postJsonOnce(url, requireSafeExtractorHeaders(headers), body)
    return PinnedExtractorResponse(response.url, response.code, response.boundedText(MAX_TEXT_BYTES))
}

private fun requireSafeExtractorHeaders(headers: Map<String, String>): Map<String, String> {
    val forbidden = setOf("authorization", "proxy-authorization", "cookie", "set-cookie")
    if (headers.size > 32) throw UnsafeResolverTarget()
    val seen = HashSet<String>()
    for ((name, value) in headers) {
        val normalizedName = name.lowercase(Locale.ROOT)
        if (normalizedName in forbidden ||
            name.isEmpty() || name.length > 128 ||
            !name.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
            } ||
            !seen.add(normalizedName) || value.length > MAX_RESOLVER_URL_CHARS ||
            value.any { it.code < 0x20 || it.code in 0x7F..0x9F }
        ) throw UnsafeResolverTarget()

        if (normalizedName == "referer" || normalizedName == "origin") {
            val parsed = value.toHttpUrlOrNull() ?: throw UnsafeResolverTarget()
            if (parsed.encodedUsername.isNotEmpty() || parsed.encodedPassword.isNotEmpty()) {
                throw UnsafeResolverTarget()
            }
        }
    }
    return headers.toMap()
}

private fun requireSafeExtractorFields(fields: Map<String, String>): Map<String, String> {
    if (fields.size > 16 || fields.entries.sumOf { it.key.length.toLong() + it.value.length } > MAX_EXTRACTOR_POST_BYTES ||
        fields.any { (name, value) ->
            name.isEmpty() || name.length > 128 || value.any { it.code < 0x20 || it.code in 0x7F..0x9F }
        }
    ) throw UnsafeResolverTarget()
    return fields.toMap()
}

private val dnsThreadIds = AtomicInteger()
private val DOOD_PASS_MD5_PATH = Regex("""/pass_md5/([^/'"?#\s\\<>]{1,512})""")
private val GMPLAYER_VIDEO_PATH = Regex(
    """/video/([A-Za-z0-9._~-]{1,256})(?:/[\s\S]*)?"""
)

/**
 * Android's system resolver can ignore interruption. Canceled callers still return promptly;
 * at most two resolver calls can remain blocked and at most eight lookups can wait behind them.
 * Saturation rejects new lookups, which callers handle as a closed failure.
 */
private val dnsLookupExecutor = ThreadPoolExecutor(
    DNS_LOOKUP_WORKERS,
    DNS_LOOKUP_WORKERS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(DNS_LOOKUP_QUEUE_CAPACITY),
    ThreadFactory { task ->
        Thread(task, "AurasOrbitDns-${dnsThreadIds.incrementAndGet()}").apply { isDaemon = true }
    },
    ThreadPoolExecutor.AbortPolicy(),
)

/**
 * Orbit-owned bounded resolver for the eight short-link families pinned at
 * recloudstream/cloudstream 5024ace2922a148d38df2a816b483b529789cf58.
 * It is intentionally hostname-bound and uses Orbit's shared NiceHttp client;
 * no upstream implementation body is copied. Upstream source is GPL-3.0.
 */
suspend fun unshortenLinkSafe(url: String): String {
    val original = url
    return try {
        val result = withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            resolveRecognizedChain(original, HttpBudget())
        }
        result ?: original
    } catch (cancelled: CancellationException) {
        // Keep caller cancellation observable instead of converting it to a URL fallback.
        throw cancelled
    } catch (_: Exception) {
        original
    }
}

/**
 * Fetches one Mp4Upload page with Orbit's bounded HTTP path. The initial URL and
 * every manual redirect must stay on mp4upload.com or one of its subdomains.
 */
suspend fun fetchMp4UploadPage(url: String): String? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS) return null
    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            HttpBudget(MP4UPLOAD_ALLOWED_DOMAIN).get(url, allowRedirects = true).boundedText()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Fetches one Odnoklassniki embed page. Both the initial URL and redirects stay
 * under ok.ru or odnoklassniki.ru; page headers are dropped on hostname changes. */
suspend fun fetchOdnoklassnikiEmbedPage(url: String, origin: String): String? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS) return null
    val safeOrigin = normalizeAllowedOrigin(origin, ODNOKLASSNIKI_ALLOWED_HOSTS) ?: return null
    val headers = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Origin" to safeOrigin,
    )
    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            HttpBudget(allowedExactHosts = ODNOKLASSNIKI_ALLOWED_HOSTS)
                .get(url, allowRedirects = true, requestHeaders = headers)
                .boundedText()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Fetches one Filemoon page or iframe using only the aliases pinned by Filemoon.kt. */
suspend fun fetchFilemoonPage(
    url: String,
    referer: String,
    includeAcceptLanguage: Boolean = false,
): String? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS ||
        !isFilemoonHttpsUrl(url) ||
        referer.length !in 1..MAX_RESOLVER_URL_CHARS ||
        !isFilemoonHttpsUrl(referer)
    ) return null

    val headers = buildMap {
        put("Referer", referer)
        put("Sec-Fetch-Dest", "iframe")
        put("Sec-Fetch-Mode", "navigate")
        put("Sec-Fetch-Site", "cross-site")
        put("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0")
        if (includeAcceptLanguage) put("Accept-Language", "en-US,en;q=0.5")
    }

    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            val response = HttpBudget(
                allowedExactHosts = FILEMOON_ALLOWED_HOSTS,
                allowCookies = false,
                maxUrlChars = MAX_RESOLVER_URL_CHARS,
                httpsOnly = true,
            )
                .get(url, allowRedirects = true, requestHeaders = headers)
            if (response.code !in 200..299) {
                response.body.close()
                null
            } else {
                response.boundedText()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Bounded response data for one Filemoon WebView resource. */
internal data class FilemoonWebViewResource(
    val mimeType: String,
    val charset: String?,
    val body: ByteArray,
)

internal sealed interface FilemoonWebViewFetchResult {
    data class Resource(val value: FilemoonWebViewResource) : FilemoonWebViewFetchResult
    data class HlsRedirect(val url: String) : FilemoonWebViewFetchResult
}

private val FILEMOON_HLS_REDIRECT_URL =
    Regex("(m3u8|master\\.txt)", RegexOption.IGNORE_CASE)

internal fun isAllowedFilemoonWebViewUrl(url: String): Boolean =
    url.length in 1..MAX_RESOLVER_URL_CHARS && isFilemoonHttpsUrl(url)

/**
 * A single cookie-free request budget shared by all WebView resources. Requests
 * are serialized so request, redirect, and aggregate body limits cannot race.
 */
internal class FilemoonWebViewRequestBudget(private val referer: String) {
    private val lock = Mutex()
    private val lifetimeJob: Job = SupervisorJob()
    private val http = HttpBudget(
        allowedExactHosts = FILEMOON_ALLOWED_HOSTS,
        allowCookies = false,
        maxRequests = MAX_FILEMOON_WEBVIEW_HTTP_REQUESTS,
        maxUrlChars = MAX_RESOLVER_URL_CHARS,
        httpsOnly = true,
    )
    private var aggregateBytes = 0L

    fun fetchBlocking(url: String): FilemoonWebViewFetchResult? =
        runBlocking(Dispatchers.IO + lifetimeJob) { fetch(url) }

    fun validateCapturedUrlAsync(url: String, completion: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO + lifetimeJob).launch {
            val validated = try {
                validateFilemoonCapturedHlsUrl(url)
            } catch (_: Exception) {
                null
            }
            completion(validated)
        }
    }

    fun close() {
        lifetimeJob.cancel()
    }

    suspend fun fetch(url: String): FilemoonWebViewFetchResult? = lock.withLock {
        if (url.length !in 1..MAX_RESOLVER_URL_CHARS || !isFilemoonHttpsUrl(url) ||
            referer.length !in 1..MAX_RESOLVER_URL_CHARS || !isFilemoonHttpsUrl(referer)
        ) return@withLock null

        val initialHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to referer,
        )
        val visited = HashSet<String>()
        visited += try {
            cycleKey(url)
        } catch (_: Exception) {
            return@withLock null
        }
        var currentUrl = url
        var sendInitialHeaders = true

        while (true) {
            val response = http.get(
                currentUrl,
                allowRedirects = false,
                requestHeaders = if (sendInitialHeaders) initialHeaders else emptyMap(),
            )
            val location = response.headers["location"]
            if (response.code in REDIRECT_STATUS_CODES && location != null) {
                response.body.close()
                http.consumeResolutionHop()
                val nextUrl = resolveReference(response.url, location)
                if (nextUrl.length !in 1..MAX_RESOLVER_URL_CHARS) return@withLock null
                if (FILEMOON_HLS_REDIRECT_URL.containsMatchIn(nextUrl)) {
                    return@withLock FilemoonWebViewFetchResult.HlsRedirect(nextUrl)
                }
                if (!isFilemoonHttpsUrl(nextUrl)) return@withLock null
                val nextKey = try {
                    cycleKey(nextUrl)
                } catch (_: Exception) {
                    return@withLock null
                }
                if (!visited.add(nextKey)) return@withLock null
                if (!sameHttpOrigin(response.url, nextUrl)) sendInitialHeaders = false
                currentUrl = nextUrl
                continue
            }
            if (response.code !in 200..299 || !isFilemoonHttpsUrl(response.url)) {
                response.body.close()
                return@withLock null
            }

            val responseBody = response.body
            val contentType = responseBody.contentType()
            val remainingAggregateBytes = MAX_FILEMOON_WEBVIEW_TOTAL_BYTES - aggregateBytes
            if (remainingAggregateBytes < 0L) {
                responseBody.close()
                return@withLock null
            }
            val resourceLimit = minOf(MAX_FILEMOON_WEBVIEW_RESOURCE_BYTES, remainingAggregateBytes)
            val declaredSize = response.size?.takeIf { it >= 0L } ?: responseBody.contentLength()
            if (resourceLimit == 0L && declaredSize != 0L) {
                responseBody.close()
                return@withLock null
            }
            val bytes = response.boundedBytes(resourceLimit) { consumedBytes ->
                // Count streamed bytes immediately so rejected or canceled bodies cannot
                // reclaim aggregate allowance and exceed the session cap on a later request.
                aggregateBytes += consumedBytes
            }
            return@withLock FilemoonWebViewFetchResult.Resource(
                FilemoonWebViewResource(
                    contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                    contentType?.charset(Charsets.UTF_8)?.name(),
                    bytes,
                )
            )
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}

/** Validates a captured Filemoon HLS URL as HTTPS and public before handoff. */
internal suspend fun validateFilemoonCapturedHlsUrl(url: String): String? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS || !isStrictHttpsUrl(url)) return null
    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            validateSafeTarget(url).url
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Fetches VidStack's encrypted JSON through the shared exact-host, pinned HTTPS transport. */
internal suspend fun fetchVidStackEncryptedResponse(url: String): String? {
    if (url.length !in 1..MAX_VIDSTACK_API_URL_CHARS || !isStrictHttpsUrl(url)) return null
    return try {
        withTimeoutOrNull(MAX_VIDSTACK_REQUEST_MILLIS) {
            val response = HttpBudget(
                allowedExactHosts = VIDSTACK_ALLOWED_HOSTS,
                allowCookies = false,
                maxRequests = 1,
                maxUrlChars = MAX_VIDSTACK_API_URL_CHARS,
                httpsOnly = true,
            ).get(
                url,
                allowRedirects = false,
                requestHeaders = mapOf(
                    "User-Agent" to VIDSTACK_USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                ),
            )
            if (response.code !in 200..299) {
                response.body.close()
                null
            } else {
                response.boundedText(MAX_VIDSTACK_RESPONSE_BYTES)
                    .trim()
                    .takeIf { it.isNotEmpty() && it.length <= MAX_VIDSTACK_RESPONSE_BYTES.toInt() }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Fetches Voe's page pair through one cookie-free, exact-host, DNS-pinned request budget. */
internal suspend fun fetchVoePage(url: String, referer: String?, mainUrl: String): String? {
    val allowedHost = normalizeVoeHost(mainUrl) ?: return null
    val allowedHosts = setOf(allowedHost)
    if (url.length !in 1..MAX_VOE_PAGE_URL_CHARS || url != url.trim() ||
        url.any { it.isISOControl() } || !isStrictHttpsUrl(url) ||
        !isExactAllowedHost(url, allowedHosts)
    ) return null

    val safeReferer = normalizeVoeReferer(referer)
    val requestHeaders = buildMap {
        put("Accept", "text/html,application/xhtml+xml")
        if (safeReferer != null) put("Referer", safeReferer)
    }

    return try {
        withTimeoutOrNull(MAX_VOE_PAGE_REQUEST_MILLIS) {
            // Three total HTTP requests cap the initial page, same-origin HTTP redirects,
            // and the single JavaScript redirect parsed below at no more than three fetches.
            val budget = HttpBudget(
                allowedExactHosts = allowedHosts,
                allowCookies = false,
                maxRequests = 3,
                maxUrlChars = MAX_VOE_PAGE_URL_CHARS,
                httpsOnly = true,
            )

            suspend fun readPage(targetUrl: String): Pair<String, String>? {
                val response = budget.get(
                    targetUrl,
                    allowRedirects = true,
                    requestHeaders = requestHeaders,
                )
                if (response.code !in 200..299) {
                    response.body.close()
                    return null
                }
                val body = response.boundedText(MAX_VOE_PAGE_RESPONSE_BYTES)
                    .takeIf { it.isNotEmpty() && it.length <= MAX_VOE_PAGE_RESPONSE_BYTES.toInt() }
                    ?: return null
                return response.url to body
            }

            val firstPage = readPage(url) ?: return@withTimeoutOrNull null
            val redirect = VOE_WINDOW_REDIRECT.find(firstPage.second)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.length in 1..MAX_VOE_PAGE_URL_CHARS }
                ?: return@withTimeoutOrNull firstPage.second
            val redirectUrl = resolveReference(firstPage.first, redirect)
            if (redirectUrl.length !in 1..MAX_VOE_PAGE_URL_CHARS ||
                !isStrictHttpsUrl(redirectUrl) || !isExactAllowedHost(redirectUrl, allowedHosts)
            ) return@withTimeoutOrNull null

            readPage(redirectUrl)?.second
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * Resolves the VidHidePro origin from each extractor instance's metadata. The host is not
 * globally allowlisted: custom repository subclasses can use their own HTTPS origin, while
 * malformed, credential-bearing, port-confused, and trailing-dot authorities fail closed.
 */
internal fun normalizeVidHideProOrigin(mainUrl: String): String? =
    parseVidHideProUrl(mainUrl, requireOrigin = true)?.origin

/** Validates the page authority before applying only the pinned path-token rewrite. */
internal fun normalizeVidHideProPageUrl(url: String, mainUrl: String): String? {
    val expected = parseVidHideProUrl(mainUrl, requireOrigin = true) ?: return null
    val page = parseVidHideProUrl(url, requireOrigin = false) ?: return null
    if (url.length !in 1..MAX_VIDHIDE_PAGE_URL_CHARS || page.host != expected.host) return null
    return try {
        val uri = URI(url)
        val path = uri.rawPath.orEmpty()
        val rewrittenPath = when {
            "/d/" in path -> path.replace("/d/", "/v/")
            "/download/" in path -> path.replace("/download/", "/v/")
            "/file/" in path -> path.replace("/file/", "/v/")
            else -> path.replace("/f/", "/v/")
        }
        buildString {
            append("https://")
            append(uri.rawAuthority)
            append(rewrittenPath)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }.let { URI(it).toASCIIString() }
            .takeIf { it.length <= MAX_VIDHIDE_PAGE_URL_CHARS }
    } catch (_: Exception) {
        null
    }
}

/** Fetches one VidHidePro embed page through a cookie-free, same-host, DNS-pinned budget. */
internal suspend fun fetchVidHideProPage(url: String, referer: String?, mainUrl: String): String? {
    val expectedOrigin = normalizeVidHideProOrigin(mainUrl) ?: return null
    val expected = parseVidHideProUrl(expectedOrigin, requireOrigin = true) ?: return null
    val initial = parseVidHideProUrl(url, requireOrigin = false) ?: return null
    if (initial.host != expected.host || url.length !in 1..MAX_VIDHIDE_PAGE_URL_CHARS) return null

    val requestHeaders = buildMap {
        put("Accept", "text/html,application/xhtml+xml")
        put("Sec-Fetch-Dest", "empty")
        put("Sec-Fetch-Mode", "cors")
        put("Sec-Fetch-Site", "cross-site")
        put("Origin", expected.origin)
        put("User-Agent", MainAPIKt.USER_AGENT)
        normalizeVidHideCallerReferer(referer)?.let { put("Referer", it) }
    }

    return try {
        withTimeoutOrNull(MAX_VIDHIDE_PAGE_REQUEST_MILLIS) {
            val budget = HttpBudget(
                allowedExactHosts = setOf(expected.host),
                allowCookies = false,
                maxRequests = 3,
                maxUrlChars = MAX_VIDHIDE_PAGE_URL_CHARS,
                httpsOnly = true,
            )
            var currentUrl = url
            for (requestIndex in 0 until 3) {
                currentCoroutineContext().ensureActive()
                val current = parseVidHideProUrl(currentUrl, requireOrigin = false)
                    ?: return@withTimeoutOrNull null
                if (current.host != expected.host) return@withTimeoutOrNull null

                val response = budget.get(
                    currentUrl,
                    allowRedirects = false,
                    requestHeaders = requestHeaders,
                )
                val location = response.headers["location"]
                if (response.code in REDIRECT_STATUS_CODES) {
                    response.body.close()
                    if (location.isNullOrBlank() || requestIndex == 2) {
                        return@withTimeoutOrNull null
                    }
                    val nextUrl = resolveReference(response.url, location)
                    val next = parseVidHideProUrl(nextUrl, requireOrigin = false)
                        ?: return@withTimeoutOrNull null
                    if (next.host != expected.host || nextUrl.length > MAX_VIDHIDE_PAGE_URL_CHARS) {
                        return@withTimeoutOrNull null
                    }
                    currentUrl = nextUrl
                    continue
                }
                if (response.code !in 200..299) {
                    response.body.close()
                    return@withTimeoutOrNull null
                }
                return@withTimeoutOrNull response.boundedText(MAX_VIDHIDE_PAGE_RESPONSE_BYTES)
                    .takeIf { it.isNotEmpty() }
            }
            null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * Validates a VidHidePro URL without a repository-wide host allowlist. Explicit ports are
 * accepted only as canonical :443; IPv6 zones, user-info, scheme confusion, and trailing dots
 * are rejected before HttpBudget performs public-address validation and DNS pinning.
 */
private fun parseVidHideProUrl(value: String, requireOrigin: Boolean): VidHideUrl? {
    if (value.length !in 1..MAX_VIDHIDE_PAGE_URL_CHARS || value != value.trim() ||
        value.any { it.isISOControl() || it.isWhitespace() }
    ) return null
    return try {
        val uri = URI(value)
        val authority = uri.rawAuthority ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            '@' in authority || authority.endsWith(':') || uri.port !in -1..65_535 ||
            uri.port == 0 || (uri.port != -1 && uri.port != 443) ||
            uri.rawPath?.let { it.isNotEmpty() && it != "/" } == true && requireOrigin ||
            (uri.rawQuery != null || uri.rawFragment != null) && requireOrigin ||
            !isCanonicalVidHideAuthority(authority)
        ) return null

        val parsed = value.toHttpUrl()
        val host = parsed.host.lowercase(Locale.ROOT)
        if (parsed.scheme != "https" || host.isEmpty() || host.endsWith('.') ||
            host.startsWith('.') || ".." in host ||
            (requireOrigin && (parsed.encodedPath != "/" || parsed.encodedQuery != null ||
                parsed.encodedFragment != null))
        ) return null
        val authorityHost = if (':' in host) "[$host]" else host
        VidHideUrl(host, "https://$authorityHost")
    } catch (_: Exception) {
        null
    }
}

private fun isCanonicalVidHideAuthority(authority: String): Boolean {
    if (authority.isEmpty() || '%' in authority || '\\' in authority) return false
    if (authority.startsWith('[')) {
        val closing = authority.indexOf(']')
        if (closing <= 1 || '%' in authority.substring(1, closing)) return false
        val suffix = authority.substring(closing + 1)
        return suffix.isEmpty() || suffix == ":443"
    }
    if ('[' in authority || ']' in authority || authority.count { it == ':' } > 1) return false
    val colon = authority.lastIndexOf(':')
    val rawHost = if (colon < 0) authority else authority.substring(0, colon)
    if (rawHost.isEmpty() || rawHost.endsWith('.')) return false
    if (colon >= 0 && authority.substring(colon + 1) != "443") return false
    return try {
        val asciiHost = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        asciiHost.isNotEmpty() && !asciiHost.endsWith('.') && ".." !in asciiHost
    } catch (_: Exception) {
        false
    }
}

private fun normalizeVidHideCallerReferer(value: String?): String? {
    if (value.isNullOrEmpty() || value.length > MAX_VIDHIDE_PAGE_URL_CHARS) return null
    val outgoingValue = value.trim()
    if (outgoingValue.isEmpty() || outgoingValue.any { it != '\t' && it !in ' '..'~' }) {
        return null
    }
    // NiceHttp 0.4.18 puts this value directly into a Referer header; it does not parse the URL
    // or remove its query or fragment. OkHttp's Headers.of(Map) trims the value before storing it.
    // Match that trim, while keeping the resulting header request-local.
    return outgoingValue
}

private data class VidHideUrl(val host: String, val origin: String)

/** Runs Voe's pinned HLS expansion with cookies disabled in its bounded playlist transport. */
internal suspend fun <T> withVoePlaylistCookiesDisabled(block: suspend () -> T): T =
    withContext(VOE_PLAYLIST_COOKIES_DISABLED) { block() }

private val VOE_WINDOW_REDIRECT = Regex(
    """window.location.href\s*=\s*'([^']{1,4096})';"""
)

private fun normalizeVoeHost(mainUrl: String): String? {
    if (mainUrl.length !in 1..MAX_VOE_PAGE_URL_CHARS || !isStrictHttpsUrl(mainUrl)) return null
    return try {
        val uri = URI(mainUrl)
        val parsed = mainUrl.toHttpUrl()
        val path = uri.rawPath
        val host = parsed.host.lowercase(Locale.ROOT)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port != -1 && uri.port != 443 ||
            path != null && path.isNotEmpty() && path != "/" ||
            host !in VOE_ALLOWED_HOSTS
        ) null else host
    } catch (_: Exception) {
        null
    }
}

/** Retains the caller's origin as a referer without forwarding path/query credentials. */
private fun normalizeVoeReferer(value: String?): String? {
    if (value.isNullOrBlank() || value.length > MAX_VOE_PAGE_URL_CHARS ||
        value != value.trim() || value.any { it.isISOControl() }
    ) return null
    return try {
        val uri = URI(value)
        val parsed = value.toHttpUrl()
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            uri.port !in -1..65_535 || uri.port == 0 ||
            parsed.scheme != "https" || parsed.host.isEmpty() || parsed.host.endsWith('.')
        ) return null
        parsed.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    } catch (_: Exception) {
        null
    }
}

private const val VIDSTACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"

/** One bounded Dood page pair, fetched through one request/redirect budget. */
internal data class DoodStreamPage(
    val html: String,
    val mediaBase: String,
    val token: String,
    val referer: String,
)

/**
 * Fetches the Dood embed and its `/pass_md5/` endpoint through Orbit's public-address-pinned
 * transport. The initial host must match the extractor's mutable mainUrl host. Redirects may
 * reach another public host, but the Referer is stripped as soon as the HTTP origin changes,
 * and this path does not store or send cookies.
 */
internal suspend fun fetchDoodStreamPage(url: String, mainUrl: String): DoodStreamPage? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS || mainUrl.length !in 1..MAX_RESOLVER_URL_CHARS) {
        return null
    }

    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            val normalized = normalizeDoodRequest(url, mainUrl) ?: return@withTimeoutOrNull null
            val budget = HttpBudget(allowCookies = false)
            val pageResponse = budget.get(normalized.requestUrl, allowRedirects = true)
            if (pageResponse.code !in 200..299) {
                pageResponse.body.close()
                return@withTimeoutOrNull null
            }

            val html = pageResponse.boundedText(MAX_DOOD_PAGE_BYTES)
            currentCoroutineContext().ensureActive()
            val pageUrl = pageResponse.url
            val path = DOOD_PASS_MD5_PATH.find(html)?.groupValues?.getOrNull(1)
                ?: return@withTimeoutOrNull null
            val passMd5Url = resolveReference(pageUrl, "/pass_md5/$path")
            val passMd5Uri = URI(passMd5Url)
            val pageUri = URI(pageUrl)
            val passPath = passMd5Uri.rawPath
                ?: return@withTimeoutOrNull null
            if (!passPath.startsWith("/pass_md5/")) return@withTimeoutOrNull null
            val token = passPath.removePrefix("/pass_md5/")
            if (token.isEmpty() || token.contains('/') || token.length > 512 ||
                token == "." || token == ".." ||
                token.contains("%2f", ignoreCase = true) || token.contains("%5c", ignoreCase = true)
            ) return@withTimeoutOrNull null
            if (passMd5Uri.rawUserInfo != null || passMd5Uri.rawQuery != null ||
                passMd5Uri.rawFragment != null ||
                !sameHttpOrigin(pageUrl, passMd5Url) ||
                pageUri.rawUserInfo != null
            ) return@withTimeoutOrNull null

            val passResponse = budget.get(
                passMd5Url,
                allowRedirects = true,
                requestHeaders = mapOf("Referer" to pageUrl),
            )
            if (passResponse.code !in 200..299) {
                passResponse.body.close()
                return@withTimeoutOrNull null
            }
            val mediaBase = passResponse.boundedText(MAX_DOOD_PASS_MD5_BYTES).trim()
            if (mediaBase.isEmpty() || mediaBase.length > MAX_DOOD_PASS_MD5_BYTES.toInt()) {
                return@withTimeoutOrNull null
            }

            DoodStreamPage(html, mediaBase, token, normalized.referer)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Rejects malformed, credential-bearing, non-HTTP(S), and non-public media targets. */
suspend fun validateMp4UploadMediaUrl(url: String): String? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS) return null
    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            validateSafeTarget(url).url
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Rejects malformed, credential-bearing, non-HTTP(S), and non-public extractor media targets. */
suspend fun validatePublicExtractorMediaUrl(url: String): String? =
    validatePublicExtractorMediaUrl(url, requireHttps = false)

internal suspend fun validatePublicExtractorMediaUrl(url: String, requireHttps: Boolean): String? {
    if (url.length !in 1..MAX_RESOLVER_URL_CHARS || (requireHttps && !isStrictHttpsUrl(url))) return null
    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            validateSafeTarget(url).url
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * Bounded Orbit implementation of the historical GMPlayer response flow from
 * CloudStream commit a65828e2b093f4deca3da7b211254b06c9c382fb (GPL-3.0).
 */
suspend fun extractGMPlayerLinks(
    extractorName: String,
    url: String,
    referer: String?,
    mainUrl: String,
): List<ExtractorLink>? {
    if (extractorName.isEmpty() || extractorName.length > 512 ||
        url.length !in 1..MAX_GMPLAYER_URL_CHARS ||
        referer == null || referer.length !in 1..MAX_GMPLAYER_REFERER_CHARS
    ) return null

    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            currentCoroutineContext().ensureActive()
            val request = normalizeGMPlayerRequest(url, mainUrl, referer)
                ?: return@withTimeoutOrNull null
            val response = HttpBudget(allowCookies = false, maxRequests = 1).postFormOnce(
                request.endpoint,
                requestHeaders = mapOf(
                    "Accept" to "*/*",
                    "Referer" to request.referer,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to request.origin,
                ),
                formFields = mapOf("hash" to request.id, "r" to request.referer),
            )
            if (response.code !in 200..299) {
                response.body.close()
                return@withTimeoutOrNull null
            }

            val json = JSONObject(response.boundedText(MAX_GMPLAYER_RESPONSE_BYTES))
            val source = json.optString("videoSource")
            val mediaUrl = validatePublicExtractorMediaUrl(source)
                ?: return@withTimeoutOrNull null

            listOf(
                ExtractorLink(
                    extractorName,
                    extractorName,
                    mediaUrl,
                    request.referer,
                    Qualities.Unknown.value,
                    mapOf("accept" to "*/*"),
                    null,
                    ExtractorLinkType.M3U8,
                    emptyList(),
                )
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * Reads one public HLS playlist through the shared bounded transport. Arbitrary
 * public plugin hosts are supported; every initial and redirected target is
 * resolved, checked for public addresses, and pinned for its request.
 */
internal data class PublicM3u8Playlist(
    val url: String,
    val body: String,
)

internal suspend fun fetchPublicM3u8Playlist(
    url: String,
    headers: Map<String, String>,
): PublicM3u8Playlist? = fetchPublicM3u8Playlist(url, headers, requireHttps = false)

internal suspend fun fetchPublicM3u8Playlist(
    url: String,
    headers: Map<String, String>,
    requireHttps: Boolean,
): PublicM3u8Playlist? {
    if (url.length !in 1..MAX_PUBLIC_PLAYLIST_URL_CHARS ||
        (requireHttps && !isStrictHttpsUrl(url)) || !arePlaylistHeadersBounded(headers)
    ) {
        return null
    }

    return try {
        withTimeoutOrNull(RESOLUTION_DEADLINE_MILLIS) {
            val allowCookies = currentCoroutineContext()[VoePlaylistCookiesDisabled.Key] == null
            val response = HttpBudget(
                allowCookies = allowCookies,
                maxUrlChars = MAX_PUBLIC_PLAYLIST_URL_CHARS,
                httpsOnly = requireHttps,
            )
                .get(url, allowRedirects = true, requestHeaders = headers)
            if (response.code !in 200..299) {
                response.body.close()
                null
            } else {
                PublicM3u8Playlist(response.url, response.boundedText(MAX_PUBLIC_PLAYLIST_BYTES))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun arePlaylistHeadersBounded(headers: Map<String, String>): Boolean {
    if (headers.size > MAX_PUBLIC_PLAYLIST_HEADER_COUNT) return false
    var totalChars = 0
    for ((name, value) in headers) {
        if (name.isEmpty() || name.length > 256 || value.length > MAX_PUBLIC_PLAYLIST_HEADER_CHARS) {
            return false
        }
        if (!name.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                    it in "!#$%&'*+-.^_`|~"
            }
        ) return false
        if (value.any { it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }) return false
        totalChars += name.length + value.length
        if (totalChars > MAX_PUBLIC_PLAYLIST_HEADER_CHARS) return false
    }
    return true
}

private enum class ShortFamily {
    ADFLY,
    LINKUP,
    LINKSAFE,
    NUOVO_INDIRIZZO,
    NUOVO_LINK,
    UPROT,
    DAVISON_BARKER,
    ISECURE,
}

private val adflyHosts = setOf(
    "adf.ly", "j.gs", "q.gs", "u.bb", "ay.gy", "atominik.com", "tinyium.com",
    "microify.com", "threadsphere.bid", "clearload.bid", "activetect.net", "swiftviz.net",
    "briskgram.net", "stoodsef.com", "baymaleti.net", "thouth.net", "uclaut.net",
    "gloyah.net", "larati.net", "scuseami.net",
)

private fun familyFor(url: String): ShortFamily? {
    val host = normalizedHost(url) ?: return null
    return when {
        adflyHosts.any { hostMatches(host, it) } -> ShortFamily.ADFLY
        hostMatches(host, "linkup.pro") || hostMatches(host, "buckler.link") -> ShortFamily.LINKUP
        hostMatches(host, "linksafe.cc") -> ShortFamily.LINKSAFE
        hostMatches(host, "mixdrop.nuovoindirizzo.com") -> ShortFamily.NUOVO_INDIRIZZO
        hostMatches(host, "nuovolink.com") -> ShortFamily.NUOVO_LINK
        hostMatches(host, "uprot.net") -> ShortFamily.UPROT
        hostMatches(host, "davisonbarker.pro") || hostMatches(host, "lowrihouston.pro") ->
            ShortFamily.DAVISON_BARKER
        hostMatches(host, "isecure.link") -> ShortFamily.ISECURE
        else -> null
    }
}

private fun normalizedHost(url: String): String? = try {
    Url(url.trim()).host.trimEnd('.').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

private fun hostMatches(host: String, domain: String): Boolean =
    host == domain || host.endsWith(".$domain")

private suspend fun resolveRecognizedChain(url: String, budget: HttpBudget): String {
    if (familyFor(url) == null) return url

    var current = url.trim()
    val visited = HashSet<String>()
    while (budget.hasResolutionHops()) {
        currentCoroutineContext().ensureActive()
        if (!visited.add(cycleKey(current))) return current

        val family = familyFor(current) ?: return current
        budget.consumeResolutionHop()
        val next = when (family) {
            ShortFamily.ADFLY -> resolveAdfly(current, budget)
            ShortFamily.LINKUP -> resolveLinkup(current, budget)
            ShortFamily.LINKSAFE -> resolveLinksafe(current)
            ShortFamily.NUOVO_INDIRIZZO -> resolveNuovoIndirizzo(current, budget)
            ShortFamily.NUOVO_LINK -> resolveNuovoLink(current, budget)
            ShortFamily.UPROT -> resolveUprot(current, budget)
            ShortFamily.DAVISON_BARKER -> resolveDavisonBarker(current)
            ShortFamily.ISECURE -> resolveIsecure(current, budget)
        }.trim()

        if (next.isEmpty()) return url
        current = budget.validateTarget(next).url
    }
    return current
}

private suspend fun resolveAdfly(url: String, budget: HttpBudget): String {
    val html = budget.get(url, allowRedirects = true).boundedText()
    val token = Regex("""var\s+ysmm\s*=\s*['"]([^'"]+)['"]""")
        .find(html)?.groupValues?.getOrNull(1) ?: return url
    val left = StringBuilder()
    val right = StringBuilder()
    var index = 0
    while (index + 1 < token.length) {
        left.append(token[index])
        right.append(token[index + 1])
        index += 2
    }

    val mixed = (left.toString() + right.reverse()).toCharArray()
    val digits = mixed.indices.filter { mixed[it].isDigit() }
    for (pair in digits.chunked(2)) {
        if (pair.size == 2) {
            val decodedDigit = mixed[pair[0]].code xor mixed[pair[1]].code
            if (decodedDigit in 0..9) mixed[pair[0]] = ('0'.code + decodedDigit).toChar()
        }
    }

    var target = decodeBase64Latin1(String(mixed))
    if (target.length < 32) return url
    target = target.drop(16).dropLast(16)
    if (target.contains("go.php?u=")) {
        val encoded = target.substringAfter("u=", "")
        if (encoded.isNotEmpty()) target = decodeBase64Latin1(encoded)
    }
    return target
}

private suspend fun resolveLinkup(url: String, budget: HttpBudget): String {
    var target = when {
        url.contains("/tv/") -> url.replace("/tv/", "/tva/")
        url.contains("delta") -> url.replace("/delta/", "/adelta/")
        url.contains("/ga/") || url.contains("/ga2/") ->
            decodeBase64Latin1(url.substringAfterLast('/')).trim()
        url.contains("/speedx/") ->
            url.replace("http://linkup.pro/speedx", "http://speedvideo.net")
        else -> {
            val response = budget.get(url, allowRedirects = true)
            val html = response.boundedText()
            val document = Ksoup.parse(html)
            val iframe = document.selectFirst("iframe[src]")?.attr("src")
            val action = document.selectFirst("form[action]")?.attr("action")
            val encodedHref = Regex("""href"\s*,\s*"([^"]*)"""")
                .findAll(html).drop(1).firstOrNull()?.groupValues?.getOrNull(1)
            val candidate = sequenceOf(iframe, action, encodedHref)
                .firstOrNull { !it.isNullOrBlank() }
            candidate?.let { resolveReference(response.url, it) } ?: response.url
        }
    }

    if (url.contains("/tv/") || url.contains("delta") || url.contains("/ga/") ||
        url.contains("/ga2/") || url.contains("/speedx/")) {
        val response = budget.get(target, allowRedirects = false)
        try {
            response.headers["location"]?.let { target = resolveReference(target, it) }
        } finally {
            response.body.close()
        }
    }

    val targetHost = normalizedHost(target).orEmpty()
    if (targetHost.split('.').any { it == "snip" }) {
        when {
            target.contains("out_generator") -> {
                val extracted = Regex("""(?:[?&])url=([^&]+)""").find(target)?.groupValues?.get(1)
                if (extracted != null) target = extracted.decodeURLQueryComponent()
            }
            target.contains("/decode/") -> {
                val response = budget.get(target, allowRedirects = true)
                target = response.url
                response.body.close()
            }
        }
    }
    return target
}

private fun resolveLinksafe(url: String): String {
    val payload = url.substringAfter("?url=", missingDelimiterValue = url)
    return decodeBase64Latin1(payload)
}

private suspend fun resolveNuovoIndirizzo(url: String, budget: HttpBudget): String {
    val response = budget.get(url, allowRedirects = true)
    return try {
        response.headers["refresh"]?.substringAfter('=') ?: "non trovato"
    } finally {
        response.body.close()
    }
}

private suspend fun resolveNuovoLink(url: String, budget: HttpBudget): String {
    val response = budget.get(url, allowRedirects = true)
    val html = response.boundedText()
    val firstLink = Ksoup.parse(html).selectFirst("a[href]")?.attr("href")
    return firstLink?.takeIf { it.isNotBlank() }?.let { resolveReference(response.url, it) } ?: url
}

private suspend fun resolveUprot(url: String, budget: HttpBudget): String {
    val page = budget.get(url, allowRedirects = true).boundedText()
    val anchors = Ksoup.parse(page).select("a[href]")
    for (anchor in anchors) {
        if (!anchor.outerHtml().contains("Continue")) continue
        val href = anchor.attr("href")
        val targetHost = normalizedHost(href) ?: continue
        if (hostMatches(targetHost, "maxstream.video") ||
            (hostMatches(targetHost, "uprot.net") && href != url)) return href
    }
    return url
}

private fun resolveDavisonBarker(url: String): String {
    val target = url.substringAfter("dest=", missingDelimiterValue = url)
    return target.decodeURLQueryComponent()
}

private suspend fun resolveIsecure(url: String, budget: HttpBudget): String {
    val page = budget.get(url, allowRedirects = true).boundedText()
    val iframe = Ksoup.parse(page).selectFirst("iframe[src]")?.attr("src")?.trim()
    return iframe?.takeIf { it.isNotEmpty() }?.let { resolveReference(url, it) } ?: url
}

private class HttpBudget(
    private val allowedHostDomain: String? = null,
    private val allowedExactHosts: Set<String>? = null,
    allowCookies: Boolean = true,
    maxRequests: Int = MAX_HTTP_GETS,
    private val maxUrlChars: Int? = null,
    private val httpsOnly: Boolean = false,
) {
    private var remainingRequests = maxRequests
    private var remainingPostRequests = 1
    private var remainingHops = MAX_RESOLUTION_HOPS
    private val validatedTargets = HashMap<String, SafeTarget>()
    private val cookieJar: CookieJar = if (allowCookies) ResolutionCookieJar() else EmptyCookieJar

    fun hasResolutionHops(): Boolean = remainingHops > 0

    fun consumeResolutionHop() {
        if (remainingHops <= 0) throw ResolverBudgetExceeded()
        remainingHops--
    }

    suspend fun validateTarget(url: String): SafeTarget {
        if (maxUrlChars != null && url.length !in 1..maxUrlChars) {
            throw UnsafeResolverTarget()
        }
        if (httpsOnly && !isStrictHttpsUrl(url)) throw UnsafeResolverTarget()
        if (allowedHostDomain != null && !isWithinAllowedHost(url, allowedHostDomain)) {
            throw UnsafeResolverTarget()
        }
        if (allowedExactHosts != null && !isExactAllowedHost(url, allowedExactHosts)) {
            throw UnsafeResolverTarget()
        }
        val key = cycleKey(url)
        return validatedTargets[key] ?: validateSafeTarget(url).also {
            validatedTargets[key] = it
        }
    }

    suspend fun get(
        url: String,
        allowRedirects: Boolean,
        requestHeaders: Map<String, String> = emptyMap(),
        interceptor: Interceptor? = null,
    ): NiceResponse {
        var target = validateTarget(url)
        val initialOrigin = target.origin()
        val redirectChain = HashSet<String>()
        redirectChain += cycleKey(target.url)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (remainingRequests <= 0) throw ResolverBudgetExceeded()
            remainingRequests--

            // Keep caller headers confined to the original scheme, host, and effective
            // port. The base user agent remains on every request through Requests.
            val headers = if (target.origin() == initialOrigin) requestHeaders else emptyMap()
            val response = requestsPinnedTo(target, cookieJar).get(
                target.url,
                headers = headers,
                interceptor = interceptor,
                allowRedirects = false,
                cacheTime = 0,
                timeout = REQUEST_TIMEOUT_SECONDS,
            )
            val location = response.headers["location"]
            if (!allowRedirects || response.code !in REDIRECT_STATUS_CODES || location == null) {
                return response
            }

            response.body.close()
            if (redirects >= MAX_REDIRECT_CODES) throw ResolverBudgetExceeded()
            consumeResolutionHop()
            target = validateTarget(resolveReference(target.url, location))
            if (!redirectChain.add(cycleKey(target.url))) throw ResolverBudgetExceeded()
            redirects++
        }
    }

    /** Executes one non-redirecting form POST through the same validated, DNS-pinned path. */
    suspend fun postFormOnce(
        url: String,
        requestHeaders: Map<String, String>,
        formFields: Map<String, String>,
    ): NiceResponse {
        if (remainingPostRequests <= 0 || remainingRequests <= 0) throw ResolverBudgetExceeded()
        val target = validateTarget(url)
        remainingPostRequests--
        remainingRequests--
        return requestsPinnedTo(target, cookieJar).post(
            target.url,
            headers = requestHeaders,
            data = formFields,
            allowRedirects = false,
            cacheTime = 0,
            timeout = REQUEST_TIMEOUT_SECONDS,
        )
    }

    /** Executes one non-redirecting JSON POST through the same validated, DNS-pinned path. */
    suspend fun postJsonOnce(
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: RequestBody,
    ): NiceResponse {
        if (remainingPostRequests <= 0 || remainingRequests <= 0) throw ResolverBudgetExceeded()
        val target = validateTarget(url)
        remainingPostRequests--
        remainingRequests--
        return requestsPinnedTo(target, cookieJar).post(
            target.url,
            requestBody = requestBody,
            headers = requestHeaders,
            allowRedirects = false,
            cacheTime = 0,
            timeout = REQUEST_TIMEOUT_SECONDS,
        )
    }
}

private fun isWithinAllowedHost(url: String, domain: String): Boolean = try {
    val candidate = url.trim()
    val uri = URI(candidate)
    val host = Url(candidate).host.lowercase(Locale.ROOT)
    uri.rawUserInfo == null && host.isNotEmpty() && !host.endsWith('.') &&
        !host.startsWith('.') && ".." !in host &&
        (host == domain || host.endsWith(".$domain"))
} catch (_: Exception) {
    false
}

private fun isExactAllowedHost(url: String, hosts: Set<String>): Boolean = try {
    val candidate = url.trim()
    val uri = URI(candidate)
    val parsed = Url(candidate)
    val scheme = parsed.protocol.name.lowercase(Locale.ROOT)
    val host = parsed.host.lowercase(Locale.ROOT)
    uri.rawUserInfo == null && (scheme == "http" || scheme == "https") &&
        !host.endsWith('.') && !host.startsWith('.') && ".." !in host && hosts.contains(host)
} catch (_: Exception) {
    false
}

private fun isFilemoonHttpsUrl(url: String): Boolean =
    isStrictHttpsUrl(url) && isExactAllowedHost(url, FILEMOON_ALLOWED_HOSTS)

internal fun isStrictHttpsUrl(url: String): Boolean = try {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) && uri.rawAuthority != null &&
        uri.rawUserInfo == null && !uri.rawAuthority.endsWith(':') &&
        (uri.port == -1 || uri.port == 443)
} catch (_: Exception) {
    false
}

private data class NormalizedDoodRequest(
    val requestUrl: String,
    val referer: String,
)

private data class NormalizedGMPlayerRequest(
    val id: String,
    val endpoint: String,
    val origin: String,
    val referer: String,
)

/** Keeps GMPlayer's id and POST target within the dynamic extractor HTTPS origin. */
private fun normalizeGMPlayerRequest(
    url: String,
    mainUrl: String,
    referer: String,
): NormalizedGMPlayerRequest? {
    if (url.length !in 1..MAX_GMPLAYER_URL_CHARS ||
        mainUrl.length !in 1..MAX_GMPLAYER_URL_CHARS ||
        referer.length !in 1..MAX_GMPLAYER_REFERER_CHARS ||
        url != url.trim() || mainUrl != mainUrl.trim() || referer != referer.trim() ||
        referer.any { it.code < 0x20 || it.code in 0x7F..0x9F }
    ) return null

    return try {
        val baseUri = URI(mainUrl)
        val baseAuthority = baseUri.rawAuthority
        val base = mainUrl.toHttpUrl()
        if (baseUri.scheme?.equals("https", ignoreCase = true) != true ||
            baseAuthority == null || baseAuthority.isEmpty() || baseAuthority.endsWith(':') ||
            baseUri.rawUserInfo != null || baseUri.rawQuery != null || baseUri.rawFragment != null ||
            baseUri.port == 0 || baseUri.port > 65_535 || base.encodedPath != "/" ||
            base.scheme != "https" || base.host.isEmpty() || base.host.endsWith('.')
        ) return null

        val pageUri = URI(url)
        val pageAuthority = pageUri.rawAuthority
        val page = url.toHttpUrl()
        if (pageUri.scheme?.equals("https", ignoreCase = true) != true ||
            pageAuthority == null || pageAuthority.isEmpty() || pageAuthority.endsWith(':') ||
            pageUri.rawUserInfo != null || pageUri.rawQuery != null || pageUri.rawFragment != null ||
            pageUri.port == 0 || pageUri.port > 65_535 || page.scheme != "https" ||
            !sameHttpOrigin(page.toString(), base.toString())
        ) return null

        val id = GMPLAYER_VIDEO_PATH.matchEntire(pageUri.rawPath.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.takeUnless { it == "." || it == ".." }
            ?: return null

        val refererUri = URI(referer)
        val refererAuthority = refererUri.rawAuthority
        val parsedReferer = referer.toHttpUrl()
        if (refererAuthority == null || refererAuthority.isEmpty() || refererAuthority.endsWith(':') ||
            refererUri.rawUserInfo != null ||
            (parsedReferer.scheme != "http" && parsedReferer.scheme != "https") ||
            parsedReferer.host.isEmpty()
        ) return null

        val endpoint = base.newBuilder()
            .encodedPath("/player/index.php")
            .query(null)
            .fragment(null)
            .addQueryParameter("data", id)
            .addQueryParameter("do", "getVideo")
            .build()
            .toString()
        if (!sameHttpOrigin(endpoint, base.toString())) return null

        val origin = base.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .removeSuffix("/")

        NormalizedGMPlayerRequest(id, endpoint, origin, referer)
    } catch (_: Exception) {
        null
    }
}

/** Host check for Dood aliases follows Orbit's direct-match www normalization. */
private fun normalizeDoodRequest(url: String, mainUrl: String): NormalizedDoodRequest? {
    return try {
        val candidate = url.trim()
        val baseCandidate = mainUrl.trim()
        val candidateUri = URI(candidate)
        val baseUri = URI(baseCandidate)
        val parsed = candidate.toHttpUrl()
        val base = baseCandidate.toHttpUrl()
        val candidateAuthority = candidateUri.rawAuthority
        val baseAuthority = baseUri.rawAuthority
        if ((candidateUri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) ||
            (baseUri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) ||
            candidateAuthority == null || candidateAuthority.isEmpty() ||
            candidateAuthority.endsWith(':') || baseAuthority == null || baseAuthority.isEmpty() ||
            baseAuthority.endsWith(':') ||
            candidateUri.rawUserInfo != null || baseUri.rawUserInfo != null ||
            candidateUri.rawFragment != null || baseUri.rawQuery != null || baseUri.rawFragment != null ||
            base.encodedPath != "/" || candidateUri.port == 0 || candidateUri.port > 65_535 ||
            baseUri.port == 0 || baseUri.port > 65_535 ||
            (baseUri.port >= 0 && candidateUri.port != baseUri.port)
        ) return null

        fun normalizedHost(host: String): String? {
            val normalized = host.lowercase(Locale.ROOT)
            if (normalized.isEmpty() || normalized.endsWith('.') || normalized.startsWith('.') ||
                ".." in normalized
            ) return null
            return normalized.removePrefix("www.")
        }

        if (normalizedHost(parsed.host) == null ||
            normalizedHost(parsed.host) != normalizedHost(base.host)
        ) return null

        val safeReferer = base.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
        NormalizedDoodRequest(parsed.toString(), safeReferer)
    } catch (_: Exception) {
        null
    }
}

private fun normalizeAllowedOrigin(origin: String, hosts: Set<String>): String? {
    return try {
        val candidate = origin.trim().trimEnd('/')
        val uri = URI(candidate)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val rawPath = uri.rawPath
        if ((scheme != "http" && scheme != "https") || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port == 0 ||
            uri.port > 65_535 || (rawPath != null && rawPath.isNotEmpty() && rawPath != "/") ||
            !hosts.contains(host)
        ) return null
        val port = if (uri.port < 0 || (scheme == "http" && uri.port == 80) ||
            (scheme == "https" && uri.port == 443)
        ) "" else ":${uri.port}"
        "$scheme://$host$port"
    } catch (_: Exception) {
        null
    }
}

private val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
private val BLOCKED_HOST_SUFFIXES = setOf(
    "localhost", "local", "localdomain", "internal", "test", "example", "invalid",
    "onion", "arpa", "home", "lan",
)

private data class SafeTarget(
    val url: String,
    val asciiHost: String,
    val addresses: List<InetAddress>,
)

private data class RequestOrigin(
    val scheme: String,
    val asciiHost: String,
    val effectivePort: Int,
)

/** HttpUrl canonicalizes IDNs and reports default ports as effective ports. */
private fun SafeTarget.origin(): RequestOrigin {
    return requestOrigin(url)
}

internal fun sameHttpOrigin(firstUrl: String, secondUrl: String): Boolean = try {
    requestOrigin(firstUrl) == requestOrigin(secondUrl)
} catch (_: Exception) {
    false
}

private fun requestOrigin(url: String): RequestOrigin {
    val parsed = url.toHttpUrl()
    return RequestOrigin(parsed.scheme, parsed.host, parsed.port)
}

/** Runs potentially non-interruptible system DNS calls behind a cancellable, capacity-bounded gate. */
private suspend fun lookupAddressesBounded(host: String): List<InetAddress> =
    suspendCancellableCoroutine { continuation ->
        val submissionLock = Any()
        val task = object : FutureTask<List<InetAddress>>(
            Callable { InetAddress.getAllByName(host).toList() }
        ) {
            override fun done() {
                if (!continuation.isActive) return
                try {
                    continuation.resumeWith(Result.success(get()))
                } catch (_: CancellationException) {
                    // The associated caller was canceled; its handler already removes the task.
                } catch (error: ExecutionException) {
                    continuation.resumeWith(Result.failure(error.cause ?: error))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    continuation.resumeWith(Result.failure(interrupted))
                }
            }
        }

        continuation.invokeOnCancellation {
            synchronized(submissionLock) {
                task.cancel(true)
                dnsLookupExecutor.remove(task)
            }
        }

        synchronized(submissionLock) {
            if (!continuation.isActive) {
                task.cancel(false)
                return@suspendCancellableCoroutine
            }
            try {
                dnsLookupExecutor.execute(task)
            } catch (rejected: RejectedExecutionException) {
                task.cancel(false)
                continuation.resumeWith(Result.failure(rejected))
            }
        }
    }

/** DNS answers for playback are resolved once by the player's factory-scoped pin cache. */
internal suspend fun lookupPublicPlaybackAddresses(hostname: String): List<InetAddress> =
    withTimeout(PLAYBACK_DNS_TIMEOUT_MILLIS) {
        val host = hostname.trim().trim('[', ']').lowercase(Locale.ROOT)
        if (host.isEmpty() || host.length > 253 || host.endsWith('.') || '%' in host ||
            BLOCKED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") } ||
            ('.' !in host && ':' !in host)
        ) throw UnsafeResolverTarget()

        val asciiHost = if (':' in host) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        val addresses = lookupAddressesBounded(asciiHost)
        currentCoroutineContext().ensureActive()
        if (addresses.isEmpty() || addresses.size > MAX_PLAYBACK_DNS_ADDRESSES ||
            addresses.any { !isPublicAddress(it) }
        ) throw UnsafeResolverTarget()
        addresses.toList()
    }

private suspend fun validateSafeTarget(value: String): SafeTarget {
    val candidate = value.trim()
    val parsed = Url(candidate)
    val scheme = parsed.protocol.name.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") throw UnsafeResolverTarget()

    val uri = URI(candidate)
    if (uri.rawUserInfo != null || uri.port == 0 || uri.port > 65_535) {
        throw UnsafeResolverTarget()
    }

    val host = parsed.host.trim('[', ']').trimEnd('.').lowercase(Locale.ROOT)
    if (host.isEmpty() || host.endsWith('.') ||
        BLOCKED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") } ||
        ('.' !in host && ':' !in host)
    ) {
        throw UnsafeResolverTarget()
    }

    val asciiHost = if (':' in host) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
    val addresses = lookupAddressesBounded(asciiHost)
    currentCoroutineContext().ensureActive()
    if (addresses.isEmpty() || addresses.size > MAX_PLAYBACK_DNS_ADDRESSES ||
        addresses.any { !isPublicAddress(it) }
    ) {
        throw UnsafeResolverTarget()
    }
    return SafeTarget(candidate, asciiHost, addresses)
}

private fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false

    val bytes = address.address.map { it.toInt() and 0xFF }
    if (address is Inet4Address || bytes.size == 4) return isPublicIpv4(bytes)
    if (address !is Inet6Address || bytes.size != 16) return false

    // IPv4-mapped IPv6 uses the same rejection rules as its embedded address.
    if (bytes.take(10).all { it == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF) {
        return isPublicIpv4(bytes.drop(12))
    }

    // Only global-unicast 2000::/3 is eligible; exclude special-use and tunneling ranges.
    val first = bytes[0]
    if ((first and 0xE0) != 0x20) return false
    // ORCHID v1/v2 are /28 ranges, not single values in the low byte.
    if (matchesCidr(bytes, intArrayOf(0x20, 0x01, 0x00, 0x10), 28) ||
        matchesCidr(bytes, intArrayOf(0x20, 0x01, 0x00, 0x20), 28)
    ) return false

    // Reject the broader special-use ranges as well as the documentation ranges.
    if (matchesCidr(bytes, intArrayOf(0x20, 0x01, 0x00), 23) || // IETF protocol assignments
        matchesCidr(bytes, intArrayOf(0x20, 0x01, 0x0D, 0xB8), 32) || // documentation
        matchesCidr(bytes, intArrayOf(0x20, 0x02), 16) || // 6to4
        matchesCidr(bytes, intArrayOf(0x3F, 0xFF, 0x00), 20) // documentation
    ) return false
    return true
}

private fun isPublicIpv4(bytes: List<Int>): Boolean {
    if (bytes.size != 4) return false
    if (matchesCidr(bytes, intArrayOf(192, 31, 196), 24) ||
        matchesCidr(bytes, intArrayOf(192, 52, 193), 24) ||
        matchesCidr(bytes, intArrayOf(192, 175, 48), 24)
    ) return false // special-purpose anycast ranges

    val (a, b, c) = bytes
    if (a == 0 || a == 10 || a == 127 || a >= 224) return false
    if (a == 100 && b in 64..127) return false // carrier-grade NAT
    if (a == 169 && b == 254) return false
    if (a == 172 && b in 16..31) return false
    if (a == 192 && (b == 0 || b == 168)) return false
    if (a == 192 && b == 88 && c == 99) return false
    if (a == 198 && (b in 18..19 || (b == 51 && c == 100))) return false
    if (a == 203 && b == 0 && c == 113) return false
    return true
}

private fun matchesCidr(address: List<Int>, prefix: IntArray, prefixLength: Int): Boolean {
    if (prefixLength < 0 || prefixLength > address.size * 8 || prefixLength > prefix.size * 8) {
        return false
    }

    val completeBytes = prefixLength / 8
    for (index in 0 until completeBytes) {
        if (address[index] != prefix[index]) return false
    }

    val remainingBits = prefixLength % 8
    if (remainingBits == 0) return true
    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    return (address[completeBytes] and mask) == (prefix[completeBytes] and mask)
}

private fun requestsPinnedTo(target: SafeTarget, cookieJar: CookieJar): Requests {
    val shared = MainActivityKt.getApp()
    val pinnedDns = Dns { hostname ->
        val normalized = hostname.trim('[', ']').trimEnd('.').lowercase(Locale.ROOT)
        if (normalized == target.asciiHost) {
            target.addresses
        } else {
            Dns.SYSTEM.lookup(hostname)
        }
    }
    val requestClient = shared.baseClient.newBuilder()
        .dns(pinnedDns)
        .cookieJar(cookieJar)
        .connectionPool(ConnectionPool())
        .proxy(Proxy.NO_PROXY)
        .build()
    return Requests(
        requestClient,
        mapOf("user-agent" to MainAPIKt.USER_AGENT),
        null,
        emptyMap(),
        emptyMap(),
        0,
        TimeUnit.SECONDS,
        REQUEST_TIMEOUT_SECONDS,
        null,
    )
}

/** Keeps short-link cookies isolated to one resolution and applies OkHttp domain/path rules. */
private class ResolutionCookieJar : CookieJar {
    private val lock = Any()
    private val storedCookies = ArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            storedCookies.removeAll { it.expiresAt <= now }
            for (cookie in cookies) {
                storedCookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                if (cookie.expiresAt > now) storedCookies += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        storedCookies.removeAll { it.expiresAt <= now }
        storedCookies.filter { it.matches(url) }
    }
}

/** Dood extraction uses no cookies so a redirect cannot carry stored credentials to another origin. */
private object EmptyCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
}

private suspend fun NiceResponse.boundedText(maxBytes: Long = MAX_TEXT_BYTES): String = withContext(Dispatchers.IO) {
    val responseBody = body
    if ((size ?: 0L) > maxBytes) {
        responseBody.close()
        throw ResolverBodyTooLarge()
    }
    val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val bytes = responseBody.byteStream().use { input -> readBoundedTextBytes(input, maxBytes) }
    String(bytes, charset)
}

internal fun readBoundedTextBytes(input: InputStream, maxBytes: Long): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val remaining = maxBytes + 1L - total
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) break
        if (read == 0) {
            val next = input.read()
            if (next < 0) break
            total++
            if (total > maxBytes) throw ResolverBodyTooLarge()
            bytes.write(next)
            continue
        }
        total += read
        if (total > maxBytes) throw ResolverBodyTooLarge()
        bytes.write(buffer, 0, read)
    }
    return bytes.toByteArray()
}

private suspend fun NiceResponse.boundedBytes(
    maxBytes: Long,
    onBytesRead: (Long) -> Unit,
): ByteArray = withContext(Dispatchers.IO) {
    val responseBody = body
    val declaredSize = size?.takeIf { it >= 0L } ?: responseBody.contentLength()
    if (declaredSize > maxBytes) {
        responseBody.close()
        throw ResolverBodyTooLarge()
    }

    val bytes = ByteArrayOutputStream()
    responseBody.byteStream().use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val remaining = maxBytes - total
            if (remaining <= 0L) {
                if (declaredSize == total) break
                throw ResolverBodyTooLarge()
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) {
                val next = input.read()
                if (next < 0) break
                total++
                onBytesRead(1L)
                bytes.write(next)
                continue
            }
            total += read
            onBytesRead(read.toLong())
            bytes.write(buffer, 0, read)
        }
    }
    bytes.toByteArray()
}

private fun decodeBase64Latin1(value: String): String {
    val bytes = Base64.decode(value, Base64.DEFAULT)
    return buildString(bytes.size) {
        for (byte in bytes) append((byte.toInt() and 0xFF).toChar())
    }
}

private fun resolveReference(base: String, candidate: String): String = try {
    URI(base).resolve(candidate.trim()).toString()
} catch (_: Exception) {
    candidate.trim()
}

private fun cycleKey(url: String): String {
    try {
        val candidate = url.trim()
        if (URI(candidate).rawUserInfo != null) throw UnsafeResolverTarget()
        val parsed = candidate.toHttpUrl()
        val scheme = parsed.scheme
        val host = parsed.host.removePrefix("[").removeSuffix("]")
        val port = parsed.port
        if ((scheme != "http" && scheme != "https") || host.isEmpty() || port !in 1..65_535) {
            throw UnsafeResolverTarget()
        }

        // HttpUrl canonicalizes IDNs and IPv6, and exposes the effective default port.
        // Static examples: https://bücher.example/a -> https://xn--bcher-kva.example:443/a;
        // https://münich.example/a -> https://xn--mnich-kva.example:443/a.
        val authorityHost = if (':' in host) "[$host]" else host
        val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
        val fragment = parsed.encodedFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$authorityHost:$port${parsed.encodedPath}$query$fragment"
    } catch (_: Exception) {
        throw UnsafeResolverTarget()
    }
}

private class ResolverBudgetExceeded : Exception()
private class ResolverBodyTooLarge : Exception()
private class UnsafeResolverTarget : Exception()
