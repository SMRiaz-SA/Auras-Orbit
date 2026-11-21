package com.auras.orbit.network

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Orbit-owned Android host for CloudStream's WebViewResolver API. */
object OrbitWebViewResolverRuntime {
    private const val MAX_TIMEOUT_MS = 60_000L
    private const val MAX_CAPTURED_REQUESTS = 64
    private const val MAX_WEB_REQUESTS = 128
    private const val MAX_RESPONSE_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_SCRIPT_CHARS = 64 * 1024
    private const val MAX_SCRIPT_RESULT_CHARS = 16 * 1024
    private const val MAX_HEADER_COUNT = 64
    private const val MAX_HEADER_NAME_CHARS = 256
    private const val MAX_HEADER_VALUE_CHARS = 4096
    private const val MAX_RESPONSE_HEADER_COUNT = 64
    private const val MAX_RESPONSE_HEADER_TOTAL_BYTES = 16 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private val sessionsByActivity = IdentityHashMap<Activity, MutableSet<ResolveSession>>()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    internal fun shouldFetchWebRequestWithOkHttp(useOkhttp: Boolean, method: String, url: String): Boolean {
        if (!useOkhttp) return false
        val normalizedMethod = method.uppercase(Locale.ROOT)
        if (normalizedMethod != "GET" && normalizedMethod != "POST") return false
        val normalizedUrl = url.lowercase(Locale.ROOT)
        return !normalizedUrl.contains("recaptcha") && !normalizedUrl.contains("/cdn-cgi/")
    }

    internal fun collectBoundedResponseHeaders(headers: okhttp3.Headers): Map<String, String> {
        val retained = LinkedHashMap<String, RetainedHeader>()
        var retainedBytes = 0
        val inspectedCount = minOf(headers.size, MAX_RESPONSE_HEADER_COUNT)
        for (index in 0 until inspectedCount) {
            val name = headers.name(index)
            val value = headers.value(index)
            if (name.length > MAX_HEADER_NAME_CHARS || value.length > MAX_HEADER_VALUE_CHARS) continue

            val nameBytes = name.toByteArray(Charsets.UTF_8).size
            val valueBytes = value.toByteArray(Charsets.UTF_8).size
            val key = name.lowercase(Locale.ROOT)
            val previous = retained[key]
            val separatorBytes = if (previous == null) 0 else 2
            val newValueBytes = (previous?.valueBytes ?: 0) + separatorBytes + valueBytes
            if (newValueBytes > MAX_HEADER_VALUE_CHARS) continue
            val addedBytes = (if (previous == null) nameBytes else 0) + separatorBytes + valueBytes
            if (retainedBytes + addedBytes > MAX_RESPONSE_HEADER_TOTAL_BYTES) continue

            if (previous == null) {
                retained[key] = RetainedHeader(name, value, valueBytes)
            } else {
                previous.value = "${previous.value}, $value"
                previous.valueBytes = newValueBytes
            }
            retainedBytes += addedBytes
        }
        return retained.values.associate { it.name to it.value }
    }

    @JvmStatic
    suspend fun resolve(
        activity: Activity,
        interceptUrl: Regex,
        additionalUrls: List<Regex>,
        userAgent: String?,
        useOkhttp: Boolean,
        script: String?,
        scriptCallback: ((String) -> Unit)?,
        timeoutMs: Long,
        request: Request,
        requestCallback: (Request) -> Boolean
    ): Pair<Request?, List<Request>> = suspendCancellableCoroutine { continuation ->
        val session = ResolveSession(
            activity = activity,
            interceptUrl = interceptUrl,
            additionalUrls = additionalUrls.take(MAX_CAPTURED_REQUESTS),
            userAgent = userAgent,
            useOkhttp = useOkhttp,
            script = script?.take(MAX_SCRIPT_CHARS),
            scriptCallback = scriptCallback,
            timeoutMs = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS),
            initialRequest = request,
            requestCallback = requestCallback,
            continuation = continuation
        )
        synchronized(lifecycleLock) {
            sessionsByActivity.getOrPut(activity) { LinkedHashSet() }.add(session)
        }
        continuation.invokeOnCancellation { session.cancel(it) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            session.start()
        } else {
            mainHandler.post { session.start() }
        }
    }

    /** Cancels all WebViews before their owning Activity is stopped or destroyed. */
    @JvmStatic
    fun cancelForActivity(activity: Activity) {
        val sessions = synchronized(lifecycleLock) {
            sessionsByActivity.remove(activity)?.toList().orEmpty()
        }
        sessions.forEach { it.cancel(CancellationException("WebView host Activity stopped")) }
    }

    private fun removeSession(activity: Activity, session: ResolveSession) {
        synchronized(lifecycleLock) {
            val sessions = sessionsByActivity[activity] ?: return
            sessions.remove(session)
            if (sessions.isEmpty()) sessionsByActivity.remove(activity)
        }
    }

    private class ResolveSession(
        private val activity: Activity,
        private val interceptUrl: Regex,
        private val additionalUrls: List<Regex>,
        private val userAgent: String?,
        private val useOkhttp: Boolean,
        private val script: String?,
        private val scriptCallback: ((String) -> Unit)?,
        private val timeoutMs: Long,
        private val initialRequest: Request,
        private val requestCallback: (Request) -> Boolean,
        private val continuation: CancellableContinuation<Pair<Request?, List<Request>>>
    ) {
        private val completed = AtomicBoolean(false)
        private val totalRequestCount = AtomicInteger(0)
        private val networkFetchCount = AtomicInteger(0)
        private val capturedLock = Any()
        private val capturedRequests = ArrayList<Request>()
        private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        @Volatile private var finalRequest: Request? = null
        @Volatile private var webView: WebView? = null
        @Volatile private var initialJob: Job? = null
        @Volatile private var timeoutAction: Runnable? = null
        private val scriptCallbacksEnabled = AtomicBoolean(true)

        fun start() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                finish(IllegalStateException("WebView creation must run on Android main thread."))
                return
            }
            if (completed.get() || !continuation.isActive) {
                finish(CancellationException("WebView request was canceled before start"))
                return
            }
            try {
                val view = WebView(activity)
                webView = view
                configure(view)
                val timeout = Runnable { finish(null) }
                timeoutAction = timeout
                mainHandler.postDelayed(timeout, timeoutMs)

                if (initialRequest.method.equals("GET", ignoreCase = true)) {
                    view.loadUrl(initialRequest.url.toString(), headersForWebView(initialRequest))
                } else if (!useOkhttp && initialRequest.method.equals("POST", ignoreCase = true)) {
                    initialJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            if (captureIfMatching(initialRequest)) return@launch
                            val body = boundedRequestBodyBytes(initialRequest.body)
                            mainHandler.post {
                                if (!completed.get()) view.postUrl(initialRequest.url.toString(), body)
                            }
                        } catch (error: Throwable) {
                            if (!completed.get()) finish(error)
                        }
                    }
                } else if (!useOkhttp) {
                    throw IllegalArgumentException(
                        "Native WebView entry transport supports only GET and POST requests."
                    )
                } else {
                    initialJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            if (captureIfMatching(initialRequest)) return@launch
                            if (completed.get()) return@launch
                            if (!consumeNetworkBudget()) {
                                finish(null)
                                return@launch
                            }
                            val response = fetch(initialRequest)
                            if (completed.get()) return@launch
                            val text = decodeBody(response.body, response.contentType)
                            mainHandler.post {
                                if (!completed.get()) {
                                    view.loadDataWithBaseURL(
                                        initialRequest.url.toString(), text,
                                        response.mimeType, response.charset.name(), null
                                    )
                                }
                            }
                        } catch (error: Throwable) {
                            if (!completed.get()) finish(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                finish(error)
            }
        }

        private fun configure(view: WebView) {
            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // API 16+ defaults these deprecated file-origin flags to false; Orbit's minSdk is 23.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true

            val defaultUserAgent = settings.userAgentString
            WebViewResolver.webViewUserAgent = defaultUserAgent
            if (userAgent != null) settings.userAgentString = userAgent

            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (completed.get()) return emptyResponse()
                    val requestNumber = totalRequestCount.incrementAndGet()
                    val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                    if (scheme != "http" && scheme != "https") return emptyResponse()
                    scheduleScriptForRequest(view, requestNumber)
                    val captured = toRequest(request) ?: return emptyResponse()
                    if (captureIfMatching(captured)) return emptyResponse()
                    if (completed.get() || requestNumber > MAX_WEB_REQUESTS) return emptyResponse()
                    if (!shouldFetchWebRequestWithOkHttp(useOkhttp, captured.method, captured.url.toString())) {
                        // Let Chromium own cookies, browser headers, and challenge flows.
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (!consumeNetworkBudget()) return emptyResponse()
                    return try {
                        val response = fetch(captured)
                        if (completed.get()) emptyResponse() else response.toWebResourceResponse()
                    } catch (error: Throwable) {
                        if (completed.get()) emptyResponse()
                        else if (error is CancellationException) {
                            finish(error)
                            emptyResponse()
                        } else {
                            // CloudStream falls back to WebView-owned transport on client errors.
                            super.shouldInterceptRequest(view, request)
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                    return scheme != "http" && scheme != "https"
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    // CloudStream's pinned Android resolver proceeds on certificate errors;
                    // Orbit deliberately cancels all TLS failures.
                    handler?.cancel()
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    finish(IOException("WebView renderer process exited."))
                    return true
                }
            }
        }

        private fun scheduleScriptForRequest(view: WebView, requestNumber: Int) {
            val code = script ?: return
            if (requestNumber > MAX_WEB_REQUESTS || completed.get()) return
            mainHandler.post {
                if (!scriptCallbacksEnabled.get() || webView !== view) return@post
                try {
                    view.evaluateJavascript(code) { result ->
                        if (!scriptCallbacksEnabled.get()) return@evaluateJavascript
                        try {
                            scriptCallback?.invoke(result.orEmpty().take(MAX_SCRIPT_RESULT_CHARS))
                        } catch (error: Throwable) {
                            finish(error)
                        }
                    }
                } catch (error: Throwable) {
                    if (!completed.get()) finish(error)
                }
            }
        }

        private fun captureIfMatching(request: Request): Boolean {
            if (completed.get()) return true
            val url = request.url.toString()
            if (interceptUrl.containsMatchIn(url)) {
                synchronized(capturedLock) { finalRequest = request }
                try {
                    requestCallback(request)
                    finish(null)
                } catch (error: Throwable) {
                    finish(error)
                }
                return true
            }
            if (additionalUrls.none { it.containsMatchIn(url) }) return false

            val stored = synchronized(capturedLock) {
                if (capturedRequests.size >= MAX_CAPTURED_REQUESTS) false
                else {
                    capturedRequests.add(request)
                    true
                }
            }
            if (!stored) return false
            try {
                if (requestCallback(request)) finish(null)
            } catch (error: Throwable) {
                finish(error)
            }
            return completed.get()
        }

        private fun consumeNetworkBudget(): Boolean =
            networkFetchCount.incrementAndGet() <= MAX_WEB_REQUESTS

        private fun toRequest(request: WebResourceRequest): Request? = try {
            val builder = Request.Builder().url(request.url.toString())
            request.requestHeaders.entries.take(MAX_HEADER_COUNT).forEach { (name, value) ->
                if (name.length <= MAX_HEADER_NAME_CHARS && value.length <= MAX_HEADER_VALUE_CHARS) {
                    builder.header(name, value)
                }
            }
            val method = request.method.uppercase(Locale.ROOT)
            val body = if (method in BODY_REQUIRED_METHODS) ByteArray(0).toRequestBody(null) else null
            builder.method(method, body).build()
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun headersForWebView(request: Request): Map<String, String> = buildMap {
            for (index in 0 until request.headers.size) {
                val name = request.headers.name(index)
                val value = request.headers.value(index)
                if (name.length <= MAX_HEADER_NAME_CHARS && value.length <= MAX_HEADER_VALUE_CHARS) {
                    put(name, value)
                }
            }
        }

        private fun fetch(request: Request): BoundedResponse = fetchWithOkHttp(request)

        private fun fetchWithOkHttp(request: Request): BoundedResponse {
            val call = okHttpClient.newCall(request)
            if (!registerCancelableResource(call, completed, activeCalls, Call::cancel)) {
                throw CancellationException("WebView request completed before OkHttp started.")
            }
            try {
                return call.execute().use { response -> response.toBoundedResponse() }
            } finally {
                activeCalls.remove(call)
            }
        }

        private fun Response.toBoundedResponse(): BoundedResponse {
            val responseHeaders = collectBoundedResponseHeaders(headers)
            val contentType = responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value ?: "application/octet-stream"
            val bounded = body.byteStream().use(::readBounded)
            return BoundedResponse(
                if (bounded.exceeded) 413 else code,
                if (bounded.exceeded) "Response Too Large" else message,
                contentType,
                responseHeaders,
                if (bounded.exceeded) TOO_LARGE_BODY else bounded.bytes
            )
        }

        private fun BoundedResponse.toWebResourceResponse(): WebResourceResponse {
            val mimeType = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = CHARSET_PATTERN.find(contentType)?.groupValues?.getOrNull(1)
                ?.let { runCatching { Charset.forName(it.trim('"', '\'')) }.getOrNull() }
                ?: Charsets.UTF_8
            val safeHeaders = LinkedHashMap<String, String>()
            headers.forEach { (name, value) ->
                if (name.length <= MAX_HEADER_NAME_CHARS && value.length <= MAX_HEADER_VALUE_CHARS) {
                    safeHeaders[name] = value
                }
            }
            return WebResourceResponse(
                mimeType,
                charset.name(),
                code.coerceIn(100, 599),
                reason.ifBlank { "OK" },
                safeHeaders,
                ByteArrayInputStream(body)
            )
        }

        private fun decodeBody(bytes: ByteArray, contentType: String): String {
            val charset = CHARSET_PATTERN.find(contentType)?.groupValues?.getOrNull(1)
                ?.let { runCatching { Charset.forName(it.trim('"', '\'')) }.getOrNull() }
                ?: Charsets.UTF_8
            return String(bytes, charset)
        }

        private fun boundedRequestBodyBytes(body: RequestBody?): ByteArray {
            if (body == null) return ByteArray(0)
            val declaredLength = body.contentLength()
            if (declaredLength < 0L || declaredLength > MAX_RESPONSE_BODY_BYTES) {
                throw IOException("Native WebView POST body must have a known size within Orbit's bound.")
            }
            val buffer = Buffer()
            body.writeTo(buffer)
            if (buffer.size > MAX_RESPONSE_BODY_BYTES.toLong()) {
                throw IOException("Native WebView POST body exceeds Orbit's bound.")
            }
            return buffer.readByteArray()
        }

        private fun readBounded(input: InputStream): BoundedBody {
            val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BODY_BYTES, 16 * 1024))
            val buffer = ByteArray(8192)
            var total = 0
            while (total <= MAX_RESPONSE_BODY_BYTES) {
                val allowed = minOf(buffer.size, MAX_RESPONSE_BODY_BYTES + 1 - total)
                val count = input.read(buffer, 0, allowed)
                if (count < 0) break
                if (count == 0) throw IOException("Response stream made no progress.")
                total += count
                val keep = minOf(count, MAX_RESPONSE_BODY_BYTES - output.size())
                if (keep > 0) output.write(buffer, 0, keep)
            }
            return if (total > MAX_RESPONSE_BODY_BYTES) BoundedBody(ByteArray(0), true)
            else BoundedBody(output.toByteArray(), false)
        }

        private fun finish(error: Throwable?) {
            if (!completed.compareAndSet(false, true)) return
            if (error != null || finalRequest == null) scriptCallbacksEnabled.set(false)
            timeoutAction?.let(mainHandler::removeCallbacks)
            initialJob?.cancel()
            activeCalls.forEach(Call::cancel)
            val result = synchronized(capturedLock) {
                finalRequest to capturedRequests.toList()
            }
            val cleanup = Runnable {
                val view = webView
                if (view != null) {
                    runCatching { view.stopLoading() }
                    (view.parent as? ViewGroup)?.removeView(view)
                    runCatching { view.destroy() }
                    webView = null
                }
                removeSession(activity, this)
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWith(Result.failure(error))
                    else continuation.resumeWith(Result.success(result))
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
            else mainHandler.post(cleanup)
        }

        fun cancel(cause: Throwable?) {
            finish(cause ?: CancellationException("WebView resolution canceled"))
        }
    }

    private data class RetainedHeader(val name: String, var value: String, var valueBytes: Int)

    private data class BoundedBody(val bytes: ByteArray, val exceeded: Boolean)

    private data class BoundedResponse(
        val code: Int,
        val reason: String,
        val contentType: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val charset: Charset = CHARSET_PATTERN.find(contentType)?.groupValues?.getOrNull(1)
            ?.let { runCatching { Charset.forName(it.trim('"', '\'')) }.getOrNull() }
            ?: Charsets.UTF_8,
        val mimeType: String = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
    )

    private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    private val CHARSET_PATTERN = Regex("(?i)charset\\s*=\\s*([^;]+)")
    private val TOO_LARGE_BODY = "Response exceeded Orbit's 2 MiB WebView limit.".toByteArray()

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
    )
}

/** Adds a cancellable resource, then closes the completion race before its work can start. */
internal fun <T> registerCancelableResource(
    resource: T,
    completed: AtomicBoolean,
    activeResources: MutableSet<T>,
    cancelResource: (T) -> Unit
): Boolean {
    activeResources.add(resource)
    if (completed.get()) {
        activeResources.remove(resource)
        runCatching { cancelResource(resource) }
        return false
    }
    return true
}
