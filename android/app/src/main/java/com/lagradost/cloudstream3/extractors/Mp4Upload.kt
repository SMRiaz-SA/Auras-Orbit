package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.fetchMp4UploadPage
import com.lagradost.cloudstream3.utils.validateMp4UploadMediaUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Orbit-owned bounded implementation matching the behavior pinned at
 * CloudStream 3796406e636356f556c16e04e1da4e37d6a19038 (GPL-3.0).
 */
class Mp4Upload : ExtractorApi() {
    override fun getName(): String = EXTRACTOR_NAME
    override fun getMainUrl(): String = MAIN_URL
    override fun getRequiresReferer(): Boolean = true

    /** Keep the JVM continuation signature exposed by Orbit's CS3 compatibility base. */
    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>
        )
    }

    private suspend fun extract(originalUrl: String): List<ExtractorLink>? {
        return try {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val pageUrl = normalizePageUrl(originalUrl) ?: return@withTimeoutOrNull null
                val page = fetchMp4UploadPage(pageUrl) ?: return@withTimeoutOrNull null
                val unpacked = ExtractorApiKt.getAndUnpack(page)
                val source = findPlayerSource(unpacked)
                    ?: return@withTimeoutOrNull null
                val absoluteMediaUrl = resolveMediaUrl(pageUrl, source)
                    ?: return@withTimeoutOrNull null
                val mediaUrl = validateMp4UploadMediaUrl(absoluteMediaUrl)
                    ?: return@withTimeoutOrNull null

                listOf(
                    ExtractorLink(
                        EXTRACTOR_NAME,
                        EXTRACTOR_NAME,
                        mediaUrl,
                        originalUrl,
                        detectQuality(unpacked),
                        emptyMap(),
                        null,
                        null,
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

    private fun normalizePageUrl(value: String): String? {
        if (value.length !in 1..MAX_URL_CHARS) return null
        return try {
            val candidate = value.trim()
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if ((scheme != "http" && scheme != "https") || uri.rawUserInfo != null ||
                host.endsWith('.') || host.startsWith('.') || ".." in host ||
                !(host == ALLOWED_DOMAIN || host.endsWith(".$ALLOWED_DOMAIN")) ||
                uri.port == 0 || uri.port > MAX_PORT
            ) return null

            val id = PAGE_ID.matchEntire(uri.rawPath.orEmpty())?.groupValues?.getOrNull(1)
            if (id != null) "$MAIN_URL/embed-$id.html" else candidate
        } catch (_: Exception) {
            null
        }
    }

    private fun findPlayerSource(text: String): String? {
        for (call in PLAYER_SOURCE_CALL.findAll(text).take(MAX_PLAYER_CALLS)) {
            val openParen = call.range.last
            var argumentStart = openParen + 1
            while (argumentStart < text.length && text[argumentStart].isWhitespace()) argumentStart++

            if (argumentStart < text.length && text[argumentStart] in QUOTES) {
                readQuotedValue(text, argumentStart)?.let { return it }
                continue
            }

            val closeParen = findCallEnd(text, openParen) ?: continue
            val bodyStart = openParen + 1
            val bodyLength = closeParen - bodyStart
            if (bodyLength <= 0 || bodyLength > MAX_PLAYER_CALL_CHARS) continue
            val body = text.substring(bodyStart, closeParen)
            val sourceValue = OBJECT_SOURCE_VALUE.find(body)?.groupValues?.getOrNull(2)
            sourceValue?.let { normalizeJavaScriptUrl(it) }?.let { return it }
        }
        return null
    }

    private fun readQuotedValue(text: String, quoteStart: Int): String? {
        val quote = text[quoteStart]
        val endLimit = minOf(text.length, quoteStart + MAX_SOURCE_URL_CHARS + 2)
        val value = StringBuilder()
        var escaped = false
        for (index in quoteStart + 1 until endLimit) {
            val character = text[index]
            if (escaped) {
                value.append(if (character == '/') '/' else character)
                escaped = false
            } else when (character) {
                '\\' -> escaped = true
                quote -> return value.toString().takeIf { it.isNotBlank() }
                else -> value.append(character)
            }
            if (value.length > MAX_SOURCE_URL_CHARS) return null
        }
        return null
    }

    private fun findCallEnd(text: String, openParen: Int): Int? {
        val endLimit = minOf(text.length, openParen + MAX_PLAYER_CALL_CHARS + 1)
        var depth = 1
        var quote: Char? = null
        var escaped = false
        for (index in openParen + 1 until endLimit) {
            val character = text[index]
            val activeQuote = quote
            if (activeQuote != null) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == activeQuote) quote = null
                continue
            }
            when (character) {
                '\'', '"', '`' -> quote = character
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun normalizeJavaScriptUrl(value: String): String? =
        value.replace("\\/", "/").trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_SOURCE_URL_CHARS
        }

    private fun resolveMediaUrl(pageUrl: String, source: String): String? = try {
        URI(pageUrl).resolve(source.trim()).toString().takeIf {
            it.length in 1..MAX_URL_CHARS
        }
    } catch (_: Exception) {
        null
    }

    private fun detectQuality(text: String): Int {
        val height = HEIGHT_VALUE.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return height?.takeIf { it in 1..MAX_QUALITY_VALUE } ?: UNKNOWN_QUALITY
    }

    private companion object {
        const val EXTRACTOR_NAME = "Mp4Upload"
        const val MAIN_URL = "https://www.mp4upload.com"
        const val ALLOWED_DOMAIN = "mp4upload.com"
        const val MAX_URL_CHARS = 4_096
        const val MAX_PORT = 65_535
        const val MAX_PLAYER_CALLS = 64
        const val MAX_PLAYER_CALL_CHARS = 4_096
        const val MAX_SOURCE_URL_CHARS = 4_096
        const val MAX_QUALITY_VALUE = 10_000
        const val UNKNOWN_QUALITY = 400
        const val MAX_EXTRACTION_MILLIS = 30_000L
        val QUOTES = setOf('\'', '"')
        val PAGE_ID = Regex("""^/(?:embed-)?([A-Za-z0-9]{1,256})(?:\.html)?(?:/.*)?$""", RegexOption.IGNORE_CASE)
        val PLAYER_SOURCE_CALL = Regex("""\bplayer\s*\.\s*src\s*\(""", RegexOption.IGNORE_CASE)
        val OBJECT_SOURCE_VALUE = Regex("""\bsrc\s*:\s*(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
        val HEIGHT_VALUE = Regex("""\bheight\s*=\s*["']?([0-9]{1,5})(?:\b|["'])""", RegexOption.IGNORE_CASE)
    }
}
