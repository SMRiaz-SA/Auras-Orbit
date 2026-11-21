package com.auras.orbit.extractors

import android.content.Context
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.lagradost.cloudstream3.utils.FilemoonWebViewFetchResult
import com.lagradost.cloudstream3.utils.FilemoonWebViewRequestBudget
import com.lagradost.cloudstream3.utils.FilemoonWebViewResource
import com.lagradost.cloudstream3.utils.isAllowedFilemoonWebViewUrl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val FILEMOON_WEBVIEW_TIMEOUT_MILLIS = 15_000L
private const val MAX_FILEMOON_WEBVIEW_HTML_CHARS = 512 * 1024

private val FILEMOON_CAPTURE_SESSION_LOCK = Mutex()
private val FILEMOON_CAPTURE_DISABLED = AtomicBoolean(false)
private val FILEMOON_HLS_URL = Regex("(m3u8|master\\.txt)", RegexOption.IGNORE_CASE)

private const val FILEMOON_WEBVIEW_CSP =
    "default-src 'none'; " +
        "script-src https: 'unsafe-inline' 'unsafe-eval'; " +
        "style-src https: 'unsafe-inline'; " +
        "img-src https: data: blob:; font-src https: data:; " +
        "media-src https: blob:; frame-src https:; child-src 'none'; " +
        "connect-src https:; object-src 'none'; worker-src 'none'; " +
        "form-action https://filemoon.to https://filemoon.in https://filemoon.sx; " +
        "base-uri https://filemoon.to https://filemoon.in https://filemoon.sx"

private const val FILEMOON_NETWORK_API_GUARD =
    "<script>(function(){'use strict';var a=['WebSocket','WebSocketStream','EventSource','WebTransport','RTCPeerConnection','webkitRTCPeerConnection'];" +
        "for(var i=0;i<a.length;i++){try{Object.defineProperty(window,a[i],{value:void 0,writable:false,configurable:false});" +
        "if(window[a[i]]!==void 0)throw 0;}catch(e){try{window.stop();}catch(_){}}}})();</script>"

/**
 * Captures Filemoon's first HLS-looking URL from an already fetched, bounded page.
 * The WebView never performs a direct network load: HTTP(S) resources either come
 * from the shared public-address-pinned Filemoon budget or receive an empty block.
 */
internal suspend fun captureFilemoonHlsUrl(
    html: String,
    baseUrl: String,
    referer: String,
): String? {
    if (html.isEmpty() || html.length > MAX_FILEMOON_WEBVIEW_HTML_CHARS ||
        !isAllowedFilemoonWebViewUrl(baseUrl) || !isAllowedFilemoonWebViewUrl(referer) ||
        FILEMOON_CAPTURE_DISABLED.get()
    ) return null

    val context = try {
        OrbitCs3HostServices.get().getApplicationContext()
    } catch (_: Exception) {
        null
    } ?: return null

    return try {
        kotlinx.coroutines.withTimeoutOrNull(FILEMOON_WEBVIEW_TIMEOUT_MILLIS) {
            FILEMOON_CAPTURE_SESSION_LOCK.withLock {
                if (FILEMOON_CAPTURE_DISABLED.get()) return@withLock null
                val session = FilemoonCaptureSession(context, html, baseUrl, referer)
                try {
                    session.awaitResult()
                } finally {
                    val safeCleanup = withContext(NonCancellable) {
                        session.destroyAndAwait()
                    }
                    if (!safeCleanup) FILEMOON_CAPTURE_DISABLED.set(true)
                }
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private class FilemoonCaptureSession(
    private val context: Context,
    private val html: String,
    private val baseUrl: String,
    referer: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val budget = FilemoonWebViewRequestBudget(referer)
    private val stopped = AtomicBoolean(false)
    private val candidateSeen = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    @Volatile private var webView: WebView? = null
    @Volatile private var resultContinuation: CancellableContinuation<String?>? = null
    private var serviceWorkerSettingsSnapshot: ServiceWorkerSettingsSnapshot? = null
    private var cleanupSucceeded = true

    suspend fun awaitResult(): String? = suspendCancellableCoroutine { continuation ->
        resultContinuation = continuation
        continuation.invokeOnCancellation { requestDestroy() }
        if (!mainHandler.post {
                if (stopped.get()) {
                    destroyOnMain()
                } else {
                    startOnMain()
                }
            }
        ) {
            stopped.set(true)
            budget.close()
            continuation.resume(null)
        }
    }

    suspend fun destroyAndAwait(): Boolean {
        stopped.set(true)
        budget.close()
        return suspendCancellableCoroutine { continuation ->
            val cleanup = Runnable {
                continuation.resume(destroyOnMain())
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
            else if (!mainHandler.post(cleanup)) continuation.resume(false)
        }
    }

    private fun startOnMain() {
        if (stopped.get() || destroyed.get()) {
            destroyOnMain()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val settings = ServiceWorkerController.getInstance().serviceWorkerWebSettings
                serviceWorkerSettingsSnapshot = ServiceWorkerSettingsSnapshot(
                    settings.blockNetworkLoads,
                    settings.allowFileAccess,
                    settings.allowContentAccess,
                )
                settings.apply {
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
            }

            val view = WebView(context)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                // API 16+ defaults these deprecated file-origin flags to false; Orbit's minSdk is 23.
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse = intercept(
                    request.url.toString(),
                    request.method,
                    request.isForMainFrame,
                )

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = overrideNavigation(request.url.toString())

                @Deprecated("Required for older Android WebView callback compatibility.")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    overrideNavigation(url)

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    finish(null)
                }
            }
            val protectedHtml = withSecurityPolicy(html)
            if (protectedHtml == null) {
                finish(null)
                return
            }
            view.loadDataWithBaseURL(
                baseUrl,
                protectedHtml,
                "text/html",
                "UTF-8",
                null,
            )
        } catch (_: Exception) {
            finish(null)
        }
    }

    private fun overrideNavigation(url: String): Boolean {
        if (FILEMOON_HLS_URL.containsMatchIn(url)) {
            captureCandidate(url)
            return true
        }
        return !isAllowedFilemoonWebViewUrl(url)
    }

    private fun intercept(url: String, method: String, isMainFrame: Boolean): WebResourceResponse {
        if (stopped.get() || url.length > 4_096) return blockedResponse()
        if (FILEMOON_HLS_URL.containsMatchIn(url)) {
            captureCandidate(url)
            return blockedResponse()
        }
        if (!method.equals("GET", ignoreCase = true) ||
            !isAllowedFilemoonWebViewUrl(url)
        ) return blockedResponse()

        val fetched = try {
            budget.fetchBlocking(url)
        } catch (_: Exception) {
            null
        } ?: return blockedResponse()
        val resource = when (fetched) {
            is FilemoonWebViewFetchResult.HlsRedirect -> {
                captureCandidate(fetched.url)
                return blockedResponse()
            }
            is FilemoonWebViewFetchResult.Resource -> fetched.value
        }

        if (isMainFrame && !resource.mimeType.contains("html", ignoreCase = true)) {
            finish(null)
            return blockedResponse()
        }
        val responseResource = if (resource.mimeType.contains("html", ignoreCase = true)) {
            val charset = try {
                Charset.forName(resource.charset ?: "UTF-8")
            } catch (_: Exception) {
                finish(null)
                return blockedResponse()
            }
            val document = try {
                withSecurityPolicy(String(resource.body, charset))
            } catch (_: Exception) {
                null
            } ?: run {
                finish(null)
                return blockedResponse()
            }
            resource.copy(body = document.toByteArray(charset), charset = charset.name())
        } else {
            resource
        }
        return try {
            responseResource.toWebResponse()
        } catch (_: Exception) {
            blockedResponse()
        }
    }

    private fun captureCandidate(url: String) {
        if (!candidateSeen.compareAndSet(false, true) || stopped.get()) return
        budget.validateCapturedUrlAsync(url) { validated ->
            finish(validated)
        }
    }

    private fun finish(result: String?) {
        if (!stopped.compareAndSet(false, true)) return
        budget.close()
        val resumeOnMain = Runnable {
            val safeToReturn = destroyOnMain()
            val continuation = resultContinuation
            if (continuation?.isActive == true) {
                continuation.resume(if (safeToReturn) result else null)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) resumeOnMain.run()
        else mainHandler.post(resumeOnMain)
    }

    private fun requestDestroy() {
        stopped.set(true)
        budget.close()
        if (Looper.myLooper() == Looper.getMainLooper()) destroyOnMain()
        else mainHandler.post { destroyOnMain() }
    }

    private fun destroyOnMain(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!destroyed.compareAndSet(false, true)) return cleanupSucceeded
        val view = webView
        webView = null
        var viewDestroyed = true
        if (view != null) {
            try {
                view.stopLoading()
            } catch (_: Exception) {
            }
            try {
                view.destroy()
            } catch (_: Exception) {
                viewDestroyed = false
            }
        }
        cleanupSucceeded = if (viewDestroyed) restoreServiceWorkerSettings()
        else {
            keepServiceWorkerNetworkBlocked()
            false
        }
        return cleanupSucceeded
    }

    private fun restoreServiceWorkerSettings(): Boolean {
        val previous = serviceWorkerSettingsSnapshot ?: return true
        return try {
            val settings = ServiceWorkerController.getInstance().serviceWorkerWebSettings
            settings.allowFileAccess = previous.allowFileAccess
            settings.allowContentAccess = previous.allowContentAccess
            settings.blockNetworkLoads = previous.blockNetworkLoads
            serviceWorkerSettingsSnapshot = null
            true
        } catch (_: Exception) {
            keepServiceWorkerNetworkBlocked()
            false
        }
    }

    private fun keepServiceWorkerNetworkBlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
            }
        } catch (_: Exception) {
        }
    }

    private fun withSecurityPolicy(document: String): String? {
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"$FILEMOON_WEBVIEW_CSP\">$FILEMOON_NETWORK_API_GUARD"
        val headOpen = Regex("(?i)<head(?:\\s[^>]*)?>").find(document)
        if (headOpen != null) {
            val beforeHead = document.substring(0, headOpen.range.first)
            if ("<!--" in beforeHead ||
                Regex("(?is)<(?:script|svg|iframe|object|embed)\\b|\\son[a-z]+\\s*=")
                    .containsMatchIn(beforeHead)
            ) return null
            val insertAt = headOpen.range.last + 1
            return document.substring(0, insertAt) + meta + document.substring(insertAt)
        }
        val htmlOpen = Regex("(?i)<html(?:\\s[^>]*)?>").find(document)
        if (htmlOpen != null) {
            val beforeHtml = document.substring(0, htmlOpen.range.first)
            if ("<!--" in beforeHtml ||
                Regex("(?is)<(?:script|svg|iframe|object|embed)\\b|\\son[a-z]+\\s*=")
                    .containsMatchIn(beforeHtml)
            ) return null
            val insertAt = htmlOpen.range.last + 1
            return document.substring(0, insertAt) + "<head>$meta</head>" + document.substring(insertAt)
        }
        return "<!doctype html><html><head>$meta</head><body>$document</body></html>"
    }
}

private data class ServiceWorkerSettingsSnapshot(
    val blockNetworkLoads: Boolean,
    val allowFileAccess: Boolean,
    val allowContentAccess: Boolean,
)

private fun FilemoonWebViewResource.toWebResponse(): WebResourceResponse {
    val headers = if (mimeType.contains("html", ignoreCase = true)) {
        mapOf(
            "Content-Security-Policy" to FILEMOON_WEBVIEW_CSP,
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
        )
    } else {
        mapOf("Cache-Control" to "no-store")
    }
    return WebResourceResponse(
        mimeType,
        charset,
        200,
        "OK",
        headers,
        ByteArrayInputStream(body),
    )
}

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    ByteArrayInputStream(ByteArray(0)),
)
