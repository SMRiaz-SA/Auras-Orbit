package com.lagradost.cloudstream3.extractors

import android.util.Base64
import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.fetchVoePage
import com.lagradost.cloudstream3.utils.isStrictHttpsUrl
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.lagradost.cloudstream3.utils.withVoePlaylistCookiesDisabled
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned bounded implementation of the pinned CloudStream Voe extractor. */
open class Voe : ExtractorApi() {
    override fun getName(): String = VOE_NAME
    override fun getMainUrl(): String = VOE_MAIN_URL
    override fun getRequiresReferer(): Boolean = true

    /** Preserve the pinned callback-form suspend descriptor used by CS3 callers. */
    @Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = { extract(url, referer, callback) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<Unit>
        )
    }

    private suspend fun extract(
        inputUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val extractorName = getName()
                val extractorMainUrl = getMainUrl()
                if (!isVoePageUrl(inputUrl, extractorMainUrl)) return@withTimeoutOrNull

                val html = fetchVoePage(inputUrl, referer, extractorMainUrl)
                    ?: return@withTimeoutOrNull
                currentCoroutineContext().ensureActive()
                val scriptData = Ksoup.parse(html)
                    .selectFirst("script[type=application/json]")
                    ?.data()
                    ?.trim()
                    ?: return@withTimeoutOrNull
                val encoded = unwrapEncodedPayload(scriptData) ?: return@withTimeoutOrNull
                val payload = decryptF7(encoded) ?: return@withTimeoutOrNull

                val rawHls = payload.optString("source", "")
                if (rawHls.isNotEmpty() && rawHls.length <= MAX_OUTPUT_URL_CHARS) {
                    val hlsUrl = validatePublicExtractorMediaUrl(rawHls, requireHttps = true)
                    if (hlsUrl != null) {
                        currentCoroutineContext().ensureActive()
                        val links = withVoePlaylistCookiesDisabled {
                            M3u8Helper.generateM3u8Https(
                                extractorName,
                                hlsUrl,
                                "$extractorMainUrl/",
                                UNKNOWN_QUALITY,
                                mapOf("Origin" to "$extractorMainUrl/"),
                            )
                        }
                        for (link in links.take(MAX_HLS_LINKS)) {
                            currentCoroutineContext().ensureActive()
                            // The shared bounded HLS helper validates the master, redirects,
                            // variants and playlist URI targets before returning any link.
                            callback(link)
                        }
                    }
                }

                val rawMp4 = payload.optString("direct_access_url", "")
                if (rawMp4.isNotEmpty() && rawMp4.length <= MAX_OUTPUT_URL_CHARS) {
                    val mp4Url = validatePublicExtractorMediaUrl(rawMp4, requireHttps = true)
                    if (mp4Url != null) {
                        currentCoroutineContext().ensureActive()
                        callback(
                            ExtractorLink(
                                "$extractorName MP4",
                                "$extractorName MP4",
                                mp4Url,
                                inputUrl,
                                UNKNOWN_QUALITY,
                                emptyMap(),
                                null,
                                null,
                                emptyList(),
                            )
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Malformed or unavailable pages and payloads fail closed without logging secrets.
        }
    }

    private fun isVoePageUrl(value: String, expectedMainUrl: String): Boolean {
        if (value.length !in 1..MAX_PAGE_URL_CHARS || value != value.trim() ||
            value.any { it.isISOControl() }
        ) return false
        return try {
            val uri = URI(value)
            val host = uri.host?.lowercase() ?: return false
            isStrictHttpsUrl(value) && uri.rawUserInfo == null &&
                host == getVoeHost(expectedMainUrl) && !host.endsWith('.')
        } catch (_: Exception) {
            false
        }
    }

    private fun unwrapEncodedPayload(value: String): String? {
        if (value.length !in 1..MAX_SCRIPT_CHARS) return null
        val trimmed = value.trim()
        val encoded = trimmed.substringAfter("[\"", trimmed).substringBeforeLast("\"]", trimmed)
        return encoded.takeIf { it.isNotEmpty() && it.length <= MAX_ENCODED_CHARS }
    }

    private fun decryptF7(encoded: String): JSONObject? {
        if (encoded.length !in 1..MAX_ENCODED_CHARS) return null
        return try {
            val rotated = rot13(encoded)
            val markerCleaned = removeUnderscores(replacePatterns(rotated))
            if (markerCleaned.length > MAX_FIRST_BASE64_CHARS) return null
            val first = decodeBase64Latin1(markerCleaned, MAX_FIRST_DECODED_BYTES) ?: return null
            val shifted = charShift(first, 3)
            val reversed = shifted.reversed()
            if (reversed.length > MAX_SECOND_BASE64_CHARS) return null
            val decodedJson = decodeBase64Latin1(reversed, MAX_JSON_CHARS) ?: return null
            if (decodedJson.isEmpty() || decodedJson.length > MAX_JSON_CHARS) return null
            JSONObject(decodedJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Latin1(value: String, maxBytes: Int): String? {
        if (value.isEmpty() || value.length > MAX_ENCODED_CHARS) return null
        val decoded = try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty() || decoded.size > maxBytes) return null
        return buildString(decoded.size) {
            for (byte in decoded) append((byte.toInt() and 0xFF).toChar())
        }
    }

    private fun rot13(value: String): String = buildString(value.length) {
        for (character in value) {
            append(
                when (character) {
                    in 'A'..'Z' -> ((character - 'A' + 13) % 26 + 'A'.code).toChar()
                    in 'a'..'z' -> ((character - 'a' + 13) % 26 + 'a'.code).toChar()
                    else -> character
                }
            )
        }
    }

    private fun replacePatterns(value: String): String {
        var output = value
        for (pattern in F7_MARKERS) output = output.replace(pattern, "_")
        return output
    }

    private fun removeUnderscores(value: String): String = buildString(value.length) {
        for (character in value) if (character != '_') append(character)
    }

    private fun charShift(value: String, shift: Int): String = buildString(value.length) {
        for (character in value) append((character.code - shift).toChar())
    }

    private companion object {
        const val VOE_NAME = "Voe"
        const val VOE_MAIN_URL = "https://voe.sx"
        const val MAX_PAGE_URL_CHARS = 4_096
        const val MAX_SCRIPT_CHARS = 160 * 1024
        const val MAX_ENCODED_CHARS = 128 * 1024
        const val MAX_FIRST_BASE64_CHARS = 128 * 1024
        const val MAX_FIRST_DECODED_BYTES = 96 * 1024
        const val MAX_SECOND_BASE64_CHARS = 96 * 1024
        const val MAX_JSON_CHARS = 64 * 1024
        const val MAX_OUTPUT_URL_CHARS = 4_096
        const val MAX_HLS_LINKS = 64
        const val MAX_EXTRACTION_MILLIS = 30_000L
        const val UNKNOWN_QUALITY = 400
        val F7_MARKERS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    }
}

/** Exact aliases retained by the pinned built-in registry, in its declared order. */
class Voe1 : Voe() {
    override fun getMainUrl(): String = "https://donaldlineelse.com"
}

class Voe2 : Voe() {
    override fun getMainUrl(): String = "https://charlestoughrace.com"
}

class Tubeless : Voe() {
    override fun getName(): String = "Tubeless"
    override fun getMainUrl(): String = "https://tubelessceliolymph.com"
}

class Simpulumlamerop : Voe() {
    override fun getName(): String = "Simplum"
    override fun getMainUrl(): String = "https://simpulumlamerop.com"
}

class Urochsunloath : Voe() {
    override fun getName(): String = "Uroch"
    override fun getMainUrl(): String = "https://urochsunloath.com"
}

class NathanFromSubject : Voe() {
    override fun getMainUrl(): String = "https://nathanfromsubject.com"
}

class Yipsu : Voe() {
    override fun getName(): String = "Yipsu"
    override fun getMainUrl(): String = "https://yip.su"
}

class MetaGnathTuggers : Voe() {
    override fun getName(): String = "Metagnath"
    override fun getMainUrl(): String = "https://metagnathtuggers.com"
}

private fun getVoeHost(mainUrl: String): String? = try {
    val uri = URI(mainUrl)
    val host = uri.host?.lowercase() ?: return null
    host.takeIf {
        uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && !host.endsWith('.')
    }
} catch (_: Exception) {
    null
}
