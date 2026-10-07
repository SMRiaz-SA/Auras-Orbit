package com.lagradost.cloudstream3.metaproviders

import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTorrentLoadResponse
import com.lagradost.cloudstream3.newTorrentSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/** First-party Android Torrent search using the same Magnetz index as desktop. */
object MagnetzTorrentProvider : MainAPI() {
    private const val API_BASE = "https://magnetz.eu/api/magnets"
    private val sqidPattern = Regex("^[A-Za-z0-9]+$")

    override var name = "Magnetz Torrent Search"
    override var mainUrl = "https://magnetz.eu"
    override var lang = AllLanguagesName
    override var canBeOverridden = false
    override val supportedTypes = setOf(TvType.Torrent)
    override val searchTimeoutMs = 20_000L
    override val loadTimeoutMs = 15_000L

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return newSearchResponseList(emptyList(), false)

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val response = app.get(
            "$API_BASE/search?query=$encodedQuery&page=${page.coerceAtLeast(1)}",
            timeout = searchTimeoutMs,
        )
        val root = mapper.readTree(response.text)
        val data = root["data"]
        if (data == null || !data.isArray) {
            val message = root["message"]?.asText()?.takeIf { it.isNotBlank() }
            throw ErrorLoadingException(message ?: "Magnetz returned an invalid search response")
        }

        val results = data.mapNotNull { item ->
            val resultName = item["name"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val sqid = item["sqid"]?.asText()?.trim()?.takeIf(sqidPattern::matches)
                ?: return@mapNotNull null
            val magnet = item["magnet_link"]?.asText()?.trim()
                ?.takeIf { it.startsWith("magnet:?", ignoreCase = true) }
                ?: return@mapNotNull null

            newTorrentSearchResponse(
                name = resultName,
                url = "$API_BASE/$sqid",
                fix = false,
            ) {
                sizeBytes = item["size"]?.takeUnless { it.isNull }?.asLong()?.takeIf { it > 0L }
                humanSize = item["human_size"]?.asText()?.takeIf { it.isNotBlank() }
                infoHash = item["info_hash"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                seeders = item["seeders"]?.takeUnless { it.isNull }?.asInt()
                leechers = item["leechers"]?.takeUnless { it.isNull }?.asInt()
                isVerified = item["is_verified"]?.asBoolean(false) ?: false
            }
        }.distinctBy { result ->
            result.infoHash?.lowercase() ?: result.url.substringAfterLast('/').lowercase()
        }

        val hasNext = root["links"]?.get("next")?.let {
            !it.isNull && it.asText().isNotBlank()
        } ?: false
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        if (!url.startsWith("$API_BASE/")) throw ErrorLoadingException("Invalid Magnetz result")
        val sqid = url.removePrefix("$API_BASE/").substringBefore('?')
            .takeIf(sqidPattern::matches)
            ?: throw ErrorLoadingException("Invalid Magnetz result")

        val root = mapper.readTree(app.get("$API_BASE/$sqid", timeout = loadTimeoutMs).text)
        val data = root["data"] ?: throw ErrorLoadingException(
            root["message"]?.asText()?.takeIf { it.isNotBlank() } ?: "Magnetz result was not found"
        )
        val magnet = data["magnet_link"]?.asText()?.trim()
            ?.takeIf { it.startsWith("magnet:?", ignoreCase = true) }
            ?: throw ErrorLoadingException("Magnetz did not provide a playable magnet link")
        val resultName = data["name"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Torrent"

        return newTorrentLoadResponse(resultName, url, magnet = magnet)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val magnet = data.trim()
        if (!magnet.startsWith("magnet:?", ignoreCase = true)) return false

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = magnet,
                type = ExtractorLinkType.MAGNET,
            )
        )
        return true
    }
}
