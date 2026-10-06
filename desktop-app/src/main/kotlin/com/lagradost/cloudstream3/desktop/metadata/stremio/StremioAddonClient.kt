package com.lagradost.cloudstream3.desktop.metadata.stremio

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.metadata.MetadataConfig
import com.lagradost.cloudstream3.desktop.stremio.ManagedStremioAddon
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.stremio.StremioCatalogDescriptor
import com.lagradost.cloudstream3.desktop.stremio.StremioManifestParser
import com.lagradost.cloudstream3.desktop.stremio.StremioTransport
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.net.readBoundedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.InputStream

object StremioAddonClient {
    private const val TAG = "StremioAddonClient"
    private const val MAX_ADDON_RESPONSE_BYTES = 8 * 1024 * 1024
    private val mapper = jacksonObjectMapper()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioManifest(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("version") val version: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("resources") val resources: List<Any>? = null,
        @JsonProperty("types") val types: List<String>? = null,
        @JsonProperty("idPrefixes") val idPrefixes: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioCatalogResponse(
        @JsonProperty("metas") val metas: List<StremioMetaItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioMetaResponse(
        @JsonProperty("meta") val meta: StremioMetaItem? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioVideo(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("released") val released: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("rating") val rating: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioTrailer(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("ytId") val ytId: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioMetaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("videos") val videos: List<StremioVideo>? = null,
        @JsonProperty("trailers") val trailers: List<StremioTrailer>? = null,
        @JsonProperty("moviedb_id") val moviedbId: Int? = null,
    )

    fun normalizeManifestUrl(rawUrl: String): String {
        return StremioTransport.normalizeManifestUrl(rawUrl)
    }

    fun getTransportBaseUrl(manifestUrl: String): String {
        return StremioTransport.getBaseUrl(manifestUrl)
    }

    suspend fun testManifest(rawUrl: String): Result<StremioManifest> = withContext(Dispatchers.IO) {
        val normalized = normalizeManifestUrl(rawUrl)
        if (normalized.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("URL is empty"))
        }

        try {
            val response = app.get(
                url = normalized,
                headers = mapOf("Accept" to "application/json", "User-Agent" to "CloudStream-Desktop/1.0"),
                timeout = 5000L,
            )
            if (response.code != 200) {
                return@withContext Result.failure(Exception("HTTP ${response.code} received from manifest"))
            }

            val manifest = parseBoundedJson<StremioManifest>(response.body.byteStream())
                ?: return@withContext Result.failure(Exception("Invalid manifest JSON format"))

            if (manifest.id.isNullOrBlank() && manifest.name.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Manifest missing required 'id' or 'name'"))
            }

            Result.success(manifest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun search(query: String, type: String = "series"): List<StremioMetaItem>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        val searchCatalogs = getSearchCatalogs(type)
        if (searchCatalogs.isEmpty()) return@withContext null

        val results = LinkedHashMap<String, StremioMetaItem>()
        for ((manifestUrl, catalog) in searchCatalogs) {
            try {
                val url = StremioTransport.buildCatalogUrl(
                    manifestOrBaseUrl = manifestUrl,
                    type = catalog.type,
                    catalogId = catalog.id,
                    search = query,
                )
                val response = app.get(
                    url = url,
                    headers = mapOf("Accept" to "application/json", "User-Agent" to "CloudStream-Desktop/1.0"),
                    timeout = 4000L,
                )
                val parsed = parseBoundedJson<StremioCatalogResponse>(response.body.byteStream())
                parsed?.metas.orEmpty().forEach { meta ->
                    val key = meta.id?.takeIf { it.isNotBlank() } ?: "${meta.type}:${meta.name}"
                    results.putIfAbsent(key, meta)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.d(TAG, "Search failed for '$query' on ${catalog.id}: ${e.message}")
            }
        }
        results.values.toList().takeIf { it.isNotEmpty() }
    }

    suspend fun getMeta(
        id: String,
        type: String = "series",
        preferredManifestUrl: String? = null,
    ): StremioMetaItem? = withContext(Dispatchers.IO) {
        val metaAddons = StremioAddonManager.getEnabledMetadataAddons()
        val candidateUrls = getMetadataManifestUrls(metaAddons, preferredManifestUrl, includeFallbackWhenInstalled = false)
        for (manifestUrl in candidateUrls) {
            val baseUrl = getTransportBaseUrl(manifestUrl)
            if (baseUrl.isBlank()) continue

            val installedAddon = metaAddons.firstOrNull { it.manifestUrl.equals(manifestUrl, ignoreCase = true) }
            if (installedAddon != null && !StremioAddonManager.supportsRequest(installedAddon, "meta", type, id)) continue

            try {
                val url = StremioTransport.buildMetadataUrl(manifestUrl, type, id)
                val response = app.get(
                    url = url,
                    headers = mapOf("Accept" to "application/json", "User-Agent" to "CloudStream-Desktop/1.0"),
                    timeout = 4000L,
                )
                val meta = parseBoundedJson<StremioMetaResponse>(response.body.byteStream())?.meta
                if (meta != null) return@withContext meta
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.d(TAG, "getMeta failed for id='$id' on $baseUrl: ${e.message}")
            }
        }
        null
    }

    suspend fun getMetaCandidates(
        id: String,
        type: String = "series",
        preferredManifestUrl: String? = null,
    ): List<StremioMetaItem> = withContext(Dispatchers.IO) {
        val metaAddons = StremioAddonManager.getEnabledMetadataAddons()
        val candidateUrls = getMetadataManifestUrls(metaAddons, preferredManifestUrl, includeFallbackWhenInstalled = true)

        if (candidateUrls.isEmpty()) return@withContext emptyList()

        candidateUrls.map { manifestUrl ->
            async {
                val baseUrl = getTransportBaseUrl(manifestUrl)
                if (baseUrl.isBlank()) return@async null

                val installedAddon = metaAddons.firstOrNull { it.manifestUrl.equals(manifestUrl, ignoreCase = true) }
                if (installedAddon != null &&
                    !StremioAddonManager.supportsRequest(installedAddon, "meta", type, id)
                ) {
                    return@async null
                }

                try {
                    val url = StremioTransport.buildMetadataUrl(manifestUrl, type, id)
                    val response = app.get(
                        url = url,
                        headers = mapOf("Accept" to "application/json", "User-Agent" to "CloudStream-Desktop/1.0"),
                        timeout = 4000L,
                    )
                    parseBoundedJson<StremioMetaResponse>(response.body.byteStream())?.meta
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.d(TAG, "getMeta failed for id='$id' on $baseUrl: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun getMetadataManifestUrls(
        metaAddons: List<ManagedStremioAddon>,
        preferredManifestUrl: String?,
        includeFallbackWhenInstalled: Boolean,
    ): List<String> {
        val installedUrls = metaAddons.sortedByDescending {
            it.manifestUrl.equals(preferredManifestUrl, ignoreCase = true)
        }.map { it.manifestUrl }
        if (installedUrls.isNotEmpty() && !includeFallbackWhenInstalled) return installedUrls

        val fallbackUrl = MetadataConfig.stremioAddonUrl.value.trim()
            .takeIf { MetadataConfig.stremioAddonEnabled.value && it.isNotBlank() }
            ?.let(::normalizeManifestUrl)
        return buildList {
            addAll(installedUrls)
            if (fallbackUrl != null && none { it.equals(fallbackUrl, ignoreCase = true) }) add(fallbackUrl)
        }
    }

    private suspend fun getSearchCatalogs(type: String): List<Pair<String, StremioCatalogDescriptor>> {
        val installedAddons = StremioAddonManager.getEnabledMetadataAddons()
        val manifests = if (installedAddons.isNotEmpty()) {
            installedAddons.map { addon -> addon.manifestUrl to addon }
        } else {
            val fallback = MetadataConfig.stremioAddonUrl.value.trim()
            if (MetadataConfig.stremioAddonEnabled.value && fallback.isNotBlank()) {
                val normalized = normalizeManifestUrl(fallback)
                listOf(normalized to null)
            } else {
                emptyList()
            }
        }

        return manifests.flatMap { (manifestUrl, addon) ->
            val catalogs = addon?.catalogs?.takeIf { it.isNotEmpty() } ?: try {
                val normalizedUrl = normalizeManifestUrl(manifestUrl)
                val response = app.get(normalizedUrl, timeout = 5000L)
                val manifestJson = response.body.byteStream().use { stream ->
                    stream.readBoundedBytes(MAX_ADDON_RESPONSE_BYTES).toString(Charsets.UTF_8)
                }
                StremioManifestParser.parse(normalizedUrl, manifestJson).catalogs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.d(TAG, "Unable to read search catalogs from $manifestUrl: ${e.message}")
                emptyList()
            }

            catalogs.filter { catalog ->
                catalog.type.equals(type, ignoreCase = true) &&
                    catalog.extra.any { it.name.equals("search", ignoreCase = true) }
            }.map { manifestUrl to it }
        }
    }

    private inline fun <reified T> parseBoundedJson(input: InputStream): T? {
        val bytes = input.use { stream -> stream.readBoundedBytes(MAX_ADDON_RESPONSE_BYTES) }
        return try {
            mapper.readValue<T>(bytes)
        } catch (_: JsonProcessingException) {
            null
        }
    }
}
