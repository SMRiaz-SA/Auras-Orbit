package com.lagradost.cloudstream3.desktop.ui.badges

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.stremio.StremioTransport
import com.lagradost.cloudstream3.desktop.utils.appScope
import com.lagradost.common.collections.BoundedLruCache
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.net.readBoundedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Asynchronous rating lookup for media cards.
 */
object FastRatingEnricher {
    private const val TAG = "FastRatingEnricher"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    private val mapper = jacksonObjectMapper()

    // Normalized Title -> Rating (e.g. 8.8)
    private val ratingCache = BoundedLruCache<String, Double>(512)
    private val inFlightQueries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _ratingsUpdateSignal = MutableStateFlow(0L)
    val ratingsUpdateSignal: StateFlow<Long> = _ratingsUpdateSignal.asStateFlow()

    fun getCachedRating(cleanTitle: String): Double? {
        val key = normalizeKey(cleanTitle)
        return ratingCache[key]
    }

    fun requestRatingAsync(cleanTitle: String, isAnime: Boolean, isSeries: Boolean = false) {
        val key = normalizeKey(cleanTitle)
        if (ratingCache[key] != null || !inFlightQueries.add(key)) return
        appScope.launch(Dispatchers.IO) {
            try {
                val rating = if (isAnime) {
                    fetchAniListRating(cleanTitle) ?: fetchCinemetaRating(cleanTitle, isSeries)
                } else {
                    fetchCinemetaRating(cleanTitle, isSeries)
                }

                if (rating != null && rating > 0.0) {
                    ratingCache[key] = rating
                    _ratingsUpdateSignal.value = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.d(TAG, "Failed rating lookup for '$cleanTitle': ${e.message}")
            } finally {
                inFlightQueries.remove(key)
            }
        }
    }

    private suspend fun fetchCinemetaRating(cleanTitle: String, isSeries: Boolean): Double? {
        val metaAddons = StremioAddonManager.getEnabledMetadataAddons()
        if (metaAddons.isEmpty()) return null
        val candidateBaseUrl = StremioTransport.getBaseUrl(metaAddons.first().manifestUrl)
        if (candidateBaseUrl.isBlank()) return null

        val primaryType = if (isSeries) "series" else "movie"
        val fallbackType = if (isSeries) "movie" else "series"
        val encoded = URLEncoder.encode(cleanTitle.trim(), "UTF-8")

        // Try primary type first (e.g. movie)
        val score = queryCinemetaType(candidateBaseUrl, primaryType, encoded)
        if (score != null) return score

        // Fallback to secondary type (e.g. series)
        return queryCinemetaType(candidateBaseUrl, fallbackType, encoded)
    }

    private suspend fun queryCinemetaType(baseUrl: String, type: String, encodedQuery: String): Double? {
        return try {
            val searchUrl = "$baseUrl/catalog/$type/top/search=$encodedQuery.json"
            val responseText = app.get(searchUrl, timeout = 3000L)
                .body.byteStream().readBoundedBytes(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
            val root = mapper.readTree(responseText)
            val metas = root["metas"]
            if (metas != null && metas.isArray && metas.size() > 0) {
                val first = metas.get(0)
                val id = first["id"]?.asText() ?: return null
                val metaType = first["type"]?.asText() ?: type

                // Direct meta query to fetch the verified IMDb rating
                val metaUrl = "$baseUrl/meta/$metaType/$id.json"
                val metaRespText = app.get(metaUrl, timeout = 3000L)
                    .body.byteStream().readBoundedBytes(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
                val metaRoot = mapper.readTree(metaRespText)
                val metaObj = metaRoot["meta"]
                val scoreStr = metaObj?.get("imdbRating")?.asText()
                val score = scoreStr?.toDoubleOrNull() ?: metaObj?.get("imdbRating")?.asDouble()
                if (score != null && score > 0.0) {
                    return score
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchAniListRating(cleanTitle: String): Double? {
        return try {
            val query = """
                query (${'$'}search: String) {
                    Media(search: ${'$'}search, type: ANIME) {
                        averageScore
                    }
                }
            """.trimIndent()

            val payload = mapOf(
                "query" to query,
                "variables" to mapOf("search" to cleanTitle.trim()),
            )

            val jsonBody = mapper.writeValueAsString(payload)
            val response = app.post(
                url = "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                json = jsonBody,
                timeout = 3000L,
            )

            val responseText = response.body.byteStream().readBoundedBytes(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
            val root = mapper.readTree(responseText)
            val scoreInt = root["data"]?.get("Media")?.get("averageScore")?.asInt()
            if (scoreInt != null && scoreInt > 0) {
                return (scoreInt / 10.0)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeKey(title: String): String {
        return title.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
