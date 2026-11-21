package com.lagradost.cloudstream3.network

import android.webkit.CookieManager
import com.lagradost.cloudstream3.MainActivityKt
import com.lagradost.nicehttp.Requests.Companion.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Narrow Java bridge for the suspend resolver and HTTP retry APIs. */
internal object CloudflareKillerWebViewBridge {
    @JvmStatic
    fun resolve(url: String, host: String): Map<String, String>? {
        val solvedCookies = AtomicReference<Map<String, String>?>(null)
        try {
            runBlocking {
                WebViewResolver(
                    // Never finish based on a URL match; wait for a host-scoped clearance cookie.
                    interceptUrl = Regex(".^"),
                    additionalUrls = listOf(Regex(".")),
                    userAgent = null,
                    useOkhttp = false
                ).resolveUsingWebView(url) { observedRequest ->
                    if (!observedRequest.url.host.equals(host, ignoreCase = true)) {
                        false
                    } else {
                        val cookieHeader = try {
                            CookieManager.getInstance().getCookie(url)
                        } catch (_: Exception) {
                            null
                        }
                        val cookies = cookieHeader?.let(CloudflareKiller::parseCookieMap)
                            ?: emptyMap()
                        if (cookies["cf_clearance"].isNullOrBlank()) {
                            false
                        } else {
                            solvedCookies.set(cookies)
                            true
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            return null
        } catch (_: Exception) {
            return null
        }
        return solvedCookies.get()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun proceed(request: Request): Response = runBlocking {
        MainActivityKt.getApp().baseClient.newCall(request).await()
    }
}
