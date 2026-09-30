package com.lagradost.cloudstream3.desktop.stremio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.subtitles.LanguageNormalizer
import com.lagradost.cloudstream3.desktop.utils.appScope
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Universal Manager for External Stremio Addons in CloudStream Desktop.
 * Provides a manifest-driven, non-intrusive addon architecture for Subtitles & Metadata.
 */
object StremioAddonManager {
    private const val TAG = "StremioAddonManager"
    private const val PREF_INSTALLED_ADDONS = "cs_desktop_stremio_installed_addons"

    private val mapper = jacksonObjectMapper()

    val DEFAULT_ADDONS: List<ManagedStremioAddon> = emptyList()

    private val _addons = MutableStateFlow<List<ManagedStremioAddon>>(emptyList())
    val addons: StateFlow<List<ManagedStremioAddon>> = _addons.asStateFlow()

    init {
        loadAddons()
    }

    fun loadAddons() {
        try {
            val savedJson = DesktopDataStore.getKey<String>(PREF_INSTALLED_ADDONS)
            if (savedJson != null) {
                if (savedJson.isBlank() || savedJson.trim() == "[]") {
                    _addons.value = emptyList()
                    return
                }
                val list = mapper.readValue(
                    savedJson,
                    mapper.typeFactory.constructCollectionType(List::class.java, ManagedStremioAddon::class.java),
                ) as? List<ManagedStremioAddon>
                if (list != null) {
                    _addons.value = list
                    return
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load installed addons from storage", e)
        }

        // Clean initial state: zero pre-installed third-party addons
        _addons.value = emptyList()
        saveAddons()
    }

    private fun saveAddons() {
        try {
            val json = mapper.writeValueAsString(_addons.value)
            DesktopDataStore.setKey(PREF_INSTALLED_ADDONS, json)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist installed addons", e)
        }
    }

    suspend fun addAddon(rawUrl: String): Result<ManagedStremioAddon> = withContext(Dispatchers.IO) {
        val normalizedUrl = StremioTransport.normalizeManifestUrl(rawUrl)

        // Check if already installed
        val existing = _addons.value.find { it.manifestUrl.equals(normalizedUrl, ignoreCase = true) }
        if (existing != null) {
            return@withContext Result.failure(IllegalArgumentException("Addon is already installed: ${existing.name}"))
        }

        try {
            AppLogger.i(TAG, "Fetching addon manifest from $normalizedUrl...")
            val responseText = app.get(normalizedUrl, timeout = 8000L).text
            val manifest = StremioManifestParser.parse(normalizedUrl, responseText)

            val newAddon = ManagedStremioAddon(
                manifestUrl = normalizedUrl,
                name = manifest.name.ifBlank { "Addon" },
                description = manifest.description,
                version = manifest.version,
                logoUrl = manifest.logoUrl,
                backgroundUrl = manifest.backgroundUrl,
                enabled = true,
                providesSubtitles = manifest.providesSubtitles,
                providesMetadata = manifest.providesMetadata,
                providesStreams = manifest.providesStreams,
                providesCatalogs = manifest.providesCatalogs,
                resources = manifest.resources,
                catalogs = manifest.catalogs,
                types = manifest.types,
                idPrefixes = manifest.idPrefixes,
                catalogsSummary = manifest.catalogs.map { it.name.ifBlank { it.id } },
                isP2P = manifest.behaviorHints.p2p,
                isConfigurable = manifest.behaviorHints.configurable,
                configurationRequired = manifest.behaviorHints.configurationRequired,
            )

            val updated = _addons.value + newAddon
            _addons.value = updated
            saveAddons()

            AppLogger.i(TAG, "Successfully installed addon: ${newAddon.name} (v${newAddon.version})")
            Result.success(newAddon)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to fetch/parse manifest from $normalizedUrl: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun removeAddon(manifestUrl: String) {
        val updated = _addons.value.filterNot { it.manifestUrl.equals(manifestUrl, ignoreCase = true) }
        _addons.value = updated
        saveAddons()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        val updated = _addons.value.map { addon ->
            if (addon.manifestUrl.equals(manifestUrl, ignoreCase = true)) {
                addon.copy(enabled = enabled)
            } else {
                addon
            }
        }
        _addons.value = updated
        saveAddons()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _addons.value.indices || toIndex !in _addons.value.indices || fromIndex == toIndex) return
        val list = _addons.value.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _addons.value = list
        saveAddons()
    }

    fun refreshAddon(manifestUrl: String) {
        appScope.launch(Dispatchers.IO) {
            try {
                val normalizedUrl = StremioTransport.normalizeManifestUrl(manifestUrl)
                val responseText = app.get(normalizedUrl, timeout = 6000L).text
                val manifest = StremioManifestParser.parse(normalizedUrl, responseText)

                val updated = _addons.value.map { addon ->
                    if (addon.manifestUrl.equals(normalizedUrl, ignoreCase = true)) {
                        addon.copy(
                            name = manifest.name.ifBlank { addon.name },
                            description = manifest.description,
                            version = manifest.version,
                            logoUrl = manifest.logoUrl,
                            backgroundUrl = manifest.backgroundUrl,
                            providesSubtitles = manifest.providesSubtitles,
                            providesMetadata = manifest.providesMetadata,
                            providesStreams = manifest.providesStreams,
                            providesCatalogs = manifest.providesCatalogs,
                            resources = manifest.resources,
                            catalogs = manifest.catalogs,
                            types = manifest.types,
                            idPrefixes = manifest.idPrefixes,
                            catalogsSummary = manifest.catalogs.map { it.name.ifBlank { it.id } },
                            isP2P = manifest.behaviorHints.p2p,
                            isConfigurable = manifest.behaviorHints.configurable,
                            configurationRequired = manifest.behaviorHints.configurationRequired,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                }
                _addons.value = updated
                saveAddons()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to refresh addon $manifestUrl: ${e.message}", e)
            }
        }
    }

    fun getEnabledSubtitleAddons(): List<ManagedStremioAddon> {
        return _addons.value.filter { it.enabled && it.providesSubtitles }
    }

    fun getEnabledMetadataAddons(): List<ManagedStremioAddon> {
        return _addons.value.filter { it.enabled && it.providesMetadata }
    }

    fun getEnabledStreamAddons(): List<ManagedStremioAddon> {
        return _addons.value.filter { it.enabled && it.providesStreams }
    }

    internal fun supportsRequest(
        addon: ManagedStremioAddon,
        resourceName: String,
        type: String,
        id: String,
    ): Boolean {
        val resources = addon.resources.filter { it.name.equals(resourceName, ignoreCase = true) }
        if (addon.resources.isNotEmpty() && resources.isEmpty()) return false

        val declarations: List<StremioResource?> = resources.ifEmpty { listOf(null) }
        return declarations.any { resource ->
            val supportedTypes = resource?.types?.takeIf { it.isNotEmpty() } ?: addon.types
            val supportedPrefixes = resource?.idPrefixes?.takeIf { it.isNotEmpty() } ?: addon.idPrefixes
            (supportedTypes.isEmpty() || supportedTypes.any { it.equals(type, ignoreCase = true) }) &&
                (supportedPrefixes.isEmpty() || supportedPrefixes.any { id.startsWith(it, ignoreCase = true) })
        }
    }

    suspend fun searchStreams(
        imdbId: String?,
        season: Int? = null,
        episode: Int? = null,
        title: String? = null,
        onLink: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (cleanImdb == null && !title.isNullOrBlank()) {
            cleanImdb = resolveImdbId(title, isSeries = (season != null && season > 0))
        }
        if (cleanImdb == null) {
            AppLogger.d(TAG, "Skipping Stremio stream query: No verified IMDb ID (title='$title')")
            return@withContext
        }

        val isSeries = (season != null && season > 0) || (episode != null && episode > 0)
        val requestType = if (isSeries) "series" else "movie"
        val requestVideoId = if (isSeries) {
            "$cleanImdb:${season ?: 1}:${episode ?: 1}"
        } else {
            cleanImdb
        }
        queryStreamsById(requestType, requestVideoId, onLink)
    }

    /**
     * Queries installed Stremio stream addons for the exact protocol video ID.
     * Unlike [searchStreams], this does not assume IMDb IDs or synthesize season/episode IDs.
     */
    suspend fun searchStreamsById(type: String, videoId: String): List<ExtractorLink> = withContext(Dispatchers.IO) {
        val requestType = type.trim()
        val requestVideoId = videoId.trim()
        if (requestType.isBlank() || requestVideoId.isBlank()) return@withContext emptyList()

        val links = CopyOnWriteArrayList<ExtractorLink>()
        queryStreamsById(requestType, requestVideoId, links::add)
        links.toList()
    }

    private suspend fun queryStreamsById(
        requestType: String,
        requestVideoId: String,
        onLink: (ExtractorLink) -> Unit,
    ): Unit = kotlinx.coroutines.coroutineScope {
        val activeAddons = getEnabledStreamAddons().filter { addon ->
            supportsRequest(addon, "stream", requestType, requestVideoId)
        }
        if (activeAddons.isEmpty()) return@coroutineScope

        AppLogger.i(TAG, "Querying ${activeAddons.size} Stremio stream addons for $requestType:$requestVideoId")

        val deferred = activeAddons.map { addon ->
            async {
                withTimeoutOrNull(5000L) {
                    try {
                        val streamUrl = StremioTransport.buildStreamUrl(
                            manifestUrl = addon.manifestUrl,
                            type = requestType,
                            id = requestVideoId,
                        )
                        AppLogger.d(TAG, "Querying stream addon '${addon.name}': $streamUrl")
                        val responseText = app.get(streamUrl, timeout = 4500L).text
                        val parsed = mapper.readValue(responseText, StremioStreamResponse::class.java)
                        val streams = parsed.streams ?: return@withTimeoutOrNull

                        for (item in streams) {
                            val playable = StremioStreamMapper.map(item) ?: continue

                            val titleText = item.title ?: item.description ?: ""
                            val addonName = addon.name.ifBlank { "Stremio" }
                            val parsedQuality = parseQualityFromText(titleText, item.name)

                            val cleanLabel = buildString {
                                append("⚡ [Stremio] $addonName")
                                val itemDesc = if (!item.name.isNullOrBlank() && !item.name.equals(addonName, ignoreCase = true)) {
                                    item.name.trim()
                                } else if (titleText.isNotBlank()) {
                                    titleText.lines().firstOrNull()?.trim()
                                } else {
                                    null
                                }

                                if (!itemDesc.isNullOrBlank()) {
                                    append(" - $itemDesc")
                                }
                            }

                            if (playable is StremioPlayableStream.External) {
                                runCatching {
                                    val resolved = loadExtractor(
                                        url = playable.url,
                                        subtitleCallback = {},
                                        callback = onLink,
                                    )
                                    if (!resolved) {
                                        AppLogger.d(TAG, "No CloudStream extractor matched external URL from '${addon.name}'")
                                    }
                                }.onFailure { e ->
                                    AppLogger.d(TAG, "No CloudStream extractor resolved external stream URL: ${e.message}")
                                }
                                continue
                            }

                            val streamUrlStr: String
                            val linkType: ExtractorLinkType
                            val headers: Map<String, String>
                            when (playable) {
                                is StremioPlayableStream.Direct -> {
                                    streamUrlStr = playable.url
                                    headers = playable.requestHeaders
                                    val path = runCatching { java.net.URI(streamUrlStr).path.orEmpty() }.getOrDefault("")
                                    linkType = when {
                                        path.endsWith(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                                        path.endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                }
                                is StremioPlayableStream.Torrent -> {
                                    streamUrlStr = playable.magnetUrl
                                    headers = emptyMap()
                                    linkType = ExtractorLinkType.MAGNET
                                }
                                is StremioPlayableStream.YouTube -> {
                                    streamUrlStr = playable.url
                                    headers = emptyMap()
                                    linkType = ExtractorLinkType.VIDEO
                                }
                                is StremioPlayableStream.External -> continue
                            }

                            val extractorLink = newExtractorLink(
                                source = addonName,
                                name = cleanLabel,
                                url = streamUrlStr,
                                type = linkType,
                            ) {
                                this.referer = headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value ?: ""
                                this.quality = parsedQuality
                                this.headers = headers
                                if (playable is StremioPlayableStream.YouTube) {
                                    this.extractorData = "yt-dlp"
                                }
                            }

                            if (playable is StremioPlayableStream.Torrent) {
                                com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
                                    .applyStremioFileIndex(extractorLink, playable.fileIdx)
                            }
                            onLink(extractorLink)
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Stream query failed for '${addon.name}': ${e.message}")
                    }
                }
            }
        }

        deferred.awaitAll()
    }

    private fun parseQualityFromText(title: String, name: String?): Int {
        val combined = "$title ${name ?: ""}".lowercase()
        return when {
            combined.contains("4k") || combined.contains("2160p") || combined.contains("uhd") -> Qualities.P2160.value
            combined.contains("1440p") || combined.contains("2k") -> Qualities.P1440.value
            combined.contains("1080p") || combined.contains("fhd") || combined.contains("full hd") -> Qualities.P1080.value
            combined.contains("720p") || combined.contains("hd") -> Qualities.P720.value
            combined.contains("480p") || combined.contains("sd") -> Qualities.P480.value
            combined.contains("360p") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }

    suspend fun searchSubtitles(
        query: String,
        lang: String?,
        season: Int?,
        episode: Int?,
        imdbId: String? = null,
    ): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val activeAddons = getEnabledSubtitleAddons()
        if (activeAddons.isEmpty()) return@withContext emptyList()

        // Resolve IMDb ID via active metadata addons if needed
        var resolvedImdb = imdbId?.takeIf { it.startsWith("tt") }
        if (resolvedImdb == null && query.isNotBlank()) {
            resolvedImdb = resolveImdbId(query, isSeries = (season != null && season > 0))
        }

        if (resolvedImdb == null) {
            AppLogger.d(TAG, "Cannot query Stremio subtitle addons: No valid IMDb ID for '$query'")
            return@withContext emptyList()
        }

        val isSeries = (season != null && season > 0) || (episode != null && episode > 0)
        val requestType = if (isSeries) "series" else "movie"
        val requestVideoId = if (isSeries) {
            "$resolvedImdb:${season ?: 1}:${episode ?: 1}"
        } else {
            resolvedImdb
        }
        val requestAddons = activeAddons.filter { addon ->
            supportsRequest(addon, "subtitles", requestType, requestVideoId)
        }
        if (requestAddons.isEmpty()) return@withContext emptyList()

        val allResults = mutableListOf<Map<String, Any?>>()

        val deferredList = requestAddons.map { addon ->
            async {
                try {
                    val subUrl = StremioTransport.buildSubtitleUrl(
                        manifestUrl = addon.manifestUrl,
                        type = requestType,
                        id = requestVideoId,
                    )
                    AppLogger.d(TAG, "Querying subtitle addon '${addon.name}': $subUrl")
                    val responseText = app.get(subUrl, timeout = 6000L).text
                    val parsed = mapper.readValue(responseText, StremioSubtitleResponse::class.java)
                    val items = parsed.subtitles ?: return@async emptyList()

                    val langCounters = mutableMapOf<String, Int>()
                    val addonEntities = mutableListOf<Map<String, Any?>>()

                    for (item in items) {
                        val downloadUrl = item.url ?: continue
                        val itemLang = item.lang ?: "eng"

                        // Filter by language if user requested specific language
                        if (!lang.isNullOrBlank() && !LanguageNormalizer.isMatch(lang, itemLang)) {
                            continue
                        }

                        val norm = LanguageNormalizer.normalize(itemLang)
                        val count = (langCounters[norm.displayName] ?: 0) + 1
                        langCounters[norm.displayName] = count

                        val scoreText = item.rating?.toIntOrNull()?.let { score ->
                            if (score > 10) " (Score: $score)" else ""
                        } ?: ""
                        val encodingInfo = item.subEncoding?.takeIf { it.isNotBlank() && !it.equals("UTF-8", true) }?.let { " [$it]" } ?: ""
                        val displayName = "${norm.displayName} - Track #$count$scoreText$encodingInfo"

                        addonEntities.add(
                            mapOf(
                                "idPrefix" to (item.id ?: "stremio_${addon.name}_$count"),
                                "name" to displayName,
                                "lang" to norm.code2,
                                "langName" to norm.displayName,
                                "langBadge" to norm.badge,
                                "data" to downloadUrl,
                                "source" to addon.name,
                                "seasonNumber" to season,
                                "epNumber" to episode,
                            ),
                        )
                    }
                    addonEntities
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Subtitle query failed for '${addon.name}': ${e.message}")
                    emptyList()
                }
            }
        }

        val results = deferredList.awaitAll().flatten()
        allResults.addAll(results)
        AppLogger.i(TAG, "Aggregated ${allResults.size} subtitles across ${requestAddons.size} Stremio addons")
        return@withContext allResults
    }

    private suspend fun resolveImdbId(query: String, isSeries: Boolean): String? {
        val type = if (isSeries) "series" else "movie"
        val id = com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient
            .search(query.trim(), type)
            ?.firstNotNullOfOrNull { meta -> meta.id?.takeIf { it.startsWith("tt", ignoreCase = true) } }
        if (id != null) AppLogger.d(TAG, "Resolved '$query' -> $id via Stremio catalog search")
        return id
    }
}
