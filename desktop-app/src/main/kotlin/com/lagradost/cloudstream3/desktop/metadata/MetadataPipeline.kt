package com.lagradost.cloudstream3.desktop.metadata

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.desktop.metadata.providers.AniListMetadataProvider
import com.lagradost.cloudstream3.desktop.metadata.providers.CinemetaMetadataProvider
import com.lagradost.cloudstream3.desktop.metadata.providers.KitsuMetadataProvider
import com.lagradost.cloudstream3.desktop.metadata.providers.TmdbMetadataProvider
import com.lagradost.cloudstream3.desktop.metadata.providers.TvMazeMetadataProvider
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.repo.HeroCache
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.performance.PerformanceDiagnosticStage
import com.lagradost.common.performance.PerformanceDiagnosticStatus
import com.lagradost.common.performance.PerformanceDiagnostics
import com.lagradost.common.performance.normalizePerformanceSourceLabel
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrator that coordinates metadata resolution, progressive enrichment,
 * and background caching across all registered [MetadataProvider] instances.
 */
object MetadataPipeline {
    private const val TAG = "MetadataPipeline"
    private const val MAX_IDENTITY_CACHE_ENTRIES = 512
    internal const val IDENTITY_RESOLVER_TIMEOUT_MS = 8_000L

    private val identityCache = LinkedHashMap<String, MetadataMatch>(64, 0.75f, true)
    private val identityCacheLock = Any()
    private val inFlightResolutions = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<MetadataMatch?>>()

    private val providers = mutableListOf<MetadataProvider>(
        TmdbMetadataProvider,
        TvMazeMetadataProvider,
        AniListMetadataProvider,
        KitsuMetadataProvider,
        CinemetaMetadataProvider,
    )

    /**
     * Registers an additional metadata provider into the pipeline.
     */
    fun registerProvider(provider: MetadataProvider) {
        synchronized(providers) {
            if (providers.none { it.id == provider.id }) {
                providers.add(provider)
            }
        }
    }

    /**
     * Unregisters a metadata provider by ID.
     */
    fun unregisterProvider(id: String) {
        synchronized(providers) {
            providers.removeAll { it.id == id }
        }
    }

    /**
     * Searches enabled metadata identity resolvers for a user-confirmed network-stream match.
     * This accepts only a title and media type; the stream URL (which may contain a signed token)
     * is deliberately not sent to metadata providers.
     */
    suspend fun findNetworkStreamMatches(title: String, type: com.lagradost.cloudstream3.TvType): List<MetadataMatch> =
        withContext(Dispatchers.IO) {
            val normalizedTitle = title.trim()
            if (normalizedTitle.isBlank()) return@withContext emptyList()

            val isAnime = type == com.lagradost.cloudstream3.TvType.Anime || type == com.lagradost.cloudstream3.TvType.AnimeMovie
            val resolvers = synchronized(providers) { providers.toList() }.filter { provider ->
                MetadataConfig.isProviderEnabled(provider.id) &&
                    (provider.supportedTypes.contains(type) || (isAnime && (provider.id == "anilist" || provider.id == "kitsu")))
            }

            coroutineScope {
                resolvers.map { resolver ->
                    resolver to async(Dispatchers.IO) {
                        try {
                            if (resolver.id == TmdbMetadataProvider.id) {
                                TmdbMetadataProvider.searchNetworkStream(normalizedTitle, type)
                            } else {
                                listOfNotNull(resolver.resolve(normalizedTitle, null, type, rawUrl = null))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Network stream metadata search failed in ${resolver.id} for '$normalizedTitle'", e)
                            emptyList()
                        }
                    }
                }.flatMap { (_, matches) -> matches.await() }
            }.distinctBy { match ->
                "${match.providerId}:${match.tmdbId ?: match.imdbId ?: match.anilistId ?: "${match.matchedTitle.lowercase()}:${match.matchedYear}"}"
            }
        }

    /**
     * Clears cached identity matches. If [title] is provided, only matches for that title are evicted.
     */
    fun clearCache(title: String? = null) {
        if (title.isNullOrBlank()) {
            synchronized(identityCacheLock) { identityCache.clear() }
            inFlightResolutions.clear()
            AppLogger.d(TAG, "Cleared entire metadata identity cache")
        } else {
            val keyPrefix = title.lowercase().trim()
            var count = 0
            synchronized(identityCacheLock) {
                val it = identityCache.keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    if (key.startsWith(keyPrefix)) {
                        it.remove()
                        inFlightResolutions.remove(key)
                        count++
                    }
                }
            }
            AppLogger.d(TAG, "Evicted $count cache entries for '$title'")
        }
    }

    /**
     * Executes the full metadata enrichment lifecycle for the given [loaded] response.
     */
    suspend fun enrich(
        loaded: LoadResponse,
        url: String,
        fetchCast: Boolean = true,
        callbacks: MetadataEnrichmentCallbacks = MetadataEnrichmentCallbacks(),
    ) {
        val diagnosticStartedAt = System.nanoTime()
        var diagnosticStatus = PerformanceDiagnosticStatus.FAILURE
        try {
            withContext(Dispatchers.IO) {
                val isDummy = url.startsWith("dummy_")
                val urlClean = url.removePrefix("dummy_")

                val providerTrailers = loaded.trailers.mapNotNull { providerTrailer ->
                    val videoId = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.TrailerUtils
                        .youtubeId(providerTrailer.extractorUrl)
                        ?: return@mapNotNull null
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.TrailerData(
                        id = videoId,
                        name = "${loaded.name} Trailer",
                        url = "https://www.youtube.com/watch?v=$videoId",
                        rawKey = videoId,
                        thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                        isOfficial = false,
                        source = "provider:${loaded.apiName}",
                    )
                }
                if (providerTrailers.isNotEmpty()) {
                    callbacks.onTrailersLoaded(providerTrailers)
                }

                // 1. Season adjustment from title if all episodes default to null or 1
                val titleSeason = Regex("""(?i)\b(?:season|series)\b\s*(\d+)""").find(loaded.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)\bs(\d{1,2})\b""").find(loaded.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)\bs(\d{1,2})e\d+""").find(loaded.name)?.groupValues?.get(1)?.toIntOrNull()

                val allEpisodes = when (loaded) {
                    is com.lagradost.cloudstream3.TvSeriesLoadResponse -> loaded.episodes
                    is com.lagradost.cloudstream3.AnimeLoadResponse -> loaded.episodes.values.flatten()
                    else -> emptyList()
                }
                val hasMultipleSeasons = allEpisodes.mapNotNull { it.season }.distinct().size > 1
                if (titleSeason != null && titleSeason > 1 && !hasMultipleSeasons) {
                    allEpisodes.forEach { ep -> ep.season = titleSeason }
                }

                val (cleanName, titleYear) = TitleUtils.cleanProviderTitle(loaded.name)
                if (loaded.year == null && titleYear != null) {
                    loaded.year = titleYear
                }

                // 2. Extract year from URL slug if available and not title prefix
                if (loaded.year == null) {
                    try {
                        val pathSegment = urlClean.substringBefore("?").split("/").lastOrNull { it.isNotBlank() }
                        if (pathSegment != null) {
                            val yearMatches = Regex("""\b(19\d{2}|20\d{2})\b""").findAll(pathSegment).map { it.range.first to it.groupValues[1].toInt() }.toList()
                            val parsedYear = yearMatches.firstOrNull { (pos, _) ->
                                !(pos <= 2 && Regex("""^\d{4}\b""").containsMatchIn(cleanName))
                            }?.second
                            if (parsedYear != null) {
                                loaded.year = parsedYear
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Ground-Truth Type Disambiguation: If loaded has multiple episodes, series response, or season/series tokens, enforce TvSeries
                val hasSeriesPattern = Regex("""(?i)\b(?:season|series|s\d{1,2}|episodes?|complete|all-episodes|web-series|tv-series)\b""").containsMatchIn(loaded.name) ||
                    Regex("""(?i)\b(?:season|series|s\d{1,2}|episodes?|all-episodes|web-series|tv-series)\b""").containsMatchIn(urlClean)
                val isMovie = loaded.type == com.lagradost.cloudstream3.TvType.Movie || loaded.type == com.lagradost.cloudstream3.TvType.AnimeMovie
                if (isMovie && (loaded is com.lagradost.cloudstream3.TvSeriesLoadResponse || allEpisodes.size > 1 || titleSeason != null || hasSeriesPattern)) {
                    loaded.type = if (loaded.type == com.lagradost.cloudstream3.TvType.AnimeMovie) com.lagradost.cloudstream3.TvType.Anime else com.lagradost.cloudstream3.TvType.TvSeries
                }

                // Direct Scraper Ground-Truth ID Extraction (Fast-Path)
                val directImdbId = parseImdbId(loaded.syncData["imdb"])
                    ?: loaded.syncData.values.firstNotNullOfOrNull { raw ->
                        findImdbId(raw)
                    }
                    ?: findImdbId(urlClean)
                    ?: findImdbId(loaded.url)

                val directTmdbId = loaded.syncData["tmdb"]?.toIntOrNull()?.takeIf { it > 0 }
                    ?: Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""").find(urlClean)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
                    ?: Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""").find(loaded.url)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

                AppLogger.i(TAG, "▶ START pipeline | raw='${loaded.name}' | clean='$cleanName' | year=${loaded.year} | type=${loaded.type} | directImdb=$directImdbId | directTmdb=$directTmdbId")

                val isAnime = loaded is com.lagradost.cloudstream3.AnimeLoadResponse ||
                    loaded.type == com.lagradost.cloudstream3.TvType.Anime ||
                    loaded.type == com.lagradost.cloudstream3.TvType.AnimeMovie ||
                    loaded.type == com.lagradost.cloudstream3.TvType.OVA ||
                    loaded.tags?.any { it.contains("anime", ignoreCase = true) || it.contains("animation", ignoreCase = true) } == true

                val currentProviders = synchronized(providers) { providers.toList() }
                val supportedProviders = currentProviders.filter { provider ->
                    if (!MetadataConfig.isProviderEnabled(provider.id)) return@filter false
                    provider.supportedTypes.contains(loaded.type) || (isAnime && (provider.id == "anilist" || provider.id == "kitsu"))
                }
                val sortedResolvers = supportedProviders.sortedWith(
                    compareBy(
                        {
                            if (isAnime) {
                                val primary = MetadataConfig.animePrimaryProvider.value
                                if (it.id == primary) {
                                    0
                                } else if (it.id == "anilist" || it.id == "kitsu") {
                                    1
                                } else {
                                    2
                                }
                            } else {
                                if (it.id == "cinemeta") 0 else 1
                            }
                        },
                        { it.priority },
                    ),
                )

                // 3. Resolve Media Identity (Stage 1 Resolvers with Canonical Identity Caching)
                val identityKey = "${cleanName.lowercase().trim()}_${loaded.year}_${loaded.type}"
                val suppliedMatch = suppliedIdentityMatch(cleanName, loaded.year, directImdbId, directTmdbId)
                val activeMatch = resolveIdentityMatch(
                    identityKey = identityKey,
                    title = loaded.name,
                    year = loaded.year,
                    type = loaded.type,
                    rawUrl = urlClean,
                    resolvers = sortedResolvers,
                    suppliedMatch = suppliedMatch,
                ).match

                // 4. Progressive Enrichment (Stage 1 -> Stage 2+ Concurrent Execution)
                val context = MetadataEnrichmentContext(
                    rawUrl = if (isDummy) "dummy_$urlClean" else urlClean,
                    isDummy = isDummy,
                    fetchCast = fetchCast,
                    directImdbId = activeMatch?.imdbId ?: directImdbId,
                    directTmdbId = activeMatch?.tmdbId ?: directTmdbId,
                )

                val sortedEnrichers = supportedProviders.sortedWith(
                    compareBy(
                        {
                            if (isAnime) {
                                val primary = MetadataConfig.animePrimaryProvider.value
                                if (it.id == primary) {
                                    0
                                } else if (it.id == "anilist" || it.id == "kitsu") {
                                    1
                                } else {
                                    2
                                }
                            } else {
                                if (it.id == "tmdb") 0 else 1
                            }
                        },
                        { it.priority },
                    ),
                )

                // Run enrichers in parallel so total time = max(provider time) instead of sum(provider time)
                coroutineScope {
                    val enrichJobs = sortedEnrichers.map { enricher ->
                        async(Dispatchers.IO) {
                            try {
                                enricher.enrich(loaded, activeMatch, context, callbacks)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Enricher ${enricher.id} failed", e)
                            }
                        }
                    }
                    enrichJobs.forEach { it.await() }
                }

                // 5. Store high-res metadata into Hero cache
                val heroTitle = loaded.name.takeIf { it.isNotBlank() }
                val heroBackdrop = loaded.backgroundPosterUrl?.takeIf { it.isNotBlank() }
                val heroLogo = loaded.logoUrl?.takeIf { it.isNotBlank() }
                val heroTags = loaded.tags?.take(4) ?: emptyList()
                val heroPlot = loaded.plot?.take(200)
                val heroScore = loaded.score?.toString()

                val heroMeta = HeroMeta(
                    title = heroTitle,
                    backdropUrl = heroBackdrop,
                    logoUrl = heroLogo,
                    tags = heroTags,
                    plot = heroPlot,
                    score = heroScore,
                    year = loaded.year,
                    type = loaded.type,
                    contentRating = loaded.contentRating,
                    duration = loaded.duration,
                )
                val cacheKey = "${loaded.apiName}_$urlClean"
                DesktopDataStore.setKey("herometa_$cacheKey", heroMeta)
                HeroCache.put(cacheKey, heroMeta)

                callbacks.onEnrichmentComplete()
                AppLogger.i(TAG, "✓ Pipeline completed successfully for '${loaded.name}'")
            }
            diagnosticStatus = PerformanceDiagnosticStatus.SUCCESS
        } catch (cancelled: CancellationException) {
            diagnosticStatus = PerformanceDiagnosticStatus.CANCELLED
            throw cancelled
        } finally {
            PerformanceDiagnostics.recordAsync(
                PerformanceDiagnosticStage.METADATA_ENRICHMENT,
                normalizePerformanceSourceLabel(loaded.apiName) ?: "unknown",
                profileId = ProfileManager.activeProfileId.toString(),
                status = diagnosticStatus,
                durationMs = ((System.nanoTime() - diagnosticStartedAt) / 1_000_000L).coerceAtLeast(0L),
            )
        }
    }

    /**
     * Attempts to retrieve a verified IMDb ID from the in-memory identity cache.
     */
    fun getCachedImdbId(showName: String?): String? {
        if (showName.isNullOrBlank()) return null
        val (cleanName, _) = TitleUtils.cleanProviderTitle(showName)
        val cleanLower = cleanName.lowercase().trim()
        return synchronized(identityCacheLock) {
            identityCache.values.firstOrNull { match ->
                match.imdbId?.startsWith("tt", ignoreCase = true) == true &&
                    (match.matchedTitle.equals(cleanName, ignoreCase = true) || match.matchedTitle.lowercase().trim() == cleanLower)
            }?.imdbId
        }
    }

    /** Resolves one identity while reusing verified IDs, cached results, and concurrent work. */
    internal suspend fun resolveIdentityMatch(
        identityKey: String,
        title: String,
        year: Int?,
        type: com.lagradost.cloudstream3.TvType,
        rawUrl: String?,
        resolvers: List<MetadataProvider>,
        suppliedMatch: MetadataMatch? = null,
        resolverTimeoutMs: Long = IDENTITY_RESOLVER_TIMEOUT_MS,
    ): IdentityResolution {
        val startedAtNanos = System.nanoTime()
        fun finish(match: MetadataMatch?, source: String): IdentityResolution {
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            AppLogger.i(TAG, "Identity resolution completed in ${elapsedMs}ms (source=$source, matched=${match != null})")
            PerformanceDiagnostics.recordAsync(
                PerformanceDiagnosticStage.METADATA_IDENTITY,
                normalizePerformanceSourceLabel(source) ?: "unknown",
                profileId = ProfileManager.activeProfileId.toString(),
                status = when {
                    match != null -> PerformanceDiagnosticStatus.SUCCESS
                    source == "timeout" -> PerformanceDiagnosticStatus.TIMEOUT
                    else -> PerformanceDiagnosticStatus.FAILURE
                },
                durationMs = elapsedMs.coerceAtLeast(0L),
            )
            return IdentityResolution(match, source, elapsedMs)
        }

        if (suppliedMatch != null) {
            // The scraper-supplied identity is grounded in the provider payload or URL, so it
            // supersedes an older title-keyed match for this same cache entry.
            cacheIdentityMatch(identityKey, suppliedMatch)
            inFlightResolutions[identityKey]?.complete(suppliedMatch)
            return finish(suppliedMatch, "supplied")
        }

        val cached = synchronized(identityCacheLock) { identityCache[identityKey] }
        if (cached != null) {
            return finish(cached, "cache")
        }

        var isInitiator = false
        val deferred = inFlightResolutions.computeIfAbsent(identityKey) {
            isInitiator = true
            kotlinx.coroutines.CompletableDeferred()
        }

        if (isInitiator) {
            try {
                val resolved = resolveFromProviders(
                    resolvers = resolvers,
                    title = title,
                    year = year,
                    type = type,
                    rawUrl = rawUrl,
                    resolverTimeoutMs = resolverTimeoutMs,
                )
                val preferred = cacheIdentityMatchIfAbsent(identityKey, resolved)
                deferred.complete(preferred)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
                throw e
            } finally {
                inFlightResolutions.remove(identityKey, deferred)
            }
        }

        val match = try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return finish(match, if (isInitiator) "resolvers" else "in-flight")
    }

    internal suspend fun resolveFromProviders(
        resolvers: List<MetadataProvider>,
        title: String,
        year: Int?,
        type: com.lagradost.cloudstream3.TvType,
        rawUrl: String?,
        resolverTimeoutMs: Long = IDENTITY_RESOLVER_TIMEOUT_MS,
    ): MetadataMatch? = coroutineScope {
        val resolverJobs = resolvers.map { resolver ->
            resolver to async(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(resolverTimeoutMs) {
                        resolver.resolve(title, year, type, rawUrl)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Resolver ${resolver.id} failed for '$title'", e)
                    null
                }
            }
        }

        for ((index, entry) in resolverJobs.withIndex()) {
            val (resolver, job) = entry
            val match = job.await()
            if (match != null) {
                AppLogger.i(TAG, "✓ Stage 1 Identity Resolved by ${resolver.id} -> '${match.matchedTitle}' (${match.matchedYear})")
                resolverJobs.drop(index + 1).forEach { (_, lowerPriorityJob) -> lowerPriorityJob.cancel() }
                return@coroutineScope match
            }
        }
        null
    }

    private fun cacheIdentityMatch(identityKey: String, match: MetadataMatch) {
        synchronized(identityCacheLock) {
            identityCache[identityKey] = match
            while (identityCache.size > MAX_IDENTITY_CACHE_ENTRIES) {
                identityCache.remove(identityCache.keys.first())
            }
        }
    }

    private fun cacheIdentityMatchIfAbsent(identityKey: String, resolved: MetadataMatch?): MetadataMatch? =
        synchronized(identityCacheLock) {
            val cached = identityCache[identityKey]
            if (cached != null) return@synchronized cached
            if (resolved == null) return@synchronized null

            identityCache[identityKey] = resolved
            while (identityCache.size > MAX_IDENTITY_CACHE_ENTRIES) {
                identityCache.remove(identityCache.keys.first())
            }
            resolved
        }

    private fun parseImdbId(raw: String?): String? {
        val candidate = raw?.trim() ?: return null
        return candidate.takeIf { Regex("""(?i)^tt\d{6,10}$""").matches(it) }
            ?.lowercase()
            ?.takeIf { it != "tt0000000" }
    }

    private fun findImdbId(raw: String?): String? = raw?.let {
        Regex("""(?i)\b(tt\d{6,10})\b""").find(it)?.groupValues?.get(1)?.lowercase()?.takeIf { id -> id != "tt0000000" }
    }

    internal fun suppliedIdentityMatch(
        title: String,
        year: Int?,
        imdbId: String?,
        tmdbId: Int?,
    ): MetadataMatch? {
        val verifiedImdbId = parseImdbId(imdbId)
        val verifiedTmdbId = tmdbId?.takeIf { it > 0 }
        if (verifiedImdbId == null && verifiedTmdbId == null) return null
        return MetadataMatch(
            providerId = "supplied",
            matchedTitle = title,
            matchedYear = year,
            imdbId = verifiedImdbId,
            tmdbId = verifiedTmdbId,
        )
    }

    internal data class IdentityResolution(
        val match: MetadataMatch?,
        val source: String,
        val elapsedMs: Long,
    )
}
