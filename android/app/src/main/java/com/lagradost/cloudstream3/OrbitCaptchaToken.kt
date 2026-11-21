@file:JvmName("OrbitCaptchaToken")

package com.lagradost.cloudstream3

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val REQUEST_TIMEOUT_MILLIS = 8_000L
private const val FLOW_TIMEOUT_MILLIS = 30_000L
private const val MAX_REDIRECTS_PER_REQUEST = 3
private const val MAX_FLOW_REQUESTS = 12
private const val MAX_API_JS_BYTES = 512 * 1024L
private const val MAX_ANCHOR_BYTES = 512 * 1024L
private const val MAX_RELOAD_BYTES = 64 * 1024L
private const val NO_CACHE_REQUEST_POLICY = "no-cache, no-store"

private data class CaptchaHttpResult(
    val statusCode: Int,
    val text: String?,
    val location: String?,
)

/**
 * Implements the pinned CloudStream api.js -> anchor -> reload sequence.
 * Every request has a finite timeout/body cap, redirects stay on Google's HTTPS origin,
 * and cancellation aborts the active OkHttp call.
 */
suspend fun getCaptchaToken(url: String, key: String, referer: String?): String? {
    try {
        if (url.length > 4_096 || key.isBlank() || key.length > 1_024 ||
            referer != null && referer.length > 4_096
        ) return null

        val siteUrl = Url(url)
        val protocol = siteUrl.protocol.name
        if ((protocol != "http" && protocol != "https") || siteUrl.host.isBlank()) return null

        // Keep the exact host/protocol/port encoding used by the pinned MainAPI.kt.
        val domain = MainAPIKt.base64Encode("$protocol://${siteUrl.host}:443".toByteArray(Charsets.UTF_8))
            .replace("\n", "")
            .replace("=", ".")

        return withTimeout(FLOW_TIMEOUT_MILLIS) {
            val client = MainActivityKt.getApp().baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .writeTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .callTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            val requestBudget = RequestBudget()

            val apiJsUrl = googleUrl("/recaptcha/api.js")
                .newBuilder()
                .addQueryParameter("render", key)
                .build()
            val apiJsRequest = Request.Builder()
                .url(apiJsUrl)
                .get()
                .header("User-Agent", MainAPIKt.USER_AGENT)
                .header("Cache-Control", NO_CACHE_REQUEST_POLICY)
                .header("Pragma", "no-cache")
                .apply { if (referer != null) header("Referer", referer) }
                .build()
            val apiJs = requestText(client, requestBudget, apiJsRequest, MAX_API_JS_BYTES)
            val version = apiJs.substringAfter("releases/", missingDelimiterValue = "")
                .substringBefore("/")
            if (version.isBlank()) return@withTimeout null

            val anchorUrl = googleUrl("/recaptcha/api2/anchor")
                .newBuilder()
                .addQueryParameter("ar", "1")
                .addQueryParameter("hl", "en")
                .addQueryParameter("size", "invisible")
                .addQueryParameter("cb", "cs3")
                .addQueryParameter("k", key)
                .addQueryParameter("co", domain)
                .addQueryParameter("v", version)
                .build()
            val anchorRequest = Request.Builder()
                .url(anchorUrl)
                .get()
                .header("User-Agent", MainAPIKt.USER_AGENT)
                .header("Cache-Control", NO_CACHE_REQUEST_POLICY)
                .header("Pragma", "no-cache")
                .build()
            val anchorHtml = requestText(client, requestBudget, anchorRequest, MAX_ANCHOR_BYTES)
            val anchorToken = Jsoup.parse(anchorHtml, anchorUrl.toString())
                .selectFirst("#recaptcha-token")
                ?.attr("value")
                ?.takeIf { it.isNotEmpty() }
                ?: return@withTimeout null

            val reloadBody = FormBody.Builder()
                .add("v", version)
                .add("k", key)
                .add("c", anchorToken)
                .add("co", domain)
                .add("sa", "")
                .add("reason", "q")
                .build()
            val reloadRequest = Request.Builder()
                .url(googleUrl("/recaptcha/api2/reload").newBuilder().addQueryParameter("k", key).build())
                .post(reloadBody)
                .header("User-Agent", MainAPIKt.USER_AGENT)
                .header("Cache-Control", NO_CACHE_REQUEST_POLICY)
                .header("Pragma", "no-cache")
                .build()
            val reloadText = requestText(client, requestBudget, reloadRequest, MAX_RELOAD_BYTES)
            val marker = "rresp\",\""
            val markerIndex = reloadText.indexOf(marker)
            if (markerIndex < 0) return@withTimeout null
            val tokenStart = markerIndex + marker.length
            val tokenEnd = reloadText.indexOf('"', tokenStart)
            if (tokenEnd < tokenStart) null else reloadText.substring(tokenStart, tokenEnd)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // CloudStream's helper returns null for malformed URLs, HTTP, and parse failures.
        return null
    }
}

private class RequestBudget {
    private var requests = 0

    fun consume() {
        requests++
        if (requests > MAX_FLOW_REQUESTS) throw IOException("CAPTCHA request limit reached")
    }
}

private fun googleUrl(path: String): HttpUrl = "https://www.google.com$path".toHttpUrl()

private fun isAllowedGoogleUrl(url: HttpUrl): Boolean =
    url.isHttps && url.host == "www.google.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty()

private suspend fun requestText(
    client: OkHttpClient,
    budget: RequestBudget,
    initialRequest: Request,
    maxBodyBytes: Long,
): String {
    var request = initialRequest
    var redirects = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!isAllowedGoogleUrl(request.url)) throw IOException("CAPTCHA request left Google's HTTPS origin")
        budget.consume()
        val result = execute(client.newCall(request), maxBodyBytes)
        if (result.statusCode in 300..399) {
            if (redirects >= MAX_REDIRECTS_PER_REQUEST) throw IOException("CAPTCHA redirect limit reached")
            val location = result.location ?: throw IOException("CAPTCHA redirect has no location")
            val nextUrl = request.url.resolve(location)
                ?.takeIf(::isAllowedGoogleUrl)
                ?: throw IOException("CAPTCHA redirect left Google's HTTPS origin")
            val redirected = request.newBuilder().url(nextUrl)
            if (result.statusCode == 303 ||
                (result.statusCode == 301 || result.statusCode == 302) && request.method == "POST"
            ) {
                redirected.get()
            }
            request = redirected.build()
            redirects++
            continue
        }
        if (result.statusCode !in 200..299) throw IOException("CAPTCHA request failed")
        return result.text ?: ""
    }
}

private suspend fun execute(call: Call, maxBodyBytes: Long): CaptchaHttpResult =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                resumeFailure(continuation, e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { received ->
                        val code = received.code
                        if (code in 300..399) {
                            CaptchaHttpResult(code, null, received.header("Location"))
                        } else {
                            val body = received.body
                            CaptchaHttpResult(
                                code,
                                if (code in 200..299) readBounded(body, maxBodyBytes) else null,
                                null,
                            )
                        }
                    }
                    resumeSuccess(continuation, result)
                } catch (error: Exception) {
                    resumeFailure(continuation, error)
                }
            }
        })
    }

private fun readBounded(body: ResponseBody, maxBodyBytes: Long): String {
    val declaredLength = body.contentLength()
    if (declaredLength > maxBodyBytes) throw IOException("CAPTCHA response body is too large")
    val charset = body.contentType()?.charset(Charset.forName("UTF-8")) ?: Charsets.UTF_8
    val output = ByteArrayOutputStream(minOf(maxBodyBytes, 8_192L).toInt())
    body.byteStream().use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val remaining = maxBodyBytes + 1L - total
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            if (count == 0) throw IOException("Response stream made no progress.")
            total += count
            if (total > maxBodyBytes) throw IOException("CAPTCHA response body is too large")
            output.write(buffer, 0, count)
        }
    }
    return String(output.toByteArray(), charset)
}

private fun resumeSuccess(
    continuation: CancellableContinuation<CaptchaHttpResult>,
    result: CaptchaHttpResult,
) {
    continuation.resume(result)
}

private fun resumeFailure(
    continuation: CancellableContinuation<CaptchaHttpResult>,
    error: Throwable,
) {
    continuation.resumeWithException(error)
}
