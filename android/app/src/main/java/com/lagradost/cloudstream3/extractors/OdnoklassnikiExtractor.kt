package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.fetchOdnoklassnikiEmbedPage
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Orbit-owned bounded implementation of the pinned CloudStream Odnoklassniki contract.
 * The page is fetched only through the exact-host, DNS-pinned resolver; no JavaScript is run.
 */
open class Odnoklassniki : ExtractorApi() {
    private val currentName = "Odnoklassniki"
    private val currentMainUrl = "https://odnoklassniki.ru"

    override fun getName(): String = currentName
    override fun getMainUrl(): String = currentMainUrl
    override fun getRequiresReferer(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>
        )
    }

    /** Keep the pinned callback signature while routing through Orbit's callback bridge. */
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? = super.getUrl(url, referer, subtitleCallback, callback, continuation)

    private suspend fun extract(originalUrl: String): List<ExtractorLink>? {
        return try {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val extractorName = getName()
                val pageOrigin = getMainUrl()
                val pageUrl = toEmbedPageUrl(originalUrl) ?: return@withTimeoutOrNull null
                val page = fetchOdnoklassnikiEmbedPage(pageUrl, pageOrigin)
                    ?: return@withTimeoutOrNull null
                val videos = parseVideos(page) ?: return@withTimeoutOrNull null
                val linkHeaders = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Origin" to pageOrigin,
                    "User-Agent" to MainAPIKt.USER_AGENT,
                )

                val links = ArrayList<ExtractorLink>(minOf(videos.size, MAX_VARIANTS))
                for (video in videos.take(MAX_VARIANTS)) {
                    currentCoroutineContext().ensureActive()
                    val mediaUrl = normalizeMediaCandidate(video.url)
                        ?.let { validatePublicExtractorMediaUrl(it) }
                        ?: continue
                    val quality = ExtractorApiKt.getQualityFromName(qualityLabel(video.name))
                    links += ExtractorLink(
                        extractorName,
                        extractorName,
                        mediaUrl,
                        pageOrigin.trimEnd('/') + "/",
                        quality,
                        linkHeaders,
                        null,
                        null,
                        emptyList(),
                    )
                }
                links.takeIf { it.isNotEmpty() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun toEmbedPageUrl(value: String): String? {
        if (value.length !in 1..MAX_URL_CHARS) return null
        return try {
            val candidate = value.trim()
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val allowedHost = host == "ok.ru" || host == "www.ok.ru" ||
                host == "odnoklassniki.ru" || host == "www.odnoklassniki.ru" ||
                host == "m.ok.ru" || host == "m.odnoklassniki.ru"
            if ((scheme != "http" && scheme != "https") || uri.rawUserInfo != null ||
                !allowedHost || uri.port == 0 || uri.port > 65_535
            ) return null

            val path = uri.rawPath.orEmpty()
            if (!path.contains(VIDEO_PATH_SEGMENT)) return null
            val embedPath = path.replace(VIDEO_PATH_SEGMENT, VIDEO_EMBED_PATH_SEGMENT)
            val authorityHost = if (':' in host) "[$host]" else host
            val port = if (uri.port < 0) "" else ":${uri.port}"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            "$scheme://$authorityHost$port$embedPath$query$fragment"
                .takeIf { it.length in 1..MAX_URL_CHARS }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVideos(page: String): List<Video>? {
        if (page.length > MAX_PAGE_CHARS) return null
        val normalized = page
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(UNICODE_ESCAPE) { match ->
                match.groupValues[1].toIntOrNull(16)?.toChar()?.toString().orEmpty()
            }
        val arrayJson = VIDEO_ARRAY.find(normalized)?.groupValues?.getOrNull(1) ?: return null
        return try {
            JsonFactory().createParser(arrayJson).use { parser ->
                if (parser.nextToken() != JsonToken.START_ARRAY) return null
                val videos = ArrayList<Video>()
                var itemCount = 0
                while (true) {
                    val itemToken = parser.nextToken() ?: return null
                    if (itemToken == JsonToken.END_ARRAY) break
                    if (++itemCount > MAX_PAGE_VARIANTS) return null
                    if (itemToken != JsonToken.START_OBJECT) {
                        parser.skipChildren()
                        continue
                    }

                    var name: String? = null
                    var url: String? = null
                    var fieldCount = 0
                    while (true) {
                        val fieldToken = parser.nextToken() ?: return null
                        if (fieldToken == JsonToken.END_OBJECT) break
                        if (fieldToken != JsonToken.FIELD_NAME ||
                            ++fieldCount > MAX_FIELDS_PER_VARIANT
                        ) return null
                        val field = parser.currentName
                        val valueToken = parser.nextToken() ?: return null
                        val maxLength = if (field == "name") MAX_VARIANT_NAME_CHARS else MAX_URL_CHARS
                        if ((field == "name" || field == "url") &&
                            valueToken == JsonToken.VALUE_STRING && parser.textLength <= maxLength
                        ) {
                            if (field == "name") name = parser.text.trim()
                            else url = parser.text.trim()
                        } else {
                            parser.skipChildren()
                        }
                    }
                    val safeName = name?.takeIf { it.isNotEmpty() }
                    val safeUrl = url?.takeIf { it.isNotEmpty() }
                    if (safeName != null && safeUrl != null) videos += Video(safeName, safeUrl)
                }
                videos
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeMediaCandidate(value: String): String? {
        if (value.length !in 1..MAX_URL_CHARS) return null
        val candidate = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            else -> return null
        }
        val parsed = candidate.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty()
        ) return null
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }
        if (uri.rawUserInfo != null || uri.port == 0 || uri.port > 65_535) return null
        return candidate
    }

    private fun qualityLabel(value: String): String = value.uppercase(Locale.ROOT)
        .replace("MOBILE", "144p")
        .replace("LOWEST", "240p")
        .replace("LOW", "360p")
        .replace("SD", "480p")
        .replace("HD", "720p")
        .replace("FULL", "1080p")
        .replace("QUAD", "1440p")
        .replace("ULTRA", "4k")

    @Serializable
    data class OkRuVideo(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    private data class Video(val name: String, val url: String)

    private companion object {
        const val MAX_EXTRACTION_MILLIS = 30_000L
        const val MAX_URL_CHARS = 4_096
        const val MAX_PAGE_CHARS = 512 * 1024
        const val MAX_PAGE_VARIANTS = 64
        const val MAX_VARIANTS = 24
        const val MAX_FIELDS_PER_VARIANT = 32
        const val MAX_VARIANT_NAME_CHARS = 128
        const val VIDEO_PATH_SEGMENT = "/video/"
        const val VIDEO_EMBED_PATH_SEGMENT = "/videoembed/"
        val VIDEO_ARRAY = Regex(""""videos"\s*:\s*(\[[^]]*])""")
        val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
    }
}
