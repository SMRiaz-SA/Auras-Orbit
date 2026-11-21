package com.lagradost.cloudstream3.network

import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.auras.orbit.network.OrbitWebViewResolverRuntime
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

private const val DEFAULT_CLOUDSTREAM_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

/**
 * Orbit's bounded Android host for the pinned CloudStream WebViewResolver surface.
 * Security and size limits intentionally differ from CloudStream; see the slice evidence.
 */
class WebViewResolver(
    interceptUrl: Regex,
    additionalUrls: List<Regex> = emptyList(),
    userAgent: String? = DEFAULT_CLOUDSTREAM_USER_AGENT,
    useOkhttp: Boolean = true,
    script: String? = null,
    scriptCallback: ((String) -> Unit)? = null,
    timeout: Long = DEFAULT_TIMEOUT
) : Interceptor {
    private val interceptPattern = interceptUrl
    private val extraPatterns = additionalUrls
    private val configuredUserAgent = userAgent
    private val useOkhttpTransport = useOkhttp
    private val javascript = script
    private val javascriptCallback = scriptCallback
    private val timeoutMs = timeout

    companion object {
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        private val HTTP_SCHEMES = setOf("http", "https")

        var webViewUserAgent: String? = null
        val DEFAULT_TIMEOUT: Long = 60_000L

        /** Returns the cached Android WebView user agent without constructing a WebView off-main. */
        @JvmName("getWebViewUserAgent1")
        fun getWebViewUserAgent(): String? {
            webViewUserAgent?.let { return it }
            val activity = runCatching { OrbitCs3HostServices.get().currentActivity }.getOrNull()
                ?: return null
            val readUserAgent = { WebSettings.getDefaultUserAgent(activity) }
            val result = if (Looper.myLooper() == Looper.getMainLooper()) {
                runCatching(readUserAgent).getOrNull()
            } else {
                val task = FutureTask(readUserAgent)
                Handler(Looper.getMainLooper()).post(task)
                runCatching { task.get(3, TimeUnit.SECONDS) }.getOrNull()
            }
            if (result != null) webViewUserAgent = result
            return result
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IOException("WebViewResolver.intercept cannot block Android's main thread.")
        }
        val original = chain.request()
        val final = kotlinx.coroutines.runBlocking { resolveUsingWebView(original).first }
        return chain.proceed(final ?: original)
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false }
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false }
    ): Pair<Request?, List<Request>> {
        val parsedUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("WebViewResolver only accepts HTTP(S) entry URLs.")
        val builder = Request.Builder().url(parsedUrl)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (referer != null) builder.header("Referer", referer)
        val normalizedMethod = method.uppercase()
        val body = if (normalizedMethod in BODY_REQUIRED_METHODS) ByteArray(0).toRequestBody(null) else null
        return resolveRequest(builder.method(normalizedMethod, body).build(), requestCallBack)
    }

    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false }
    ): Pair<Request?, List<Request>> {
        if (request.url.scheme !in HTTP_SCHEMES) {
            throw IllegalArgumentException("WebViewResolver only accepts HTTP(S) entry URLs.")
        }
        return resolveRequest(request, requestCallBack)
    }

    private suspend fun resolveRequest(
        request: Request,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        val activity = OrbitCs3HostServices.get().currentActivity
            ?: throw IllegalStateException("WebViewResolver requires an active Orbit Activity.")
        return OrbitWebViewResolverRuntime.resolve(
            activity = activity,
            interceptUrl = interceptPattern,
            additionalUrls = extraPatterns,
            userAgent = configuredUserAgent,
            useOkhttp = useOkhttpTransport,
            script = javascript,
            scriptCallback = javascriptCallback,
            timeoutMs = timeoutMs,
            request = request,
            requestCallback = requestCallBack
        )
    }

}
