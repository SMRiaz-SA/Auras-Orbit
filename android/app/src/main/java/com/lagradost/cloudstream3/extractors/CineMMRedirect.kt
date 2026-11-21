package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import io.ktor.http.Url
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val cineMMRedirectMirrors = arrayOf(
    "hgplaycdn.com",
    "habetar.com",
    "yuguaab.com",
    "guxhag.com",
    "auvexiug.com",
    "xenolyzb.com",
    "haxloppd.com",
    "cavanhabg.com",
    "dumbalag.com",
    "uasopt.com",
)

class Dhcplay : CineMMRedirect() {
    override fun getMainUrl(): String = "https://dhcplay.com"
}

class HglinkTo : CineMMRedirect() {
    override fun getMainUrl(): String = "https://hglink.to"
}

/** Bounded redirect-only aliases for the pinned CineMM mirror family. */
abstract class CineMMRedirect : ExtractorApi() {
    override fun getName(): String = "CineMMRedirect"
    override fun getRequiresReferer(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = {
            redirect(url, referer, subtitleCallback, callback)
        }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<Unit>
        )
    }

    private suspend fun redirect(
        inputUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val mirrorPath = safeEncodedPath(inputUrl, expectedHostForMainUrl(getMainUrl()))
            ?: return
        val mirror = cineMMRedirectMirrors.random()
        val mirrorUrl = "https://$mirror$mirrorPath"
        currentCoroutineContext().ensureActive()
        loadMirror(mirrorUrl, referer, subtitleCallback, callback)
    }

    private suspend fun loadMirror(
        mirrorUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        suspendCancellableCoroutine<Unit> { completion ->
            val loadContinuation = object : Continuation<Any?> {
                override val context = completion.context

                override fun resumeWith(result: Result<Any?>) {
                    result.fold(
                        onSuccess = { completion.resume(Unit) },
                        onFailure = {
                            when (it) {
                                is CancellationException -> completion.resumeWithException(it)
                                is Exception -> completion.resume(Unit)
                                else -> completion.resumeWithException(it)
                            }
                        },
                    )
                }
            }

            try {
                val result = ExtractorApiKt.loadExtractor(
                    mirrorUrl,
                    referer,
                    subtitleCallback,
                    callback,
                    loadContinuation,
                )
                if (result !== COROUTINE_SUSPENDED) completion.resume(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                completion.resume(Unit)
            }
        }
    }

    private fun safeEncodedPath(input: String, expectedHost: String?): String? {
        if (expectedHost == null || input.length !in 1..MAX_INPUT_CHARS ||
            input != input.trim() || input.any { it.isISOControl() } || '\\' in input
        ) return null

        return try {
            val uri = URI(input)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val authority = uri.rawAuthority ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.rawUserInfo != null || host != expectedHost ||
                !(authority.equals(expectedHost, ignoreCase = true) ||
                    authority.equals("$expectedHost:443", ignoreCase = true))
            ) return null

            val path = Url(input).encodedPath
            if (path.length !in 1..MAX_PATH_CHARS || !path.startsWith('/') ||
                path.startsWith("//") || '\\' in path ||
                path.any { it.isISOControl() } || containsUnsafePathEscape(path)
            ) return null
            path
        } catch (_: Exception) {
            null
        }
    }

    private fun expectedHostForMainUrl(mainUrl: String): String? = when (mainUrl) {
        DHCPLAY_MAIN_URL -> DHCPLAY_HOST
        HGLINK_MAIN_URL -> HGLINK_HOST
        else -> null
    }

    private fun containsUnsafePathEscape(path: String): Boolean {
        var index = 0
        while (index < path.length) {
            if (path[index] != '%') {
                index++
                continue
            }
            if (index + 2 >= path.length) return true
            val high = path[index + 1].digitToIntOrNull(16) ?: return true
            val low = path[index + 2].digitToIntOrNull(16) ?: return true
            val byte = (high shl 4) or low
            if (byte == BACKSLASH_BYTE || byte < 0x20 || byte == DELETE_BYTE) return true
            if (byte == UTF8_C2_BYTE && index + 5 < path.length && path[index + 3] == '%') {
                val nextHigh = path[index + 4].digitToIntOrNull(16)
                val nextLow = path[index + 5].digitToIntOrNull(16)
                if (nextHigh != null && nextLow != null) {
                    val nextByte = (nextHigh shl 4) or nextLow
                    if (nextByte in UTF8_C1_CONTROL_START..UTF8_C1_CONTROL_END) return true
                }
            }
            index += 3
        }
        return false
    }

    private companion object {
        const val MAX_INPUT_CHARS = 4_096
        const val MAX_PATH_CHARS = 4_096
        const val BACKSLASH_BYTE = 0x5c
        const val DELETE_BYTE = 0x7f
        const val UTF8_C2_BYTE = 0xc2
        const val UTF8_C1_CONTROL_START = 0x80
        const val UTF8_C1_CONTROL_END = 0x9f
        const val DHCPLAY_HOST = "dhcplay.com"
        const val HGLINK_HOST = "hglink.to"
        const val DHCPLAY_MAIN_URL = "https://dhcplay.com"
        const val HGLINK_MAIN_URL = "https://hglink.to"
    }
}
