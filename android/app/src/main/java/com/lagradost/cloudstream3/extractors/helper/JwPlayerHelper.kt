package com.lagradost.cloudstream3.extractors.helper

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Orbit implementation adapted to the pinned CloudStream JW-player behavior at commit
 * 5024ace2922a148d38df2a816b483b529789cf58. This Orbit implementation adapts the pinned API and
 * behavior, with Jackson parsing, bounded DNS-pinned HLS requests, and origin-aware header rules.
 *
 * It handles the source and track arrays plus the pinned HLS URL fallback used by the
 * sampled Filesim, Jeniusplay, StreamWish, and Vidmoly extractors. JSON decoding uses
 * Orbit's shared Jackson mapper; playlist expansion remains delegated to the bounded
 * [M3u8Helper] transport.
 */
object JwPlayerHelper {
    private val sourceRegex = Regex(""""?sources"?:\s*(\[.*?\])""")
    private val tracksRegex = Regex(""""?tracks"?:\s*(\[.*?\])""")
    private val m3u8Regex = Regex("""[:=]\s*\"([^\"\s]+(\.m3u8|master\.txt)[^\"\s]*)""")

    /**
     * Extract links and subtitle tracks from a JW-player setup script.
     * Callback ordering, source fallback, and the Boolean result match the pinned helper.
     */
    suspend fun extractStreamLinks(
        script: String,
        sourceName: String,
        mainUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val sourceMatches = sourceRegex.findAll(script).flatMap { sourceMatch ->
            parseJson<List<Source>>(sourceMatch.groupValues[1].addMarks("file", "label", "type"))
                .orEmpty().asSequence()
        }.toList()

        var extractedLinks = sourceMatches.flatMap { link ->
            val cleanUrl = link.file.replace("\\/", "/")
            val absoluteUrl = fixUrl(cleanUrl, mainUrl)
            val safeHeaders = headersForTarget(headers, mainUrl, absoluteUrl)
            if (cleanUrl.contains(".m3u8") || cleanUrl.contains(".txt")) {
                try {
                    M3u8Helper.generateM3u8(
                        source = sourceName,
                        streamUrl = absoluteUrl,
                        referer = refererForTarget(mainUrl, absoluteUrl).orEmpty(),
                        headers = safeHeaders,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d("JW_PLAYER_HELPER", "Error generating M3U8 links: ${error.message}")
                    emptyList()
                }
            } else {
                listOf(createLink(sourceName, absoluteUrl, safeHeaders, null))
            }
        }

        // Some hosts assign the playlist to another variable before passing it to JW.
        if (extractedLinks.isEmpty()) {
            extractedLinks = m3u8Regex.findAll(script).map { match ->
                val cleanUrl = match.groupValues[1].replace("\\/", "/")
                val isM3u8 = cleanUrl.contains(".m3u8") || cleanUrl.contains(".txt")
                val absoluteUrl = fixUrl(cleanUrl, mainUrl)
                createLink(
                    sourceName,
                    absoluteUrl,
                    headersForTarget(headers, mainUrl, absoluteUrl),
                    if (isM3u8) ExtractorLinkType.M3U8 else null,
                )
            }.toList()
        }

        currentCoroutineContext().ensureActive()
        val tracksMatches = tracksRegex.findAll(script).flatMap { trackMatch ->
            parseJson<List<Track>>(trackMatch.groupValues[1].addMarks("file", "kind", "label"))
                .orEmpty().asSequence()
        }.toList()
        val subtitleFiles = tracksMatches.filter {
            (it.kind.orEmpty().contains("caption") || it.kind.orEmpty().contains("subtitle")) &&
                it.file != null && it.label != null
        }.map { SubtitleFile(it.label!!, fixUrl(it.file!!, mainUrl)) }

        extractedLinks.forEach {
            currentCoroutineContext().ensureActive()
            callback(it)
        }
        subtitleFiles.forEach {
            currentCoroutineContext().ensureActive()
            subtitleCallback(it)
        }
        return sourceMatches.isNotEmpty() || subtitleFiles.isNotEmpty()
    }

    fun canParseJwScript(script: String): Boolean = sourceRegex.containsMatchIn(script)

    private fun createLink(
        sourceName: String,
        linkUrl: String,
        headers: Map<String, String>,
        type: ExtractorLinkType?,
    ): ExtractorLink = ExtractorLink(
        sourceName,
        sourceName,
        linkUrl,
        linkUrl,
        400,
        headers,
        null,
        type,
        emptyList(),
    )

    private inline fun <reified T> parseJson(json: String): T? = try {
        MainAPIKt.getMapper().readValue(json, object : TypeReference<T>() {})
    } catch (_: Exception) {
        null
    }

    private fun fixUrl(url: String, mainUrl: String): String = when {
        url.startsWith("/") -> mainUrl + url
        url.startsWith("http") -> url
        else -> "$mainUrl/$url"
    }

    /**
     * Caller headers may contain credentials. Preserve them only for the exact same origin;
     * cross-origin media hosts receive a small public-header set and root-only origin metadata.
     */
    private fun headersForTarget(
        headers: Map<String, String>,
        pageUrl: String,
        targetUrl: String,
    ): Map<String, String> {
        if (isSameOrigin(pageUrl, targetUrl)) return headers
        val safe = LinkedHashMap<String, String>()
        headers.forEach { (name, value) ->
            val key = name.lowercase()
            if (isCredentialHeader(key)) return@forEach
            when (key) {
                "accept", "accept-language", "user-agent" -> safe[name] = value
                "origin" -> sanitizedOrigin(value)?.let { safe[name] = it }
                "referer" -> sanitizedOrigin(value)?.let { safe[name] = "$it/" }
            }
        }
        return safe
    }

    private fun refererForTarget(pageUrl: String, targetUrl: String): String? {
        if (isSameOrigin(pageUrl, targetUrl)) return pageUrl
        return sanitizedOrigin(pageUrl)?.let { "$it/" }
    }

    private fun isSameOrigin(pageUrl: String, targetUrl: String): Boolean {
        val page = pageUrl.toHttpUrlOrNull() ?: return false
        val target = targetUrl.toHttpUrlOrNull() ?: return false
        return page.scheme == target.scheme && page.host == target.host && page.port == target.port
    }

    private fun sanitizedOrigin(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port"
    }

    private fun isCredentialHeader(lowercaseName: String): Boolean =
        lowercaseName in setOf("authorization", "proxy-authorization", "cookie", "cookie2") ||
            lowercaseName.contains("token") || lowercaseName.contains("secret") ||
            lowercaseName.contains("api-key") || lowercaseName.contains("apikey") ||
            lowercaseName.contains("access-key") || lowercaseName.startsWith("x-auth")

    private fun String.addMarks(vararg names: String): String = names.fold(this) { value, name ->
        value.replace(Regex("\"?$name\"?"), "\"$name\"")
    }

    @Serializable
    private data class Source(
        @JsonProperty("file") @SerialName("file") val file: String,
        @JsonProperty("label") @SerialName("label") val label: String?,
        @JsonProperty("type") @SerialName("type") val type: String?,
    )

    @Serializable
    data class Track(
        @JsonProperty("file") @SerialName("file") val file: String? = null,
        @JsonProperty("label") @SerialName("label") val label: String? = null,
        @JsonProperty("kind") @SerialName("kind") val kind: String? = null,
    )
}
