package com.lagradost.cloudstream3.desktop.explore.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.explore.models.TorrentSearchResult
import com.lagradost.common.logging.AppLogger
import java.net.URLEncoder

/** Queries the remote Magnetz metadata index. Playback remains on Orbit's existing torrent path. */
object TorrentSearchClient {
    private const val TAG = "TorrentSearchClient"
    private const val SEARCH_URL = "https://magnetz.eu/api/magnets/search"
    private val mapper = jacksonObjectMapper()

    suspend fun search(query: String): List<TorrentSearchResult> {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Enter a title to search." }

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val response = app.get("$SEARCH_URL?query=$encodedQuery&page=1", timeout = 12_000L)
        val root = mapper.readTree(response.text)
        val data = root["data"]
        if (data == null || !data.isArray) {
            val message = root["message"]?.asText()?.takeIf { it.isNotBlank() }
            throw IllegalStateException(message ?: "The search service returned an invalid response.")
        }

        val results = data.mapNotNull { node ->
            val name = node["name"]?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val magnetLink = node["magnet_link"]?.asText()?.trim()
                ?.takeIf { it.startsWith("magnet:?", ignoreCase = true) }
                ?: return@mapNotNull null

            TorrentSearchResult(
                name = name,
                magnetLink = magnetLink,
                infoHash = node["info_hash"]?.asText()?.trim()?.takeIf { it.isNotEmpty() },
                sizeBytes = node["size"]?.asLong(0L) ?: 0L,
                humanSize = node["human_size"]?.asText()?.takeIf { it.isNotBlank() },
                seeders = node["seeders"]?.asInt(),
                leechers = node["leechers"]?.asInt(),
                isVerified = node["is_verified"]?.asBoolean(false) ?: false,
            )
        }.distinctBy { result -> result.infoHash?.lowercase() ?: result.magnetLink }

        AppLogger.d(TAG, "Magnetz returned ${results.size} playable magnet results for query '$cleanQuery'")
        return results
    }
}
