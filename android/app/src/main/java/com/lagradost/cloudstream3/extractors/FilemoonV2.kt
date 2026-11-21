package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fleeksoft.ksoup.Ksoup
import com.auras.orbit.extractors.captureFilemoonHlsUrl
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.fetchFilemoonPage
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Orbit-owned bounded Filemoon implementation based on the pinned CloudStream
 * Filemoon.kt behavior. P.A.C.K.E.R. payloads are decoded as data; JavaScript is
 * never evaluated. Page and iframe requests use Orbit's host-bound resolver.
 */
open class FilemoonV2 : ExtractorApi() {
    private var currentName = EXTRACTOR_NAME
    private var currentMainUrl = MAIN_URL

    override fun getName(): String = currentName

    open fun setName(value: String) {
        currentName = value
    }

    override fun getMainUrl(): String = currentMainUrl

    open fun setMainUrl(value: String) {
        currentMainUrl = value
    }

    override fun getRequiresReferer(): Boolean = true

    /** Source-shaped suspend API retained by the Orbit ExtractorApi base. */
    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url).links }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>
        )
    }

    /** Preserve the sampled callback entry point and deliver subtitle tracks as well as links. */
    @Suppress("UNCHECKED_CAST")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> ExtractionResult = { extract(url) }
        val completion = object : Continuation<ExtractionResult> {
            override val context = continuation.context

            override fun resumeWith(result: Result<ExtractionResult>) {
                result.fold(
                    onSuccess = { extracted ->
                        try {
                            emit(extracted, subtitleCallback, callback)
                            (continuation as Continuation<Any?>).resumeWith(Result.success(Unit))
                        } catch (error: Throwable) {
                            (continuation as Continuation<Any?>).resumeWith(Result.failure(error))
                        }
                    },
                    onFailure = { error ->
                        (continuation as Continuation<Any?>).resumeWith(Result.failure(error))
                    },
                )
            }
        }

        val immediate = extraction.startCoroutineUninterceptedOrReturn(completion)
        if (immediate === COROUTINE_SUSPENDED) return immediate
        emit(immediate as ExtractionResult, subtitleCallback, callback)
        return Unit
    }

    private suspend fun extract(inputUrl: String): ExtractionResult {
        return try {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val pageUrl = normalizePageUrl(inputUrl) ?: return@withTimeoutOrNull EMPTY_RESULT
                val pageHtml = fetchFilemoonPage(pageUrl, pageUrl)
                    ?: return@withTimeoutOrNull EMPTY_RESULT

                val iframeSrc = Ksoup.parse(pageHtml)
                    .selectFirst("iframe")
                    ?.attr("src")
                    ?.trim()
                var webViewFallbackUrl: String? = null
                val playerHtml = if (iframeSrc.isNullOrEmpty()) {
                    pageHtml
                } else {
                    val iframeUrl = resolveReference(pageUrl, iframeSrc)
                    val safeIframeUrl = iframeUrl?.let(::normalizePageUrl)
                        ?: return@withTimeoutOrNull EMPTY_RESULT
                    webViewFallbackUrl = safeIframeUrl
                    fetchFilemoonPage(safeIframeUrl, pageUrl, includeAcceptLanguage = true)
                        ?: return@withTimeoutOrNull EMPTY_RESULT
                }

                currentCoroutineContext().ensureActive()
                val script = Ksoup.parse(playerHtml).select("script")
                    .firstOrNull { it.data().contains(PACKER_SIGNATURE) }
                    ?.data()
                    .orEmpty()
                val unpacked = JsUnpacker(script).unpack().orEmpty()
                val extractorName = getName()
                val entries = parseEntries(unpacked, "sources")
                val streamCandidates = entries.mapNotNull { entry ->
                    entry.file?.let { raw ->
                        normalizePlayerUrl(raw)?.let { resolved ->
                            StreamCandidate(resolved, isHlsUrl(resolved))
                        }
                    }
                }.distinctBy { it.url }.take(MAX_STREAM_CANDIDATES)

                val fallbackCandidates = if (streamCandidates.isEmpty()) {
                    HLS_URL.findAll(unpacked).take(MAX_STREAM_CANDIDATES).mapNotNull { match ->
                        match.groupValues.getOrNull(1)
                            ?.let(::normalizePlayerUrl)
                            ?.let { StreamCandidate(it, true) }
                    }.distinctBy { it.url }.toList()
                } else {
                    emptyList()
                }

                val headers = mapOf(
                    "Referer" to pageUrl,
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "User-Agent" to USER_AGENT,
                )
                val links = ArrayList<ExtractorLink>()
                for (candidate in (streamCandidates.ifEmpty { fallbackCandidates })) {
                    currentCoroutineContext().ensureActive()
                    val mediaUrl = validatePublicExtractorMediaUrl(candidate.url) ?: continue
                    if (candidate.isHls) {
                        links += M3u8Helper.Companion.generateM3u8(
                            extractorName,
                            mediaUrl,
                            pageUrl,
                            UNKNOWN_QUALITY,
                            headers,
                        )
                    } else {
                        links += ExtractorLink(
                            extractorName,
                            extractorName,
                            mediaUrl,
                            pageUrl,
                            UNKNOWN_QUALITY,
                            headers,
                            null,
                            null,
                            emptyList(),
                        )
                    }
                }

                if (links.isEmpty() && webViewFallbackUrl != null) {
                    currentCoroutineContext().ensureActive()
                    val capturedUrl = captureFilemoonHlsUrl(
                        playerHtml,
                        webViewFallbackUrl,
                        pageUrl,
                    )
                    if (capturedUrl != null) {
                        currentCoroutineContext().ensureActive()
                        links += M3u8Helper.Companion.generateM3u8Https(
                            extractorName,
                            capturedUrl,
                            pageUrl,
                            UNKNOWN_QUALITY,
                            headers,
                        )
                    }
                }

                val subtitles = ArrayList<SubtitleFile>()
                var subtitleCandidates = 0
                for (entry in parseEntries(unpacked, "tracks")) {
                    val kind = entry.kind.orEmpty().lowercase(Locale.ROOT)
                    if (!(kind.contains("caption") || kind.contains("subtitle")) ||
                        entry.label.isNullOrBlank()
                    ) continue
                    if (subtitleCandidates >= MAX_SUBTITLES) break
                    subtitleCandidates++

                    currentCoroutineContext().ensureActive()
                    val raw = entry.file ?: continue
                    val subtitleUrl = normalizePlayerUrl(raw)
                        ?.let { validatePublicExtractorMediaUrl(it) }
                        ?: continue
                    subtitles += SubtitleFile(entry.label.trim(), subtitleUrl)
                }

                ExtractionResult(links, subtitles)
            } ?: EMPTY_RESULT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EMPTY_RESULT
        }
    }

    private fun emit(
        result: ExtractionResult,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        result.links.forEach(callback)
        result.subtitles.forEach(subtitleCallback)
    }

    private fun normalizePageUrl(value: String): String? {
        if (value.length !in 1..MAX_URL_CHARS) return null
        return try {
            val candidate = value.trim()
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "https" || uri.rawUserInfo != null ||
                uri.rawAuthority == null || uri.rawAuthority.endsWith(':') ||
                (uri.port != -1 && uri.port != 443) ||
                host.endsWith('.') || host !in ALLOWED_PAGE_HOSTS ||
                uri.port == 0 || uri.port > MAX_PORT
            ) return null
            candidate
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizePlayerUrl(value: String): String? {
        val candidate = value.replace("\\/", "/").trim()
        if (candidate.isEmpty() || candidate.length > MAX_URL_CHARS) return null
        return resolveReference(getMainUrl(), candidate)
            ?.takeIf { it.length in 1..MAX_URL_CHARS }
    }

    private fun parseEntries(script: String, property: String): List<PlayerEntry> {
        val entries = ArrayList<PlayerEntry>()
        for (array in findJsonArrays(script, property).take(MAX_PLAYER_ARRAYS)) {
            if (entries.size >= MAX_PLAYER_ENTRIES) break
            try {
                JsonFactory().createParser(array).use { parser ->
                    if (parser.nextToken() != JsonToken.START_ARRAY) return@use
                    var itemCount = 0
                    while (true) {
                        val token = parser.nextToken() ?: break
                        if (token == JsonToken.END_ARRAY || itemCount >= MAX_PLAYER_ENTRIES) break
                        itemCount++
                        if (token != JsonToken.START_OBJECT) {
                            parser.skipChildren()
                            continue
                        }

                        var file: String? = null
                        var label: String? = null
                        var kind: String? = null
                        var fieldCount = 0
                        while (true) {
                            val fieldToken = parser.nextToken() ?: break
                            if (fieldToken == JsonToken.END_OBJECT) break
                            if (fieldToken != JsonToken.FIELD_NAME ||
                                ++fieldCount > MAX_FIELDS_PER_ENTRY
                            ) break
                            val field = parser.currentName
                            val valueToken = parser.nextToken() ?: break
                            if (valueToken == JsonToken.VALUE_STRING && parser.textLength <= MAX_URL_CHARS) {
                                val value = parser.text
                                when (field) {
                                    "file" -> file = value
                                    "label" -> label = value.take(MAX_LABEL_CHARS)
                                    "kind" -> kind = value.take(MAX_LABEL_CHARS)
                                }
                            } else {
                                parser.skipChildren()
                            }
                        }
                        if (!file.isNullOrBlank()) entries += PlayerEntry(file, label, kind)
                    }
                }
            } catch (_: Exception) {
                // Ignore a malformed player array and continue with bounded later arrays.
            }
        }
        return entries
    }

    private fun findJsonArrays(script: String, property: String): List<String> {
        if (script.length > MAX_PAGE_CHARS) return emptyList()
        val marker = Regex("""["']?${Regex.escape(property)}["']?\s*:\s*\[""")
        val arrays = ArrayList<String>()
        for (match in marker.findAll(script).take(MAX_PLAYER_ARRAYS)) {
            val start = match.range.last
            val end = findArrayEnd(script, start) ?: continue
            arrays += script.substring(start, end + 1)
        }
        return arrays
    }

    private fun findArrayEnd(text: String, start: Int): Int? {
        val limit = minOf(text.length, start + MAX_JSON_ARRAY_CHARS)
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until limit) {
            val character = text[index]
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
                continue
            }
            when (character) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun resolveReference(base: String, candidate: String): String? = try {
        URI(base).resolve(candidate.trim()).toString()
    } catch (_: Exception) {
        null
    }

    private fun isHlsUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains("master.txt", ignoreCase = true)

    private data class PlayerEntry(val file: String?, val label: String?, val kind: String?)
    private data class StreamCandidate(val url: String, val isHls: Boolean)
    private data class ExtractionResult(
        val links: List<ExtractorLink>,
        val subtitles: List<SubtitleFile> = emptyList(),
    )

    private companion object {
        const val EXTRACTOR_NAME = "Filemoon"
        const val MAIN_URL = "https://filemoon.to"
        const val MAX_EXTRACTION_MILLIS = 30_000L
        const val MAX_URL_CHARS = 4_096
        const val MAX_PORT = 65_535
        const val MAX_PAGE_CHARS = 512 * 1024
        const val MAX_JSON_ARRAY_CHARS = 128 * 1024
        const val MAX_PLAYER_ARRAYS = 32
        const val MAX_PLAYER_ENTRIES = 64
        const val MAX_FIELDS_PER_ENTRY = 32
        const val MAX_LABEL_CHARS = 128
        const val MAX_STREAM_CANDIDATES = 24
        const val MAX_SUBTITLES = 16
        const val UNKNOWN_QUALITY = 400
        const val PACKER_SIGNATURE = "function(p,a,c,k,e,d)"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
        val ALLOWED_PAGE_HOSTS = setOf("filemoon.to", "filemoon.in", "filemoon.sx")
        val HLS_URL = Regex("""[:=]\s*["']([^"'\s]+(?:\.m3u8|master\.txt)[^"'\s]*)["']""", RegexOption.IGNORE_CASE)
        val EMPTY_RESULT = ExtractionResult(emptyList())
    }
}
